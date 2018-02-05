/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.app.replication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.asterix.app.nc.task.BindMetadataNodeTask;
import org.apache.asterix.app.nc.task.CheckpointTask;
import org.apache.asterix.app.nc.task.ExternalLibrarySetupTask;
import org.apache.asterix.app.nc.task.LocalRecoveryTask;
import org.apache.asterix.app.nc.task.MetadataBootstrapTask;
import org.apache.asterix.app.nc.task.ReportLocalCountersTask;
import org.apache.asterix.app.nc.task.StartLifecycleComponentsTask;
import org.apache.asterix.app.nc.task.StartReplicationServiceTask;
import org.apache.asterix.app.replication.message.MetadataNodeRequestMessage;
import org.apache.asterix.app.replication.message.MetadataNodeResponseMessage;
import org.apache.asterix.app.replication.message.NCLifecycleTaskReportMessage;
import org.apache.asterix.app.replication.message.RegistrationTasksRequestMessage;
import org.apache.asterix.app.replication.message.RegistrationTasksResponseMessage;
import org.apache.asterix.common.api.INCLifecycleTask;
import org.apache.asterix.common.cluster.ClusterPartition;
import org.apache.asterix.common.cluster.IClusterStateManager;
import org.apache.asterix.common.exceptions.ErrorCode;
import org.apache.asterix.common.exceptions.RuntimeDataException;
import org.apache.asterix.common.messaging.api.ICCMessageBroker;
import org.apache.asterix.common.replication.INCLifecycleMessage;
import org.apache.asterix.common.replication.INcLifecycleCoordinator;
import org.apache.asterix.common.transactions.IRecoveryManager.SystemState;
import org.apache.asterix.metadata.MetadataManager;
import org.apache.hyracks.api.application.ICCServiceContext;
import org.apache.hyracks.api.client.NodeStatus;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class NcLifecycleCoordinator implements INcLifecycleCoordinator {

    private static final Logger LOGGER = LogManager.getLogger();
    protected IClusterStateManager clusterManager;
    protected String metadataNodeId;
    protected Set<String> pendingStartupCompletionNodes = new HashSet<>();
    protected final ICCMessageBroker messageBroker;
    private final boolean replicationEnabled;

    public NcLifecycleCoordinator(ICCServiceContext serviceCtx, boolean replicationEnabled) {
        this.messageBroker = (ICCMessageBroker) serviceCtx.getMessageBroker();
        this.replicationEnabled = replicationEnabled;
    }

    @Override
    public void notifyNodeJoin(String nodeId) {
        pendingStartupCompletionNodes.add(nodeId);
    }

    @Override
    public void notifyNodeFailure(String nodeId) throws HyracksDataException {
        pendingStartupCompletionNodes.remove(nodeId);
        clusterManager.updateNodePartitions(nodeId, false);
        if (nodeId.equals(metadataNodeId)) {
            clusterManager.updateMetadataNode(metadataNodeId, false);
        }
        clusterManager.refreshState();
    }

    @Override
    public void process(INCLifecycleMessage message) throws HyracksDataException {
        switch (message.getType()) {
            case REGISTRATION_TASKS_REQUEST:
                process((RegistrationTasksRequestMessage) message);
                break;
            case REGISTRATION_TASKS_RESULT:
                process((NCLifecycleTaskReportMessage) message);
                break;
            case METADATA_NODE_RESPONSE:
                process((MetadataNodeResponseMessage) message);
                break;
            default:
                throw new RuntimeDataException(ErrorCode.UNSUPPORTED_MESSAGE_TYPE, message.getType().name());
        }
    }

    @Override
    public void bindTo(IClusterStateManager clusterManager) {
        this.clusterManager = clusterManager;
        metadataNodeId = clusterManager.getCurrentMetadataNodeId();
    }

    private void process(RegistrationTasksRequestMessage msg) throws HyracksDataException {
        final String nodeId = msg.getNodeId();
        List<INCLifecycleTask> tasks = buildNCRegTasks(msg.getNodeId(), msg.getNodeStatus(), msg.getState());
        RegistrationTasksResponseMessage response = new RegistrationTasksResponseMessage(nodeId, tasks);
        try {
            messageBroker.sendApplicationMessageToNC(response, msg.getNodeId());
        } catch (Exception e) {
            throw HyracksDataException.create(e);
        }
    }

    private void process(NCLifecycleTaskReportMessage msg) throws HyracksDataException {
        pendingStartupCompletionNodes.remove(msg.getNodeId());
        if (msg.isSuccess()) {
            clusterManager.updateNodePartitions(msg.getNodeId(), true);
            if (msg.getNodeId().equals(metadataNodeId)) {
                clusterManager.updateMetadataNode(metadataNodeId, true);
            }
            clusterManager.refreshState();
        } else {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.log(Level.ERROR, msg.getNodeId() + " failed to complete startup. ", msg.getException());
            }
        }
    }

    protected List<INCLifecycleTask> buildNCRegTasks(String nodeId, NodeStatus nodeStatus, SystemState state) {
        LOGGER.log(Level.INFO, () -> "Building registration tasks for node: " + nodeId + " with state: " + state);
        final boolean isMetadataNode = nodeId.equals(metadataNodeId);
        if (nodeStatus == NodeStatus.ACTIVE) {
            /*
             * if the node state is already ACTIVE then it completed
             * booting and just re-registering with a new/failed CC.
             */
            return buildActiveNCRegTasks(isMetadataNode);
        }
        final List<INCLifecycleTask> tasks = new ArrayList<>();
        if (state == SystemState.CORRUPTED) {
            //need to perform local recovery for node partitions
            LocalRecoveryTask rt = new LocalRecoveryTask(Arrays.asList(clusterManager.getNodePartitions(nodeId))
                    .stream().map(ClusterPartition::getPartitionId).collect(Collectors.toSet()));
            tasks.add(rt);
        }
        if (replicationEnabled) {
            tasks.add(new StartReplicationServiceTask());
        }
        if (isMetadataNode) {
            tasks.add(new MetadataBootstrapTask());
        }
        tasks.add(new ExternalLibrarySetupTask(isMetadataNode));
        tasks.add(new ReportLocalCountersTask());
        tasks.add(new CheckpointTask());
        tasks.add(new StartLifecycleComponentsTask());
        if (isMetadataNode) {
            tasks.add(new BindMetadataNodeTask(true));
        }
        return tasks;
    }

    protected List<INCLifecycleTask> buildActiveNCRegTasks(boolean metadataNode) {
        final List<INCLifecycleTask> tasks = new ArrayList<>();
        if (metadataNode) {
            // need to unbind from old distributed state then rebind to new one
            tasks.add(new BindMetadataNodeTask(false));
            tasks.add(new BindMetadataNodeTask(true));
        }
        tasks.add(new ReportLocalCountersTask());
        return tasks;
    }

    @Override
    public void notifyMetadataNodeChange(String node) throws HyracksDataException {
        if (metadataNodeId.equals(node)) {
            return;
        }
        // if current metadata node is active, we need to unbind its metadata proxy object
        if (clusterManager.isMetadataNodeActive()) {
            MetadataNodeRequestMessage msg = new MetadataNodeRequestMessage(false);
            try {
                messageBroker.sendApplicationMessageToNC(msg, metadataNodeId);
                // when the current node responses, we will bind to the new one
                metadataNodeId = node;
            } catch (Exception e) {
                throw HyracksDataException.create(e);
            }
        } else {
            requestMetadataNodeTakeover(node);
        }
    }

    private void process(MetadataNodeResponseMessage response) throws HyracksDataException {
        // rebind metadata node since it might be changing
        MetadataManager.INSTANCE.rebindMetadataNode();
        clusterManager.updateMetadataNode(response.getNodeId(), response.isExported());
        if (!response.isExported()) {
            requestMetadataNodeTakeover(metadataNodeId);
        }
    }

    private void requestMetadataNodeTakeover(String node) throws HyracksDataException {
        MetadataNodeRequestMessage msg = new MetadataNodeRequestMessage(true);
        try {
            messageBroker.sendApplicationMessageToNC(msg, node);
        } catch (Exception e) {
            throw HyracksDataException.create(e);
        }
    }
}
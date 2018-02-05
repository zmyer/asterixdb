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
package org.apache.hyracks.api.client;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.hyracks.api.client.impl.JobSpecificationActivityClusterGraphGeneratorFactory;
import org.apache.hyracks.api.comm.NetworkAddress;
import org.apache.hyracks.api.deployment.DeploymentId;
import org.apache.hyracks.api.exceptions.HyracksException;
import org.apache.hyracks.api.job.DeployedJobSpecId;
import org.apache.hyracks.api.job.IActivityClusterGraphGeneratorFactory;
import org.apache.hyracks.api.job.JobFlag;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.api.job.JobInfo;
import org.apache.hyracks.api.job.JobSpecification;
import org.apache.hyracks.api.job.JobStatus;
import org.apache.hyracks.api.topology.ClusterTopology;
import org.apache.hyracks.api.util.JavaSerializationUtils;
import org.apache.hyracks.ipc.api.RPCInterface;
import org.apache.hyracks.ipc.impl.IPCSystem;
import org.apache.hyracks.ipc.impl.JavaSerializationBasedPayloadSerializerDeserializer;

/**
 * Connection Class used by a Hyracks Client to interact with a Hyracks Cluster
 * Controller.
 *
 * @author vinayakb
 */
public final class HyracksConnection implements IHyracksClientConnection {
    private final String ccHost;

    private final int ccPort;

    private final IPCSystem ipc;

    private final IHyracksClientInterface hci;

    private final ClusterControllerInfo ccInfo;

    /**
     * Constructor to create a connection to the Hyracks Cluster Controller.
     *
     * @param ccHost
     *            Host name (or IP Address) where the Cluster Controller can be
     *            reached.
     * @param ccPort
     *            Port to reach the Hyracks Cluster Controller at the specified
     *            host name.
     * @throws Exception
     */
    public HyracksConnection(String ccHost, int ccPort) throws Exception {
        this.ccHost = ccHost;
        this.ccPort = ccPort;
        RPCInterface rpci = new RPCInterface();
        ipc = new IPCSystem(new InetSocketAddress(0), rpci, new JavaSerializationBasedPayloadSerializerDeserializer());
        ipc.start();
        hci = new HyracksClientInterfaceRemoteProxy(ipc.getReconnectingHandle(new InetSocketAddress(ccHost, ccPort)),
                rpci);
        ccInfo = hci.getClusterControllerInfo();
    }

    @Override
    public JobStatus getJobStatus(JobId jobId) throws Exception {
        return hci.getJobStatus(jobId);
    }

    @Override
    public void cancelJob(JobId jobId) throws Exception {
        hci.cancelJob(jobId);
    }

    @Override
    public JobId startJob(JobSpecification jobSpec) throws Exception {
        return startJob(jobSpec, EnumSet.noneOf(JobFlag.class));
    }

    @Override
    public JobId startJob(JobSpecification jobSpec, EnumSet<JobFlag> jobFlags) throws Exception {
        IActivityClusterGraphGeneratorFactory jsacggf =
                new JobSpecificationActivityClusterGraphGeneratorFactory(jobSpec);
        return startJob(jsacggf, jobFlags);
    }

    @Override
    public DeployedJobSpecId deployJobSpec(JobSpecification jobSpec) throws Exception {
        JobSpecificationActivityClusterGraphGeneratorFactory jsacggf =
                new JobSpecificationActivityClusterGraphGeneratorFactory(jobSpec);
        return deployJobSpec(jsacggf);
    }

    @Override
    public DeployedJobSpecId undeployJobSpec(DeployedJobSpecId deployedJobSpecId) throws Exception {
        return hci.undeployJobSpec(deployedJobSpecId);
    }

    @Override
    public JobId startJob(DeployedJobSpecId deployedJobSpecId, Map<byte[], byte[]> jobParameters) throws Exception {
        return hci.startJob(deployedJobSpecId, jobParameters);
    }

    @Override
    public JobId startJob(IActivityClusterGraphGeneratorFactory acggf, EnumSet<JobFlag> jobFlags) throws Exception {
        return hci.startJob(JavaSerializationUtils.serialize(acggf), jobFlags);
    }

    public DeployedJobSpecId deployJobSpec(IActivityClusterGraphGeneratorFactory acggf) throws Exception {
        return hci.deployJobSpec(JavaSerializationUtils.serialize(acggf));
    }

    @Override
    public NetworkAddress getDatasetDirectoryServiceInfo() throws Exception {
        return hci.getDatasetDirectoryServiceInfo();
    }

    @Override
    public void waitForCompletion(JobId jobId) throws Exception {
        try {
            hci.waitForCompletion(jobId);
        } catch (InterruptedException e) {
            // Cancels an on-going job if the current thread gets interrupted.
            hci.cancelJob(jobId);
            throw e;
        }
    }

    @Override
    public Map<String, NodeControllerInfo> getNodeControllerInfos() throws HyracksException {
        try {
            return hci.getNodeControllersInfo();
        } catch (Exception e) {
            throw new HyracksException(e);
        }
    }

    @Override
    public ClusterTopology getClusterTopology() throws HyracksException {
        try {
            return hci.getClusterTopology();
        } catch (Exception e) {
            throw new HyracksException(e);
        }
    }

    @Override
    public DeploymentId deployBinary(List<String> jars) throws Exception {
        /** generate a deployment id */
        DeploymentId deploymentId = new DeploymentId(UUID.randomUUID().toString());
        List<URL> binaryURLs = new ArrayList<>();
        if (jars != null && !jars.isEmpty()) {
            CloseableHttpClient hc = new DefaultHttpClient();
            try {
                /** upload jars through a http client one-by-one to the CC server */
                for (String jar : jars) {
                    int slashIndex = jar.lastIndexOf('/');
                    String fileName = jar.substring(slashIndex + 1);
                    String url = "http://" + ccHost + ":" + ccInfo.getWebPort() + "/applications/"
                            + deploymentId.toString() + "&" + fileName;
                    HttpPut put = new HttpPut(url);
                    put.setEntity(new FileEntity(new File(jar), "application/octet-stream"));
                    HttpResponse response = hc.execute(put);
                    response.getEntity().consumeContent();
                    if (response.getStatusLine().getStatusCode() != 200) {
                        hci.unDeployBinary(deploymentId);
                        throw new HyracksException(response.getStatusLine().toString());
                    }
                    /** add the uploaded URL address into the URLs of jars to be deployed at NCs */
                    binaryURLs.add(new URL(url));
                }
            } finally {
                hc.close();
            }
        }
        /** deploy the URLs to the CC and NCs */
        hci.deployBinary(binaryURLs, deploymentId);
        return deploymentId;
    }

    @Override
    public void unDeployBinary(DeploymentId deploymentId) throws Exception {
        hci.unDeployBinary(deploymentId);
    }

    @Override
    public JobId startJob(DeploymentId deploymentId, JobSpecification jobSpec) throws Exception {
        return startJob(deploymentId, jobSpec, EnumSet.noneOf(JobFlag.class));
    }

    @Override
    public JobId startJob(DeploymentId deploymentId, JobSpecification jobSpec, EnumSet<JobFlag> jobFlags)
            throws Exception {
        IActivityClusterGraphGeneratorFactory jsacggf =
                new JobSpecificationActivityClusterGraphGeneratorFactory(jobSpec);
        return startJob(deploymentId, jsacggf, jobFlags);
    }

    @Override
    public JobId startJob(DeploymentId deploymentId, IActivityClusterGraphGeneratorFactory acggf,
            EnumSet<JobFlag> jobFlags) throws Exception {
        return hci.startJob(deploymentId, JavaSerializationUtils.serialize(acggf), jobFlags);
    }

    @Override
    public JobInfo getJobInfo(JobId jobId) throws Exception {
        return hci.getJobInfo(jobId);
    }

    @Override
    public void stopCluster(boolean terminateNCService) throws Exception {
        hci.stopCluster(terminateNCService);
    }

    @Override
    public String getNodeDetailsJSON(String nodeId, boolean includeStats, boolean includeConfig) throws Exception {
        return hci.getNodeDetailsJSON(nodeId, includeStats, includeConfig);
    }

    @Override
    public String getThreadDump(String node) throws Exception {
        return hci.getThreadDump(node);
    }

    @Override
    public String getHost() {
        return ccHost;
    }

    @Override
    public int getPort() {
        return ccPort;
    }

    @Override
    public boolean isConnected() {
        return hci.isConnected();
    }
}

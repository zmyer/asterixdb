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
package org.apache.asterix.replication.sync;

import java.io.IOException;

import org.apache.asterix.common.api.INcApplicationContext;
import org.apache.asterix.common.replication.IReplicationStrategy;
import org.apache.asterix.replication.messaging.ReplicationProtocol;
import org.apache.asterix.replication.messaging.CheckpointPartitionIndexesTask;
import org.apache.asterix.replication.api.PartitionReplica;

/**
 * Performs the steps required to ensure any newly added replica
 * will be in-sync with master
 */
public class ReplicaSynchronizer {

    private final INcApplicationContext appCtx;
    private final PartitionReplica replica;

    public ReplicaSynchronizer(INcApplicationContext appCtx, PartitionReplica replica) {
        this.appCtx = appCtx;
        this.replica = replica;
    }

    public void sync() throws IOException {
        syncFiles();
        checkpointReplicaIndexes();
        appCtx.getReplicationManager().register(replica);
    }

    private void syncFiles() throws IOException {
        final ReplicaFilesSynchronizer fileSync = new ReplicaFilesSynchronizer(appCtx, replica);
        fileSync.sync();
        // flush replicated dataset to generate disk component for any remaining in-memory components
        final IReplicationStrategy replStrategy = appCtx.getReplicationManager().getReplicationStrategy();
        appCtx.getDatasetLifecycleManager().flushDataset(replStrategy);
        // sync any newly generated files
        fileSync.sync();
    }

    private void checkpointReplicaIndexes() throws IOException {
        CheckpointPartitionIndexesTask task =
                new CheckpointPartitionIndexesTask(replica.getIdentifier().getPartition());
        ReplicationProtocol.sendTo(replica, task);
        ReplicationProtocol.waitForAck(replica);
    }
}

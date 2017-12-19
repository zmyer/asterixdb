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
package org.apache.asterix.app.nc;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.asterix.common.api.INcApplicationContext;
import org.apache.asterix.common.replication.IPartitionReplica;
import org.apache.asterix.common.replication.IRemoteRecoveryManager;
import org.apache.asterix.common.storage.IReplicaManager;
import org.apache.asterix.common.storage.ReplicaIdentifier;
import org.apache.asterix.replication.storage.PartitionReplica;
import org.apache.hyracks.api.exceptions.HyracksDataException;

public class ReplicaManager implements IReplicaManager {

    private final INcApplicationContext appCtx;
    /**
     * the partitions to which the current node is master
     */
    private final Set<Integer> partitions = new HashSet<>();
    /**
     * current replicas
     */
    private final Map<ReplicaIdentifier, PartitionReplica> replicas = new HashMap<>();

    public ReplicaManager(INcApplicationContext appCtx, Set<Integer> partitions) {
        this.appCtx = appCtx;
        this.partitions.addAll(partitions);
    }

    @Override
    public synchronized void addReplica(ReplicaIdentifier id) {
        if (!partitions.contains(id.getPartition())) {
            throw new IllegalStateException(
                    "This node is not the current master of partition(" + id.getPartition() + ")");
        }
        replicas.computeIfAbsent(id, k -> new PartitionReplica(k, appCtx));
        replicas.get(id).sync();
    }

    @Override
    public synchronized void removeReplica(ReplicaIdentifier id) {
        if (!replicas.containsKey(id)) {
            throw new IllegalStateException("replica with id(" + id + ") does not exist");
        }
        replicas.remove(id);
    }

    @Override
    public List<IPartitionReplica> getReplicas(int partition) {
        return replicas.entrySet().stream().filter(e -> e.getKey().getPartition() == partition).map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    @Override
    public Set<Integer> getPartitions() {
        return Collections.unmodifiableSet(partitions);
    }

    @Override
    public synchronized void promote(int partition) throws HyracksDataException {
        final IRemoteRecoveryManager remoteRecoveryManager = appCtx.getRemoteRecoveryManager();
        remoteRecoveryManager.replayReplicaPartitionLogs(Stream.of(partition).collect(Collectors.toSet()), true);
        partitions.add(partition);
    }
}
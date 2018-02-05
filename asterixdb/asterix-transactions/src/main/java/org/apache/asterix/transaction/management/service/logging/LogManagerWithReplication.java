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
package org.apache.asterix.transaction.management.service.logging;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.asterix.common.exceptions.ACIDException;
import org.apache.asterix.common.replication.IReplicationManager;
import org.apache.asterix.common.replication.IReplicationStrategy;
import org.apache.asterix.common.transactions.ILogRecord;
import org.apache.asterix.common.transactions.ITransactionSubsystem;
import org.apache.asterix.common.transactions.LogSource;
import org.apache.asterix.common.transactions.LogType;
import org.apache.hyracks.util.InvokeUtil;

public class LogManagerWithReplication extends LogManager {

    private IReplicationManager replicationManager;
    private IReplicationStrategy replicationStrategy;
    private final Set<Long> replicatedTxn = ConcurrentHashMap.newKeySet();

    public LogManagerWithReplication(ITransactionSubsystem txnSubsystem) {
        super(txnSubsystem);
    }

    @Override
    public void log(ILogRecord logRecord) {
        boolean shouldReplicate = logRecord.getLogSource() == LogSource.LOCAL && logRecord.getLogType() != LogType.WAIT;
        if (shouldReplicate) {
            switch (logRecord.getLogType()) {
                case LogType.ENTITY_COMMIT:
                case LogType.UPDATE:
                case LogType.FLUSH:
                    shouldReplicate = replicationStrategy.isMatch(logRecord.getDatasetId());
                    if (shouldReplicate && !replicatedTxn.contains(logRecord.getTxnId())) {
                        replicatedTxn.add(logRecord.getTxnId());
                    }
                    break;
                case LogType.JOB_COMMIT:
                case LogType.ABORT:
                    shouldReplicate = replicatedTxn.remove(logRecord.getTxnId());
                    break;
                default:
                    shouldReplicate = false;
            }
        }
        logRecord.setReplicate(shouldReplicate);

        //Remote flush logs do not need to be flushed separately since they may not trigger local flush
        if (logRecord.getLogType() == LogType.FLUSH && logRecord.getLogSource() == LogSource.LOCAL) {
            flushLogsQ.add(logRecord);
            return;
        }

        appendToLogTail(logRecord);
    }

    @Override
    protected void appendToLogTail(ILogRecord logRecord) {
        syncAppendToLogTail(logRecord);

        if (logRecord.isReplicate()) {
            try {
                replicationManager.replicate(logRecord);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ACIDException(e);
            }
        }

        if (logRecord.getLogSource() == LogSource.LOCAL && waitForFlush(logRecord) && !logRecord.isFlushed()) {
            InvokeUtil.doUninterruptibly(() -> {
                synchronized (logRecord) {
                    while (!logRecord.isFlushed()) {
                        logRecord.wait();
                    }
                    //wait for job Commit/Abort ACK from replicas
                    if (logRecord.isReplicate() && (logRecord.getLogType() == LogType.JOB_COMMIT
                            || logRecord.getLogType() == LogType.ABORT)) {
                        while (!logRecord.isReplicated()) {
                            logRecord.wait();
                        }
                    }
                }
            });
        }
    }

    @Override
    public void setReplicationManager(IReplicationManager replicationManager) {
        this.replicationManager = replicationManager;
        this.replicationStrategy = replicationManager.getReplicationStrategy();
    }
}

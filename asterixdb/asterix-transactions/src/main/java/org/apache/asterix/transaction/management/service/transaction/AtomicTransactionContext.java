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
package org.apache.asterix.transaction.management.service.transaction;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.asterix.common.context.PrimaryIndexOperationTracker;
import org.apache.asterix.common.exceptions.ACIDException;
import org.apache.asterix.common.transactions.TxnId;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndex;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMOperationTracker;
import org.apache.hyracks.storage.am.lsm.common.api.LSMOperationType;
import org.apache.hyracks.storage.common.IModificationOperationCallback;
import org.apache.hyracks.util.annotations.ThreadSafe;

@ThreadSafe
public class AtomicTransactionContext extends AbstractTransactionContext {

    private final Map<Long, ILSMOperationTracker> opTrackers = new HashMap<>();
    private final Map<Long, AtomicInteger> indexPendingOps = new HashMap<>();
    private final Map<Long, IModificationOperationCallback> callbacks = new HashMap<>();

    public AtomicTransactionContext(TxnId txnId) {
        super(txnId);
    }

    @Override
    public void register(long resourceId, int partition, ILSMIndex index, IModificationOperationCallback callback,
            boolean primaryIndex) {
        super.register(resourceId, partition, index, callback, primaryIndex);
        synchronized (txnOpTrackers) {
            if (primaryIndex && !opTrackers.containsKey(resourceId)) {
                opTrackers.put(resourceId, index.getOperationTracker());
                callbacks.put(resourceId, callback);
                indexPendingOps.put(resourceId, new AtomicInteger(0));
            }
        }
    }

    @Override
    public void notifyUpdateCommitted(long resourceId) {
        try {
            opTrackers.get(resourceId).completeOperation(null, LSMOperationType.MODIFICATION, null,
                    callbacks.get(resourceId));
        } catch (HyracksDataException e) {
            throw new ACIDException(e);
        }
    }

    @Override
    public void notifyEntityCommitted(int partition) {
        throw new IllegalStateException("Unexpected entity commit in atomic transaction");
    }

    @Override
    public void beforeOperation(long resourceId) {
        indexPendingOps.get(resourceId).incrementAndGet();
    }

    @Override
    public void afterOperation(long resourceId) {
        indexPendingOps.get(resourceId).decrementAndGet();
    }

    @Override
    public void cleanupForAbort() {
        // each opTracker should be cleaned
        opTrackers.forEach((resId, opTracker) -> ((PrimaryIndexOperationTracker) opTracker)
                .cleanupNumActiveOperationsForAbortedJob(indexPendingOps.get(resId).get()));
    }

    @Override
    public int hashCode() {
        return Long.hashCode(txnId.getId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AtomicTransactionContext that = (AtomicTransactionContext) o;
        return this.txnId.equals(that.txnId);
    }
}

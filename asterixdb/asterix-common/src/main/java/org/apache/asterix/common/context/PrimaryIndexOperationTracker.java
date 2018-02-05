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

package org.apache.asterix.common.context;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.asterix.common.exceptions.ACIDException;
import org.apache.asterix.common.ioopcallbacks.AbstractLSMIOOperationCallback;
import org.apache.asterix.common.transactions.AbstractOperationCallback;
import org.apache.asterix.common.transactions.ILogManager;
import org.apache.asterix.common.transactions.LogRecord;
import org.apache.asterix.common.utils.TransactionUtil;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.storage.am.common.impls.NoOpIndexAccessParameters;
import org.apache.hyracks.storage.am.common.impls.NoOpOperationCallback;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMComponent.ComponentState;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMComponentIdGenerator;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndex;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndexAccessor;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMMemoryComponent;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMOperationTracker;
import org.apache.hyracks.storage.am.lsm.common.api.LSMOperationType;
import org.apache.hyracks.storage.common.IModificationOperationCallback;
import org.apache.hyracks.storage.common.ISearchOperationCallback;

public class PrimaryIndexOperationTracker extends BaseOperationTracker {

    private final int partition;
    // Number of active operations on an ILSMIndex instance.
    private final AtomicInteger numActiveOperations;
    private final ILogManager logManager;
    private final ILSMComponentIdGenerator idGenerator;
    private boolean flushOnExit = false;
    private boolean flushLogCreated = false;

    public PrimaryIndexOperationTracker(int datasetID, int partition, ILogManager logManager, DatasetInfo dsInfo,
            ILSMComponentIdGenerator idGenerator) {
        super(datasetID, dsInfo);
        this.partition = partition;
        this.logManager = logManager;
        this.numActiveOperations = new AtomicInteger();
        this.idGenerator = idGenerator;
    }

    @Override
    public void beforeOperation(ILSMIndex index, LSMOperationType opType, ISearchOperationCallback searchCallback,
            IModificationOperationCallback modificationCallback) throws HyracksDataException {
        if (opType == LSMOperationType.MODIFICATION || opType == LSMOperationType.FORCE_MODIFICATION) {
            incrementNumActiveOperations(modificationCallback);
        } else if (opType == LSMOperationType.FLUSH || opType == LSMOperationType.MERGE
                || opType == LSMOperationType.REPLICATE) {
            dsInfo.declareActiveIOOperation();
        }
    }

    @Override
    public void afterOperation(ILSMIndex index, LSMOperationType opType, ISearchOperationCallback searchCallback,
            IModificationOperationCallback modificationCallback) throws HyracksDataException {
        // Searches are immediately considered complete, because they should not prevent the execution of flushes.
        if (opType == LSMOperationType.REPLICATE) {
            completeOperation(index, opType, searchCallback, modificationCallback);
        }
    }

    @Override
    public synchronized void completeOperation(ILSMIndex index, LSMOperationType opType,
            ISearchOperationCallback searchCallback, IModificationOperationCallback modificationCallback)
            throws HyracksDataException {
        if (opType == LSMOperationType.MODIFICATION || opType == LSMOperationType.FORCE_MODIFICATION) {
            decrementNumActiveOperations(modificationCallback);
            if (numActiveOperations.get() == 0) {
                flushIfRequested();
            } else if (numActiveOperations.get() < 0) {
                throw new HyracksDataException("The number of active operations cannot be negative!");
            }
        } else if (opType == LSMOperationType.FLUSH || opType == LSMOperationType.MERGE
                || opType == LSMOperationType.REPLICATE) {
            dsInfo.undeclareActiveIOOperation();
        }
    }

    public void flushIfRequested() throws HyracksDataException {
        // If we need a flush, and this is the last completing operation, then schedule the flush,
        // or if there is a flush scheduled by the checkpoint (flushOnExit), then schedule it

        boolean needsFlush = false;
        Set<ILSMIndex> indexes = dsInfo.getDatasetPartitionOpenIndexes(partition);

        if (!flushOnExit) {
            for (ILSMIndex lsmIndex : indexes) {
                if (lsmIndex.hasFlushRequestForCurrentMutableComponent()) {
                    needsFlush = true;
                    break;
                }
            }
        }

        if (needsFlush || flushOnExit) {
            //Make the current mutable components READABLE_UNWRITABLE to stop coming modify operations from entering them until the current flush is scheduled.
            for (ILSMIndex lsmIndex : indexes) {
                ILSMOperationTracker opTracker = lsmIndex.getOperationTracker();
                synchronized (opTracker) {
                    ILSMMemoryComponent memComponent = lsmIndex.getCurrentMemoryComponent();
                    if (memComponent.getState() == ComponentState.READABLE_WRITABLE && memComponent.isModified()) {
                        memComponent.setState(ComponentState.READABLE_UNWRITABLE);
                    }
                }
            }
            LogRecord logRecord = new LogRecord();
            flushOnExit = false;
            if (dsInfo.isDurable()) {
                /**
                 * Generate a FLUSH log.
                 * Flush will be triggered when the log is written to disk by LogFlusher.
                 */
                TransactionUtil.formFlushLogRecord(logRecord, datasetID, this);
                try {
                    logManager.log(logRecord);
                } catch (ACIDException e) {
                    throw new HyracksDataException("could not write flush log", e);
                }
                flushLogCreated = true;
            } else {
                //trigger flush for temporary indexes without generating a FLUSH log.
                triggerScheduleFlush(logRecord);
            }
        }
    }

    //This method is called sequentially by LogPage.notifyFlushTerminator in the sequence flushes were scheduled.
    public synchronized void triggerScheduleFlush(LogRecord logRecord) throws HyracksDataException {
        idGenerator.refresh();
        for (ILSMIndex lsmIndex : dsInfo.getDatasetPartitionOpenIndexes(partition)) {
            //get resource
            ILSMIndexAccessor accessor = lsmIndex.createAccessor(NoOpIndexAccessParameters.INSTANCE);
            //update resource lsn
            AbstractLSMIOOperationCallback ioOpCallback =
                    (AbstractLSMIOOperationCallback) lsmIndex.getIOOperationCallback();
            ioOpCallback.updateLastLSN(logRecord.getLSN());
            //schedule flush after update
            accessor.scheduleFlush(lsmIndex.getIOOperationCallback());
        }
        flushLogCreated = false;
    }

    public int getNumActiveOperations() {
        return numActiveOperations.get();
    }

    private void incrementNumActiveOperations(IModificationOperationCallback modificationCallback) {
        //modificationCallback can be NoOpOperationCallback when redo/undo operations are executed.
        if (modificationCallback != NoOpOperationCallback.INSTANCE) {
            numActiveOperations.incrementAndGet();
            ((AbstractOperationCallback) modificationCallback).beforeOperation();
        }
    }

    private void decrementNumActiveOperations(IModificationOperationCallback modificationCallback) {
        //modificationCallback can be NoOpOperationCallback when redo/undo operations are executed.
        if (modificationCallback != NoOpOperationCallback.INSTANCE) {
            numActiveOperations.decrementAndGet();
            ((AbstractOperationCallback) modificationCallback).afterOperation();
        }
    }

    public void cleanupNumActiveOperationsForAbortedJob(int numberOfActiveOperations) {
        numberOfActiveOperations *= -1;
        numActiveOperations.getAndAdd(numberOfActiveOperations);
        if (numActiveOperations.get() < 0) {
            throw new IllegalStateException("The number of active operations cannot be negative!");
        }
    }

    public boolean isFlushOnExit() {
        return flushOnExit;
    }

    public void setFlushOnExit(boolean flushOnExit) {
        this.flushOnExit = flushOnExit;
    }

    public boolean isFlushLogCreated() {
        return flushLogCreated;
    }

    public int getPartition() {
        return partition;
    }

}

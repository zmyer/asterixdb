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

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.asterix.common.api.IDatasetLifecycleManager;
import org.apache.asterix.common.api.IDatasetMemoryManager;
import org.apache.asterix.common.config.StorageProperties;
import org.apache.asterix.common.dataflow.DatasetLocalResource;
import org.apache.asterix.common.exceptions.ACIDException;
import org.apache.asterix.common.ioopcallbacks.AbstractLSMIOOperationCallback;
import org.apache.asterix.common.replication.IReplicationStrategy;
import org.apache.asterix.common.storage.DatasetResourceReference;
import org.apache.asterix.common.storage.IIndexCheckpointManagerProvider;
import org.apache.asterix.common.transactions.ILogManager;
import org.apache.asterix.common.transactions.LogRecord;
import org.apache.asterix.common.utils.StoragePathUtil;
import org.apache.asterix.common.utils.TransactionUtil;
import org.apache.hyracks.api.exceptions.ErrorCode;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.lifecycle.ILifeCycleComponent;
import org.apache.hyracks.storage.am.common.impls.NoOpIndexAccessParameters;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMComponentIdGenerator;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndex;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndexAccessor;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMOperationTracker;
import org.apache.hyracks.storage.am.lsm.common.api.IVirtualBufferCache;
import org.apache.hyracks.storage.am.lsm.common.impls.LSMComponentIdGenerator;
import org.apache.hyracks.storage.common.IIndex;
import org.apache.hyracks.storage.common.ILocalResourceRepository;
import org.apache.hyracks.storage.common.LocalResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DatasetLifecycleManager implements IDatasetLifecycleManager, ILifeCycleComponent {

    private static final Logger LOGGER = LogManager.getLogger();
    private final Map<Integer, DatasetResource> datasets = new ConcurrentHashMap<>();
    private final StorageProperties storageProperties;
    private final ILocalResourceRepository resourceRepository;
    private final IDatasetMemoryManager memoryManager;
    private final ILogManager logManager;
    private final LogRecord logRecord;
    private final int numPartitions;
    private volatile boolean stopped = false;
    private final IIndexCheckpointManagerProvider indexCheckpointManagerProvider;

    public DatasetLifecycleManager(StorageProperties storageProperties, ILocalResourceRepository resourceRepository,
            ILogManager logManager, IDatasetMemoryManager memoryManager,
            IIndexCheckpointManagerProvider indexCheckpointManagerProvider, int numPartitions) {
        this.logManager = logManager;
        this.storageProperties = storageProperties;
        this.resourceRepository = resourceRepository;
        this.memoryManager = memoryManager;
        this.indexCheckpointManagerProvider = indexCheckpointManagerProvider;
        this.numPartitions = numPartitions;
        logRecord = new LogRecord();
    }

    @Override
    public synchronized ILSMIndex get(String resourcePath) throws HyracksDataException {
        validateDatasetLifecycleManagerState();
        int datasetID = getDIDfromResourcePath(resourcePath);
        long resourceID = getResourceIDfromResourcePath(resourcePath);
        return getIndex(datasetID, resourceID);
    }

    @Override
    public synchronized ILSMIndex getIndex(int datasetID, long resourceID) throws HyracksDataException {
        validateDatasetLifecycleManagerState();
        DatasetResource datasetResource = datasets.get(datasetID);
        if (datasetResource == null) {
            return null;
        }
        return datasetResource.getIndex(resourceID);
    }

    @Override
    public synchronized void register(String resourcePath, IIndex index) throws HyracksDataException {
        validateDatasetLifecycleManagerState();
        int did = getDIDfromResourcePath(resourcePath);
        LocalResource resource = resourceRepository.get(resourcePath);
        DatasetResource datasetResource = datasets.get(did);
        if (datasetResource == null) {
            datasetResource = getDatasetLifecycle(did);
        }
        datasetResource.register(resource, (ILSMIndex) index);
    }

    private int getDIDfromResourcePath(String resourcePath) throws HyracksDataException {
        LocalResource lr = resourceRepository.get(resourcePath);
        if (lr == null) {
            return -1;
        }
        return ((DatasetLocalResource) lr.getResource()).getDatasetId();
    }

    private long getResourceIDfromResourcePath(String resourcePath) throws HyracksDataException {
        LocalResource lr = resourceRepository.get(resourcePath);
        if (lr == null) {
            return -1;
        }
        return lr.getId();
    }

    @Override
    public synchronized void unregister(String resourcePath) throws HyracksDataException {
        validateDatasetLifecycleManagerState();
        int did = getDIDfromResourcePath(resourcePath);
        long resourceID = getResourceIDfromResourcePath(resourcePath);

        DatasetResource dsr = datasets.get(did);
        IndexInfo iInfo = dsr == null ? null : dsr.getIndexInfo(resourceID);

        if (dsr == null || iInfo == null) {
            throw HyracksDataException.create(ErrorCode.INDEX_DOES_NOT_EXIST);
        }

        PrimaryIndexOperationTracker opTracker = dsr.getOpTracker(iInfo.getPartition());
        if (iInfo.getReferenceCount() != 0 || (opTracker != null && opTracker.getNumActiveOperations() != 0)) {
            if (LOGGER.isErrorEnabled()) {
                final String logMsg = String.format(
                        "Failed to drop in-use index %s. Ref count (%d), Operation tracker active ops (%d)",
                        resourcePath, iInfo.getReferenceCount(), opTracker.getNumActiveOperations());
                LOGGER.error(logMsg);
            }
            throw HyracksDataException.create(ErrorCode.CANNOT_DROP_IN_USE_INDEX,
                    StoragePathUtil.getIndexNameFromPath(resourcePath));
        }

        // TODO: use fine-grained counters, one for each index instead of a single counter per dataset.
        DatasetInfo dsInfo = dsr.getDatasetInfo();
        dsInfo.waitForIO();
        closeIndex(iInfo);
        dsInfo.removeIndex(resourceID);
        if (dsInfo.getReferenceCount() == 0 && dsInfo.isOpen() && dsInfo.getIndexes().isEmpty()
                && !dsInfo.isExternal()) {
            removeDatasetFromCache(dsInfo.getDatasetID());
        }
    }

    @Override
    public synchronized void open(String resourcePath) throws HyracksDataException {
        validateDatasetLifecycleManagerState();
        int did = getDIDfromResourcePath(resourcePath);
        long resourceID = getResourceIDfromResourcePath(resourcePath);

        DatasetResource dsr = datasets.get(did);
        DatasetInfo dsInfo = dsr.getDatasetInfo();
        if (dsInfo == null || !dsInfo.isRegistered()) {
            throw new HyracksDataException(
                    "Failed to open index with resource ID " + resourceID + " since it does not exist.");
        }

        IndexInfo iInfo = dsInfo.getIndexes().get(resourceID);
        if (iInfo == null) {
            throw new HyracksDataException(
                    "Failed to open index with resource ID " + resourceID + " since it does not exist.");
        }

        dsr.open(true);
        dsr.touch();

        if (!iInfo.isOpen()) {
            ILSMOperationTracker opTracker = iInfo.getIndex().getOperationTracker();
            synchronized (opTracker) {
                iInfo.getIndex().activate();
            }
            iInfo.setOpen(true);
        }
        iInfo.touch();
    }

    private boolean evictCandidateDataset() throws HyracksDataException {
        /**
         * We will take a dataset that has no active transactions, it is open (a dataset consuming memory),
         * that is not being used (refcount == 0) and has been least recently used, excluding metadata datasets.
         * The sort order defined for DatasetInfo maintains this. See DatasetInfo.compareTo().
         */
        List<DatasetResource> datasetsResources = new ArrayList<>(datasets.values());
        Collections.sort(datasetsResources);
        for (DatasetResource dsr : datasetsResources) {
            if (isCandidateDatasetForEviction(dsr)) {
                closeDataset(dsr);
                LOGGER.info(() -> "Evicted Dataset" + dsr.getDatasetID());
                return true;
            }
        }
        return false;
    }

    private boolean isCandidateDatasetForEviction(DatasetResource dsr) {
        for (PrimaryIndexOperationTracker opTracker : dsr.getOpTrackers()) {
            if (opTracker.getNumActiveOperations() != 0) {
                return false;
            }
        }
        if (dsr.getDatasetInfo().getReferenceCount() != 0 || !dsr.getDatasetInfo().isOpen()
                || dsr.isMetadataDataset()) {
            return false;
        }

        return true;
    }

    public DatasetResource getDatasetLifecycle(int did) {
        DatasetResource dsr = datasets.get(did);
        if (dsr != null) {
            return dsr;
        }
        synchronized (datasets) {
            dsr = datasets.get(did);
            if (dsr == null) {
                DatasetInfo dsInfo = new DatasetInfo(did);
                DatasetVirtualBufferCaches vbcs = new DatasetVirtualBufferCaches(did, storageProperties,
                        memoryManager.getNumPages(did), numPartitions);
                dsr = new DatasetResource(dsInfo, vbcs);
                datasets.put(did, dsr);
            }
            return dsr;
        }
    }

    @Override
    public DatasetInfo getDatasetInfo(int datasetID) {
        return getDatasetLifecycle(datasetID).getDatasetInfo();
    }

    @Override
    public synchronized void close(String resourcePath) throws HyracksDataException {
        DatasetResource dsr = null;
        IndexInfo iInfo = null;
        try {
            validateDatasetLifecycleManagerState();
            int did = getDIDfromResourcePath(resourcePath);
            long resourceID = getResourceIDfromResourcePath(resourcePath);
            dsr = datasets.get(did);
            if (dsr == null) {
                throw HyracksDataException.create(ErrorCode.NO_INDEX_FOUND_WITH_RESOURCE_ID, resourceID);
            }
            iInfo = dsr.getIndexInfo(resourceID);
            if (iInfo == null) {
                throw HyracksDataException.create(ErrorCode.NO_INDEX_FOUND_WITH_RESOURCE_ID, resourceID);
            }
        } finally {
            // Regardless of what exception is thrown in the try-block (e.g., line 279),
            // we have to un-touch the index and dataset.
            if (iInfo != null) {
                iInfo.untouch();
            }
            if (dsr != null) {
                dsr.untouch();
            }
        }
    }

    @Override
    public synchronized List<IIndex> getOpenResources() {
        List<IndexInfo> openIndexesInfo = getOpenIndexesInfo();
        List<IIndex> openIndexes = new ArrayList<>();
        for (IndexInfo iInfo : openIndexesInfo) {
            openIndexes.add(iInfo.getIndex());
        }
        return openIndexes;
    }

    @Override
    public synchronized List<IndexInfo> getOpenIndexesInfo() {
        List<IndexInfo> openIndexesInfo = new ArrayList<>();
        for (DatasetResource dsr : datasets.values()) {
            for (IndexInfo iInfo : dsr.getIndexes().values()) {
                if (iInfo.isOpen()) {
                    openIndexesInfo.add(iInfo);
                }
            }
        }
        return openIndexesInfo;
    }

    private DatasetVirtualBufferCaches getVirtualBufferCaches(int datasetID) {
        return getDatasetLifecycle(datasetID).getVirtualBufferCaches();
    }

    @Override
    public List<IVirtualBufferCache> getVirtualBufferCaches(int datasetID, int ioDeviceNum) {
        DatasetVirtualBufferCaches dvbcs = getVirtualBufferCaches(datasetID);
        return dvbcs.getVirtualBufferCaches(this, ioDeviceNum);
    }

    private void removeDatasetFromCache(int datasetID) throws HyracksDataException {
        deallocateDatasetMemory(datasetID);
        datasets.remove(datasetID);
    }

    @Override
    public synchronized PrimaryIndexOperationTracker getOperationTracker(int datasetId, int partition) {
        DatasetResource dataset = datasets.get(datasetId);
        PrimaryIndexOperationTracker opTracker = dataset.getOpTracker(partition);
        if (opTracker == null) {
            populateOpTrackerAndIdGenerator(dataset, partition);
            opTracker = dataset.getOpTracker(partition);
        }
        return opTracker;
    }

    @Override
    public synchronized ILSMComponentIdGenerator getComponentIdGenerator(int datasetId, int partition) {
        DatasetResource dataset = datasets.get(datasetId);
        ILSMComponentIdGenerator generator = dataset.getComponentIdGenerator(partition);
        if (generator == null) {
            populateOpTrackerAndIdGenerator(dataset, partition);
            generator = dataset.getComponentIdGenerator(partition);
        }
        return generator;
    }

    private void populateOpTrackerAndIdGenerator(DatasetResource dataset, int partition) {
        ILSMComponentIdGenerator idGenerator = new LSMComponentIdGenerator();
        PrimaryIndexOperationTracker opTracker = new PrimaryIndexOperationTracker(dataset.getDatasetID(), partition,
                logManager, dataset.getDatasetInfo(), idGenerator);
        dataset.setPrimaryIndexOperationTracker(partition, opTracker);
        dataset.setIdGenerator(partition, idGenerator);
    }

    private void validateDatasetLifecycleManagerState() throws HyracksDataException {
        if (stopped) {
            throw new HyracksDataException(DatasetLifecycleManager.class.getSimpleName() + " was stopped.");
        }
    }

    @Override
    public void start() {
        // no op
    }

    @Override
    public synchronized void flushAllDatasets() throws HyracksDataException {
        for (DatasetResource dsr : datasets.values()) {
            flushDatasetOpenIndexes(dsr, false);
        }
    }

    @Override
    public synchronized void flushDataset(int datasetId, boolean asyncFlush) throws HyracksDataException {
        DatasetResource dsr = datasets.get(datasetId);
        if (dsr != null) {
            flushDatasetOpenIndexes(dsr, asyncFlush);
        }
    }

    @Override
    public synchronized void scheduleAsyncFlushForLaggingDatasets(long targetLSN) throws HyracksDataException {
        //schedule flush for datasets with min LSN (Log Serial Number) < targetLSN
        for (DatasetResource dsr : datasets.values()) {
            for (PrimaryIndexOperationTracker opTracker : dsr.getOpTrackers()) {
                // check all partitions
                synchronized (opTracker) {
                    scheduleAsyncFlushForLaggingDatasetPartition(dsr, opTracker, targetLSN);
                }
            }
        }
    }

    private void scheduleAsyncFlushForLaggingDatasetPartition(DatasetResource dsr,
            PrimaryIndexOperationTracker opTracker, long targetLSN) throws HyracksDataException {
        int partition = opTracker.getPartition();
        for (ILSMIndex lsmIndex : dsr.getDatasetInfo().getDatasetPartitionOpenIndexes(partition)) {
            AbstractLSMIOOperationCallback ioCallback =
                    (AbstractLSMIOOperationCallback) lsmIndex.getIOOperationCallback();
            if (!(lsmIndex.isCurrentMutableComponentEmpty() || ioCallback.hasPendingFlush()
                    || opTracker.isFlushLogCreated() || opTracker.isFlushOnExit())) {
                long firstLSN = ioCallback.getFirstLSN();
                if (firstLSN < targetLSN) {
                    LOGGER.info("Checkpoint flush dataset {} partition {}", dsr.getDatasetID(), partition);
                    opTracker.setFlushOnExit(true);
                    if (opTracker.getNumActiveOperations() == 0) {
                        // No Modify operations currently, we need to trigger the flush and we can do so safely
                        opTracker.flushIfRequested();
                    }
                    break;
                }
            }
        }
    }

    /*
     * This method can only be called asynchronously safely if we're sure no modify operation
     * will take place until the flush is scheduled
     */
    private void flushDatasetOpenIndexes(DatasetResource dsr, boolean asyncFlush) throws HyracksDataException {
        DatasetInfo dsInfo = dsr.getDatasetInfo();
        if (dsInfo.isExternal()) {
            // no memory components for external dataset
            return;
        }
        for (PrimaryIndexOperationTracker primaryOpTracker : dsr.getOpTrackers()) {
            // flush each partition one by one
            if (primaryOpTracker.getNumActiveOperations() > 0) {
                throw new IllegalStateException(
                        "flushDatasetOpenIndexes is called on a dataset with currently active operations");
            }
            int partition = primaryOpTracker.getPartition();
            Collection<ILSMIndex> indexes = dsInfo.getDatasetPartitionOpenIndexes(partition);
            ILSMComponentIdGenerator idGenerator = getComponentIdGenerator(dsInfo.getDatasetID(), partition);
            idGenerator.refresh();

            if (dsInfo.isDurable()) {
                synchronized (logRecord) {
                    TransactionUtil.formFlushLogRecord(logRecord, dsInfo.getDatasetID(), null);
                    try {
                        logManager.log(logRecord);
                    } catch (ACIDException e) {
                        throw new HyracksDataException("could not write flush log while closing dataset", e);
                    }

                    try {
                        //notification will come from LogBuffer class (notifyFlushTerminator)
                        logRecord.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw HyracksDataException.create(e);
                    }
                }
            }
            for (ILSMIndex index : indexes) {
                //update resource lsn
                AbstractLSMIOOperationCallback ioOpCallback =
                        (AbstractLSMIOOperationCallback) index.getIOOperationCallback();
                ioOpCallback.updateLastLSN(logRecord.getLSN());
            }

            if (asyncFlush) {
                for (ILSMIndex index : indexes) {
                    ILSMIndexAccessor accessor = index.createAccessor(NoOpIndexAccessParameters.INSTANCE);
                    accessor.scheduleFlush(index.getIOOperationCallback());
                }
            } else {
                for (ILSMIndex index : indexes) {
                    // TODO: This is not efficient since we flush the indexes sequentially.
                    // Think of a way to allow submitting the flush requests concurrently.
                    // We don't do them concurrently because this may lead to a deadlock scenario
                    // between the DatasetLifeCycleManager and the PrimaryIndexOperationTracker.
                    ILSMIndexAccessor accessor = index.createAccessor(NoOpIndexAccessParameters.INSTANCE);
                    accessor.scheduleFlush(index.getIOOperationCallback());
                    // Wait for the above flush op.
                    dsInfo.waitForIO();
                }
            }
        }

    }

    private void closeDataset(DatasetResource dsr) throws HyracksDataException {
        // First wait for any ongoing IO operations
        DatasetInfo dsInfo = dsr.getDatasetInfo();
        dsInfo.waitForIO();
        try {
            flushDatasetOpenIndexes(dsr, false);
        } catch (Exception e) {
            throw HyracksDataException.create(e);
        }
        for (IndexInfo iInfo : dsInfo.getIndexes().values()) {
            closeIndex(iInfo);
        }
        removeDatasetFromCache(dsInfo.getDatasetID());
        dsInfo.setOpen(false);
    }

    @Override
    public synchronized void closeAllDatasets() throws HyracksDataException {
        ArrayList<DatasetResource> openDatasets = new ArrayList<>(datasets.values());
        for (DatasetResource dsr : openDatasets) {
            closeDataset(dsr);
        }
    }

    @Override
    public synchronized void closeUserDatasets() throws HyracksDataException {
        ArrayList<DatasetResource> openDatasets = new ArrayList<>(datasets.values());
        for (DatasetResource dsr : openDatasets) {
            if (!dsr.isMetadataDataset()) {
                closeDataset(dsr);
            }
        }
    }

    @Override
    public synchronized void stop(boolean dumpState, OutputStream outputStream) throws IOException {
        if (stopped) {
            return;
        }
        if (dumpState) {
            dumpState(outputStream);
        }

        closeAllDatasets();

        datasets.clear();
        stopped = true;
    }

    @Override
    public void dumpState(OutputStream outputStream) throws IOException {
        StringBuilder sb = new StringBuilder();

        sb.append(String.format("Memory budget = %d%n", storageProperties.getMemoryComponentGlobalBudget()));
        sb.append(String.format("Memory available = %d%n", memoryManager.getAvailable()));
        sb.append("\n");

        String dsHeaderFormat = "%-10s %-6s %-16s %-12s\n";
        String dsFormat = "%-10d %-6b %-16d %-12d\n";
        String idxHeaderFormat = "%-10s %-11s %-6s %-16s %-6s\n";
        String idxFormat = "%-10d %-11d %-6b %-16d %-6s\n";

        sb.append("[Datasets]\n");
        sb.append(String.format(dsHeaderFormat, "DatasetID", "Open", "Reference Count", "Last Access"));
        for (DatasetResource dsr : datasets.values()) {
            DatasetInfo dsInfo = dsr.getDatasetInfo();
            sb.append(String.format(dsFormat, dsInfo.getDatasetID(), dsInfo.isOpen(), dsInfo.getReferenceCount(),
                    dsInfo.getLastAccess()));
        }
        sb.append("\n");

        sb.append("[Indexes]\n");
        sb.append(String.format(idxHeaderFormat, "DatasetID", "ResourceID", "Open", "Reference Count", "Index"));
        for (DatasetResource dsr : datasets.values()) {
            DatasetInfo dsInfo = dsr.getDatasetInfo();
            dsInfo.getIndexes().forEach((key, iInfo) -> sb.append(String.format(idxFormat, dsInfo.getDatasetID(), key,
                    iInfo.isOpen(), iInfo.getReferenceCount(), iInfo.getIndex())));
        }
        outputStream.write(sb.toString().getBytes());
    }

    private synchronized void deallocateDatasetMemory(int datasetId) throws HyracksDataException {
        DatasetResource dsr = datasets.get(datasetId);
        if (dsr == null) {
            throw new HyracksDataException(
                    "Failed to allocate memory for dataset with ID " + datasetId + " since it is not open.");
        }
        DatasetInfo dsInfo = dsr.getDatasetInfo();
        if (dsInfo == null) {
            throw new HyracksDataException(
                    "Failed to deallocate memory for dataset with ID " + datasetId + " since it is not open.");
        }
        synchronized (dsInfo) {
            if (dsInfo.isOpen() && dsInfo.isMemoryAllocated()) {
                memoryManager.deallocate(datasetId);
                dsInfo.setMemoryAllocated(false);
            }
        }
    }

    @Override
    public synchronized void allocateMemory(String resourcePath) throws HyracksDataException {
        //a resource name in the case of DatasetLifecycleManager is a dataset id which is passed to the ResourceHeapBufferAllocator.
        int datasetId = Integer.parseInt(resourcePath);
        DatasetResource dsr = datasets.get(datasetId);
        if (dsr == null) {
            throw new HyracksDataException(
                    "Failed to allocate memory for dataset with ID " + datasetId + " since it is not open.");
        }
        DatasetInfo dsInfo = dsr.getDatasetInfo();
        synchronized (dsInfo) {
            // This is not needed for external datasets' indexes since they never use the virtual buffer cache.
            if (!dsInfo.isMemoryAllocated() && !dsInfo.isExternal()) {
                while (!memoryManager.allocate(datasetId)) {
                    if (!evictCandidateDataset()) {
                        throw new HyracksDataException("Cannot allocate dataset " + dsInfo.getDatasetID()
                                + " memory since memory budget would be exceeded.");
                    }
                }
                dsInfo.setMemoryAllocated(true);
            }
        }
    }

    @Override
    public void flushDataset(IReplicationStrategy replicationStrategy) throws HyracksDataException {
        for (DatasetResource dsr : datasets.values()) {
            if (replicationStrategy.isMatch(dsr.getDatasetID())) {
                flushDatasetOpenIndexes(dsr, false);
            }
        }
    }

    private void closeIndex(IndexInfo indexInfo) throws HyracksDataException {
        if (indexInfo.isOpen()) {
            ILSMOperationTracker opTracker = indexInfo.getIndex().getOperationTracker();
            synchronized (opTracker) {
                indexInfo.getIndex().deactivate(false);
            }
            indexCheckpointManagerProvider.close(DatasetResourceReference.of(indexInfo.getLocalResource()));
            indexInfo.setOpen(false);
        }
    }
}

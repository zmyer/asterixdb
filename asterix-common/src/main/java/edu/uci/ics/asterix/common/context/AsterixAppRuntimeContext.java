package edu.uci.ics.asterix.common.context;

import java.io.IOException;
import java.util.logging.Logger;

import edu.uci.ics.asterix.common.config.AsterixCompilerProperties;
import edu.uci.ics.asterix.common.config.AsterixExternalProperties;
import edu.uci.ics.asterix.common.config.AsterixMetadataProperties;
import edu.uci.ics.asterix.common.config.AsterixPropertiesAccessor;
import edu.uci.ics.asterix.common.config.AsterixStorageProperties;
import edu.uci.ics.asterix.common.config.AsterixTransactionProperties;
import edu.uci.ics.asterix.common.config.IAsterixPropertiesProvider;
import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.transaction.management.exception.ACIDException;
import edu.uci.ics.asterix.transaction.management.ioopcallbacks.LSMBTreeIOOperationCallbackFactory;
import edu.uci.ics.asterix.transaction.management.ioopcallbacks.LSMInvertedIndexIOOperationCallbackFactory;
import edu.uci.ics.asterix.transaction.management.ioopcallbacks.LSMRTreeIOOperationCallbackFactory;
import edu.uci.ics.asterix.transaction.management.opcallbacks.IndexOperationTrackerFactory;
import edu.uci.ics.asterix.transaction.management.resource.PersistentLocalResourceRepository;
import edu.uci.ics.asterix.transaction.management.resource.PersistentLocalResourceRepositoryFactory;
import edu.uci.ics.asterix.transaction.management.service.recovery.IAsterixAppRuntimeContextProvider;
import edu.uci.ics.asterix.transaction.management.service.transaction.TransactionSubsystem;
import edu.uci.ics.hyracks.api.application.INCApplicationContext;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.api.io.IIOManager;
import edu.uci.ics.hyracks.storage.am.common.api.IIndex;
import edu.uci.ics.hyracks.storage.am.common.api.IIndexLifecycleManager;
import edu.uci.ics.hyracks.storage.am.common.dataflow.IndexLifecycleManager;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMIOOperationCallbackProvider;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMIOOperationScheduler;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMMergePolicy;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMOperationTrackerFactory;
import edu.uci.ics.hyracks.storage.am.lsm.common.impls.SynchronousScheduler;
import edu.uci.ics.hyracks.storage.common.buffercache.BufferCache;
import edu.uci.ics.hyracks.storage.common.buffercache.ClockPageReplacementStrategy;
import edu.uci.ics.hyracks.storage.common.buffercache.DelayPageCleanerPolicy;
import edu.uci.ics.hyracks.storage.common.buffercache.HeapBufferAllocator;
import edu.uci.ics.hyracks.storage.common.buffercache.IBufferCache;
import edu.uci.ics.hyracks.storage.common.buffercache.ICacheMemoryAllocator;
import edu.uci.ics.hyracks.storage.common.buffercache.IPageCleanerPolicy;
import edu.uci.ics.hyracks.storage.common.buffercache.IPageReplacementStrategy;
import edu.uci.ics.hyracks.storage.common.file.IFileMapManager;
import edu.uci.ics.hyracks.storage.common.file.IFileMapProvider;
import edu.uci.ics.hyracks.storage.common.file.ILocalResourceRepository;
import edu.uci.ics.hyracks.storage.common.file.ILocalResourceRepositoryFactory;
import edu.uci.ics.hyracks.storage.common.file.ResourceIdFactory;
import edu.uci.ics.hyracks.storage.common.file.ResourceIdFactoryProvider;

public class AsterixAppRuntimeContext implements IAsterixPropertiesProvider {
    private final INCApplicationContext ncApplicationContext;

    private AsterixCompilerProperties compilerProperties;
    private AsterixExternalProperties externalProperties;
    private AsterixMetadataProperties metadataProperties;
    private AsterixStorageProperties storageProperties;
    private AsterixTransactionProperties txnProperties;

    private IIndexLifecycleManager indexLifecycleManager;
    private IFileMapManager fileMapManager;
    private IBufferCache bufferCache;
    private TransactionSubsystem txnSubsystem;

    private ILSMMergePolicy mergePolicy;
    private ILSMOperationTrackerFactory lsmBTreeOpTrackerFactory;
    private ILSMOperationTrackerFactory lsmRTreeOpTrackerFactory;
    private ILSMOperationTrackerFactory lsmInvertedIndexOpTrackerFactory;
    private ILSMIOOperationScheduler lsmIOScheduler;
    private PersistentLocalResourceRepository localResourceRepository;
    private ResourceIdFactory resourceIdFactory;
    private IIOManager ioManager;
    private boolean isShuttingdown;

    public AsterixAppRuntimeContext(INCApplicationContext ncApplicationContext) {
        this.ncApplicationContext = ncApplicationContext;
    }

    public void initialize() throws IOException, ACIDException, AsterixException {
        AsterixPropertiesAccessor propertiesAccessor = new AsterixPropertiesAccessor();
        compilerProperties = new AsterixCompilerProperties(propertiesAccessor);
        externalProperties = new AsterixExternalProperties(propertiesAccessor);
        metadataProperties = new AsterixMetadataProperties(propertiesAccessor);
        storageProperties = new AsterixStorageProperties(propertiesAccessor);
        txnProperties = new AsterixTransactionProperties(propertiesAccessor);

        Logger.getLogger("edu.uci.ics").setLevel(externalProperties.getLogLevel());

        fileMapManager = new AsterixFileMapManager();
        ICacheMemoryAllocator allocator = new HeapBufferAllocator();
        IPageReplacementStrategy prs = new ClockPageReplacementStrategy();
        IPageCleanerPolicy pcp = new DelayPageCleanerPolicy(600000);
        ioManager = ncApplicationContext.getRootContext().getIOManager();
        bufferCache = new BufferCache(ioManager, allocator, prs, pcp, fileMapManager,
                storageProperties.getBufferCachePageSize(), storageProperties.getBufferCacheNumPages(),
                storageProperties.getBufferCacheMaxOpenFiles());

        indexLifecycleManager = new IndexLifecycleManager(storageProperties.getMemoryComponentGlobalBudget());

        lsmIOScheduler = SynchronousScheduler.INSTANCE;
        mergePolicy = new ConstantMergePolicy(storageProperties.getLSMIndexMergeThreshold(), this);
        lsmBTreeOpTrackerFactory = new IndexOperationTrackerFactory(LSMBTreeIOOperationCallbackFactory.INSTANCE);
        lsmRTreeOpTrackerFactory = new IndexOperationTrackerFactory(LSMRTreeIOOperationCallbackFactory.INSTANCE);
        lsmInvertedIndexOpTrackerFactory = new IndexOperationTrackerFactory(
                LSMInvertedIndexIOOperationCallbackFactory.INSTANCE);

        ILocalResourceRepositoryFactory persistentLocalResourceRepositoryFactory = new PersistentLocalResourceRepositoryFactory(
                ioManager);
        localResourceRepository = (PersistentLocalResourceRepository) persistentLocalResourceRepositoryFactory
                .createRepository();
        resourceIdFactory = (new ResourceIdFactoryProvider(localResourceRepository)).createResourceIdFactory();

        IAsterixAppRuntimeContextProvider asterixAppRuntimeContextProvider = new AsterixAppRuntimeContextProviderForRecovery(
                this);
        txnSubsystem = new TransactionSubsystem(ncApplicationContext.getNodeId(), asterixAppRuntimeContextProvider);
        isShuttingdown = false;
    }

    public boolean isShuttingdown() {
        return isShuttingdown;
    }

    public void setShuttingdown(boolean isShuttingdown) {
        this.isShuttingdown = isShuttingdown;
    }

    public void deinitialize() throws HyracksDataException {
        bufferCache.close();
        for (IIndex index : indexLifecycleManager.getOpenIndexes()) {
            index.deactivate();
        }
    }

    public IBufferCache getBufferCache() {
        return bufferCache;
    }

    public IFileMapProvider getFileMapManager() {
        return fileMapManager;
    }

    public TransactionSubsystem getTransactionSubsystem() {
        return txnSubsystem;
    }

    public IIndexLifecycleManager getIndexLifecycleManager() {
        return indexLifecycleManager;
    }

    public ILSMMergePolicy getLSMMergePolicy() {
        return mergePolicy;
    }

    public double getBloomFilterFalsePositiveRate() {
        return storageProperties.getBloomFilterFalsePositiveRate();
    }

    public ILSMOperationTrackerFactory getLSMBTreeOperationTrackerFactory() {
        return lsmBTreeOpTrackerFactory;
    }

    public ILSMOperationTrackerFactory getLSMRTreeOperationTrackerFactory() {
        return lsmRTreeOpTrackerFactory;
    }

    public ILSMOperationTrackerFactory getLSMInvertedIndexOperationTrackerFactory() {
        return lsmInvertedIndexOpTrackerFactory;
    }

    public ILSMIOOperationCallbackProvider getLSMBTreeIOOperationCallbackProvider() {
        return AsterixRuntimeComponentsProvider.LSMBTREE_PROVIDER;
    }

    public ILSMIOOperationCallbackProvider getLSMRTreeIOOperationCallbackProvider() {
        return AsterixRuntimeComponentsProvider.LSMRTREE_PROVIDER;
    }

    public ILSMIOOperationCallbackProvider getLSMInvertedIndexIOOperationCallbackProvider() {
        return AsterixRuntimeComponentsProvider.LSMINVERTEDINDEX_PROVIDER;
    }

    public ILSMIOOperationCallbackProvider getNoOpIOOperationCallbackProvider() {
        return AsterixRuntimeComponentsProvider.NOINDEX_PROVIDER;
    }

    public ILSMIOOperationScheduler getLSMIOScheduler() {
        return lsmIOScheduler;
    }

    public ILocalResourceRepository getLocalResourceRepository() {
        return localResourceRepository;
    }

    public ResourceIdFactory getResourceIdFactory() {
        return resourceIdFactory;
    }

    public IIOManager getIOManager() {
        return ioManager;
    }

    @Override
    public AsterixStorageProperties getStorageProperties() {
        return storageProperties;
    }

    @Override
    public AsterixTransactionProperties getTransactionProperties() {
        return txnProperties;
    }

    @Override
    public AsterixCompilerProperties getCompilerProperties() {
        return compilerProperties;
    }

    @Override
    public AsterixMetadataProperties getMetadataProperties() {
        return metadataProperties;
    }

    @Override
    public AsterixExternalProperties getExternalProperties() {
        return externalProperties;
    }
}
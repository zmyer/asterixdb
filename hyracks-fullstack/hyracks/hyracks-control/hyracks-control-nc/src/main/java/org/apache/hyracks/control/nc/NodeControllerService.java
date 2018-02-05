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
package org.apache.hyracks.control.nc;

import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.hyracks.api.application.INCApplication;
import org.apache.hyracks.api.client.NodeControllerInfo;
import org.apache.hyracks.api.comm.NetworkAddress;
import org.apache.hyracks.api.control.CcId;
import org.apache.hyracks.api.dataset.IDatasetPartitionManager;
import org.apache.hyracks.api.deployment.DeploymentId;
import org.apache.hyracks.api.exceptions.ErrorCode;
import org.apache.hyracks.api.exceptions.HyracksException;
import org.apache.hyracks.api.io.IODeviceHandle;
import org.apache.hyracks.api.job.ActivityClusterGraph;
import org.apache.hyracks.api.job.DeployedJobSpecId;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.api.job.JobParameterByteStore;
import org.apache.hyracks.api.lifecycle.ILifeCycleComponentManager;
import org.apache.hyracks.api.lifecycle.LifeCycleComponentManager;
import org.apache.hyracks.api.service.IControllerService;
import org.apache.hyracks.control.common.base.IClusterController;
import org.apache.hyracks.control.common.config.ConfigManager;
import org.apache.hyracks.control.common.context.ServerContext;
import org.apache.hyracks.control.common.controllers.NCConfig;
import org.apache.hyracks.control.common.controllers.NodeParameters;
import org.apache.hyracks.control.common.controllers.NodeRegistration;
import org.apache.hyracks.control.common.heartbeat.HeartbeatData;
import org.apache.hyracks.control.common.heartbeat.HeartbeatSchema;
import org.apache.hyracks.control.common.ipc.CCNCFunctions;
import org.apache.hyracks.control.common.ipc.ClusterControllerRemoteProxy;
import org.apache.hyracks.control.common.job.profiling.om.JobProfile;
import org.apache.hyracks.control.common.work.FutureValue;
import org.apache.hyracks.control.common.work.WorkQueue;
import org.apache.hyracks.control.nc.application.NCServiceContext;
import org.apache.hyracks.control.nc.dataset.DatasetPartitionManager;
import org.apache.hyracks.control.nc.io.IOManager;
import org.apache.hyracks.control.nc.io.profiling.IIOCounter;
import org.apache.hyracks.control.nc.io.profiling.IOCounterFactory;
import org.apache.hyracks.control.nc.net.DatasetNetworkManager;
import org.apache.hyracks.control.nc.net.MessagingNetworkManager;
import org.apache.hyracks.control.nc.net.NetworkManager;
import org.apache.hyracks.control.nc.partitions.PartitionManager;
import org.apache.hyracks.control.nc.resources.memory.MemoryManager;
import org.apache.hyracks.control.nc.work.BuildJobProfilesWork;
import org.apache.hyracks.ipc.api.IIPCEventListener;
import org.apache.hyracks.ipc.api.IIPCHandle;
import org.apache.hyracks.ipc.api.IPCPerformanceCounters;
import org.apache.hyracks.ipc.exceptions.IPCException;
import org.apache.hyracks.ipc.impl.IPCSystem;
import org.apache.hyracks.net.protocols.muxdemux.FullFrameChannelInterfaceFactory;
import org.apache.hyracks.net.protocols.muxdemux.MuxDemuxPerformanceCounters;
import org.apache.hyracks.util.ExitUtil;
import org.apache.hyracks.util.InvokeUtil;
import org.apache.hyracks.util.PidHelper;
import org.apache.hyracks.util.trace.ITracer;
import org.apache.hyracks.util.trace.Tracer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kohsuke.args4j.CmdLineException;

public class NodeControllerService implements IControllerService {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final double MEMORY_FUDGE_FACTOR = 0.8;
    private static final long ONE_SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);

    private NCConfig ncConfig;

    private final String id;

    private final IOManager ioManager;

    private IPCSystem ipc;

    private PartitionManager partitionManager;

    private NetworkManager netManager;

    private IDatasetPartitionManager datasetPartitionManager;

    private DatasetNetworkManager datasetNetworkManager;

    private final WorkQueue workQueue;

    private final Timer timer;

    private boolean registrationPending;

    private Exception registrationException;

    private IClusterController primaryCcs;

    private final Map<CcId, IClusterController> ccsMap = Collections.synchronizedMap(new HashMap<>());

    private final Map<JobId, Joblet> jobletMap;

    private final Map<Long, ActivityClusterGraph> deployedJobSpecActivityClusterGraphMap;

    private final Map<JobId, JobParameterByteStore> jobParameterByteStoreMap = new HashMap<>();

    private ExecutorService executor;

    private NodeParameters nodeParameters;

    private Map<IClusterController, Thread> heartbeatThreads = new ConcurrentHashMap<>();

    private Map<IClusterController, Timer> ccTimers = new ConcurrentHashMap<>();

    private final ServerContext serverCtx;

    private NCServiceContext serviceCtx;

    private final INCApplication application;

    private final ILifeCycleComponentManager lccm;

    private final MemoryMXBean memoryMXBean;

    private final List<GarbageCollectorMXBean> gcMXBeans;

    private final ThreadMXBean threadMXBean;

    private final RuntimeMXBean runtimeMXBean;

    private final OperatingSystemMXBean osMXBean;

    private final Mutable<FutureValue<Map<String, NodeControllerInfo>>> getNodeControllerInfosAcceptor;

    private final MemoryManager memoryManager;

    private StackTraceElement[] shutdownCallStack;

    private IIOCounter ioCounter;

    private MessagingNetworkManager messagingNetManager;

    private final ConfigManager configManager;

    private NodeRegistration nodeRegistration;

    private final AtomicLong maxJobId = new AtomicLong(-1);

    static {
        ExitUtil.init();
    }

    private NCShutdownHook ncShutdownHook;

    public NodeControllerService(NCConfig config) throws Exception {
        this(config, getApplication(config));
    }

    public NodeControllerService(NCConfig config, INCApplication application) throws IOException, CmdLineException {
        ncConfig = config;
        configManager = ncConfig.getConfigManager();
        if (application == null) {
            throw new IllegalArgumentException("INCApplication cannot be null");
        }
        configManager.processConfig();
        this.application = application;
        id = ncConfig.getNodeId();
        if (id == null) {
            throw new HyracksException("id not set");
        }
        lccm = new LifeCycleComponentManager();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Setting uncaught exception handler " + getLifeCycleComponentManager());
        }
        // Set shutdown hook before so it doesn't have the same uncaught exception handler
        ncShutdownHook = new NCShutdownHook(this);
        Runtime.getRuntime().addShutdownHook(ncShutdownHook);
        Thread.currentThread().setUncaughtExceptionHandler(getLifeCycleComponentManager());
        ioManager =
                new IOManager(IODeviceHandle.getDevices(ncConfig.getIODevices()), application.getFileDeviceResolver());

        workQueue = new WorkQueue(id, Thread.NORM_PRIORITY); // Reserves MAX_PRIORITY of the heartbeat thread.
        jobletMap = new ConcurrentHashMap<>();
        deployedJobSpecActivityClusterGraphMap = new Hashtable<>();
        timer = new Timer(true);
        serverCtx = new ServerContext(ServerContext.ServerType.NODE_CONTROLLER,
                new File(new File(NodeControllerService.class.getName()), id));
        memoryMXBean = ManagementFactory.getMemoryMXBean();
        gcMXBeans = ManagementFactory.getGarbageCollectorMXBeans();
        threadMXBean = ManagementFactory.getThreadMXBean();
        runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        osMXBean = ManagementFactory.getOperatingSystemMXBean();
        getNodeControllerInfosAcceptor = new MutableObject<>();
        memoryManager = new MemoryManager((long) (memoryMXBean.getHeapMemoryUsage().getMax() * MEMORY_FUDGE_FACTOR));
        ioCounter = IOCounterFactory.INSTANCE.getIOCounter();
    }

    public IOManager getIoManager() {
        return ioManager;
    }

    @Override
    public NCServiceContext getContext() {
        return serviceCtx;
    }

    public ILifeCycleComponentManager getLifeCycleComponentManager() {
        return lccm;
    }

    public Map<String, NodeControllerInfo> getNodeControllersInfo() throws Exception {
        FutureValue<Map<String, NodeControllerInfo>> fv = new FutureValue<>();
        synchronized (getNodeControllerInfosAcceptor) {
            while (getNodeControllerInfosAcceptor.getValue() != null) {
                getNodeControllerInfosAcceptor.wait();
            }
            getNodeControllerInfosAcceptor.setValue(fv);
        }
        primaryCcs.getNodeControllerInfos();
        return fv.get();
    }

    void setNodeControllersInfo(Map<String, NodeControllerInfo> ncInfos) {
        FutureValue<Map<String, NodeControllerInfo>> fv;
        synchronized (getNodeControllerInfosAcceptor) {
            fv = getNodeControllerInfosAcceptor.getValue();
            getNodeControllerInfosAcceptor.setValue(null);
            getNodeControllerInfosAcceptor.notifyAll();
        }
        fv.setValue(ncInfos);
    }

    private void init() throws Exception {
        ioManager.setExecutor(executor);
        datasetPartitionManager = new DatasetPartitionManager(this, executor, ncConfig.getResultManagerMemory(),
                ncConfig.getResultTTL(), ncConfig.getResultSweepThreshold());
        datasetNetworkManager = new DatasetNetworkManager(ncConfig.getResultListenAddress(),
                ncConfig.getResultListenPort(), datasetPartitionManager, ncConfig.getNetThreadCount(),
                ncConfig.getNetBufferCount(), ncConfig.getResultPublicAddress(), ncConfig.getResultPublicPort(),
                FullFrameChannelInterfaceFactory.INSTANCE);
        if (ncConfig.getMessagingListenAddress() != null && serviceCtx.getMessagingChannelInterfaceFactory() != null) {
            messagingNetManager = new MessagingNetworkManager(this, ncConfig.getMessagingListenAddress(),
                    ncConfig.getMessagingListenPort(), ncConfig.getNetThreadCount(),
                    ncConfig.getMessagingPublicAddress(), ncConfig.getMessagingPublicPort(),
                    serviceCtx.getMessagingChannelInterfaceFactory());
        }
    }

    @Override
    public void start() throws Exception {
        LOGGER.log(Level.INFO, "Starting NodeControllerService");
        ipc = new IPCSystem(new InetSocketAddress(ncConfig.getClusterListenAddress(), ncConfig.getClusterListenPort()),
                new NodeControllerIPCI(this), new CCNCFunctions.SerializerDeserializer());
        ipc.start();
        partitionManager = new PartitionManager(this);
        netManager = new NetworkManager(ncConfig.getDataListenAddress(), ncConfig.getDataListenPort(), partitionManager,
                ncConfig.getNetThreadCount(), ncConfig.getNetBufferCount(), ncConfig.getDataPublicAddress(),
                ncConfig.getDataPublicPort(), FullFrameChannelInterfaceFactory.INSTANCE);
        netManager.start();
        startApplication();
        init();
        datasetNetworkManager.start();
        if (messagingNetManager != null) {
            messagingNetManager.start();
        }

        final InetSocketAddress ccAddress =
                new InetSocketAddress(ncConfig.getClusterAddress(), ncConfig.getClusterPort());
        this.primaryCcs = addCc(ncConfig.getClusterControllerId(), ccAddress);

        workQueue.start();

        // Schedule tracing a human-readable datetime
        timer.schedule(new TraceCurrentTimeTask(serviceCtx.getTracer()), 0, 60000);

        LOGGER.log(Level.INFO, "Started NodeControllerService");
        application.startupCompleted();
    }

    public ClusterControllerRemoteProxy addCc(CcId ccId, InetSocketAddress ccAddress) throws Exception {
        ClusterControllerRemoteProxy ccProxy;
        synchronized (ccsMap) {
            if (ccsMap.containsKey(ccId)) {
                throw new IllegalStateException("cc already registered: " + ccId);
            }
            final IIPCEventListener ipcEventListener = new IIPCEventListener() {
                @Override
                public void ipcHandleRestored(IIPCHandle handle) throws IPCException {
                    // we need to re-register in case of NC -> CC connection reset
                    try {
                        registerNode(ccsMap.get(ccId));
                    } catch (Exception e) {
                        LOGGER.log(Level.WARN, "Failed Registering with cc", e);
                        throw new IPCException(e);
                    }
                }
            };
            ccProxy = new ClusterControllerRemoteProxy(ccId,
                    ipc.getHandle(ccAddress, ncConfig.getClusterConnectRetries(), 1, ipcEventListener));
            registerNode(ccProxy);
            ccsMap.put(ccId, ccProxy);
        }
        return ccProxy;
    }

    public void makePrimaryCc(CcId ccId) throws Exception {
        synchronized (ccsMap) {
            if (!ccsMap.containsKey(ccId)) {
                throw new IllegalArgumentException("unknown cc: " + ccId);
            }
            primaryCcs = ccsMap.get(ccId);
        }
    }

    public void removeCc(CcId ccId) throws Exception {
        synchronized (ccsMap) {
            final IClusterController ccs = ccsMap.get(ccId);
            if (ccs == null) {
                throw new IllegalArgumentException("unknown cc: " + ccId);
            }
            if (primaryCcs.equals(ccs)) {
                throw new IllegalStateException("cannot remove primary cc: " + ccId);
            }
            // TODO(mblow): consider how to handle running jobs
            ccs.unregisterNode(id);
            Thread hbThread = heartbeatThreads.remove(ccs);
            hbThread.interrupt();
            Timer ccTimer = ccTimers.remove(ccs);
            if (ccTimer != null) {
                ccTimer.cancel();
            }
        }
    }

    protected void registerNode(IClusterController ccs) throws Exception {
        LOGGER.info("Registering with Cluster Controller {}", ccs);
        registrationPending = true;
        HeartbeatSchema.GarbageCollectorInfo[] gcInfos = new HeartbeatSchema.GarbageCollectorInfo[gcMXBeans.size()];
        for (int i = 0; i < gcInfos.length; ++i) {
            gcInfos[i] = new HeartbeatSchema.GarbageCollectorInfo(gcMXBeans.get(i).getName());
        }
        HeartbeatSchema hbSchema = new HeartbeatSchema(gcInfos);
        // Use "public" versions of network addresses and ports, if defined
        InetSocketAddress ncAddress;
        if (ncConfig.getClusterPublicPort() == 0) {
            ncAddress = ipc.getSocketAddress();
        } else {
            ncAddress = new InetSocketAddress(ncConfig.getClusterPublicAddress(), ncConfig.getClusterPublicPort());
        }
        NetworkAddress datasetAddress = datasetNetworkManager.getPublicNetworkAddress();
        NetworkAddress netAddress = netManager.getPublicNetworkAddress();
        NetworkAddress messagingAddress =
                messagingNetManager != null ? messagingNetManager.getPublicNetworkAddress() : null;
        int allCores = osMXBean.getAvailableProcessors();
        nodeRegistration = new NodeRegistration(ncAddress, id, ncConfig, netAddress, datasetAddress, osMXBean.getName(),
                osMXBean.getArch(), osMXBean.getVersion(), allCores, runtimeMXBean.getVmName(),
                runtimeMXBean.getVmVersion(), runtimeMXBean.getVmVendor(), runtimeMXBean.getClassPath(),
                runtimeMXBean.getLibraryPath(), runtimeMXBean.getBootClassPath(), runtimeMXBean.getInputArguments(),
                runtimeMXBean.getSystemProperties(), hbSchema, messagingAddress, application.getCapacity(),
                PidHelper.getPid(), maxJobId.get());

        ccs.registerNode(nodeRegistration);

        completeNodeRegistration(ccs);

        // Start heartbeat generator.
        if (!heartbeatThreads.containsKey(ccs)) {
            Thread heartbeatThread =
                    new Thread(new HeartbeatTask(ccs, nodeParameters.getHeartbeatPeriod()), id + "-Heartbeat");
            heartbeatThread.setPriority(Thread.MAX_PRIORITY);
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();
            heartbeatThreads.put(ccs, heartbeatThread);
        }
        if (!ccTimers.containsKey(ccs) && nodeParameters.getProfileDumpPeriod() > 0) {
            Timer ccTimer = new Timer("Timer-" + ccs.getCcId(), true);
            // Schedule profile dump generator.
            ccTimer.schedule(new ProfileDumpTask(ccs), 0, nodeParameters.getProfileDumpPeriod());
            ccTimers.put(ccs, ccTimer);
        }

        LOGGER.info("Registering with Cluster Controller {} complete", ccs);
    }

    synchronized void setNodeRegistrationResult(NodeParameters parameters, Exception exception) {
        this.nodeParameters = parameters;
        this.registrationException = exception;
        this.registrationPending = false;
        notifyAll();
    }

    private synchronized void completeNodeRegistration(IClusterController ccs) throws Exception {
        while (registrationPending) {
            wait();
        }
        if (registrationException != null) {
            LOGGER.log(Level.WARN, "Registering with Cluster Controller failed with exception", registrationException);
            throw registrationException;
        }
        serviceCtx.setDistributedState(nodeParameters.getDistributedState());
        application.onRegisterNode(ccs.getCcId());
    }

    private void startApplication() throws Exception {
        serviceCtx = new NCServiceContext(this, serverCtx, ioManager, id, memoryManager, lccm,
                ncConfig.getNodeScopedAppConfig());
        application.init(serviceCtx);
        executor = Executors.newCachedThreadPool(serviceCtx.getThreadFactory());
        application.start(ncConfig.getAppArgsArray());
    }

    public void updateMaxJobId(JobId jobId) {
        maxJobId.getAndUpdate(currentMaxId -> Math.max(currentMaxId, jobId.getId()));
    }

    @Override
    public synchronized void stop() throws Exception {
        if (shutdownCallStack == null) {
            shutdownCallStack = new Throwable().getStackTrace();
            LOGGER.log(Level.INFO, "Stopping NodeControllerService");
            application.preStop();
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.log(Level.ERROR, "Some jobs failed to exit, continuing with abnormal shutdown");
            }
            partitionManager.close();
            datasetPartitionManager.close();
            netManager.stop();
            datasetNetworkManager.stop();
            if (messagingNetManager != null) {
                messagingNetManager.stop();
            }
            workQueue.stop();
            application.stop();
            /*
             * Stop heartbeats only after NC has stopped to avoid false node failure detection
             * on CC if an NC takes a long time to stop.
             */
            heartbeatThreads.values().parallelStream().forEach(t -> {
                t.interrupt();
                InvokeUtil.doUninterruptibly(() -> t.join(1000));
            });
            synchronized (ccsMap) {
                ccsMap.values().parallelStream().forEach(ccs -> {
                    try {
                        ccs.notifyShutdown(id);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARN, "Exception notifying CC of shutdown", e);
                    }
                });
            }
            ipc.stop();

            LOGGER.log(Level.INFO, "Stopped NodeControllerService");
        } else {
            LOGGER.log(Level.ERROR, "Duplicate shutdown call; original: " + Arrays.toString(shutdownCallStack),
                    new Exception("Duplicate shutdown call"));
        }
        if (ncShutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(ncShutdownHook);
                LOGGER.info("removed shutdown hook for {}", id);
            } catch (IllegalStateException e) {
                LOGGER.log(Level.DEBUG, "ignoring exception while attempting to remove shutdown hook", e);
            }
        }
    }

    public String getId() {
        return id;
    }

    public ServerContext getServerContext() {
        return serverCtx;
    }

    public Map<JobId, Joblet> getJobletMap() {
        return jobletMap;
    }

    public void removeJobParameterByteStore(JobId jobId) {
        jobParameterByteStoreMap.remove(jobId);
    }

    public JobParameterByteStore createOrGetJobParameterByteStore(JobId jobId) throws HyracksException {
        JobParameterByteStore jpbs = jobParameterByteStoreMap.get(jobId);
        if (jpbs == null) {
            jpbs = new JobParameterByteStore();
            jobParameterByteStoreMap.put(jobId, jpbs);
        }
        return jpbs;
    }

    public void storeActivityClusterGraph(DeployedJobSpecId deployedJobSpecId, ActivityClusterGraph acg)
            throws HyracksException {
        if (deployedJobSpecActivityClusterGraphMap.get(deployedJobSpecId.getId()) != null) {
            throw HyracksException.create(ErrorCode.DUPLICATE_DEPLOYED_JOB, deployedJobSpecId);
        }
        deployedJobSpecActivityClusterGraphMap.put(deployedJobSpecId.getId(), acg);
    }

    public void removeActivityClusterGraph(DeployedJobSpecId deployedJobSpecId) throws HyracksException {
        if (deployedJobSpecActivityClusterGraphMap.get(deployedJobSpecId.getId()) == null) {
            throw HyracksException.create(ErrorCode.ERROR_FINDING_DEPLOYED_JOB, deployedJobSpecId);
        }
        deployedJobSpecActivityClusterGraphMap.remove(deployedJobSpecId.getId());
    }

    public void checkForDuplicateDeployedJobSpec(DeployedJobSpecId deployedJobSpecId) throws HyracksException {
        if (deployedJobSpecActivityClusterGraphMap.get(deployedJobSpecId.getId()) != null) {
            throw HyracksException.create(ErrorCode.DUPLICATE_DEPLOYED_JOB, deployedJobSpecId);
        }
    }

    public ActivityClusterGraph getActivityClusterGraph(DeployedJobSpecId deployedJobSpecId) throws HyracksException {
        return deployedJobSpecActivityClusterGraphMap.get(deployedJobSpecId.getId());
    }

    public NetworkManager getNetworkManager() {
        return netManager;
    }

    public DatasetNetworkManager getDatasetNetworkManager() {
        return datasetNetworkManager;
    }

    public PartitionManager getPartitionManager() {
        return partitionManager;
    }

    public IClusterController getPrimaryClusterController() {
        return primaryCcs;
    }

    public IClusterController getClusterController(CcId ccId) {
        return ccsMap.get(ccId);
    }

    public NodeParameters getNodeParameters() {
        return nodeParameters;
    }

    @Override
    public ExecutorService getExecutor() {
        return executor;
    }

    public NCConfig getConfiguration() {
        return ncConfig;
    }

    public WorkQueue getWorkQueue() {
        return workQueue;
    }

    private class HeartbeatTask implements Runnable {
        private final Semaphore delayBlock = new Semaphore(0);
        private final IClusterController cc;
        private final long heartbeatPeriodNanos;

        private final HeartbeatData hbData;

        HeartbeatTask(IClusterController cc, long heartbeatPeriod) {
            this.cc = cc;
            this.heartbeatPeriodNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatPeriod);
            hbData = new HeartbeatData();
            hbData.gcCollectionCounts = new long[gcMXBeans.size()];
            hbData.gcCollectionTimes = new long[gcMXBeans.size()];
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long nextFireNanoTime = System.nanoTime() + heartbeatPeriodNanos;
                    final boolean success = execute();
                    sleepUntilNextFire(success ? nextFireNanoTime - System.nanoTime() : ONE_SECOND_NANOS);
                } catch (InterruptedException e) { // NOSONAR
                    break;
                }
            }
            LOGGER.log(Level.INFO, "Heartbeat thread interrupted; shutting down");
        }

        private void sleepUntilNextFire(long delayNanos) throws InterruptedException {
            if (delayNanos > 0) {
                delayBlock.tryAcquire(delayNanos, TimeUnit.NANOSECONDS); //NOSONAR - ignore result of tryAcquire
            } else {
                LOGGER.warn("After sending heartbeat, next one is already late by "
                        + TimeUnit.NANOSECONDS.toMillis(-delayNanos) + "ms; sending without delay");
            }
        }

        private boolean execute() throws InterruptedException {
            MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
            hbData.heapInitSize = heapUsage.getInit();
            hbData.heapUsedSize = heapUsage.getUsed();
            hbData.heapCommittedSize = heapUsage.getCommitted();
            hbData.heapMaxSize = heapUsage.getMax();
            MemoryUsage nonheapUsage = memoryMXBean.getNonHeapMemoryUsage();
            hbData.nonheapInitSize = nonheapUsage.getInit();
            hbData.nonheapUsedSize = nonheapUsage.getUsed();
            hbData.nonheapCommittedSize = nonheapUsage.getCommitted();
            hbData.nonheapMaxSize = nonheapUsage.getMax();
            hbData.threadCount = threadMXBean.getThreadCount();
            hbData.peakThreadCount = threadMXBean.getPeakThreadCount();
            hbData.totalStartedThreadCount = threadMXBean.getTotalStartedThreadCount();
            hbData.systemLoadAverage = osMXBean.getSystemLoadAverage();
            int gcN = gcMXBeans.size();
            for (int i = 0; i < gcN; ++i) {
                GarbageCollectorMXBean gcMXBean = gcMXBeans.get(i);
                hbData.gcCollectionCounts[i] = gcMXBean.getCollectionCount();
                hbData.gcCollectionTimes[i] = gcMXBean.getCollectionTime();
            }

            MuxDemuxPerformanceCounters netPC = netManager.getPerformanceCounters();
            hbData.netPayloadBytesRead = netPC.getPayloadBytesRead();
            hbData.netPayloadBytesWritten = netPC.getPayloadBytesWritten();
            hbData.netSignalingBytesRead = netPC.getSignalingBytesRead();
            hbData.netSignalingBytesWritten = netPC.getSignalingBytesWritten();

            MuxDemuxPerformanceCounters datasetNetPC = datasetNetworkManager.getPerformanceCounters();
            hbData.datasetNetPayloadBytesRead = datasetNetPC.getPayloadBytesRead();
            hbData.datasetNetPayloadBytesWritten = datasetNetPC.getPayloadBytesWritten();
            hbData.datasetNetSignalingBytesRead = datasetNetPC.getSignalingBytesRead();
            hbData.datasetNetSignalingBytesWritten = datasetNetPC.getSignalingBytesWritten();

            IPCPerformanceCounters ipcPC = ipc.getPerformanceCounters();
            hbData.ipcMessagesSent = ipcPC.getMessageSentCount();
            hbData.ipcMessageBytesSent = ipcPC.getMessageBytesSent();
            hbData.ipcMessagesReceived = ipcPC.getMessageReceivedCount();
            hbData.ipcMessageBytesReceived = ipcPC.getMessageBytesReceived();

            hbData.diskReads = ioCounter.getReads();
            hbData.diskWrites = ioCounter.getWrites();
            hbData.numCores = Runtime.getRuntime().availableProcessors();

            try {
                cc.nodeHeartbeat(id, hbData);
                LOGGER.log(Level.DEBUG, "Successfully sent heartbeat");
                return true;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.log(Level.DEBUG, "Exception sending heartbeat; will retry after 1s", e);
                } else {
                    LOGGER.log(Level.ERROR, "Exception sending heartbeat; will retry after 1s: " + e.toString());
                }
                return false;
            }
        }
    }

    private class ProfileDumpTask extends TimerTask {
        private IClusterController cc;

        public ProfileDumpTask(IClusterController cc) {
            this.cc = cc;
        }

        @Override
        public void run() {
            try {
                FutureValue<List<JobProfile>> fv = new FutureValue<>();
                BuildJobProfilesWork bjpw = new BuildJobProfilesWork(NodeControllerService.this, cc.getCcId(), fv);
                workQueue.scheduleAndSync(bjpw);
                List<JobProfile> profiles = fv.get();
                if (!profiles.isEmpty()) {
                    cc.reportProfile(id, profiles);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARN, "Exception reporting profile", e);
            }
        }
    }

    private class TraceCurrentTimeTask extends TimerTask {

        private ITracer tracer;
        private long traceCategory;

        public TraceCurrentTimeTask(ITracer tracer) {
            this.tracer = tracer;
            this.traceCategory = tracer.getRegistry().get("Timestamp");
        }

        @Override
        public void run() {
            try {
                tracer.instant("CurrentTime", traceCategory, Tracer.Scope.p, Tracer.dateTimeStamp());
            } catch (Exception e) {
                LOGGER.log(Level.WARN, "Exception tracing current time", e);
            }
        }
    }

    public void sendApplicationMessageToCC(CcId ccId, byte[] data, DeploymentId deploymentId) throws Exception {
        ccsMap.get(ccId).sendApplicationMessageToCC(data, deploymentId, id);
    }

    public IDatasetPartitionManager getDatasetPartitionManager() {
        return datasetPartitionManager;
    }

    public MessagingNetworkManager getMessagingNetworkManager() {
        return messagingNetManager;
    }

    private static INCApplication getApplication(NCConfig config)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (config.getAppClass() != null) {
            Class<?> c = Class.forName(config.getAppClass());
            return (INCApplication) c.newInstance();
        } else {
            return BaseNCApplication.INSTANCE;
        }
    }

    @Override
    public Object getApplicationContext() {
        return application.getApplicationContext();
    }
}

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

package org.apache.asterix.hyracks.bootstrap;

import static org.apache.asterix.algebra.base.ILangExtension.Language.AQL;
import static org.apache.asterix.algebra.base.ILangExtension.Language.SQLPP;
import static org.apache.asterix.api.http.server.ServletConstants.ASTERIX_APP_CONTEXT_INFO_ATTR;
import static org.apache.asterix.api.http.server.ServletConstants.HYRACKS_CONNECTION_ATTR;
import static org.apache.asterix.common.api.IClusterManagementWork.ClusterState.SHUTTING_DOWN;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import org.apache.asterix.api.http.ctx.StatementExecutorContext;
import org.apache.asterix.api.http.server.ActiveStatsApiServlet;
import org.apache.asterix.api.http.server.ApiServlet;
import org.apache.asterix.api.http.server.ClusterApiServlet;
import org.apache.asterix.api.http.server.ClusterControllerDetailsApiServlet;
import org.apache.asterix.api.http.server.ConnectorApiServlet;
import org.apache.asterix.api.http.server.DdlApiServlet;
import org.apache.asterix.api.http.server.DiagnosticsApiServlet;
import org.apache.asterix.api.http.server.FullApiServlet;
import org.apache.asterix.api.http.server.NodeControllerDetailsApiServlet;
import org.apache.asterix.api.http.server.QueryApiServlet;
import org.apache.asterix.api.http.server.QueryCancellationServlet;
import org.apache.asterix.api.http.server.QueryResultApiServlet;
import org.apache.asterix.api.http.server.QueryServiceServlet;
import org.apache.asterix.api.http.server.QueryStatusApiServlet;
import org.apache.asterix.api.http.server.QueryWebInterfaceServlet;
import org.apache.asterix.api.http.server.RebalanceApiServlet;
import org.apache.asterix.api.http.server.ServletConstants;
import org.apache.asterix.api.http.server.ShutdownApiServlet;
import org.apache.asterix.api.http.server.UpdateApiServlet;
import org.apache.asterix.api.http.server.VersionApiServlet;
import org.apache.asterix.app.active.ActiveNotificationHandler;
import org.apache.asterix.app.cc.CCExtensionManager;
import org.apache.asterix.app.external.ExternalLibraryUtils;
import org.apache.asterix.app.replication.NcLifecycleCoordinator;
import org.apache.asterix.common.api.AsterixThreadFactory;
import org.apache.asterix.common.api.INodeJobTracker;
import org.apache.asterix.common.config.AsterixExtension;
import org.apache.asterix.common.config.ExternalProperties;
import org.apache.asterix.common.config.GlobalConfig;
import org.apache.asterix.common.config.MetadataProperties;
import org.apache.asterix.common.config.PropertiesAccessor;
import org.apache.asterix.common.config.ReplicationProperties;
import org.apache.asterix.common.context.IStorageComponentProvider;
import org.apache.asterix.common.dataflow.ICcApplicationContext;
import org.apache.asterix.common.library.ILibraryManager;
import org.apache.asterix.common.replication.INcLifecycleCoordinator;
import org.apache.asterix.common.utils.Servlets;
import org.apache.asterix.external.library.ExternalLibraryManager;
import org.apache.asterix.file.StorageComponentProvider;
import org.apache.asterix.messaging.CCMessageBroker;
import org.apache.asterix.metadata.MetadataManager;
import org.apache.asterix.metadata.api.IAsterixStateProxy;
import org.apache.asterix.metadata.bootstrap.AsterixStateProxy;
import org.apache.asterix.metadata.lock.MetadataLockManager;
import org.apache.asterix.runtime.job.resource.JobCapacityController;
import org.apache.asterix.runtime.utils.CcApplicationContext;
import org.apache.asterix.translator.IStatementExecutorContext;
import org.apache.asterix.translator.IStatementExecutorFactory;
import org.apache.asterix.util.MetadataBuiltinFunctions;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.api.application.ICCServiceContext;
import org.apache.hyracks.api.application.IServiceContext;
import org.apache.hyracks.api.client.HyracksConnection;
import org.apache.hyracks.api.client.IHyracksClientConnection;
import org.apache.hyracks.api.config.IConfigManager;
import org.apache.hyracks.api.job.resource.IJobCapacityController;
import org.apache.hyracks.api.lifecycle.LifeCycleComponentManager;
import org.apache.hyracks.control.cc.BaseCCApplication;
import org.apache.hyracks.control.cc.ClusterControllerService;
import org.apache.hyracks.control.common.controllers.CCConfig;
import org.apache.hyracks.http.api.IServlet;
import org.apache.hyracks.http.server.HttpServer;
import org.apache.hyracks.http.server.WebManager;
import org.apache.hyracks.util.LoggingConfigUtil;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CCApplication extends BaseCCApplication {

    private static final Logger LOGGER = LogManager.getLogger();
    private static IAsterixStateProxy proxy;
    protected ICCServiceContext ccServiceCtx;
    protected CCExtensionManager ccExtensionManager;
    protected IStorageComponentProvider componentProvider;
    protected StatementExecutorContext statementExecutorCtx;
    protected WebManager webManager;
    protected ICcApplicationContext appCtx;
    private IJobCapacityController jobCapacityController;
    private IHyracksClientConnection hcc;

    @Override
    public void init(IServiceContext serviceCtx) throws Exception {
        ccServiceCtx = (ICCServiceContext) serviceCtx;
        ccServiceCtx.setThreadFactory(
                new AsterixThreadFactory(ccServiceCtx.getThreadFactory(), new LifeCycleComponentManager()));
    }

    @Override
    public void start(String[] args) throws Exception {
        if (args.length > 0) {
            throw new IllegalArgumentException("Unrecognized argument(s): " + Arrays.toString(args));
        }
        final ClusterControllerService controllerService =
                (ClusterControllerService) ccServiceCtx.getControllerService();
        ccServiceCtx.setMessageBroker(new CCMessageBroker(controllerService));

        configureLoggingLevel(ccServiceCtx.getAppConfig().getLoggingLevel(ExternalProperties.Option.LOG_LEVEL));

        LOGGER.info("Starting Asterix cluster controller");

        String strIP = ccServiceCtx.getCCContext().getClusterControllerInfo().getClientNetAddress();
        int port = ccServiceCtx.getCCContext().getClusterControllerInfo().getClientNetPort();
        hcc = new HyracksConnection(strIP, port);
        MetadataBuiltinFunctions.init();
        ILibraryManager libraryManager = new ExternalLibraryManager();
        ReplicationProperties repProp =
                new ReplicationProperties(PropertiesAccessor.getInstance(ccServiceCtx.getAppConfig()));
        INcLifecycleCoordinator lifecycleCoordinator = createNcLifeCycleCoordinator(repProp.isReplicationEnabled());
        ExternalLibraryUtils.setUpExternaLibraries(libraryManager, false);
        componentProvider = new StorageComponentProvider();
        GlobalRecoveryManager globalRecoveryManager = createGlobalRecoveryManager();
        statementExecutorCtx = new StatementExecutorContext();
        appCtx = createApplicationContext(libraryManager, globalRecoveryManager, lifecycleCoordinator);
        List<AsterixExtension> extensions = new ArrayList<>();
        extensions.addAll(this.getExtensions());
        ccExtensionManager = new CCExtensionManager(extensions);
        appCtx.setExtensionManager(ccExtensionManager);
        final CCConfig ccConfig = controllerService.getCCConfig();
        if (System.getProperty("java.rmi.server.hostname") == null) {
            System.setProperty("java.rmi.server.hostname", ccConfig.getClusterListenAddress());
        }
        MetadataProperties metadataProperties = appCtx.getMetadataProperties();

        setAsterixStateProxy(AsterixStateProxy.registerRemoteObject(metadataProperties.getMetadataCallbackPort()));
        ccServiceCtx.setDistributedState(proxy);
        MetadataManager.initialize(proxy, metadataProperties);
        ccServiceCtx.addJobLifecycleListener(appCtx.getActiveNotificationHandler());

        // create event loop groups
        webManager = new WebManager();
        configureServers();
        webManager.start();
        ccServiceCtx.addClusterLifecycleListener(new ClusterLifecycleListener(appCtx));
        final INodeJobTracker nodeJobTracker = appCtx.getNodeJobTracker();
        ccServiceCtx.addJobLifecycleListener(nodeJobTracker);
        ccServiceCtx.addClusterLifecycleListener(nodeJobTracker);

        jobCapacityController = new JobCapacityController(controllerService.getResourceManager());
    }

    protected ICcApplicationContext createApplicationContext(ILibraryManager libraryManager,
            GlobalRecoveryManager globalRecoveryManager, INcLifecycleCoordinator lifecycleCoordinator)
            throws AlgebricksException, IOException {
        return new CcApplicationContext(ccServiceCtx, getHcc(), libraryManager, () -> MetadataManager.INSTANCE,
                globalRecoveryManager, lifecycleCoordinator, new ActiveNotificationHandler(), componentProvider,
                new MetadataLockManager());
    }

    protected GlobalRecoveryManager createGlobalRecoveryManager() throws Exception {
        return new GlobalRecoveryManager(ccServiceCtx, getHcc(), componentProvider);
    }

    protected INcLifecycleCoordinator createNcLifeCycleCoordinator(boolean replicationEnabled) {
        return new NcLifecycleCoordinator(ccServiceCtx, replicationEnabled);
    }

    @Override
    protected void configureLoggingLevel(Level level) {
        super.configureLoggingLevel(level);
        LoggingConfigUtil.defaultIfMissing(GlobalConfig.ASTERIX_LOGGER_NAME, level);
    }

    protected List<AsterixExtension> getExtensions() {
        return appCtx.getExtensionProperties().getExtensions();
    }

    protected void configureServers() throws Exception {
        webManager.add(setupWebServer(appCtx.getExternalProperties()));
        webManager.add(setupJSONAPIServer(appCtx.getExternalProperties()));
        webManager.add(setupQueryWebServer(appCtx.getExternalProperties()));
    }

    @Override
    public void stop() throws Exception {
        LOGGER.info("Stopping Asterix cluster controller");
        appCtx.getClusterStateManager().setState(SHUTTING_DOWN);
        ((ActiveNotificationHandler) appCtx.getActiveNotificationHandler()).stop();
        AsterixStateProxy.unregisterRemoteObject();
        webManager.stop();
    }

    protected HttpServer setupWebServer(ExternalProperties externalProperties) throws Exception {
        HttpServer webServer = new HttpServer(webManager.getBosses(), webManager.getWorkers(),
                externalProperties.getWebInterfacePort());
        webServer.setAttribute(HYRACKS_CONNECTION_ATTR, hcc);
        webServer.addServlet(new ApiServlet(webServer.ctx(), new String[] { "/*" }, appCtx,
                ccExtensionManager.getCompilationProvider(AQL), ccExtensionManager.getCompilationProvider(SQLPP),
                getStatementExecutorFactory(), componentProvider));
        return webServer;
    }

    protected HttpServer setupJSONAPIServer(ExternalProperties externalProperties) throws Exception {
        HttpServer jsonAPIServer =
                new HttpServer(webManager.getBosses(), webManager.getWorkers(), externalProperties.getAPIServerPort());
        jsonAPIServer.setAttribute(HYRACKS_CONNECTION_ATTR, hcc);
        jsonAPIServer.setAttribute(ASTERIX_APP_CONTEXT_INFO_ATTR, appCtx);
        jsonAPIServer.setAttribute(ServletConstants.EXECUTOR_SERVICE_ATTR,
                ccServiceCtx.getControllerService().getExecutor());
        jsonAPIServer.setAttribute(ServletConstants.RUNNING_QUERIES_ATTR, statementExecutorCtx);
        jsonAPIServer.setAttribute(ServletConstants.SERVICE_CONTEXT_ATTR, ccServiceCtx);

        // AQL rest APIs.
        addServlet(jsonAPIServer, Servlets.AQL_QUERY);
        addServlet(jsonAPIServer, Servlets.AQL_UPDATE);
        addServlet(jsonAPIServer, Servlets.AQL_DDL);
        addServlet(jsonAPIServer, Servlets.AQL);

        // SQL+x+ rest APIs.
        addServlet(jsonAPIServer, Servlets.SQLPP_QUERY);
        addServlet(jsonAPIServer, Servlets.SQLPP_UPDATE);
        addServlet(jsonAPIServer, Servlets.SQLPP_DDL);
        addServlet(jsonAPIServer, Servlets.SQLPP);

        // Other APIs.
        addServlet(jsonAPIServer, Servlets.QUERY_STATUS);
        addServlet(jsonAPIServer, Servlets.QUERY_RESULT);
        addServlet(jsonAPIServer, Servlets.QUERY_SERVICE);
        addServlet(jsonAPIServer, Servlets.QUERY_AQL);
        addServlet(jsonAPIServer, Servlets.RUNNING_REQUESTS);
        addServlet(jsonAPIServer, Servlets.CONNECTOR);
        addServlet(jsonAPIServer, Servlets.SHUTDOWN);
        addServlet(jsonAPIServer, Servlets.VERSION);
        addServlet(jsonAPIServer, Servlets.CLUSTER_STATE);
        addServlet(jsonAPIServer, Servlets.REBALANCE);
        addServlet(jsonAPIServer, Servlets.CLUSTER_STATE_NODE_DETAIL); // must not precede add of CLUSTER_STATE
        addServlet(jsonAPIServer, Servlets.CLUSTER_STATE_CC_DETAIL); // must not precede add of CLUSTER_STATE
        addServlet(jsonAPIServer, Servlets.DIAGNOSTICS);
        addServlet(jsonAPIServer, Servlets.ACTIVE_STATS);
        return jsonAPIServer;
    }

    protected void addServlet(HttpServer server, String path) {
        server.addServlet(createServlet(server.ctx(), path, path));
    }

    protected HttpServer setupQueryWebServer(ExternalProperties externalProperties) throws Exception {
        HttpServer queryWebServer = new HttpServer(webManager.getBosses(), webManager.getWorkers(),
                externalProperties.getQueryWebInterfacePort());
        queryWebServer.setAttribute(HYRACKS_CONNECTION_ATTR, hcc);
        queryWebServer.addServlet(new QueryWebInterfaceServlet(appCtx, queryWebServer.ctx(), new String[] { "/*" }));
        return queryWebServer;
    }

    protected IServlet createServlet(ConcurrentMap<String, Object> ctx, String key, String... paths) {
        switch (key) {
            case Servlets.AQL:
                return new FullApiServlet(ctx, paths, appCtx, ccExtensionManager.getCompilationProvider(AQL),
                        getStatementExecutorFactory(), componentProvider);
            case Servlets.AQL_QUERY:
                return new QueryApiServlet(ctx, paths, appCtx, ccExtensionManager.getCompilationProvider(AQL),
                        getStatementExecutorFactory(), componentProvider);
            case Servlets.AQL_UPDATE:
                return new UpdateApiServlet(ctx, paths, appCtx, ccExtensionManager.getCompilationProvider(AQL),
                        getStatementExecutorFactory(), componentProvider);
            case Servlets.AQL_DDL:
                return new DdlApiServlet(ctx, paths, appCtx, ccExtensionManager.getCompilationProvider(AQL),
                        getStatementExecutorFactory(), componentProvider);
            case Servlets.SQLPP:
                return new FullApiServlet(ctx, paths, appCtx, ccExtensionManager.getCompilationProvider(SQLPP),
                        getStatementExecutorFactory(), componentProvider);
            case Servlets.SQLPP_QUERY:
                return new QueryApiServlet(ctx, paths, appCtx, ccExtensionManager.getCompilationProvider(SQLPP),
                        getStatementExecutorFactory(), componentProvider);
            case Servlets.SQLPP_UPDATE:
                return new UpdateApiServlet(ctx, paths, appCtx, ccExtensionManager.getCompilationProvider(SQLPP),
                        getStatementExecutorFactory(), componentProvider);
            case Servlets.SQLPP_DDL:
                return new DdlApiServlet(ctx, paths, appCtx, ccExtensionManager.getCompilationProvider(SQLPP),
                        getStatementExecutorFactory(), componentProvider);
            case Servlets.RUNNING_REQUESTS:
                return new QueryCancellationServlet(ctx, paths);
            case Servlets.QUERY_STATUS:
                return new QueryStatusApiServlet(ctx, appCtx, paths);
            case Servlets.QUERY_RESULT:
                return new QueryResultApiServlet(ctx, appCtx, paths);
            case Servlets.QUERY_SERVICE:
                return new QueryServiceServlet(ctx, paths, appCtx, SQLPP,
                        ccExtensionManager.getCompilationProvider(SQLPP), getStatementExecutorFactory(),
                        componentProvider, null);
            case Servlets.QUERY_AQL:
                return new QueryServiceServlet(ctx, paths, appCtx, AQL, ccExtensionManager.getCompilationProvider(AQL),
                        getStatementExecutorFactory(), componentProvider, null);
            case Servlets.CONNECTOR:
                return new ConnectorApiServlet(ctx, paths, appCtx);
            case Servlets.REBALANCE:
                return new RebalanceApiServlet(ctx, paths, appCtx);
            case Servlets.SHUTDOWN:
                return new ShutdownApiServlet(appCtx, ctx, paths);
            case Servlets.VERSION:
                return new VersionApiServlet(ctx, paths);
            case Servlets.CLUSTER_STATE:
                return new ClusterApiServlet(appCtx, ctx, paths);
            case Servlets.CLUSTER_STATE_NODE_DETAIL:
                return new NodeControllerDetailsApiServlet(appCtx, ctx, paths);
            case Servlets.CLUSTER_STATE_CC_DETAIL:
                return new ClusterControllerDetailsApiServlet(appCtx, ctx, paths);
            case Servlets.DIAGNOSTICS:
                return new DiagnosticsApiServlet(appCtx, ctx, paths);
            case Servlets.ACTIVE_STATS:
                return new ActiveStatsApiServlet(appCtx, ctx, paths);
            default:
                throw new IllegalStateException(String.valueOf(key));
        }
    }

    public IStatementExecutorFactory getStatementExecutorFactory() {
        return ccExtensionManager.getStatementExecutorFactory(ccServiceCtx.getControllerService().getExecutor());
    }

    public IStatementExecutorContext getStatementExecutorContext() {
        return statementExecutorCtx;
    }

    @Override
    public IJobCapacityController getJobCapacityController() {
        return jobCapacityController;
    }

    @Override
    public void registerConfig(IConfigManager configManager) {
        super.registerConfig(configManager);
        ApplicationConfigurator.registerConfigOptions(configManager);
    }

    public static synchronized void setAsterixStateProxy(IAsterixStateProxy proxy) {
        CCApplication.proxy = proxy;
    }

    @Override
    public ICcApplicationContext getApplicationContext() {
        return appCtx;
    }

    public IHyracksClientConnection getHcc() {
        return hcc;
    }
}

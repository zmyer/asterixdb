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

package org.apache.asterix.app.message;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.asterix.algebra.base.ILangExtension;
import org.apache.asterix.api.http.server.ResultUtil;
import org.apache.asterix.app.cc.CCExtensionManager;
import org.apache.asterix.app.translator.RequestParameters;
import org.apache.asterix.common.api.IClusterManagementWork;
import org.apache.asterix.common.cluster.IClusterStateManager;
import org.apache.asterix.common.config.GlobalConfig;
import org.apache.asterix.common.context.IStorageComponentProvider;
import org.apache.asterix.common.dataflow.ICcApplicationContext;
import org.apache.asterix.common.messaging.api.ICcAddressedMessage;
import org.apache.asterix.compiler.provider.ILangCompilationProvider;
import org.apache.asterix.hyracks.bootstrap.CCApplication;
import org.apache.asterix.lang.aql.parser.TokenMgrError;
import org.apache.asterix.lang.common.base.IParser;
import org.apache.asterix.lang.common.base.Statement;
import org.apache.asterix.messaging.CCMessageBroker;
import org.apache.asterix.metadata.MetadataManager;
import org.apache.asterix.translator.IRequestParameters;
import org.apache.asterix.translator.IStatementExecutor;
import org.apache.asterix.translator.IStatementExecutorContext;
import org.apache.asterix.translator.IStatementExecutorFactory;
import org.apache.asterix.translator.ResultProperties;
import org.apache.asterix.translator.SessionConfig;
import org.apache.asterix.translator.SessionOutput;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.api.application.ICCServiceContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.exceptions.HyracksException;
import org.apache.hyracks.control.cc.ClusterControllerService;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ExecuteStatementRequestMessage implements ICcAddressedMessage {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LogManager.getLogger();
    //TODO: Make configurable: https://issues.apache.org/jira/browse/ASTERIXDB-2062
    public static final long DEFAULT_NC_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5);
    //TODO: Make configurable: https://issues.apache.org/jira/browse/ASTERIXDB-2063
    public static final long DEFAULT_QUERY_CANCELLATION_WAIT_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private final String requestNodeId;
    private final long requestMessageId;
    private final ILangExtension.Language lang;
    private final String statementsText;
    private final SessionConfig sessionConfig;
    private final ResultProperties resultProperties;
    private final String clientContextID;
    private final String handleUrl;
    private final Map<String, String> optionalParameters;

    public ExecuteStatementRequestMessage(String requestNodeId, long requestMessageId, ILangExtension.Language lang,
            String statementsText, SessionConfig sessionConfig, ResultProperties resultProperties,
            String clientContextID, String handleUrl, Map<String, String> optionalParameters) {
        this.requestNodeId = requestNodeId;
        this.requestMessageId = requestMessageId;
        this.lang = lang;
        this.statementsText = statementsText;
        this.sessionConfig = sessionConfig;
        this.resultProperties = resultProperties;
        this.clientContextID = clientContextID;
        this.handleUrl = handleUrl;
        this.optionalParameters = optionalParameters;
    }

    @Override
    public void handle(ICcApplicationContext ccAppCtx) throws HyracksDataException, InterruptedException {
        ICCServiceContext ccSrvContext = ccAppCtx.getServiceContext();
        ClusterControllerService ccSrv = (ClusterControllerService) ccSrvContext.getControllerService();
        CCApplication ccApp = (CCApplication) ccSrv.getApplication();
        CCMessageBroker messageBroker = (CCMessageBroker) ccSrvContext.getMessageBroker();
        final String rejectionReason = getRejectionReason(ccSrv);
        if (rejectionReason != null) {
            sendRejection(rejectionReason, messageBroker);
            return;
        }
        CCExtensionManager ccExtMgr = (CCExtensionManager) ccAppCtx.getExtensionManager();
        ILangCompilationProvider compilationProvider = ccExtMgr.getCompilationProvider(lang);
        IStorageComponentProvider storageComponentProvider = ccAppCtx.getStorageComponentProvider();
        IStatementExecutorFactory statementExecutorFactory = ccApp.getStatementExecutorFactory();
        IStatementExecutorContext statementExecutorContext = ccApp.getStatementExecutorContext();
        ExecuteStatementResponseMessage responseMsg = new ExecuteStatementResponseMessage(requestMessageId);
        try {
            IParser parser = compilationProvider.getParserFactory().createParser(statementsText);
            List<Statement> statements = parser.parse();
            StringWriter outWriter = new StringWriter(256);
            PrintWriter outPrinter = new PrintWriter(outWriter);
            SessionOutput.ResultDecorator resultPrefix = ResultUtil.createPreResultDecorator();
            SessionOutput.ResultDecorator resultPostfix = ResultUtil.createPostResultDecorator();
            SessionOutput.ResultAppender appendHandle = ResultUtil.createResultHandleAppender(handleUrl);
            SessionOutput.ResultAppender appendStatus = ResultUtil.createResultStatusAppender();
            SessionOutput sessionOutput = new SessionOutput(sessionConfig, outPrinter, resultPrefix, resultPostfix,
                    appendHandle, appendStatus);
            IStatementExecutor.ResultMetadata outMetadata = new IStatementExecutor.ResultMetadata();
            MetadataManager.INSTANCE.init();
            IStatementExecutor translator = statementExecutorFactory.create(ccAppCtx, statements, sessionOutput,
                    compilationProvider, storageComponentProvider);
            final IStatementExecutor.Stats stats = new IStatementExecutor.Stats();
            final IRequestParameters requestParameters = new RequestParameters(null, resultProperties, stats,
                    outMetadata, clientContextID, optionalParameters);
            translator.compileAndExecute(ccApp.getHcc(), statementExecutorContext, requestParameters);
            outPrinter.close();
            responseMsg.setResult(outWriter.toString());
            responseMsg.setMetadata(outMetadata);
            responseMsg.setStats(stats);
        } catch (AlgebricksException | HyracksException | TokenMgrError
                | org.apache.asterix.aqlplus.parser.TokenMgrError pe) {
            // we trust that "our" exceptions are serializable and have a comprehensible error message
            GlobalConfig.ASTERIX_LOGGER.log(Level.WARN, pe.getMessage(), pe);
            responseMsg.setError(pe);
        } catch (Exception e) {
            GlobalConfig.ASTERIX_LOGGER.log(Level.ERROR, "Unexpected exception", e);
            responseMsg.setError(new Exception(e.toString()));
        }
        try {
            messageBroker.sendApplicationMessageToNC(responseMsg, requestNodeId);
        } catch (Exception e) {
            LOGGER.log(Level.WARN, e.toString(), e);
        }
    }

    private String getRejectionReason(ClusterControllerService ccSrv) {
        if (ccSrv.getNodeManager().getNodeControllerState(requestNodeId) == null) {
            return "Node is not registerted with the CC";
        }
        ICcApplicationContext appCtx = (ICcApplicationContext) ccSrv.getApplicationContext();
        IClusterStateManager csm = appCtx.getClusterStateManager();
        final IClusterManagementWork.ClusterState clusterState = csm.getState();
        if (clusterState != IClusterManagementWork.ClusterState.ACTIVE) {
            return "Cannot execute request, cluster is " + clusterState;
        }
        return null;
    }

    private void sendRejection(String reason, CCMessageBroker messageBroker) {
        ExecuteStatementResponseMessage responseMsg = new ExecuteStatementResponseMessage(requestMessageId);
        responseMsg.setError(new Exception(reason));
        try {
            messageBroker.sendApplicationMessageToNC(responseMsg, requestNodeId);
        } catch (Exception e) {
            LOGGER.log(Level.WARN, e.toString(), e);
        }
    }

    @Override
    public String toString() {
        return String.format("%s(id=%s, from=%s): %s", getClass().getSimpleName(), requestMessageId, requestNodeId,
                statementsText);
    }
}

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
package org.apache.asterix.api.http.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import org.apache.asterix.algebra.base.ILangExtension;
import org.apache.asterix.common.api.Duration;
import org.apache.asterix.common.api.IApplicationContext;
import org.apache.asterix.common.api.IClusterManagementWork;
import org.apache.asterix.common.config.GlobalConfig;
import org.apache.asterix.common.context.IStorageComponentProvider;
import org.apache.asterix.common.dataflow.ICcApplicationContext;
import org.apache.asterix.common.exceptions.AsterixException;
import org.apache.asterix.common.exceptions.ErrorCode;
import org.apache.asterix.compiler.provider.ILangCompilationProvider;
import org.apache.asterix.lang.aql.parser.TokenMgrError;
import org.apache.asterix.lang.common.base.IParser;
import org.apache.asterix.lang.common.base.Statement;
import org.apache.asterix.metadata.MetadataManager;
import org.apache.asterix.translator.IRequestParameters;
import org.apache.asterix.translator.IStatementExecutor;
import org.apache.asterix.translator.IStatementExecutor.ResultDelivery;
import org.apache.asterix.translator.IStatementExecutor.Stats;
import org.apache.asterix.translator.IStatementExecutorContext;
import org.apache.asterix.translator.IStatementExecutorFactory;
import org.apache.asterix.translator.ResultProperties;
import org.apache.asterix.translator.SessionConfig;
import org.apache.asterix.translator.SessionOutput;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.api.application.IServiceContext;
import org.apache.hyracks.api.exceptions.HyracksException;
import org.apache.hyracks.control.common.controllers.CCConfig;
import org.apache.hyracks.http.api.IServletRequest;
import org.apache.hyracks.http.api.IServletResponse;
import org.apache.hyracks.http.server.utils.HttpUtil;
import org.apache.hyracks.util.JSONUtil;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.netty.handler.codec.http.HttpResponseStatus;

public class QueryServiceServlet extends AbstractQueryApiServlet {
    protected static final Logger LOGGER = LogManager.getLogger();
    protected final ILangExtension.Language queryLanguage;
    private final ILangCompilationProvider compilationProvider;
    private final IStatementExecutorFactory statementExecutorFactory;
    private final IStorageComponentProvider componentProvider;
    private final IStatementExecutorContext queryCtx;
    protected final IServiceContext serviceCtx;
    protected final Function<IServletRequest, Map<String, String>> optionalParamProvider;
    protected String hostName;

    public QueryServiceServlet(ConcurrentMap<String, Object> ctx, String[] paths, IApplicationContext appCtx,
            ILangExtension.Language queryLanguage, ILangCompilationProvider compilationProvider,
            IStatementExecutorFactory statementExecutorFactory, IStorageComponentProvider componentProvider,
            Function<IServletRequest, Map<String, String>> optionalParamProvider) {
        super(appCtx, ctx, paths);
        this.queryLanguage = queryLanguage;
        this.compilationProvider = compilationProvider;
        this.statementExecutorFactory = statementExecutorFactory;
        this.componentProvider = componentProvider;
        this.queryCtx = (IStatementExecutorContext) ctx.get(ServletConstants.RUNNING_QUERIES_ATTR);
        this.serviceCtx = (IServiceContext) ctx.get(ServletConstants.SERVICE_CONTEXT_ATTR);
        this.optionalParamProvider = optionalParamProvider;
        try {
            this.hostName =
                    InetAddress.getByName(serviceCtx.getAppConfig().getString(CCConfig.Option.CLUSTER_PUBLIC_ADDRESS))
                            .getHostName();
        } catch (UnknownHostException e) {
            LOGGER.warn("Reverse DNS not properly configured, CORS defaulting to localhost", e);
            this.hostName = "localhost";
        }
    }

    @Override
    protected void post(IServletRequest request, IServletResponse response) {
        try {
            handleRequest(request, response);
        } catch (IOException e) {
            // Servlet methods should not throw exceptions
            // http://cwe.mitre.org/data/definitions/600.html
            GlobalConfig.ASTERIX_LOGGER.log(Level.ERROR, e.getMessage(), e);
        } catch (Throwable th) {// NOSONAR: Logging and re-throwing
            try {
                GlobalConfig.ASTERIX_LOGGER.log(Level.ERROR, th.getMessage(), th);
            } catch (Throwable ignored) { // NOSONAR: Logging failure
            }
            throw th;
        }
    }

    @Override
    protected void options(IServletRequest request, IServletResponse response) throws Exception {
        response.setHeader("Access-Control-Allow-Origin",
                "http://" + hostName + ":" + appCtx.getExternalProperties().getQueryWebInterfacePort());
        response.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
        response.setStatus(HttpResponseStatus.OK);
    }

    public enum Parameter {
        STATEMENT("statement"),
        FORMAT("format"),
        CLIENT_ID("client_context_id"),
        PRETTY("pretty"),
        MODE("mode"),
        TIMEOUT("timeout"),
        PLAN_FORMAT("plan-format"),
        MAX_RESULT_READS("max-result-reads");

        private final String str;

        Parameter(String str) {
            this.str = str;
        }

        public String str() {
            return str;
        }
    }

    private enum Attribute {
        HEADER("header"),
        LOSSLESS("lossless");

        private final String str;

        Attribute(String str) {
            this.str = str;
        }

        public String str() {
            return str;
        }
    }

    private enum Metrics {
        ELAPSED_TIME("elapsedTime"),
        EXECUTION_TIME("executionTime"),
        RESULT_COUNT("resultCount"),
        RESULT_SIZE("resultSize"),
        ERROR_COUNT("errorCount"),
        PROCESSED_OBJECTS_COUNT("processedObjects");

        private final String str;

        Metrics(String str) {
            this.str = str;
        }

        public String str() {
            return str;
        }
    }

    static class RequestParameters {
        String host;
        String path;
        String statement;
        String format;
        String timeout;
        boolean pretty;
        String clientContextID;
        String mode;
        String maxResultReads;

        @Override
        public String toString() {
            try {
                ObjectMapper om = new ObjectMapper();
                ObjectNode on = om.createObjectNode();
                on.put("host", host);
                on.put("path", path);
                on.put("statement", JSONUtil.escape(new StringBuilder(), statement).toString());
                on.put("pretty", pretty);
                on.put("mode", mode);
                on.put("clientContextID", clientContextID);
                on.put("format", format);
                on.put("timeout", timeout);
                on.put("maxResultReads", maxResultReads);
                return om.writer(new MinimalPrettyPrinter()).writeValueAsString(on);
            } catch (JsonProcessingException e) { // NOSONAR
                return e.getMessage();
            }
        }
    }

    static final class RequestExecutionState {
        private long execStart = -1;
        private long execEnd = -1;
        private ResultStatus resultStatus = ResultStatus.SUCCESS;
        private HttpResponseStatus httpResponseStatus = HttpResponseStatus.OK;

        void setStatus(ResultStatus resultStatus, HttpResponseStatus httpResponseStatus) {
            this.resultStatus = resultStatus;
            this.httpResponseStatus = httpResponseStatus;
        }

        ResultStatus getResultStatus() {
            return resultStatus;
        }

        HttpResponseStatus getHttpStatus() {
            return httpResponseStatus;
        }

        void start() {
            execStart = System.nanoTime();
        }

        void end() {
            execEnd = System.nanoTime();
        }

        void finish() {
            if (execStart == -1) {
                execEnd = -1;
            } else if (execEnd == -1) {
                execEnd = System.nanoTime();
            }
        }

        long duration() {
            return execEnd - execStart;
        }
    }

    private static String getParameterValue(String content, String attribute) {
        if (content == null || attribute == null) {
            return null;
        }
        int sc = content.indexOf(';');
        if (sc < 0) {
            return null;
        }
        int eq = content.indexOf('=', sc + 1);
        if (eq < 0) {
            return null;
        }
        if (content.substring(sc + 1, eq).trim().equalsIgnoreCase(attribute)) {
            return content.substring(eq + 1).trim().toLowerCase();
        }
        return null;
    }

    private static String toLower(String s) {
        return s != null ? s.toLowerCase() : s;
    }

    private static SessionConfig.OutputFormat getFormat(String format) {
        if (format != null) {
            if (format.startsWith(HttpUtil.ContentType.CSV)) {
                return SessionConfig.OutputFormat.CSV;
            }
            if (format.equals(HttpUtil.ContentType.APPLICATION_ADM)) {
                return SessionConfig.OutputFormat.ADM;
            }
            if (format.startsWith(HttpUtil.ContentType.APPLICATION_JSON)) {
                return Boolean.parseBoolean(getParameterValue(format, Attribute.LOSSLESS.str()))
                        ? SessionConfig.OutputFormat.LOSSLESS_JSON : SessionConfig.OutputFormat.CLEAN_JSON;
            }
        }
        return SessionConfig.OutputFormat.CLEAN_JSON;
    }

    private static SessionOutput createSessionOutput(RequestParameters param, String handleUrl,
            PrintWriter resultWriter) {
        SessionOutput.ResultDecorator resultPrefix = ResultUtil.createPreResultDecorator();
        SessionOutput.ResultDecorator resultPostfix = ResultUtil.createPostResultDecorator();
        SessionOutput.ResultAppender appendHandle = ResultUtil.createResultHandleAppender(handleUrl);
        SessionOutput.ResultAppender appendStatus = ResultUtil.createResultStatusAppender();

        SessionConfig.OutputFormat format = getFormat(param.format);
        //TODO:get the parameters from UI.Currently set to clean_json.
        SessionConfig sessionConfig = new SessionConfig(format);
        sessionConfig.set(SessionConfig.FORMAT_WRAPPER_ARRAY, true);
        sessionConfig.set(SessionConfig.FORMAT_INDENT_JSON, param.pretty);
        sessionConfig.set(SessionConfig.FORMAT_QUOTE_RECORD,
                format != SessionConfig.OutputFormat.CLEAN_JSON && format != SessionConfig.OutputFormat.LOSSLESS_JSON);
        sessionConfig.set(SessionConfig.FORMAT_CSV_HEADER, format == SessionConfig.OutputFormat.CSV
                && "present".equals(getParameterValue(param.format, Attribute.HEADER.str())));
        return new SessionOutput(sessionConfig, resultWriter, resultPrefix, resultPostfix, appendHandle, appendStatus);
    }

    private static void printClientContextID(PrintWriter pw, RequestParameters params) {
        if (params.clientContextID != null && !params.clientContextID.isEmpty()) {
            ResultUtil.printField(pw, ResultFields.CLIENT_ID.str(), params.clientContextID);
        }
    }

    private static void printSignature(PrintWriter pw) {
        ResultUtil.printField(pw, ResultFields.SIGNATURE.str(), "*");
    }

    private static void printType(PrintWriter pw, SessionConfig sessionConfig) {
        switch (sessionConfig.fmt()) {
            case ADM:
                ResultUtil.printField(pw, ResultFields.TYPE.str(), HttpUtil.ContentType.APPLICATION_ADM);
                break;
            case CSV:
                String contentType = HttpUtil.ContentType.CSV + "; header="
                        + (sessionConfig.is(SessionConfig.FORMAT_CSV_HEADER) ? "present" : "absent");
                ResultUtil.printField(pw, ResultFields.TYPE.str(), contentType);
                break;
            default:
                break;
        }
    }

    private static void printMetrics(PrintWriter pw, long elapsedTime, long executionTime, long resultCount,
            long resultSize, long processedObjects, long errorCount) {
        boolean hasErrors = errorCount != 0;
        pw.print("\t\"");
        pw.print(ResultFields.METRICS.str());
        pw.print("\": {\n");
        pw.print("\t");
        ResultUtil.printField(pw, Metrics.ELAPSED_TIME.str(), Duration.formatNanos(elapsedTime));
        pw.print("\t");
        ResultUtil.printField(pw, Metrics.EXECUTION_TIME.str(), Duration.formatNanos(executionTime));
        pw.print("\t");
        ResultUtil.printField(pw, Metrics.RESULT_COUNT.str(), resultCount, true);
        pw.print("\t");
        ResultUtil.printField(pw, Metrics.RESULT_SIZE.str(), resultSize, true);
        pw.print("\t");
        ResultUtil.printField(pw, Metrics.PROCESSED_OBJECTS_COUNT.str(), processedObjects, hasErrors);
        if (hasErrors) {
            pw.print("\t");
            ResultUtil.printField(pw, Metrics.ERROR_COUNT.str(), errorCount, false);
        }
        pw.print("\t}\n");
    }

    private String getOptText(JsonNode node, String fieldName) {
        final JsonNode value = node.get(fieldName);
        return value != null ? value.asText() : null;
    }

    private boolean getOptBoolean(JsonNode node, String fieldName, boolean defaultValue) {
        final JsonNode value = node.get(fieldName);
        return value != null ? value.asBoolean() : defaultValue;
    }

    private RequestParameters getRequestParameters(IServletRequest request) throws IOException {
        final String contentType = HttpUtil.getContentTypeOnly(request);
        RequestParameters param = new RequestParameters();
        param.host = host(request);
        param.path = servletPath(request);
        if (HttpUtil.ContentType.APPLICATION_JSON.equals(contentType)) {
            try {
                JsonNode jsonRequest = OBJECT_MAPPER.readTree(HttpUtil.getRequestBody(request));
                param.statement = jsonRequest.get(Parameter.STATEMENT.str()).asText();
                param.format = toLower(getOptText(jsonRequest, Parameter.FORMAT.str()));
                param.pretty = getOptBoolean(jsonRequest, Parameter.PRETTY.str(), false);
                param.mode = toLower(getOptText(jsonRequest, Parameter.MODE.str()));
                param.clientContextID = getOptText(jsonRequest, Parameter.CLIENT_ID.str());
                param.timeout = getOptText(jsonRequest, Parameter.TIMEOUT.str());
                param.maxResultReads = getOptText(jsonRequest, Parameter.MAX_RESULT_READS.str());
            } catch (JsonParseException | JsonMappingException e) {
                // if the JSON parsing fails, the statement is empty and we get an empty statement error
                GlobalConfig.ASTERIX_LOGGER.log(Level.ERROR, e.getMessage(), e);
            }
        } else {
            param.statement = request.getParameter(Parameter.STATEMENT.str());
            if (param.statement == null) {
                param.statement = HttpUtil.getRequestBody(request);
            }
            param.format = toLower(request.getParameter(Parameter.FORMAT.str()));
            param.pretty = Boolean.parseBoolean(request.getParameter(Parameter.PRETTY.str()));
            param.mode = toLower(request.getParameter(Parameter.MODE.str()));
            param.clientContextID = request.getParameter(Parameter.CLIENT_ID.str());
            param.timeout = request.getParameter(Parameter.TIMEOUT.str());
            param.maxResultReads = request.getParameter(Parameter.MAX_RESULT_READS.str());
        }
        return param;
    }

    private static ResultDelivery parseResultDelivery(String mode) {
        if ("async".equals(mode)) {
            return ResultDelivery.ASYNC;
        } else if ("deferred".equals(mode)) {
            return ResultDelivery.DEFERRED;
        } else {
            return ResultDelivery.IMMEDIATE;
        }
    }

    private static String handlePath(ResultDelivery delivery) {
        switch (delivery) {
            case ASYNC:
                return "/status/";
            case DEFERRED:
                return "/result/";
            case IMMEDIATE:
            default:
                return "";
        }
    }

    /**
     * Determines the URL for a result handle based on the host and the path of the incoming request and the result
     * delivery mode. Usually there will be a "status" endpoint for ASYNC requests that exposes the status of the
     * execution and a "result" endpoint for DEFERRED requests that will deliver the result for a successful execution.
     *
     * @param host
     *            hostname used for this request
     * @param path
     *            servlet path for this request
     * @param delivery
     *            ResultDelivery mode for this request
     * @return a handle (URL) that allows a client to access further information for this request
     */
    protected String getHandleUrl(String host, String path, ResultDelivery delivery) {
        return "http://" + host + path + handlePath(delivery);
    }

    private void handleRequest(IServletRequest request, IServletResponse response) throws IOException {
        RequestParameters param = getRequestParameters(request);
        LOGGER.info(param.toString());
        long elapsedStart = System.nanoTime();
        final PrintWriter httpWriter = response.writer();

        ResultDelivery delivery = parseResultDelivery(param.mode);

        final ResultProperties resultProperties = param.maxResultReads == null ? new ResultProperties(delivery)
                : new ResultProperties(delivery, Long.parseLong(param.maxResultReads));

        String handleUrl = getHandleUrl(param.host, param.path, delivery);
        SessionOutput sessionOutput = createSessionOutput(param, handleUrl, httpWriter);
        SessionConfig sessionConfig = sessionOutput.config();
        HttpUtil.setContentType(response, HttpUtil.ContentType.APPLICATION_JSON, HttpUtil.Encoding.UTF8);

        Stats stats = new Stats();
        RequestExecutionState execution = new RequestExecutionState();

        // buffer the output until we are ready to set the status of the response message correctly
        sessionOutput.hold();
        sessionOutput.out().print("{\n");
        printRequestId(sessionOutput.out());
        printClientContextID(sessionOutput.out(), param);
        printSignature(sessionOutput.out());
        printType(sessionOutput.out(), sessionConfig);
        long errorCount = 1; // so far we just return 1 error
        try {
            if (param.statement == null || param.statement.isEmpty()) {
                throw new AsterixException("Empty request, no statement provided");
            }
            String statementsText = param.statement + ";";
            Map<String, String> optionalParams = null;
            if (optionalParamProvider != null) {
                optionalParams = optionalParamProvider.apply(request);
            }
            // CORS
            response.setHeader("Access-Control-Allow-Origin",
                    "http://" + hostName + ":" + appCtx.getExternalProperties().getQueryWebInterfacePort());
            response.setHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
            response.setStatus(execution.getHttpStatus());
            executeStatement(statementsText, sessionOutput, resultProperties, stats, param, execution, optionalParams);
            if (ResultDelivery.IMMEDIATE == delivery || ResultDelivery.DEFERRED == delivery) {
                ResultUtil.printStatus(sessionOutput, execution.getResultStatus());
            }
            errorCount = 0;
        } catch (Exception | TokenMgrError | org.apache.asterix.aqlplus.parser.TokenMgrError e) {
            handleExecuteStatementException(e, execution);
            response.setStatus(execution.getHttpStatus());
            ResultUtil.printError(sessionOutput.out(), e);
            ResultUtil.printStatus(sessionOutput, execution.getResultStatus());
        } finally {
            // make sure that we stop buffering and return the result to the http response
            sessionOutput.release();
            execution.finish();
        }
        printMetrics(sessionOutput.out(), System.nanoTime() - elapsedStart, execution.duration(), stats.getCount(),
                stats.getSize(), stats.getProcessedObjects(), errorCount);
        sessionOutput.out().print("}\n");
        sessionOutput.out().flush();
        if (sessionOutput.out().checkError()) {
            LOGGER.warn("Error flushing output writer");
        }
    }

    protected void executeStatement(String statementsText, SessionOutput sessionOutput,
            ResultProperties resultProperties, IStatementExecutor.Stats stats, RequestParameters param,
            RequestExecutionState execution, Map<String, String> optionalParameters) throws Exception {
        IClusterManagementWork.ClusterState clusterState =
                ((ICcApplicationContext) appCtx).getClusterStateManager().getState();
        if (clusterState != IClusterManagementWork.ClusterState.ACTIVE) {
            // using a plain IllegalStateException here to get into the right catch clause for a 500
            throw new IllegalStateException("Cannot execute request, cluster is " + clusterState);
        }
        IParser parser = compilationProvider.getParserFactory().createParser(statementsText);
        List<Statement> statements = parser.parse();
        MetadataManager.INSTANCE.init();
        IStatementExecutor translator = statementExecutorFactory.create((ICcApplicationContext) appCtx, statements,
                sessionOutput, compilationProvider, componentProvider);
        execution.start();
        final IRequestParameters requestParameters = new org.apache.asterix.app.translator.RequestParameters(
                getHyracksDataset(), resultProperties, stats, null, param.clientContextID, optionalParameters);
        translator.compileAndExecute(getHyracksClientConnection(), queryCtx, requestParameters);
        execution.end();
    }

    protected void handleExecuteStatementException(Throwable t, RequestExecutionState execution) {
        if (t instanceof org.apache.asterix.aqlplus.parser.TokenMgrError || t instanceof TokenMgrError
                || t instanceof AlgebricksException) {
            GlobalConfig.ASTERIX_LOGGER.log(Level.INFO, t.getMessage(), t);
            execution.setStatus(ResultStatus.FATAL, HttpResponseStatus.BAD_REQUEST);
        } else if (t instanceof HyracksException) {
            GlobalConfig.ASTERIX_LOGGER.log(Level.WARN, t.getMessage(), t);
            if (((HyracksException) t).getErrorCode() == ErrorCode.QUERY_TIMEOUT) {
                execution.setStatus(ResultStatus.TIMEOUT, HttpResponseStatus.OK);
            } else {
                execution.setStatus(ResultStatus.FATAL, HttpResponseStatus.INTERNAL_SERVER_ERROR);
            }
        } else {
            GlobalConfig.ASTERIX_LOGGER.log(Level.WARN, "Unexpected exception", t);
            execution.setStatus(ResultStatus.FATAL, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }
}

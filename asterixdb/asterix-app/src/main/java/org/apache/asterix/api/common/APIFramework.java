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
package org.apache.asterix.api.common;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.asterix.algebra.base.ILangExpressionToPlanTranslator;
import org.apache.asterix.algebra.base.ILangExpressionToPlanTranslatorFactory;
import org.apache.asterix.api.http.server.ResultUtil;
import org.apache.asterix.common.api.INodeJobTracker;
import org.apache.asterix.common.config.CompilerProperties;
import org.apache.asterix.common.config.OptimizationConfUtil;
import org.apache.asterix.common.exceptions.ACIDException;
import org.apache.asterix.common.exceptions.AsterixException;
import org.apache.asterix.common.exceptions.CompilationException;
import org.apache.asterix.common.exceptions.ErrorCode;
import org.apache.asterix.common.transactions.TxnId;
import org.apache.asterix.common.utils.Job;
import org.apache.asterix.common.utils.Job.SubmissionMode;
import org.apache.asterix.compiler.provider.ILangCompilationProvider;
import org.apache.asterix.compiler.provider.IRuleSetFactory;
import org.apache.asterix.dataflow.data.common.ConflictingTypeResolver;
import org.apache.asterix.dataflow.data.common.ExpressionTypeComputer;
import org.apache.asterix.dataflow.data.common.MergeAggregationExpressionFactory;
import org.apache.asterix.dataflow.data.common.MissableTypeComputer;
import org.apache.asterix.dataflow.data.common.PartialAggregationTypeComputer;
import org.apache.asterix.external.feed.watch.FeedActivityDetails;
import org.apache.asterix.formats.base.IDataFormat;
import org.apache.asterix.jobgen.QueryLogicalExpressionJobGen;
import org.apache.asterix.lang.common.base.IAstPrintVisitorFactory;
import org.apache.asterix.lang.common.base.IQueryRewriter;
import org.apache.asterix.lang.common.base.IReturningStatement;
import org.apache.asterix.lang.common.base.IRewriterFactory;
import org.apache.asterix.lang.common.base.Statement;
import org.apache.asterix.lang.common.rewrites.LangRewritingContext;
import org.apache.asterix.lang.common.statement.FunctionDecl;
import org.apache.asterix.lang.common.statement.Query;
import org.apache.asterix.lang.common.statement.StartFeedStatement;
import org.apache.asterix.lang.common.util.FunctionUtil;
import org.apache.asterix.metadata.declared.MetadataProvider;
import org.apache.asterix.optimizer.base.FuzzyUtils;
import org.apache.asterix.runtime.job.listener.JobEventListenerFactory;
import org.apache.asterix.transaction.management.service.transaction.TxnIdFactory;
import org.apache.asterix.translator.CompiledStatements.ICompiledDmlStatement;
import org.apache.asterix.translator.IStatementExecutor.Stats;
import org.apache.asterix.translator.SessionConfig;
import org.apache.asterix.translator.SessionOutput;
import org.apache.asterix.utils.ResourceUtils;
import org.apache.hyracks.algebricks.common.constraints.AlgebricksAbsolutePartitionConstraint;
import org.apache.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraint;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.common.utils.Pair;
import org.apache.hyracks.algebricks.compiler.api.HeuristicCompilerFactoryBuilder;
import org.apache.hyracks.algebricks.compiler.api.ICompiler;
import org.apache.hyracks.algebricks.compiler.api.ICompilerFactory;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalPlan;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.expressions.ExpressionRuntimeProvider;
import org.apache.hyracks.algebricks.core.algebra.expressions.IConflictingTypeResolver;
import org.apache.hyracks.algebricks.core.algebra.expressions.IExpressionEvalSizeComputer;
import org.apache.hyracks.algebricks.core.algebra.expressions.IExpressionTypeComputer;
import org.apache.hyracks.algebricks.core.algebra.expressions.IMergeAggregationExpressionFactory;
import org.apache.hyracks.algebricks.core.algebra.expressions.IMissableTypeComputer;
import org.apache.hyracks.algebricks.core.algebra.prettyprint.AbstractLogicalOperatorPrettyPrintVisitor;
import org.apache.hyracks.algebricks.core.algebra.prettyprint.AlgebricksAppendable;
import org.apache.hyracks.algebricks.core.algebra.prettyprint.LogicalOperatorPrettyPrintVisitor;
import org.apache.hyracks.algebricks.core.algebra.prettyprint.LogicalOperatorPrettyPrintVisitorJson;
import org.apache.hyracks.algebricks.core.algebra.prettyprint.PlanPrettyPrinter;
import org.apache.hyracks.algebricks.core.rewriter.base.AlgebricksOptimizationContext;
import org.apache.hyracks.algebricks.core.rewriter.base.IOptimizationContextFactory;
import org.apache.hyracks.algebricks.core.rewriter.base.PhysicalOptimizationConfig;
import org.apache.hyracks.algebricks.data.IPrinterFactoryProvider;
import org.apache.hyracks.api.client.IClusterInfoCollector;
import org.apache.hyracks.api.client.IHyracksClientConnection;
import org.apache.hyracks.api.client.NodeControllerInfo;
import org.apache.hyracks.api.config.IOptionType;
import org.apache.hyracks.api.exceptions.HyracksException;
import org.apache.hyracks.api.job.JobId;
import org.apache.hyracks.api.job.JobSpecification;
import org.apache.hyracks.api.job.resource.IClusterCapacity;
import org.apache.hyracks.control.common.config.OptionTypes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.ImmutableSet;

/**
 * Provides helper methods for compilation of a query into a JobSpec and submission
 * to Hyracks through the Hyracks client interface.
 */
public class APIFramework {

    private static final int MIN_FRAME_LIMIT_FOR_SORT = 3;
    private static final int MIN_FRAME_LIMIT_FOR_GROUP_BY = 4;
    private static final int MIN_FRAME_LIMIT_FOR_JOIN = 5;
    private static final String LPLAN = "Logical plan";
    private static final String OPLAN = "Optimized logical plan";

    // A white list of supported configurable parameters.
    private static final Set<String> CONFIGURABLE_PARAMETER_NAMES =
            ImmutableSet.of(CompilerProperties.COMPILER_JOINMEMORY_KEY, CompilerProperties.COMPILER_GROUPMEMORY_KEY,
                    CompilerProperties.COMPILER_SORTMEMORY_KEY, CompilerProperties.COMPILER_PARALLELISM_KEY,
                    FunctionUtil.IMPORT_PRIVATE_FUNCTIONS, FuzzyUtils.SIM_FUNCTION_PROP_NAME,
                    FuzzyUtils.SIM_THRESHOLD_PROP_NAME, StartFeedStatement.WAIT_FOR_COMPLETION,
                    FeedActivityDetails.FEED_POLICY_NAME, FeedActivityDetails.COLLECT_LOCATIONS, "inline_with",
                    "hash_merge", "output-record-type");

    private final IRewriterFactory rewriterFactory;
    private final IAstPrintVisitorFactory astPrintVisitorFactory;
    private final ILangExpressionToPlanTranslatorFactory translatorFactory;
    private final IRuleSetFactory ruleSetFactory;

    public APIFramework(ILangCompilationProvider compilationProvider) {
        this.rewriterFactory = compilationProvider.getRewriterFactory();
        this.astPrintVisitorFactory = compilationProvider.getAstPrintVisitorFactory();
        this.translatorFactory = compilationProvider.getExpressionToPlanTranslatorFactory();
        this.ruleSetFactory = compilationProvider.getRuleSetFactory();
    }

    private static class OptimizationContextFactory implements IOptimizationContextFactory {

        public static final OptimizationContextFactory INSTANCE = new OptimizationContextFactory();

        private OptimizationContextFactory() {
        }

        @Override
        public IOptimizationContext createOptimizationContext(int varCounter,
                IExpressionEvalSizeComputer expressionEvalSizeComputer,
                IMergeAggregationExpressionFactory mergeAggregationExpressionFactory,
                IExpressionTypeComputer expressionTypeComputer, IMissableTypeComputer missableTypeComputer,
                IConflictingTypeResolver conflictingTypeResolver, PhysicalOptimizationConfig physicalOptimizationConfig,
                AlgebricksPartitionConstraint clusterLocations) {
            return new AlgebricksOptimizationContext(varCounter, expressionEvalSizeComputer,
                    mergeAggregationExpressionFactory, expressionTypeComputer, missableTypeComputer,
                    conflictingTypeResolver, physicalOptimizationConfig, clusterLocations);
        }
    }

    private void printPlanPrefix(SessionOutput output, String planName) {
        if (output.config().is(SessionConfig.FORMAT_HTML)) {
            output.out().println("<h4>" + planName + ":</h4>");
            if (LPLAN.equalsIgnoreCase(planName)) {
                output.out().println("<pre class = query-plan>");
            } else if (OPLAN.equalsIgnoreCase(planName)) {
                output.out().println("<pre class = query-optimized-plan>");
            } else {
                output.out().println("<pre>");
            }
        } else {
            output.out().println("----------" + planName + ":");
        }
    }

    private void printPlanPostfix(SessionOutput output) {
        if (output.config().is(SessionConfig.FORMAT_HTML)) {
            output.out().println("</pre>");
        }
    }

    public Pair<IReturningStatement, Integer> reWriteQuery(List<FunctionDecl> declaredFunctions,
            MetadataProvider metadataProvider, IReturningStatement q, SessionOutput output, boolean inlineUdfs)
            throws CompilationException {
        if (q == null) {
            return null;
        }
        SessionConfig conf = output.config();
        if (!conf.is(SessionConfig.FORMAT_ONLY_PHYSICAL_OPS) && conf.is(SessionConfig.OOB_EXPR_TREE)) {
            output.out().println();
            printPlanPrefix(output, "Expression tree");
            q.accept(astPrintVisitorFactory.createLangVisitor(output.out()), 0);
            printPlanPostfix(output);
        }
        IQueryRewriter rw = rewriterFactory.createQueryRewriter();
        rw.rewrite(declaredFunctions, q, metadataProvider, new LangRewritingContext(q.getVarCounter()), inlineUdfs);
        return new Pair<>(q, q.getVarCounter());
    }

    public JobSpecification compileQuery(IClusterInfoCollector clusterInfoCollector, MetadataProvider metadataProvider,
            Query query, int varCounter, String outputDatasetName, SessionOutput output,
            ICompiledDmlStatement statement) throws AlgebricksException, ACIDException {

        // establish facts
        final boolean isQuery = query != null;
        final boolean isLoad = statement != null && statement.getKind() == Statement.Kind.LOAD;

        SessionConfig conf = output.config();
        if (!conf.is(SessionConfig.FORMAT_ONLY_PHYSICAL_OPS) && conf.is(SessionConfig.OOB_REWRITTEN_EXPR_TREE)) {
            output.out().println();

            printPlanPrefix(output, "Rewritten expression tree");
            if (isQuery) {
                query.accept(astPrintVisitorFactory.createLangVisitor(output.out()), 0);
            }
            printPlanPostfix(output);
        }

        final TxnId txnId = TxnIdFactory.create();
        metadataProvider.setTxnId(txnId);
        ILangExpressionToPlanTranslator t =
                translatorFactory.createExpressionToPlanTranslator(metadataProvider, varCounter);

        ILogicalPlan plan = isLoad ? t.translateLoad(statement) : t.translate(query, outputDatasetName, statement);

        if (!conf.is(SessionConfig.FORMAT_ONLY_PHYSICAL_OPS) && conf.is(SessionConfig.OOB_LOGICAL_PLAN)) {
            output.out().println();

            printPlanPrefix(output, "Logical plan");
            if (isQuery || isLoad) {
                PlanPrettyPrinter.printPlan(plan, getPrettyPrintVisitor(output.config().getLpfmt(), output.out()), 0);
            }
            printPlanPostfix(output);
        }
        CompilerProperties compilerProperties = metadataProvider.getApplicationContext().getCompilerProperties();
        Map<String, String> querySpecificConfig = validateConfig(metadataProvider.getConfig());
        final PhysicalOptimizationConfig physOptConf =
                getPhysicalOptimizationConfig(compilerProperties, querySpecificConfig);

        HeuristicCompilerFactoryBuilder builder =
                new HeuristicCompilerFactoryBuilder(OptimizationContextFactory.INSTANCE);
        builder.setPhysicalOptimizationConfig(physOptConf);
        builder.setLogicalRewrites(ruleSetFactory.getLogicalRewrites(metadataProvider.getApplicationContext()));
        builder.setPhysicalRewrites(ruleSetFactory.getPhysicalRewrites(metadataProvider.getApplicationContext()));
        IDataFormat format = metadataProvider.getDataFormat();
        ICompilerFactory compilerFactory = builder.create();
        builder.setExpressionEvalSizeComputer(format.getExpressionEvalSizeComputer());
        builder.setIMergeAggregationExpressionFactory(new MergeAggregationExpressionFactory());
        builder.setPartialAggregationTypeComputer(new PartialAggregationTypeComputer());
        builder.setExpressionTypeComputer(ExpressionTypeComputer.INSTANCE);
        builder.setMissableTypeComputer(MissableTypeComputer.INSTANCE);
        builder.setConflictingTypeResolver(ConflictingTypeResolver.INSTANCE);

        int parallelism = getParallelism(querySpecificConfig.get(CompilerProperties.COMPILER_PARALLELISM_KEY),
                compilerProperties.getParallelism());
        AlgebricksAbsolutePartitionConstraint computationLocations =
                chooseLocations(clusterInfoCollector, parallelism, metadataProvider.getClusterLocations());
        builder.setClusterLocations(computationLocations);

        ICompiler compiler = compilerFactory.createCompiler(plan, metadataProvider, t.getVarCounter());
        if (conf.isOptimize()) {
            compiler.optimize();
            if (conf.is(SessionConfig.OOB_OPTIMIZED_LOGICAL_PLAN)) {
                if (conf.is(SessionConfig.FORMAT_ONLY_PHYSICAL_OPS)) {
                    // For Optimizer tests.
                    AlgebricksAppendable buffer = new AlgebricksAppendable(output.out());
                    PlanPrettyPrinter.printPhysicalOps(plan, buffer, 0);
                } else {
                    printPlanPrefix(output, "Optimized logical plan");
                    if (isQuery || isLoad) {
                        PlanPrettyPrinter.printPlan(plan,
                                getPrettyPrintVisitor(output.config().getLpfmt(), output.out()), 0);
                    }
                    printPlanPostfix(output);
                }
            }
        }
        if (isQuery && query.isExplain()) {
            try {
                LogicalOperatorPrettyPrintVisitor pvisitor = new LogicalOperatorPrettyPrintVisitor();
                PlanPrettyPrinter.printPlan(plan, pvisitor, 0);
                ResultUtil.printResults(metadataProvider.getApplicationContext(), pvisitor.get().toString(), output,
                        new Stats(), null);
                return null;
            } catch (IOException e) {
                throw new AlgebricksException(e);
            }
        }

        if (!conf.isGenerateJobSpec()) {
            return null;
        }

        builder.setBinaryBooleanInspectorFactory(format.getBinaryBooleanInspectorFactory());
        builder.setBinaryIntegerInspectorFactory(format.getBinaryIntegerInspectorFactory());
        builder.setComparatorFactoryProvider(format.getBinaryComparatorFactoryProvider());
        builder.setExpressionRuntimeProvider(
                new ExpressionRuntimeProvider(new QueryLogicalExpressionJobGen(metadataProvider.getFunctionManager())));
        builder.setHashFunctionFactoryProvider(format.getBinaryHashFunctionFactoryProvider());
        builder.setHashFunctionFamilyProvider(format.getBinaryHashFunctionFamilyProvider());
        builder.setMissingWriterFactory(format.getMissingWriterFactory());
        builder.setPredicateEvaluatorFactoryProvider(format.getPredicateEvaluatorFactoryProvider());
        builder.setPrinterProvider(getPrinterFactoryProvider(format, conf.fmt()));
        builder.setSerializerDeserializerProvider(format.getSerdeProvider());
        builder.setTypeTraitProvider(format.getTypeTraitProvider());
        builder.setNormalizedKeyComputerFactoryProvider(format.getNormalizedKeyComputerFactoryProvider());

        JobEventListenerFactory jobEventListenerFactory =
                new JobEventListenerFactory(txnId, metadataProvider.isWriteTransaction());
        JobSpecification spec = compiler.createJob(metadataProvider.getApplicationContext(), jobEventListenerFactory);

        if (isQuery) {
            // Sets a required capacity, only for read-only queries.
            // DDLs and DMLs are considered not that frequent.
            // limit the computation locations to the locations that will be used in the query
            final INodeJobTracker nodeJobTracker = metadataProvider.getApplicationContext().getNodeJobTracker();
            final AlgebricksAbsolutePartitionConstraint jobLocations =
                    getJobLocations(spec, nodeJobTracker, computationLocations);
            final IClusterCapacity jobRequiredCapacity =
                    ResourceUtils.getRequiredCapacity(plan, jobLocations, physOptConf);
            spec.setRequiredClusterCapacity(jobRequiredCapacity);
        }

        printJobSpec(query, spec, conf, output);
        return spec;
    }

    protected PhysicalOptimizationConfig getPhysicalOptimizationConfig(CompilerProperties compilerProperties,
            Map<String, String> querySpecificConfig) throws AlgebricksException {
        int frameSize = compilerProperties.getFrameSize();
        int sortFrameLimit = getFrameLimit(CompilerProperties.COMPILER_SORTMEMORY_KEY,
                querySpecificConfig.get(CompilerProperties.COMPILER_SORTMEMORY_KEY),
                compilerProperties.getSortMemorySize(), frameSize, MIN_FRAME_LIMIT_FOR_SORT);
        int groupFrameLimit = getFrameLimit(CompilerProperties.COMPILER_GROUPMEMORY_KEY,
                querySpecificConfig.get(CompilerProperties.COMPILER_GROUPMEMORY_KEY),
                compilerProperties.getGroupMemorySize(), frameSize, MIN_FRAME_LIMIT_FOR_GROUP_BY);
        int joinFrameLimit = getFrameLimit(CompilerProperties.COMPILER_JOINMEMORY_KEY,
                querySpecificConfig.get(CompilerProperties.COMPILER_JOINMEMORY_KEY),
                compilerProperties.getJoinMemorySize(), frameSize, MIN_FRAME_LIMIT_FOR_JOIN);
        final PhysicalOptimizationConfig physOptConf = OptimizationConfUtil.getPhysicalOptimizationConfig();
        physOptConf.setFrameSize(frameSize);
        physOptConf.setMaxFramesExternalSort(sortFrameLimit);
        physOptConf.setMaxFramesExternalGroupBy(groupFrameLimit);
        physOptConf.setMaxFramesForJoin(joinFrameLimit);
        return physOptConf;
    }

    protected IPrinterFactoryProvider getPrinterFactoryProvider(IDataFormat format,
            SessionConfig.OutputFormat outputFormat) throws AlgebricksException {
        switch (outputFormat) {
            case LOSSLESS_JSON:
                return format.getLosslessJSONPrinterFactoryProvider();
            case CSV:
                return format.getCSVPrinterFactoryProvider();
            case ADM:
                return format.getADMPrinterFactoryProvider();
            case CLEAN_JSON:
                return format.getCleanJSONPrinterFactoryProvider();
            default:
                throw new AlgebricksException("Unexpected OutputFormat: " + outputFormat);
        }
    }

    protected void printJobSpec(Query rwQ, JobSpecification spec, SessionConfig conf, SessionOutput output)
            throws AlgebricksException {
        if (conf.is(SessionConfig.OOB_HYRACKS_JOB)) {
            printPlanPrefix(output, "Hyracks job");
            if (rwQ != null) {
                try {
                    final ObjectWriter objectWriter = new ObjectMapper().writerWithDefaultPrettyPrinter();
                    output.out().println(objectWriter.writeValueAsString(spec.toJSON()));
                } catch (IOException e) {
                    throw new AlgebricksException(e);
                }
                output.out().println(spec.getUserConstraints());
            }
            printPlanPostfix(output);
        }
    }

    private AbstractLogicalOperatorPrettyPrintVisitor getPrettyPrintVisitor(SessionConfig.PlanFormat planFormat,
            PrintWriter out) {
        return planFormat.equals(SessionConfig.PlanFormat.JSON) ? new LogicalOperatorPrettyPrintVisitorJson(out)
                : new LogicalOperatorPrettyPrintVisitor(out);
    }

    public void executeJobArray(IHyracksClientConnection hcc, JobSpecification[] specs, PrintWriter out)
            throws Exception {
        for (JobSpecification spec : specs) {
            spec.setMaxReattempts(0);
            JobId jobId = hcc.startJob(spec);
            long startTime = System.currentTimeMillis();
            hcc.waitForCompletion(jobId);
            long endTime = System.currentTimeMillis();
            double duration = (endTime - startTime) / 1000.00;
            out.println("<pre>Duration: " + duration + " sec</pre>");
        }
    }

    public void executeJobArray(IHyracksClientConnection hcc, Job[] jobs, PrintWriter out) throws Exception {
        for (Job job : jobs) {
            job.getJobSpec().setMaxReattempts(0);
            long startTime = System.currentTimeMillis();
            try {
                JobId jobId = hcc.startJob(job.getJobSpec());
                if (job.getSubmissionMode() == SubmissionMode.ASYNCHRONOUS) {
                    continue;
                }
                hcc.waitForCompletion(jobId);
            } catch (Exception e) {
                e.printStackTrace();
                continue;
            }
            long endTime = System.currentTimeMillis();
            double duration = (endTime - startTime) / 1000.00;
            out.println("<pre>Duration: " + duration + " sec</pre>");
        }
    }

    // Chooses the location constraints, i.e., whether to use storage parallelism or use a user-sepcified number
    // of cores.
    private static AlgebricksAbsolutePartitionConstraint chooseLocations(IClusterInfoCollector clusterInfoCollector,
            int parallelismHint, AlgebricksAbsolutePartitionConstraint storageLocations) throws AlgebricksException {
        try {
            Map<String, NodeControllerInfo> ncMap = clusterInfoCollector.getNodeControllerInfos();

            // Gets total number of cores in the cluster.
            int totalNumCores = getTotalNumCores(ncMap);

            // If storage parallelism is not larger than the total number of cores, we use the storage parallelism.
            // Otherwise, we will use all available cores.
            if (parallelismHint == CompilerProperties.COMPILER_PARALLELISM_AS_STORAGE
                    && storageLocations.getLocations().length <= totalNumCores) {
                return storageLocations;
            }
            return getComputationLocations(ncMap, parallelismHint);
        } catch (HyracksException e) {
            throw new AlgebricksException(e);
        }
    }

    // Computes the location constraints based on user-configured parallelism parameter.
    // Note that the parallelism parameter is only a hint -- it will not be respected if it is too small or too large.
    private static AlgebricksAbsolutePartitionConstraint getComputationLocations(Map<String, NodeControllerInfo> ncMap,
            int parallelismHint) {
        // Unifies the handling of non-positive parallelism.
        int parallelism = parallelismHint <= 0 ? -2 * ncMap.size() : parallelismHint;

        // Calculates per node parallelism, with load balance, i.e., randomly selecting nodes with larger
        // parallelism.
        int numNodes = ncMap.size();
        int numNodesWithOneMorePartition = parallelism % numNodes;
        int perNodeParallelismMin = parallelism / numNodes;
        int perNodeParallelismMax = parallelism / numNodes + 1;
        List<String> allNodes = new ArrayList<>();
        Set<String> selectedNodesWithOneMorePartition = new HashSet<>();
        ncMap.forEach((key, value) -> allNodes.add(key));
        Random random = new Random();
        for (int index = numNodesWithOneMorePartition; index >= 1; --index) {
            int pick = random.nextInt(index);
            selectedNodesWithOneMorePartition.add(allNodes.get(pick));
            Collections.swap(allNodes, pick, index - 1);
        }

        // Generates cluster locations, which has duplicates for a node if it contains more than one partitions.
        List<String> locations = new ArrayList<>();
        ncMap.forEach((nodeId, value) -> {
            int availableCores = value.getNumAvailableCores();
            int nodeParallelism =
                    selectedNodesWithOneMorePartition.contains(nodeId) ? perNodeParallelismMax : perNodeParallelismMin;
            int coresToUse =
                    nodeParallelism >= 0 && nodeParallelism < availableCores ? nodeParallelism : availableCores;
            for (int count = 0; count < coresToUse; ++count) {
                locations.add(nodeId);
            }
        });
        return new AlgebricksAbsolutePartitionConstraint(locations.toArray(new String[0]));
    }

    // Gets the total number of available cores in the cluster.
    private static int getTotalNumCores(Map<String, NodeControllerInfo> ncMap) {
        return ncMap.values().stream().mapToInt(NodeControllerInfo::getNumAvailableCores).sum();
    }

    // Gets the frame limit.
    private static int getFrameLimit(String parameterName, String parameter, long memBudgetInConfiguration,
            int frameSize, int minFrameLimit) throws AlgebricksException {
        IOptionType<Long> longBytePropertyInterpreter = OptionTypes.LONG_BYTE_UNIT;
        long memBudget = parameter == null ? memBudgetInConfiguration : longBytePropertyInterpreter.parse(parameter);
        int frameLimit = (int) (memBudget / frameSize);
        if (frameLimit < minFrameLimit) {
            throw AsterixException.create(ErrorCode.COMPILATION_BAD_QUERY_PARAMETER_VALUE, parameterName,
                    frameSize * minFrameLimit);
        }
        // Sets the frame limit to the minimum frame limit if the caculated frame limit is too small.
        return Math.max(frameLimit, minFrameLimit);
    }

    // Gets the parallelism parameter.
    private static int getParallelism(String parameter, int parallelismInConfiguration) {
        IOptionType<Integer> integerIPropertyInterpreter = OptionTypes.INTEGER;
        return parameter == null ? parallelismInConfiguration : integerIPropertyInterpreter.parse(parameter);
    }

    // Validates if the query contains unsupported query parameters.
    private static Map<String, String> validateConfig(Map<String, String> config) throws AlgebricksException {
        for (String parameterName : config.keySet()) {
            if (!CONFIGURABLE_PARAMETER_NAMES.contains(parameterName)) {
                throw AsterixException.create(ErrorCode.COMPILATION_UNSUPPORTED_QUERY_PARAMETER, parameterName);
            }
        }
        return config;
    }

    public static AlgebricksAbsolutePartitionConstraint getJobLocations(JobSpecification spec,
            INodeJobTracker jobTracker, AlgebricksAbsolutePartitionConstraint clusterLocations) {
        final Set<String> jobParticipatingNodes = jobTracker.getJobParticipatingNodes(spec);
        return new AlgebricksAbsolutePartitionConstraint(Arrays.stream(clusterLocations.getLocations())
                .filter(jobParticipatingNodes::contains).toArray(String[]::new));
    }
}

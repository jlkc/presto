/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution;

import com.facebook.presto.client.Input;
import com.facebook.presto.execution.StateMachine.StateChangeListener;
import com.facebook.presto.importer.PeriodicImportManager;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.ShardManager;
import com.facebook.presto.split.SplitManager;
import com.facebook.presto.sql.analyzer.Analysis;
import com.facebook.presto.sql.analyzer.Analyzer;
import com.facebook.presto.sql.analyzer.QueryExplainer;
import com.facebook.presto.sql.analyzer.Session;
import com.facebook.presto.sql.planner.DistributedExecutionPlanner;
import com.facebook.presto.sql.planner.DistributedLogicalPlanner;
import com.facebook.presto.sql.planner.InputExtractor;
import com.facebook.presto.sql.planner.LogicalPlanner;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.sql.planner.PlanNodeIdAllocator;
import com.facebook.presto.sql.planner.StageExecutionPlan;
import com.facebook.presto.sql.planner.SubPlan;
import com.facebook.presto.sql.planner.optimizations.PlanOptimizer;
import com.facebook.presto.sql.tree.Statement;
import com.facebook.presto.storage.StorageManager;
import com.facebook.presto.util.SetThreadName;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import io.airlift.concurrent.ThreadPoolExecutorMBean;
import io.airlift.units.Duration;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import static com.facebook.presto.util.Threads.threadsNamed;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@ThreadSafe
public class SqlQueryExecution
        implements QueryExecution
{
    private static final String ROOT_OUTPUT_BUFFER_NAME = "out";

    private final QueryStateMachine stateMachine;

    private final Statement statement;
    private final Metadata metadata;
    private final SplitManager splitManager;
    private final NodeScheduler nodeScheduler;
    private final List<PlanOptimizer> planOptimizers;
    private final RemoteTaskFactory remoteTaskFactory;
    private final LocationFactory locationFactory;
    private final int maxPendingSplitsPerNode;
    private final ExecutorService queryExecutor;
    private final ShardManager shardManager;
    private final StorageManager storageManager;
    private final PeriodicImportManager periodicImportManager;

    private final QueryExplainer queryExplainer;

    private final AtomicReference<SqlStageExecution> outputStage = new AtomicReference<>();

    public SqlQueryExecution(QueryId queryId,
            String query,
            Session session,
            URI self,
            Statement statement,
            Metadata metadata,
            SplitManager splitManager,
            NodeScheduler nodeScheduler,
            List<PlanOptimizer> planOptimizers,
            RemoteTaskFactory remoteTaskFactory,
            LocationFactory locationFactory,
            int maxPendingSplitsPerNode,
            ExecutorService queryExecutor,
            ShardManager shardManager,
            StorageManager storageManager,
            PeriodicImportManager periodicImportManager)
    {
        try (SetThreadName setThreadName = new SetThreadName("Query-%s", queryId)) {
            this.statement = checkNotNull(statement, "statement is null");
            this.metadata = checkNotNull(metadata, "metadata is null");
            this.splitManager = checkNotNull(splitManager, "splitManager is null");
            this.nodeScheduler = checkNotNull(nodeScheduler, "nodeScheduler is null");
            this.planOptimizers = checkNotNull(planOptimizers, "planOptimizers is null");
            this.remoteTaskFactory = checkNotNull(remoteTaskFactory, "remoteTaskFactory is null");
            this.locationFactory = checkNotNull(locationFactory, "locationFactory is null");
            this.queryExecutor = checkNotNull(queryExecutor, "queryExecutor is null");
            this.shardManager = checkNotNull(shardManager, "shardManager is null");
            this.storageManager = checkNotNull(storageManager, "storageManager is null");
            this.periodicImportManager = checkNotNull(periodicImportManager, "periodicImportManager is null");

            checkArgument(maxPendingSplitsPerNode > 0, "maxPendingSplitsPerNode must be greater than 0");
            this.maxPendingSplitsPerNode = maxPendingSplitsPerNode;

            checkNotNull(queryId, "queryId is null");
            checkNotNull(query, "query is null");
            checkNotNull(session, "session is null");
            checkNotNull(self, "self is null");
            this.stateMachine = new QueryStateMachine(queryId, query, session, self, queryExecutor);

            this.queryExplainer = new QueryExplainer(session, planOptimizers, metadata, periodicImportManager, storageManager);
        }
    }

    @Override
    public void start()
    {
        try (SetThreadName setThreadName = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            try {
                // transition to planning
                if (!stateMachine.beginPlanning()) {
                    // query already started or finished
                    return;
                }

                // analyze query
                SubPlan subplan = analyzeQuery();

                // plan distribution of query
                planDistribution(subplan);

                // transition to starting
                if (!stateMachine.starting()) {
                    // query already started or finished
                    return;
                }

                // if query is not finished, start the stage, otherwise cancel it
                SqlStageExecution stage = outputStage.get();

                if (!stateMachine.isDone()) {
                    stage.addOutputBuffer(ROOT_OUTPUT_BUFFER_NAME);
                    stage.noMoreOutputBuffers();
                    stage.start();
                }
                else {
                    stage.cancel(true);
                }
            }
            catch (Throwable e) {
                fail(e);
                Throwables.propagateIfInstanceOf(e, Error.class);
            }
        }
    }

    @Override
    public void addStateChangeListener(StateChangeListener<QueryState> stateChangeListener)
    {
        try (SetThreadName setThreadName = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            stateMachine.addStateChangeListener(stateChangeListener);
        }
    }

    private SubPlan analyzeQuery()
    {
        try {
            return doAnalyzeQuery();
        }
        catch (StackOverflowError e) {
            throw new RuntimeException("statement is too large (stack overflow during analysis)", e);
        }
    }

    private SubPlan doAnalyzeQuery()
    {
        // time analysis phase
        long analysisStart = System.nanoTime();

        // analyze query
        Analyzer analyzer = new Analyzer(stateMachine.getSession(), metadata, Optional.of(queryExplainer));

        Analysis analysis = analyzer.analyze(statement);
        PlanNodeIdAllocator idAllocator = new PlanNodeIdAllocator();
        // plan query
        LogicalPlanner logicalPlanner = new LogicalPlanner(stateMachine.getSession(), planOptimizers, idAllocator, metadata, periodicImportManager, storageManager);
        Plan plan = logicalPlanner.plan(analysis);

        List<Input> inputs = new InputExtractor(metadata).extract(plan.getRoot());
        stateMachine.setInputs(inputs);

        // fragment the plan
        SubPlan subplan = new DistributedLogicalPlanner(metadata, idAllocator).createSubplans(plan, false);

        stateMachine.recordAnalysisTime(analysisStart);
        return subplan;
    }

    private void planDistribution(SubPlan subplan)
    {
        // time distribution planning
        long distributedPlanningStart = System.nanoTime();

        // plan the execution on the active nodes
        DistributedExecutionPlanner distributedPlanner = new DistributedExecutionPlanner(splitManager, stateMachine.getSession(), shardManager);
        StageExecutionPlan outputStageExecutionPlan = distributedPlanner.plan(subplan);

        if (stateMachine.isDone()) {
            return;
        }

        // record field names
        stateMachine.setOutputFieldNames(outputStageExecutionPlan.getFieldNames());

        // build the stage execution objects (this doesn't schedule execution)
        SqlStageExecution outputStage = new SqlStageExecution(stateMachine.getQueryId(),
                locationFactory,
                outputStageExecutionPlan,
                nodeScheduler,
                remoteTaskFactory,
                stateMachine.getSession(),
                maxPendingSplitsPerNode,
                queryExecutor);
        this.outputStage.set(outputStage);
        outputStage.addStateChangeListener(new StateChangeListener<StageInfo>()
        {
            @Override
            public void stateChanged(StageInfo stageInfo)
            {
                doUpdateState(stageInfo);
            }
        });

        // record planning time
        stateMachine.recordDistributedPlanningTime(distributedPlanningStart);

        // update state in case output finished before listener was added
        doUpdateState(outputStage.getStageInfo());
    }

    @Override
    public void cancel()
    {
        try (SetThreadName setThreadName = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            stateMachine.cancel();
            cancelOutputStage();
        }
    }

    private void cancelOutputStage()
    {
        try (SetThreadName setThreadName = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            SqlStageExecution stageExecution = outputStage.get();
            if (stageExecution != null) {
                stageExecution.cancel(true);
            }
        }
    }

    @Override
    public void cancelStage(StageId stageId)
    {
        Preconditions.checkNotNull(stageId, "stageId is null");

        try (SetThreadName setThreadName = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            SqlStageExecution stageExecution = outputStage.get();
            if (stageExecution != null) {
                stageExecution.cancelStage(stageId);
            }
        }
    }

    @Override
    public void fail(Throwable cause)
    {
        try (SetThreadName setThreadName = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            // transition to failed state, only if not already finished
            stateMachine.fail(cause);
            cancelOutputStage();
        }
    }

    @Override
    public Duration waitForStateChange(QueryState currentState, Duration maxWait)
            throws InterruptedException
    {
        try (SetThreadName setThreadName = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            return stateMachine.waitForStateChange(currentState, maxWait);
        }
    }

    @Override
    public void recordHeartbeat()
    {
        stateMachine.recordHeartbeat();
    }

    @Override
    public QueryInfo getQueryInfo()
    {
        try (SetThreadName setThreadName = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            SqlStageExecution outputStage = this.outputStage.get();
            StageInfo stageInfo = null;
            if (outputStage != null) {
                stageInfo = outputStage.getStageInfo();
            }
            return stateMachine.getQueryInfo(stageInfo);
        }
    }

    private void doUpdateState(StageInfo outputStageInfo)
    {
        // if already complete, just return
        if (stateMachine.isDone()) {
            return;
        }

        // if output stage is done, transition to done
        StageState outputStageState = outputStageInfo.getState();
        if (outputStageState.isDone()) {
            if (outputStageState == StageState.FAILED) {
                stateMachine.fail(failureCause(outputStageInfo));
            }
            else if (outputStageState == StageState.CANCELED) {
                stateMachine.cancel();
            }
            else {
                stateMachine.finished();
            }
        }
        else if (stateMachine.getQueryState() == QueryState.STARTING) {
            // if output stage has at least one task, we are running
            if (!outputStageInfo.getTasks().isEmpty()) {
                stateMachine.running();
                stateMachine.recordExecutionStart();
            }
        }
    }

    private static Throwable failureCause(StageInfo stageInfo)
    {
        if (!stageInfo.getFailures().isEmpty()) {
            return stageInfo.getFailures().get(0).toException();
        }

        for (TaskInfo taskInfo : stageInfo.getTasks()) {
            if (!taskInfo.getFailures().isEmpty()) {
                return taskInfo.getFailures().get(0).toException();
            }
        }

        for (StageInfo subStageInfo : stageInfo.getSubStages()) {
            Throwable cause = failureCause(subStageInfo);
            if (cause != null) {
                return cause;
            }
        }

        return null;
    }

    public static class SqlQueryExecutionFactory
            implements QueryExecutionFactory<SqlQueryExecution>
    {
        private final int maxPendingSplitsPerNode;
        private final Metadata metadata;
        private final SplitManager splitManager;
        private final NodeScheduler nodeScheduler;
        private final List<PlanOptimizer> planOptimizers;
        private final RemoteTaskFactory remoteTaskFactory;
        private final LocationFactory locationFactory;
        private final ShardManager shardManager;
        private final StorageManager storageManager;
        private final PeriodicImportManager periodicImportManager;

        private final ExecutorService executor;
        private final ThreadPoolExecutorMBean executorMBean;

        @Inject
        SqlQueryExecutionFactory(QueryManagerConfig config,
                Metadata metadata,
                LocationFactory locationFactory,
                SplitManager splitManager,
                NodeScheduler nodeScheduler,
                List<PlanOptimizer> planOptimizers,
                RemoteTaskFactory remoteTaskFactory,
                ShardManager shardManager,
                StorageManager storageManager,
                PeriodicImportManager periodicImportManager)
        {
            Preconditions.checkNotNull(config, "config is null");
            this.maxPendingSplitsPerNode = config.getMaxPendingSplitsPerNode();
            this.metadata = checkNotNull(metadata, "metadata is null");
            this.locationFactory = checkNotNull(locationFactory, "locationFactory is null");
            this.splitManager = checkNotNull(splitManager, "splitManager is null");
            this.nodeScheduler = checkNotNull(nodeScheduler, "nodeScheduler is null");
            this.planOptimizers = checkNotNull(planOptimizers, "planOptimizers is null");
            this.remoteTaskFactory = checkNotNull(remoteTaskFactory, "remoteTaskFactory is null");
            this.shardManager = checkNotNull(shardManager, "shardManager is null");
            this.storageManager = checkNotNull(storageManager, "storageManager is null");
            this.periodicImportManager = checkNotNull(periodicImportManager, "periodicImportManager is null");

            this.executor = Executors.newCachedThreadPool(threadsNamed("query-scheduler-%d"));
            this.executorMBean = new ThreadPoolExecutorMBean((ThreadPoolExecutor) executor);
        }

        @Managed
        @Nested
        public ThreadPoolExecutorMBean getExecutor()
        {
            return executorMBean;
        }

        @Override
        public SqlQueryExecution createQueryExecution(QueryId queryId, String query, Session session, Statement statement)
        {
            SqlQueryExecution queryExecution = new SqlQueryExecution(queryId,
                    query,
                    session,
                    locationFactory.createQueryLocation(queryId),
                    statement,
                    metadata,
                    splitManager,
                    nodeScheduler,
                    planOptimizers,
                    remoteTaskFactory,
                    locationFactory,
                    maxPendingSplitsPerNode,
                    executor,
                    shardManager,
                    storageManager,
                    periodicImportManager);

            return queryExecution;
        }
    }
}

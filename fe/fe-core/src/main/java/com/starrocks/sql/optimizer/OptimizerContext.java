// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.sql.optimizer;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.qe.VariableMgr;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.common.ErrorType;
import com.starrocks.sql.common.StarRocksPlannerException;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.dump.DumpInfo;
import com.starrocks.sql.optimizer.rule.RuleSet;
import com.starrocks.sql.optimizer.task.SeriallyTaskScheduler;
import com.starrocks.sql.optimizer.task.TaskContext;
import com.starrocks.sql.optimizer.task.TaskScheduler;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class OptimizerContext {
    private final UUID queryId;
    private final Memo memo;
    private final RuleSet ruleSet;
    private final GlobalStateMgr globalStateMgr;
    private final TaskScheduler taskScheduler;
    private final ColumnRefFactory columnRefFactory;
    private SessionVariable sessionVariable;
    private DumpInfo dumpInfo;
    private CTEContext cteContext;
    private TaskContext currentTaskContext;
    private final OptimizerConfig optimizerConfig;
    private final List<MaterializationContext> candidateMvs;

    private long updateTableId = -1;
    private boolean enableLeftRightJoinEquivalenceDerive = true;
    private final Stopwatch optimizerTimer = Stopwatch.createStarted();

    @VisibleForTesting
    public OptimizerContext(Memo memo, ColumnRefFactory columnRefFactory) {
        this.memo = memo;
        this.ruleSet = new RuleSet();
        this.globalStateMgr = GlobalStateMgr.getCurrentState();
        this.taskScheduler = SeriallyTaskScheduler.create();
        this.columnRefFactory = columnRefFactory;
        this.sessionVariable = VariableMgr.newSessionVariable();
        this.optimizerConfig = new OptimizerConfig();
        this.candidateMvs = Lists.newArrayList();
        this.queryId = UUID.randomUUID();
    }

    @VisibleForTesting
    public OptimizerContext(Memo memo, ColumnRefFactory columnRefFactory, ConnectContext connectContext) {
        this(memo, columnRefFactory, connectContext, OptimizerConfig.defaultConfig());
    }

    public OptimizerContext(Memo memo, ColumnRefFactory columnRefFactory, ConnectContext connectContext,
                            OptimizerConfig optimizerConfig) {
        this.memo = memo;
        this.ruleSet = new RuleSet();
        this.globalStateMgr = GlobalStateMgr.getCurrentState();
        this.taskScheduler = SeriallyTaskScheduler.create();
        this.columnRefFactory = columnRefFactory;
        this.queryId = connectContext.getQueryId();
        this.sessionVariable = connectContext.getSessionVariable();
        this.dumpInfo = connectContext.getDumpInfo();
        this.cteContext = new CTEContext();
        cteContext.reset();
        this.cteContext.setEnableCTE(sessionVariable.isCboCteReuse());
        this.cteContext.setInlineCTERatio(sessionVariable.getCboCTERuseRatio());
        this.cteContext.setMaxCTELimit(sessionVariable.getCboCTEMaxLimit());
        this.optimizerConfig = optimizerConfig;
        this.candidateMvs = Lists.newArrayList();
    }

    public Memo getMemo() {
        return memo;
    }

    public RuleSet getRuleSet() {
        return ruleSet;
    }

    public GlobalStateMgr getCatalog() {
        return globalStateMgr;
    }

    public TaskScheduler getTaskScheduler() {
        return taskScheduler;
    }

    public ColumnRefFactory getColumnRefFactory() {
        return columnRefFactory;
    }

    public final SessionVariable getSessionVariable() {
        return sessionVariable;
    }

    public void setSessionVariable(SessionVariable sessionVariable) {
        this.sessionVariable = sessionVariable;
    }

    public DumpInfo getDumpInfo() {
        return dumpInfo;
    }

    public CTEContext getCteContext() {
        return cteContext;
    }

    public void setTaskContext(TaskContext context) {
        this.currentTaskContext = context;
    }

    public TaskContext getTaskContext() {
        return currentTaskContext;
    }

    public UUID getQueryId() {
        return queryId;
    }

    public OptimizerConfig getOptimizerConfig() {
        return optimizerConfig;
    }

    public List<MaterializationContext> getCandidateMvs() {
        return candidateMvs;
    }

    public void addCandidateMvs(MaterializationContext candidateMv) {
        this.candidateMvs.add(candidateMv);
    }

    public void setEnableLeftRightJoinEquivalenceDerive(boolean enableLeftRightJoinEquivalenceDerive) {
        this.enableLeftRightJoinEquivalenceDerive = enableLeftRightJoinEquivalenceDerive;
    }

    public boolean isEnableLeftRightJoinEquivalenceDerive() {
        return enableLeftRightJoinEquivalenceDerive;
    }

    public void setUpdateTableId(long updateTableId) {
        this.updateTableId = updateTableId;
    }

    public long getUpdateTableId() {
        return updateTableId;
    }

    public long optimizerElapsedMs() {
        return optimizerTimer.elapsed(TimeUnit.MILLISECONDS);
    }

    /**
     * Whether reach optimizer timeout
     */
    public boolean reachTimeout() {
        long timeout = getSessionVariable().getOptimizerExecuteTimeout();
        return optimizerElapsedMs() > timeout;
    }

    /**
     * Throw exception if reach optimizer timeout
     */
    public void checkTimeout() {
        if (!reachTimeout()) {
            return;
        }
        Memo memo = getMemo();
        Group group = memo == null ? null : memo.getRootGroup();
        throw new StarRocksPlannerException("StarRocks planner use long time " + optimizerElapsedMs() +
                " ms in " + (group == null ? "logical" : "memo") + " phase, This probably because " +
                "1. FE Full GC, " +
                "2. Hive external table fetch metadata took a long time, " +
                "3. The SQL is very complex. " +
                "You could " +
                "1. adjust FE JVM config, " +
                "2. try query again, " +
                "3. enlarge new_planner_optimize_timeout session variable",
                ErrorType.INTERNAL_ERROR);
    }
}

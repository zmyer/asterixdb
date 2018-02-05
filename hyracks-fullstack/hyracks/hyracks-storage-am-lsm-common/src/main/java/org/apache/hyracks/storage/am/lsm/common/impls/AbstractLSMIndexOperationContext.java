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
package org.apache.hyracks.storage.am.lsm.common.impls;

import java.util.ArrayList;
import java.util.List;

import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.storage.am.common.ophelpers.IndexOperation;
import org.apache.hyracks.storage.am.common.tuples.PermutingTupleReference;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMComponent;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMDiskComponent;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndex;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndexOperationContext;
import org.apache.hyracks.storage.common.IModificationOperationCallback;
import org.apache.hyracks.storage.common.ISearchOperationCallback;
import org.apache.hyracks.storage.common.ISearchPredicate;
import org.apache.hyracks.storage.common.MultiComparator;
import org.apache.hyracks.util.trace.ITracer;
import org.apache.hyracks.util.trace.ITracer.Scope;

public abstract class AbstractLSMIndexOperationContext implements ILSMIndexOperationContext {

    protected final ILSMIndex index;
    protected final PermutingTupleReference indexTuple;
    protected final MultiComparator filterCmp;
    protected final PermutingTupleReference filterTuple;
    protected final int[] allFields;
    protected final List<ILSMComponent> componentHolder;
    protected final List<ILSMDiskComponent> componentsToBeMerged;
    protected final List<ILSMDiskComponent> componentsToBeReplicated;
    protected final ISearchOperationCallback searchCallback;
    protected final IModificationOperationCallback modificationCallback;
    protected IndexOperation op;
    protected boolean accessingComponents = false;
    protected ISearchPredicate searchPredicate;
    protected final ITracer tracer;
    protected final long traceCategory;
    private long enterExitTime = 0L;

    public AbstractLSMIndexOperationContext(ILSMIndex index, int[] treeFields, int[] filterFields,
            IBinaryComparatorFactory[] filterCmpFactories, ISearchOperationCallback searchCallback,
            IModificationOperationCallback modificationCallback, ITracer tracer) {
        this.index = index;
        this.searchCallback = searchCallback;
        this.modificationCallback = modificationCallback;
        this.componentHolder = new ArrayList<>();
        this.componentsToBeMerged = new ArrayList<>();
        this.componentsToBeReplicated = new ArrayList<>();
        if (filterFields != null) {
            indexTuple = new PermutingTupleReference(treeFields);
            filterCmp = MultiComparator.create(filterCmpFactories);
            filterTuple = new PermutingTupleReference(filterFields);
            allFields = new int[treeFields.length + filterFields.length];
            for (int i = 0; i < treeFields.length; i++) {
                allFields[i] = treeFields[i];
            }
            for (int i = treeFields.length; i < treeFields.length + filterFields.length; i++) {
                allFields[i] = filterFields[i - treeFields.length];
            }
        } else {
            indexTuple = null;
            filterCmp = null;
            filterTuple = null;
            allFields = null;
        }
        this.tracer = tracer;
        this.traceCategory = tracer.getRegistry().get("op-ctx");
    }

    @Override
    public boolean isAccessingComponents() {
        return accessingComponents;
    }

    @Override
    public void setAccessingComponents(boolean accessingComponents) {
        this.accessingComponents = accessingComponents;
    }

    @Override
    public final PermutingTupleReference getIndexTuple() {
        return indexTuple;
    }

    @Override
    public final PermutingTupleReference getFilterTuple() {
        return filterTuple;
    }

    @Override
    public final MultiComparator getFilterCmp() {
        return filterCmp;
    }

    @Override
    public void reset() {
        accessingComponents = false;
        componentHolder.clear();
        componentsToBeMerged.clear();
        componentsToBeReplicated.clear();
    }

    @Override
    public IndexOperation getOperation() {
        return op;
    }

    @Override
    public List<ILSMComponent> getComponentHolder() {
        return componentHolder;
    }

    @Override
    public ISearchOperationCallback getSearchOperationCallback() {
        return searchCallback;
    }

    @Override
    public IModificationOperationCallback getModificationCallback() {
        return modificationCallback;
    }

    @Override
    public List<ILSMDiskComponent> getComponentsToBeMerged() {
        return componentsToBeMerged;
    }

    @Override
    public List<ILSMDiskComponent> getComponentsToBeReplicated() {
        return componentsToBeReplicated;
    }

    @Override
    public void setOperation(IndexOperation newOp) throws HyracksDataException {
        reset();
        op = newOp;
    }

    @Override
    public void setSearchPredicate(ISearchPredicate searchPredicate) {
        this.searchPredicate = searchPredicate;
    }

    @Override
    public ISearchPredicate getSearchPredicate() {
        return searchPredicate;
    }

    @Override
    public final boolean isTracingEnabled() {
        return tracer.isEnabled(traceCategory);
    }

    @Override
    public void logPerformanceCounters(int tupleCount) {
        if (isTracingEnabled()) {
            tracer.instant("store-counters", traceCategory, Scope.t,
                    "{\"count\":" + tupleCount + ",\"enter-exit-duration-ns\":" + enterExitTime + "}");
            resetCounters();
        }
    }

    public void resetCounters() {
        enterExitTime = 0L;
    }

    @Override
    public void incrementEnterExitTime(long increment) {
        enterExitTime += increment;
    }

    @Override
    public ILSMIndex getIndex() {
        return index;
    }
}

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
package org.apache.asterix.runtime.operators;

import java.util.List;

import org.apache.asterix.common.api.IDatasetLifecycleManager;
import org.apache.asterix.common.api.INcApplicationContext;
import org.apache.asterix.runtime.operators.LSMIndexBulkLoadOperatorDescriptor.BulkLoadUsage;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.storage.am.common.api.IIndexDataflowHelper;
import org.apache.hyracks.storage.am.common.dataflow.IIndexDataflowHelperFactory;
import org.apache.hyracks.storage.am.common.dataflow.IndexBulkLoadOperatorNodePushable;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMComponentId;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMDiskComponent;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndex;
import org.apache.hyracks.storage.am.lsm.common.impls.LSMComponentId;
import org.apache.hyracks.storage.am.lsm.common.impls.LSMIndexDiskComponentBulkLoader;
import org.apache.hyracks.storage.am.lsm.common.util.LSMComponentIdUtils;

public class LSMIndexBulkLoadOperatorNodePushable extends IndexBulkLoadOperatorNodePushable {
    protected final BulkLoadUsage usage;

    protected final IIndexDataflowHelper primaryIndexHelper;
    protected final IDatasetLifecycleManager datasetManager;
    protected final int datasetId;
    protected final int partition;

    protected ILSMIndex primaryIndex;

    public LSMIndexBulkLoadOperatorNodePushable(IIndexDataflowHelperFactory indexDataflowHelperFactory,
            IIndexDataflowHelperFactory priamryIndexDataflowHelperFactory, IHyracksTaskContext ctx, int partition,
            int[] fieldPermutation, float fillFactor, boolean verifyInput, long numElementsHint,
            boolean checkIfEmptyIndex, RecordDescriptor recDesc, BulkLoadUsage usage, int datasetId)
            throws HyracksDataException {
        super(indexDataflowHelperFactory, ctx, partition, fieldPermutation, fillFactor, verifyInput, numElementsHint,
                checkIfEmptyIndex, recDesc);

        if (priamryIndexDataflowHelperFactory != null) {
            this.primaryIndexHelper =
                    priamryIndexDataflowHelperFactory.create(ctx.getJobletContext().getServiceContext(), partition);
        } else {
            this.primaryIndexHelper = null;
        }
        this.usage = usage;
        this.datasetId = datasetId;
        this.partition = partition;
        INcApplicationContext ncCtx =
                (INcApplicationContext) ctx.getJobletContext().getServiceContext().getApplicationContext();
        datasetManager = ncCtx.getDatasetLifecycleManager();
    }

    @Override
    protected void initializeBulkLoader() throws HyracksDataException {
        ILSMIndex targetIndex = (ILSMIndex) index;
        if (usage.equals(BulkLoadUsage.LOAD)) {
            // for a loaded dataset, we use the default Id 0 which is guaranteed to be smaller
            // than Ids of all memory components

            // TODO handle component Id for datasets loaded multiple times
            // TODO move this piece of code to io operation callback
            bulkLoader = targetIndex.createBulkLoader(fillFactor, verifyInput, numElementsHint, checkIfEmptyIndex);
            ILSMDiskComponent diskComponent = ((LSMIndexDiskComponentBulkLoader) bulkLoader).getComponent();
            LSMComponentIdUtils.persist(LSMComponentId.DEFAULT_COMPONENT_ID, diskComponent.getMetadata());
        } else {
            primaryIndexHelper.open();
            primaryIndex = (ILSMIndex) primaryIndexHelper.getIndexInstance();
            List<ILSMDiskComponent> primaryComponents = primaryIndex.getDiskComponents();
            bulkLoader = targetIndex.createBulkLoader(fillFactor, verifyInput, numElementsHint, checkIfEmptyIndex);
            if (!primaryComponents.isEmpty()) {
                // TODO move this piece of code to io operation callback
                // Ideally, this should be done in io operation callback when a bulk load operation is finished
                // However, currently we don't have an extensible callback mechanism to support this
                ILSMComponentId bulkloadId = LSMComponentIdUtils.union(primaryComponents.get(0).getId(),
                        primaryComponents.get(primaryComponents.size() - 1).getId());
                ILSMDiskComponent diskComponent = ((LSMIndexDiskComponentBulkLoader) bulkLoader).getComponent();
                LSMComponentIdUtils.persist(bulkloadId, diskComponent.getMetadata());
            }
        }
    }

    @Override
    public void close() throws HyracksDataException {
        try {
            super.close();
        } finally {
            if (primaryIndex != null) {
                primaryIndexHelper.close();
            }
        }
    }

}
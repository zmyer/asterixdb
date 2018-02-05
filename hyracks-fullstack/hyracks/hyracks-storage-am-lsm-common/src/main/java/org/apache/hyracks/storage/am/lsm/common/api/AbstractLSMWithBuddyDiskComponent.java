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
package org.apache.hyracks.storage.am.lsm.common.api;

import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.storage.am.common.api.IMetadataPageManager;
import org.apache.hyracks.storage.am.common.impls.AbstractTreeIndex;
import org.apache.hyracks.storage.am.lsm.common.impls.AbstractLSMIndex;
import org.apache.hyracks.storage.am.lsm.common.impls.IChainedComponentBulkLoader;
import org.apache.hyracks.storage.am.lsm.common.impls.IndexWithBuddyBulkLoader;
import org.apache.hyracks.storage.am.lsm.common.util.ComponentUtils;
import org.apache.hyracks.storage.common.IIndexBulkLoader;

public abstract class AbstractLSMWithBuddyDiskComponent extends AbstractLSMWithBloomFilterDiskComponent {

    public AbstractLSMWithBuddyDiskComponent(AbstractLSMIndex lsmIndex, IMetadataPageManager mdPageManager,
            ILSMComponentFilter filter) {
        super(lsmIndex, mdPageManager, filter);
    }

    public abstract AbstractTreeIndex getBuddyIndex();

    @Override
    public void markAsValid(boolean persist) throws HyracksDataException {
        super.markAsValid(persist);
        ComponentUtils.markAsValid(getBuddyIndex(), persist);
    }

    @Override
    public void activate(boolean createNewComponent) throws HyracksDataException {
        super.activate(createNewComponent);
        if (createNewComponent) {
            getBuddyIndex().create();
        }
        getBuddyIndex().activate();
    }

    @Override
    public void deactivateAndDestroy() throws HyracksDataException {
        super.deactivateAndDestroy();
        getBuddyIndex().deactivate();
        getBuddyIndex().destroy();
    }

    @Override
    public void destroy() throws HyracksDataException {
        super.destroy();
        getBuddyIndex().destroy();
    }

    @Override
    public void deactivate() throws HyracksDataException {
        super.deactivate();
        getBuddyIndex().deactivate();
    }

    @Override
    public void deactivateAndPurge() throws HyracksDataException {
        super.deactivateAndPurge();
        getBuddyIndex().deactivate();
        getBuddyIndex().purge();
    }

    @Override
    public void validate() throws HyracksDataException {
        super.validate();
        getBuddyIndex().validate();
    }

    @Override
    public IChainedComponentBulkLoader createIndexBulkLoader(float fillFactor, boolean verifyInput,
            long numElementsHint, boolean checkIfEmptyIndex) throws HyracksDataException {
        IIndexBulkLoader indexBulkLoader =
                getIndex().createBulkLoader(fillFactor, verifyInput, numElementsHint, checkIfEmptyIndex);
        IIndexBulkLoader buddyBulkLoader =
                getBuddyIndex().createBulkLoader(fillFactor, verifyInput, numElementsHint, checkIfEmptyIndex);
        return new IndexWithBuddyBulkLoader(indexBulkLoader, buddyBulkLoader);
    }

}

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

import java.nio.ByteBuffer;

import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.TypeTagUtil;
import org.apache.asterix.transaction.management.opcallbacks.AbstractIndexModificationOperationCallback;
import org.apache.asterix.transaction.management.opcallbacks.AbstractIndexModificationOperationCallback.Operation;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.dataflow.common.comm.util.FrameUtils;
import org.apache.hyracks.storage.am.common.api.IModificationOperationCallbackFactory;
import org.apache.hyracks.storage.am.common.api.ITupleFilterFactory;
import org.apache.hyracks.storage.am.common.dataflow.IIndexDataflowHelperFactory;
import org.apache.hyracks.storage.am.common.ophelpers.IndexOperation;
import org.apache.hyracks.storage.am.common.tuples.PermutingFrameTupleReference;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndexAccessor;
import org.apache.hyracks.storage.am.lsm.common.dataflow.LSMIndexInsertUpdateDeleteOperatorNodePushable;

/**
 * This operator node is used for secondary indexes with upsert operations.
 * It works in the following way:
 * For each incoming tuple
 * -If old secondary keys == new secondary keys
 * --do nothing
 * -else
 * --If old secondary keys are null?
 * ---do nothing
 * --else
 * ---delete old secondary keys
 * --If new keys are null?
 * ---do nothing
 * --else
 * ---insert new keys
 */
public class LSMSecondaryUpsertOperatorNodePushable extends LSMIndexInsertUpdateDeleteOperatorNodePushable {

    private final PermutingFrameTupleReference prevValueTuple = new PermutingFrameTupleReference();
    private int numberOfFields;
    private AbstractIndexModificationOperationCallback abstractModCallback;

    public LSMSecondaryUpsertOperatorNodePushable(IHyracksTaskContext ctx, int partition,
            IIndexDataflowHelperFactory indexHelperFactory, IModificationOperationCallbackFactory modCallbackFactory,
            ITupleFilterFactory tupleFilterFactory, int[] fieldPermutation, RecordDescriptor inputRecDesc,
            int[] prevValuePermutation) throws HyracksDataException {
        super(ctx, partition, indexHelperFactory, fieldPermutation, inputRecDesc, IndexOperation.UPSERT,
                modCallbackFactory, tupleFilterFactory);
        this.prevValueTuple.setFieldPermutation(prevValuePermutation);
        this.numberOfFields = prevValuePermutation.length;
    }

    @Override
    public void open() throws HyracksDataException {
        super.open();
        abstractModCallback = (AbstractIndexModificationOperationCallback) modCallback;
    }

    public static boolean equals(byte[] a, int aOffset, int aLength, byte[] b, int bOffset, int bLength) {
        if (aLength != bLength) {
            return false;
        }
        for (int i = 0; i < aLength; i++) {
            if (a[aOffset + i] != b[bOffset + i]) {
                return false;
            }
        }
        return true;
    }

    public static boolean equalTuples(PermutingFrameTupleReference t1, PermutingFrameTupleReference t2, int numOfFields)
            throws HyracksDataException {
        byte[] t1Data = t1.getFieldData(0);
        byte[] t2Data = t2.getFieldData(0);
        for (int i = 0; i < numOfFields; i++) {
            if (!equals(t1Data, t1.getFieldStart(i), t1.getFieldLength(i), t2Data, t2.getFieldStart(i),
                    t2.getFieldLength(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
        accessor.reset(buffer);
        ILSMIndexAccessor lsmAccessor = (ILSMIndexAccessor) indexAccessor;
        int tupleCount = accessor.getTupleCount();
        for (int i = 0; i < tupleCount; i++) {
            try {
                // if both previous value and new value are null, then we skip
                tuple.reset(accessor, i);
                prevValueTuple.reset(accessor, i);
                boolean isNewValueMissing = isMissing(tuple, 0);
                boolean isOldValueMissing = isMissing(prevValueTuple, 0);
                if (isNewValueMissing && isOldValueMissing) {
                    // No op
                    continue;
                }
                // At least, one is not null
                // If they are equal, then we skip
                if (equalTuples(tuple, prevValueTuple, numberOfFields)) {
                    continue;
                }
                if (!isOldValueMissing) {
                    // We need to delete previous
                    abstractModCallback.setOp(Operation.DELETE);
                    lsmAccessor.forceDelete(prevValueTuple);
                }
                if (!isNewValueMissing) {
                    // we need to insert the new value
                    abstractModCallback.setOp(Operation.INSERT);
                    lsmAccessor.forceInsert(tuple);
                }
            } catch (Exception e) {
                throw HyracksDataException.create(e);
            }
        }
        // No partial flushing was necessary. Forward entire frame.
        writeBuffer.ensureFrameSize(buffer.capacity());
        FrameUtils.copyAndFlip(buffer, writeBuffer.getBuffer());
        FrameUtils.flushFrame(writeBuffer.getBuffer(), writer);
    }

    private boolean isMissing(PermutingFrameTupleReference tuple, int fieldIdx) {
        return TypeTagUtil.isType(tuple, fieldIdx, ATypeTag.SERIALIZED_MISSING_TYPE_TAG);
    }
}

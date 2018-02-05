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
package org.apache.asterix.runtime.aggregates.serializable.std;

import java.io.DataOutput;
import java.io.IOException;

import org.apache.asterix.formats.nontagged.SerializerDeserializerProvider;
import org.apache.asterix.om.base.ANull;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.BuiltinType;
import org.apache.asterix.runtime.exceptions.UnsupportedItemTypeException;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluatorFactory;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;

public class SerializableSqlSumAggregateFunction extends AbstractSerializableSumAggregateFunction {
    private final boolean isLocalAgg;

    public SerializableSqlSumAggregateFunction(IScalarEvaluatorFactory[] args, boolean isLocalAgg,
            IHyracksTaskContext context) throws HyracksDataException {
        super(args, context);
        this.isLocalAgg = isLocalAgg;
    }

    @Override
    protected void processNull(byte[] state, int start) {
    }

    @Override
    protected void processSystemNull() throws HyracksDataException {
        // For global aggregates simply ignore system null here,
        // but if all input value are system null, then we should return
        // null in finish().
        if (isLocalAgg) {
            throw new UnsupportedItemTypeException(BuiltinFunctions.SQL_SUM, ATypeTag.SERIALIZED_SYSTEM_NULL_TYPE_TAG);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void finishSystemNull(DataOutput out) throws IOException {
        // Empty stream. For local agg return system null. For global agg return null.
        if (isLocalAgg) {
            out.writeByte(ATypeTag.SYSTEM_NULL.serialize());
        } else {
            serde = SerializerDeserializerProvider.INSTANCE.getSerializerDeserializer(BuiltinType.ANULL);
            serde.serialize(ANull.NULL, out);
        }
    }

}

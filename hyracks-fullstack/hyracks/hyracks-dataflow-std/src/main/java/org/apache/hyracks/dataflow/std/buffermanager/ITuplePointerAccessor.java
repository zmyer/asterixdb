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

package org.apache.hyracks.dataflow.std.buffermanager;

import org.apache.hyracks.api.comm.IFrameTupleAccessor;
import org.apache.hyracks.dataflow.std.structures.TuplePointer;

/**
 * A cursor-like tuple level accessor to point to a tuple physical byte location inside the {@link ITupleBufferManager}
 * Some of the BufferManger (e.g. {@link VariableDeletableTupleMemoryManager}are using the different frame structure as
 * the common {@link org.apache.hyracks.dataflow.common.comm.io.FrameTupleAccessor} does.
 * In order to hide the complexity inside the buffer manager, clients can use this Accessor to navigate the internal record.
 */
public interface ITuplePointerAccessor extends IFrameTupleAccessor {
    void reset(TuplePointer tuplePointer);

    int getTupleStartOffset();

    int getTupleLength();

    int getAbsFieldStartOffset(int fieldId);

    int getFieldLength(int fieldId);

}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.app.resource;

import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IPhysicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.ExchangeOperator;

public class OperatorResourcesComputer {

    public static final int MIN_OPERATOR_CORES = 1;
    private static final long MAX_BUFFER_PER_CONNECTION = 1L;

    private final int numComputationPartitions;
    private final long groupByMemorySize;
    private final long joinMemorySize;
    private final long sortMemorySize;
    private final long frameSize;

    public OperatorResourcesComputer(int numComputationPartitions, int sortFrameLimit, int groupFrameLimit,
            int joinFrameLimit, long frameSize) {
        this.numComputationPartitions = numComputationPartitions;
        this.groupByMemorySize = groupFrameLimit * frameSize;
        this.joinMemorySize = joinFrameLimit * frameSize;
        this.sortMemorySize = sortFrameLimit * frameSize;
        this.frameSize = frameSize;
    }

    public int getOperatorRequiredCores(ILogicalOperator operator) {
        if (operator.getExecutionMode() == AbstractLogicalOperator.ExecutionMode.PARTITIONED
                || operator.getExecutionMode() == AbstractLogicalOperator.ExecutionMode.LOCAL) {
            return numComputationPartitions;
        }
        return MIN_OPERATOR_CORES;
    }

    public long getOperatorRequiredMemory(ILogicalOperator operator) {
        switch (operator.getOperatorTag()) {
            case AGGREGATE:
            case ASSIGN:
            case DATASOURCESCAN:
            case DISTINCT:
            case DISTRIBUTE_RESULT:
            case EMPTYTUPLESOURCE:
            case DELEGATE_OPERATOR:
            case EXTERNAL_LOOKUP:
            case LEFT_OUTER_UNNEST_MAP:
            case LIMIT:
            case MATERIALIZE:
            case NESTEDTUPLESOURCE:
            case PROJECT:
            case REPLICATE:
            case RUNNINGAGGREGATE:
            case SCRIPT:
            case SELECT:
            case SINK:
            case SPLIT:
            case SUBPLAN:
            case TOKENIZE:
            case UNIONALL:
            case UNNEST:
            case LEFT_OUTER_UNNEST:
            case UNNEST_MAP:
            case UPDATE:
            case WRITE:
            case WRITE_RESULT:
            case INDEX_INSERT_DELETE_UPSERT:
            case INSERT_DELETE_UPSERT:
            case INTERSECT:
                return getOperatorRequiredMemory(operator, frameSize);
            case EXCHANGE:
                return getExchangeRequiredMemory((ExchangeOperator) operator);
            case GROUP:
                return getOperatorRequiredMemory(operator, groupByMemorySize);
            case ORDER:
                return getOperatorRequiredMemory(operator, sortMemorySize);
            case INNERJOIN:
            case LEFTOUTERJOIN:
                return getOperatorRequiredMemory(operator, joinMemorySize);
            default:
                throw new IllegalStateException("Unrecognized operator: " + operator.getOperatorTag());
        }
    }

    private long getOperatorRequiredMemory(ILogicalOperator op, long memorySize) {
        if (op.getExecutionMode() == AbstractLogicalOperator.ExecutionMode.PARTITIONED
                || op.getExecutionMode() == AbstractLogicalOperator.ExecutionMode.LOCAL) {
            return memorySize * numComputationPartitions;
        }
        return memorySize;
    }

    private long getExchangeRequiredMemory(ExchangeOperator op) {
        final IPhysicalOperator physicalOperator = op.getPhysicalOperator();
        final PhysicalOperatorTag physicalOperatorTag = physicalOperator.getOperatorTag();
        if (physicalOperatorTag == PhysicalOperatorTag.ONE_TO_ONE_EXCHANGE
                || physicalOperatorTag == PhysicalOperatorTag.SORT_MERGE_EXCHANGE) {
            return getOperatorRequiredMemory(op, frameSize);
        }
        return 2L * MAX_BUFFER_PER_CONNECTION * numComputationPartitions * numComputationPartitions * frameSize;
    }
}
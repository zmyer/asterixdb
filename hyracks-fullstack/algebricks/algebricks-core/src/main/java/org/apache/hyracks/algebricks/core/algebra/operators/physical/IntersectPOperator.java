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

package org.apache.hyracks.algebricks.core.algebra.operators.physical;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.IHyracksJobBuilder;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.IOperatorSchema;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.IntersectOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.OrderOperator;
import org.apache.hyracks.algebricks.core.algebra.properties.ILocalStructuralProperty;
import org.apache.hyracks.algebricks.core.algebra.properties.IPartitioningProperty;
import org.apache.hyracks.algebricks.core.algebra.properties.IPartitioningRequirementsCoordinator;
import org.apache.hyracks.algebricks.core.algebra.properties.IPhysicalPropertiesVector;
import org.apache.hyracks.algebricks.core.algebra.properties.LocalOrderProperty;
import org.apache.hyracks.algebricks.core.algebra.properties.OrderColumn;
import org.apache.hyracks.algebricks.core.algebra.properties.PhysicalRequirements;
import org.apache.hyracks.algebricks.core.algebra.properties.StructuralPropertiesVector;
import org.apache.hyracks.algebricks.core.algebra.properties.UnorderedPartitionedProperty;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenContext;
import org.apache.hyracks.algebricks.core.jobgen.impl.JobGenHelper;
import org.apache.hyracks.algebricks.data.INormalizedKeyComputerFactoryProvider;
import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.INormalizedKeyComputerFactory;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksException;
import org.apache.hyracks.api.job.IOperatorDescriptorRegistry;
import org.apache.hyracks.dataflow.std.intersect.IntersectOperatorDescriptor;

public class IntersectPOperator extends AbstractPhysicalOperator {

    @Override
    public PhysicalOperatorTag getOperatorTag() {
        return PhysicalOperatorTag.INTERSECT;
    }

    @Override
    public PhysicalRequirements getRequiredPropertiesForChildren(ILogicalOperator iop,
            IPhysicalPropertiesVector reqdByParent, IOptimizationContext context) {
        IntersectOperator intersectOp = (IntersectOperator) iop;
        StructuralPropertiesVector[] pv = new StructuralPropertiesVector[intersectOp.getNumInput()];
        for (int i = 0; i < intersectOp.getNumInput(); i++) {
            List<ILocalStructuralProperty> localProps = new ArrayList<>();
            List<OrderColumn> orderColumns = new ArrayList<>();
            for (LogicalVariable column : intersectOp.getCompareVariables(i)) {
                orderColumns.add(new OrderColumn(column, OrderOperator.IOrder.OrderKind.ASC));
            }
            localProps.add(new LocalOrderProperty(orderColumns));
            IPartitioningProperty pp = null;
            if (intersectOp.getExecutionMode() == AbstractLogicalOperator.ExecutionMode.PARTITIONED) {
                Set<LogicalVariable> partitioningVariables = new HashSet<>(intersectOp.getCompareVariables(i));
                pp = new UnorderedPartitionedProperty(partitioningVariables, null);
            }
            pv[i] = new StructuralPropertiesVector(pp, localProps);
        }
        return new PhysicalRequirements(pv, IPartitioningRequirementsCoordinator.NO_COORDINATION);
    }

    @Override
    public void computeDeliveredProperties(ILogicalOperator iop, IOptimizationContext context)
            throws AlgebricksException {
        IntersectOperator op = (IntersectOperator) iop;
        IPartitioningProperty pp =
                op.getInputs().get(0).getValue().getDeliveredPhysicalProperties().getPartitioningProperty();

        HashMap<LogicalVariable, LogicalVariable> varMaps = new HashMap<>(op.getOutputVars().size());
        for (int i = 0; i < op.getOutputVars().size(); i++) {
            varMaps.put(op.getInputVariables(0).get(i), op.getOutputVars().get(i));
        }
        pp.substituteColumnVars(varMaps);

        List<ILocalStructuralProperty> propsLocal = new ArrayList<>();
        List<OrderColumn> orderColumns = new ArrayList<>();
        for (LogicalVariable var : op.getOutputVars()) {
            orderColumns.add(new OrderColumn(var, OrderOperator.IOrder.OrderKind.ASC));
        }
        propsLocal.add(new LocalOrderProperty(orderColumns));
        deliveredProperties = new StructuralPropertiesVector(pp, propsLocal);
    }

    @Override
    public void contributeRuntimeOperator(IHyracksJobBuilder builder, JobGenContext context, ILogicalOperator op,
            IOperatorSchema opSchema, IOperatorSchema[] inputSchemas, IOperatorSchema outerPlanSchema)
            throws AlgebricksException {
        // logical op should have checked all the mismatch issues.
        IntersectOperator logicalOp = (IntersectOperator) op;
        int nInput = logicalOp.getNumInput();
        int[][] compareFields = new int[nInput][];

        IBinaryComparatorFactory[] comparatorFactories = JobGenHelper.variablesToAscBinaryComparatorFactories(
                logicalOp.getCompareVariables(0), context.getTypeEnvironment(op), context);

        INormalizedKeyComputerFactoryProvider nkcfProvider = context.getNormalizedKeyComputerFactoryProvider();
        INormalizedKeyComputerFactory nkcf = null;

        if (nkcfProvider != null) {
            Object type = context.getTypeEnvironment(op).getVarType(logicalOp.getCompareVariables(0).get(0));
            if (type != null) {
                nkcf = nkcfProvider.getNormalizedKeyComputerFactory(type, true);
            }
        }

        for (int i = 0; i < logicalOp.getNumInput(); i++) {
            compareFields[i] = JobGenHelper.variablesToFieldIndexes(logicalOp.getCompareVariables(i), inputSchemas[i]);
        }

        int[][] extraFields = null;
        if (logicalOp.getExtraVariables() != null) {
            extraFields = new int[logicalOp.getNumInput()][];
            for (int i = 0; i < logicalOp.getNumInput(); i++) {
                extraFields[i] =
                        JobGenHelper.variablesToFieldIndexes(logicalOp.getExtraVariables().get(i), inputSchemas[i]);
            }
        }

        IOperatorDescriptorRegistry spec = builder.getJobSpec();
        RecordDescriptor recordDescriptor =
                JobGenHelper.mkRecordDescriptor(context.getTypeEnvironment(op), opSchema, context);

        IntersectOperatorDescriptor opDescriptor;
        try {
            opDescriptor = new IntersectOperatorDescriptor(spec, nInput, compareFields, extraFields, nkcf,
                    comparatorFactories, recordDescriptor);
        } catch (HyracksException e) {
            throw new AlgebricksException(e);
        }

        contributeOpDesc(builder, (AbstractLogicalOperator) op, opDescriptor);
        for (int i = 0; i < op.getInputs().size(); i++) {
            builder.contributeGraphEdge(op.getInputs().get(i).getValue(), 0, op, i);
        }
    }

    @Override
    public boolean isMicroOperator() {
        return false;
    }

    @Override
    public boolean expensiveThanMaterialization() {
        return false;
    }
}

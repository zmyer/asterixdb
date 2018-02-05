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
package org.apache.asterix.app.function;

import java.util.ArrayList;
import java.util.List;

import org.apache.asterix.metadata.declared.FunctionDataSource;
import org.apache.asterix.om.base.AString;
import org.apache.asterix.om.constants.AsterixConstantValue;
import org.apache.asterix.om.functions.IFunctionToDataSourceRewriter;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.optimizer.rules.UnnestToDataScanRule;
import org.apache.commons.lang3.mutable.Mutable;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import org.apache.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import org.apache.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalExpressionTag;
import org.apache.hyracks.algebricks.core.algebra.base.LogicalVariable;
import org.apache.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.ConstantExpression;
import org.apache.hyracks.algebricks.core.algebra.expressions.IAlgebricksConstantValue;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.DataSourceScanOperator;
import org.apache.hyracks.algebricks.core.algebra.operators.logical.UnnestOperator;

public abstract class FunctionRewriter implements IFunctionToDataSourceRewriter {

    private FunctionIdentifier functionId;

    public FunctionRewriter(FunctionIdentifier functionId) {
        this.functionId = functionId;
    }

    @Override
    public final boolean rewrite(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractFunctionCallExpression f = UnnestToDataScanRule.getFunctionCall(opRef);
        List<Mutable<ILogicalExpression>> args = f.getArguments();
        if (args.size() != functionId.getArity()) {
            throw new AlgebricksException("Function " + functionId.getNamespace() + "." + functionId.getName()
                    + " expects " + functionId.getArity() + " arguments");
        }
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).getValue().getExpressionTag() != LogicalExpressionTag.CONSTANT) {
                throw new AlgebricksException("Function " + functionId.getNamespace() + "." + functionId.getName()
                        + " expects constant arguments while arg[" + i + "] is of type "
                        + args.get(i).getValue().getExpressionTag());
            }
        }
        UnnestOperator unnest = (UnnestOperator) opRef.getValue();
        if (unnest.getPositionalVariable() != null) {
            throw new AlgebricksException("No positional variables are allowed over datasource functions");
        }
        FunctionDataSource datasource = toDatasource(context, f);
        List<LogicalVariable> variables = new ArrayList<>();
        variables.add(unnest.getVariable());
        DataSourceScanOperator scan = new DataSourceScanOperator(variables, datasource);
        List<Mutable<ILogicalOperator>> scanInpList = scan.getInputs();
        scanInpList.addAll(unnest.getInputs());
        opRef.setValue(scan);
        context.computeAndSetTypeEnvironmentForOperator(scan);
        return true;
    }

    protected abstract FunctionDataSource toDatasource(IOptimizationContext context, AbstractFunctionCallExpression f)
            throws AlgebricksException;

    protected String getString(List<Mutable<ILogicalExpression>> args, int i) throws AlgebricksException {
        ConstantExpression ce = (ConstantExpression) args.get(i).getValue();
        IAlgebricksConstantValue acv = ce.getValue();
        if (!(acv instanceof AsterixConstantValue)) {
            throw new AlgebricksException("Expected arg[" + i + "] to be of type String");
        }
        AsterixConstantValue acv2 = (AsterixConstantValue) acv;
        if (acv2.getObject().getType().getTypeTag() != ATypeTag.STRING) {
            throw new AlgebricksException("Expected arg[" + i + "] to be of type String");
        }
        return ((AString) acv2.getObject()).getStringValue();
    }

}

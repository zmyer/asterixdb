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
package org.apache.asterix.lang.sqlpp.rewrites.visitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.asterix.common.exceptions.CompilationException;
import org.apache.asterix.common.functions.FunctionSignature;
import org.apache.asterix.lang.common.base.Expression;
import org.apache.asterix.lang.common.base.Expression.Kind;
import org.apache.asterix.lang.common.base.ILangExpression;
import org.apache.asterix.lang.common.expression.CallExpr;
import org.apache.asterix.lang.common.expression.FieldAccessor;
import org.apache.asterix.lang.common.expression.LiteralExpr;
import org.apache.asterix.lang.common.expression.VariableExpr;
import org.apache.asterix.lang.common.literal.StringLiteral;
import org.apache.asterix.lang.common.rewrites.LangRewritingContext;
import org.apache.asterix.lang.common.struct.Identifier;
import org.apache.asterix.lang.common.struct.VarIdentifier;
import org.apache.asterix.lang.sqlpp.util.FunctionMapUtil;
import org.apache.asterix.lang.sqlpp.util.SqlppVariableUtil;
import org.apache.asterix.lang.sqlpp.visitor.CheckDatasetOnlyResolutionVisitor;
import org.apache.asterix.lang.sqlpp.visitor.base.AbstractSqlppExpressionScopingVisitor;
import org.apache.asterix.metadata.declared.MetadataProvider;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.commons.lang3.StringUtils;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;

public class VariableCheckAndRewriteVisitor extends AbstractSqlppExpressionScopingVisitor {

    private static final FunctionSignature FN_DATASET = new FunctionSignature(BuiltinFunctions.DATASET);

    protected final MetadataProvider metadataProvider;

    /**
     * @param context, manages ids of variables and guarantees uniqueness of variables.
     */
    public VariableCheckAndRewriteVisitor(LangRewritingContext context, MetadataProvider metadataProvider,
            List<VarIdentifier> externalVars) {
        super(context, externalVars);
        this.metadataProvider = metadataProvider;
    }

    @Override
    public Expression visit(FieldAccessor fa, ILangExpression parent) throws CompilationException {
        Expression leadingExpr = fa.getExpr();
        if (leadingExpr.getKind() != Kind.VARIABLE_EXPRESSION) {
            fa.setExpr(leadingExpr.accept(this, fa));
            return fa;
        } else {
            VariableExpr varExpr = (VariableExpr) leadingExpr;
            String lastIdentifier = fa.getIdent().getValue();
            Expression resolvedExpr = resolve(varExpr,
                    /* Resolves within the dataverse that has the same name as the variable name. */
                    SqlppVariableUtil.toUserDefinedVariableName(varExpr.getVar().getValue()).getValue(), lastIdentifier,
                    fa, parent);
            if (resolvedExpr.getKind() == Kind.CALL_EXPRESSION) {
                CallExpr callExpr = (CallExpr) resolvedExpr;
                if (callExpr.getFunctionSignature().equals(FN_DATASET)) {
                    // The field access is resolved to be a dataset access in the form of "dataverse.dataset".
                    return resolvedExpr;
                }
            }
            fa.setExpr(resolvedExpr);
            return fa;
        }
    }

    @Override
    public Expression visit(VariableExpr varExpr, ILangExpression parent) throws CompilationException {
        return resolve(varExpr, metadataProvider.getDefaultDataverseName(),
                SqlppVariableUtil.toUserDefinedVariableName(varExpr.getVar().getValue()).getValue(), varExpr, parent);
    }

    // Resolve a variable expression with dataverse name and dataset name.
    private Expression resolve(VariableExpr varExpr, String dataverseName, String datasetName,
            Expression originalExprWithUndefinedIdentifier, ILangExpression parent) throws CompilationException {

        String varName = varExpr.getVar().getValue();

        VarIdentifier var = lookupVariable(varName);
        if (var != null) {
            // Exists such an identifier
            varExpr.setIsNewVar(false);
            varExpr.setVar(var);
            return varExpr;
        }

        boolean resolveToDatasetOnly = resolveToDatasetOnly(originalExprWithUndefinedIdentifier, parent);
        if (resolveToDatasetOnly) {
            return resolveAsDataset(dataverseName, datasetName);
        }

        Set<VariableExpr> localVars = scopeChecker.getCurrentScope().getLiveVariables(scopeChecker.getPrecedingScope());
        switch (localVars.size()) {
            case 0:
                return resolveAsDataset(dataverseName, datasetName);
            case 1:
                return resolveAsFieldAccess(localVars.iterator().next(),
                        SqlppVariableUtil.toUserDefinedVariableName(varName).getValue());
            default:
                // More than one possibilities.
                throw new CompilationException("Cannot resolve ambiguous alias reference for undefined identifier "
                        + SqlppVariableUtil.toUserDefinedVariableName(varName).getValue() + " in " + localVars);
        }
    }

    private VarIdentifier lookupVariable(String varName) throws CompilationException {
        if (scopeChecker.isInForbiddenScopes(varName)) {
            throw new CompilationException(
                    "Inside limit clauses, it is disallowed to reference a variable having the same name"
                            + " as any variable bound in the same scope as the limit clause.");
        }
        Identifier ident = scopeChecker.lookupSymbol(varName);
        return ident != null ? (VarIdentifier) ident : null;
    }

    private Expression resolveAsDataset(String dataverseName, String datasetName) throws CompilationException {
        if (!datasetExists(dataverseName, datasetName)) {
            throwUnresolvableError(dataverseName, datasetName);
        }
        String fullyQualifiedName = dataverseName == null ? datasetName : dataverseName + "." + datasetName;
        List<Expression> argList = new ArrayList<>(1);
        argList.add(new LiteralExpr(new StringLiteral(fullyQualifiedName)));
        return new CallExpr(new FunctionSignature(BuiltinFunctions.DATASET), argList);
    }

    // Rewrites for an field access by name
    private Expression resolveAsFieldAccess(VariableExpr var, String fieldName) throws CompilationException {
        List<Expression> argList = new ArrayList<>(2);
        argList.add(var);
        argList.add(new LiteralExpr(new StringLiteral(fieldName)));
        return new CallExpr(new FunctionSignature(BuiltinFunctions.FIELD_ACCESS_BY_NAME), argList);
    }

    private void throwUnresolvableError(String dataverseName, String datasetName) throws CompilationException {
        String defaultDataverseName = metadataProvider.getDefaultDataverseName();
        if (dataverseName == null && defaultDataverseName == null) {
            throw new CompilationException("Cannot find dataset " + datasetName
                    + " because there is no dataverse declared, nor an alias with name " + datasetName + "!");
        }
        //If no available dataset nor in-scope variable to resolve to, we throw an error.
        throw new CompilationException("Cannot find dataset " + datasetName + " in dataverse "
                + (dataverseName == null ? defaultDataverseName : dataverseName) + " nor an alias with name "
                + datasetName + "!");
    }

    // For a From/Join/UNNEST/Quantifiers binding expression, we resolve the undefined identifier reference as
    // a dataset access only.
    private boolean resolveToDatasetOnly(Expression originalExpressionWithUndefinedIdentifier, ILangExpression parent)
            throws CompilationException {
        CheckDatasetOnlyResolutionVisitor visitor = new CheckDatasetOnlyResolutionVisitor();
        return parent.accept(visitor, originalExpressionWithUndefinedIdentifier);
    }

    private boolean datasetExists(String dataverseName, String datasetName) throws CompilationException {
        try {
            return metadataProvider.findDataset(dataverseName, datasetName) != null
                    || fullyQualifiedDatasetNameExists(datasetName);
        } catch (AlgebricksException e) {
            throw new CompilationException(e);
        }
    }

    private boolean fullyQualifiedDatasetNameExists(String name) throws AlgebricksException {
        if (name.indexOf('.') < 0) {
            return false;
        }
        String[] path = StringUtils.split(name, '.');
        return path.length == 2 && metadataProvider.findDataset(path[0], path[1]) != null;
    }

    @Override
    public Expression visit(CallExpr callExpr, ILangExpression arg) throws CompilationException {
        // skip variables inside SQL-92 aggregates (they will be resolved by SqlppGroupByAggregationSugarVisitor)
        if (FunctionMapUtil.isSql92AggregateFunction(callExpr.getFunctionSignature())) {
            return callExpr;
        }
        return super.visit(callExpr, arg);
    }
}

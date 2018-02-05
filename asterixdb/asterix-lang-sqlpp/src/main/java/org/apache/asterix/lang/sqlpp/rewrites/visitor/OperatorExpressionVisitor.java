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
import java.util.Collections;
import java.util.List;

import org.apache.asterix.common.exceptions.CompilationException;
import org.apache.asterix.common.functions.FunctionConstants;
import org.apache.asterix.common.functions.FunctionSignature;
import org.apache.asterix.lang.common.base.Expression;
import org.apache.asterix.lang.common.base.ILangExpression;
import org.apache.asterix.lang.common.expression.CallExpr;
import org.apache.asterix.lang.common.expression.OperatorExpr;
import org.apache.asterix.lang.common.expression.QuantifiedExpression;
import org.apache.asterix.lang.common.expression.QuantifiedExpression.Quantifier;
import org.apache.asterix.lang.common.expression.VariableExpr;
import org.apache.asterix.lang.common.rewrites.LangRewritingContext;
import org.apache.asterix.lang.common.struct.OperatorType;
import org.apache.asterix.lang.common.struct.QuantifiedPair;
import org.apache.asterix.lang.sqlpp.util.FunctionMapUtil;
import org.apache.asterix.lang.sqlpp.visitor.base.AbstractSqlppExpressionScopingVisitor;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.hyracks.algebricks.core.algebra.expressions.IExpressionAnnotation;

public class OperatorExpressionVisitor extends AbstractSqlppExpressionScopingVisitor {

    public OperatorExpressionVisitor(LangRewritingContext context) {
        super(context);
    }

    @Override
    public Expression visit(OperatorExpr operatorExpr, ILangExpression arg) throws CompilationException {
        List<Expression> newExprList = new ArrayList<>();
        for (Expression expr : operatorExpr.getExprList()) {
            newExprList.add(expr.accept(this, operatorExpr));
        }
        operatorExpr.setExprList(newExprList);
        OperatorType opType = operatorExpr.getOpList().get(0);
        switch (opType) {
            // There can only be one LIKE/NOT_LIKE/IN/NOT_IN in an operator expression (according to the grammar).
            case LIKE:
            case NOT_LIKE:
                return processLikeOperator(operatorExpr, opType);
            case IN:
            case NOT_IN:
                return processInOperator(operatorExpr, opType);
            case CONCAT:
                // There can be multiple "||"s in one operator expression (according to the grammar).
                return processConcatOperator(operatorExpr);
            case BETWEEN:
            case NOT_BETWEEN:
                return processBetweenOperator(operatorExpr, opType);
            default:
                break;
        }
        return operatorExpr;
    }

    private Expression processLikeOperator(OperatorExpr operatorExpr, OperatorType opType) {
        Expression likeExpr =
                new CallExpr(new FunctionSignature(BuiltinFunctions.STRING_LIKE), operatorExpr.getExprList());
        switch (opType) {
            case LIKE:
                return likeExpr;
            case NOT_LIKE:
                return new CallExpr(new FunctionSignature(BuiltinFunctions.NOT),
                        new ArrayList<>(Collections.singletonList(likeExpr)));
            default:
                throw new IllegalArgumentException(String.valueOf(opType));
        }
    }

    private Expression processInOperator(OperatorExpr operatorExpr, OperatorType opType) throws CompilationException {
        VariableExpr bindingVar = new VariableExpr(context.newVariable());
        Expression itemExpr = operatorExpr.getExprList().get(0);
        Expression collectionExpr = operatorExpr.getExprList().get(1);
        OperatorExpr comparison = new OperatorExpr();
        comparison.addOperand(itemExpr);
        comparison.addOperand(bindingVar);
        comparison.setCurrentop(true);
        if (opType == OperatorType.IN) {
            comparison.addOperator(OperatorType.EQ);
            return new QuantifiedExpression(Quantifier.SOME,
                    new ArrayList<>(Collections.singletonList(new QuantifiedPair(bindingVar, collectionExpr))),
                    comparison);
        } else {
            comparison.addOperator(OperatorType.NEQ);
            return new QuantifiedExpression(Quantifier.EVERY,
                    new ArrayList<>(Collections.singletonList(new QuantifiedPair(bindingVar, collectionExpr))),
                    comparison);
        }
    }

    private Expression processConcatOperator(OperatorExpr operatorExpr) {
        // All operators have to be "||"s (according to the grammar).
        return new CallExpr(new FunctionSignature(FunctionConstants.ASTERIX_NS, FunctionMapUtil.CONCAT, 1),
                operatorExpr.getExprList());
    }

    private Expression processBetweenOperator(OperatorExpr operatorExpr, OperatorType opType)
            throws CompilationException {
        // The grammar guarantees that the BETWEEN operator gets exactly three expressions.
        Expression target = operatorExpr.getExprList().get(0);
        Expression left = operatorExpr.getExprList().get(1);
        Expression right = operatorExpr.getExprList().get(2);

        // Creates the expression left <= target.
        Expression leftComparison = createLessThanExpression(left, target, operatorExpr.getHints());
        // Creates the expression target <= right.
        Expression rightComparison = createLessThanExpression(target, right, operatorExpr.getHints());
        OperatorExpr andExpr = new OperatorExpr();
        andExpr.addOperand(leftComparison);
        andExpr.addOperand(rightComparison);
        andExpr.addOperator(OperatorType.AND);
        return opType == OperatorType.BETWEEN ? andExpr
                : new CallExpr(new FunctionSignature(BuiltinFunctions.NOT),
                        new ArrayList<>(Collections.singletonList(andExpr)));
    }

    private Expression createLessThanExpression(Expression lhs, Expression rhs, List<IExpressionAnnotation> hints)
            throws CompilationException {
        OperatorExpr comparison = new OperatorExpr();
        comparison.addOperand(lhs);
        comparison.addOperand(rhs);
        comparison.addOperator(OperatorType.LE);
        if (hints != null) {
            for (IExpressionAnnotation hint : hints) {
                comparison.addHint(hint);
            }
        }
        return comparison;
    }

}

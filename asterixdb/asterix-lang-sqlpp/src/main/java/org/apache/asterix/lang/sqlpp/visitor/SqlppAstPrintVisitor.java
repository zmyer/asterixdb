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
package org.apache.asterix.lang.sqlpp.visitor;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

import org.apache.asterix.common.exceptions.CompilationException;
import org.apache.asterix.common.functions.FunctionSignature;
import org.apache.asterix.lang.common.base.Expression;
import org.apache.asterix.lang.common.clause.GroupbyClause;
import org.apache.asterix.lang.common.clause.LetClause;
import org.apache.asterix.lang.common.expression.CallExpr;
import org.apache.asterix.lang.common.expression.GbyVariableExpressionPair;
import org.apache.asterix.lang.common.expression.VariableExpr;
import org.apache.asterix.lang.common.struct.Identifier;
import org.apache.asterix.lang.common.visitor.QueryPrintVisitor;
import org.apache.asterix.lang.sqlpp.clause.AbstractBinaryCorrelateClause;
import org.apache.asterix.lang.sqlpp.clause.FromClause;
import org.apache.asterix.lang.sqlpp.clause.FromTerm;
import org.apache.asterix.lang.sqlpp.clause.HavingClause;
import org.apache.asterix.lang.sqlpp.clause.JoinClause;
import org.apache.asterix.lang.sqlpp.clause.NestClause;
import org.apache.asterix.lang.sqlpp.clause.Projection;
import org.apache.asterix.lang.sqlpp.clause.SelectBlock;
import org.apache.asterix.lang.sqlpp.clause.SelectClause;
import org.apache.asterix.lang.sqlpp.clause.SelectElement;
import org.apache.asterix.lang.sqlpp.clause.SelectRegular;
import org.apache.asterix.lang.sqlpp.clause.SelectSetOperation;
import org.apache.asterix.lang.sqlpp.clause.UnnestClause;
import org.apache.asterix.lang.sqlpp.expression.CaseExpression;
import org.apache.asterix.lang.sqlpp.expression.SelectExpression;
import org.apache.asterix.lang.sqlpp.struct.SetOperationRight;
import org.apache.asterix.lang.sqlpp.util.FunctionMapUtil;
import org.apache.asterix.lang.sqlpp.visitor.base.ISqlppVisitor;
import org.apache.asterix.om.functions.BuiltinFunctions;
import org.apache.hyracks.algebricks.common.utils.Pair;

public class SqlppAstPrintVisitor extends QueryPrintVisitor implements ISqlppVisitor<Void, Integer> {

    public SqlppAstPrintVisitor(PrintWriter out) {
        super(out);
    }

    @Override
    public Void visit(FromClause fromClause, Integer step) throws CompilationException {
        out.print(skip(step) + "FROM [");
        int index = 0;
        for (FromTerm fromTerm : fromClause.getFromTerms()) {
            if (index > 0) {
                out.println(",");
            }
            fromTerm.accept(this, step + 1);
            ++index;
        }
        out.println(skip(step) + "]");
        return null;
    }

    @Override
    public Void visit(FromTerm fromTerm, Integer step) throws CompilationException {
        fromTerm.getLeftExpression().accept(this, step);
        out.print(skip(step) + "AS ");
        fromTerm.getLeftVariable().accept(this, 0);
        if (fromTerm.hasPositionalVariable()) {
            out.println(" AT ");
            fromTerm.getPositionalVariable().accept(this, 0);
        }
        if (fromTerm.hasCorrelateClauses()) {
            for (AbstractBinaryCorrelateClause correlateClause : fromTerm.getCorrelateClauses()) {
                correlateClause.accept(this, step);
            }
        }
        return null;
    }

    @Override
    public Void visit(JoinClause joinClause, Integer step) throws CompilationException {
        out.println(skip(step) + joinClause.getJoinType() + " JOIN");
        joinClause.getRightExpression().accept(this, step + 1);
        out.print(skip(step + 1) + "AS ");
        joinClause.getRightVariable().accept(this, 0);
        if (joinClause.hasPositionalVariable()) {
            out.print(" AT ");
            joinClause.getPositionalVariable().accept(this, 0);
        }
        out.println(skip(step + 1) + "ON");
        joinClause.getConditionExpression().accept(this, step + 1);
        return null;
    }

    @Override
    public Void visit(NestClause nestClause, Integer step) throws CompilationException {
        out.println(skip(step) + nestClause.getJoinType() + " NEST");
        nestClause.getRightExpression().accept(this, step + 1);
        out.print(skip(step + 1) + "AS ");
        nestClause.getRightVariable().accept(this, 0);
        if (nestClause.hasPositionalVariable()) {
            out.print(" AT ");
            nestClause.getPositionalVariable().accept(this, 0);
        }
        out.println(skip(step + 1) + "ON");
        nestClause.getConditionExpression().accept(this, step + 1);
        return null;
    }

    @Override
    public Void visit(Projection projection, Integer step) throws CompilationException {
        if (projection.star()) {
            out.println(skip(step) + "*");
        } else {
            projection.getExpression().accept(this, step);
            out.println(skip(step) + projection.getName());
        }
        return null;
    }

    @Override
    public Void visit(SelectBlock selectBlock, Integer step) throws CompilationException {
        selectBlock.getSelectClause().accept(this, step);
        if (selectBlock.hasFromClause()) {
            selectBlock.getFromClause().accept(this, step);
        }
        if (selectBlock.hasLetClauses()) {
            for (LetClause letClause : selectBlock.getLetList()) {
                letClause.accept(this, step);
            }
        }
        if (selectBlock.hasWhereClause()) {
            selectBlock.getWhereClause().accept(this, step);
        }
        if (selectBlock.hasGroupbyClause()) {
            selectBlock.getGroupbyClause().accept(this, step);
            if (selectBlock.hasLetClausesAfterGroupby()) {
                for (LetClause letClause : selectBlock.getLetListAfterGroupby()) {
                    letClause.accept(this, step);
                }
            }
        }
        if (selectBlock.hasHavingClause()) {
            selectBlock.getHavingClause().accept(this, step);
        }
        return null;
    }

    @Override
    public Void visit(SelectClause selectClause, Integer step) throws CompilationException {
        if (selectClause.selectRegular()) {
            selectClause.getSelectRegular().accept(this, step);
        }
        if (selectClause.selectElement()) {
            selectClause.getSelectElement().accept(this, step);
        }
        return null;
    }

    @Override
    public Void visit(SelectElement selectElement, Integer step) throws CompilationException {
        out.println(skip(step) + "SELECT ELEMENT [");
        selectElement.getExpression().accept(this, step);
        out.println(skip(step) + "]");
        return null;
    }

    @Override
    public Void visit(SelectRegular selectRegular, Integer step) throws CompilationException {
        out.println(skip(step) + "SELECT [");
        for (Projection projection : selectRegular.getProjections()) {
            projection.accept(this, step);
        }
        out.println(skip(step) + "]");
        return null;
    }

    @Override
    public Void visit(SelectSetOperation selectSetOperation, Integer step) throws CompilationException {
        selectSetOperation.getLeftInput().accept(this, step);
        if (selectSetOperation.hasRightInputs()) {
            for (SetOperationRight right : selectSetOperation.getRightInputs()) {
                String all = right.isSetSemantics() ? " ALL " : "";
                out.println(skip(step) + right.getSetOpType() + all);
                right.getSetOperationRightInput().accept(this, step + 1);
            }
        }
        return null;
    }

    @Override
    public Void visit(SelectExpression selectStatement, Integer step) throws CompilationException {
        if (selectStatement.isSubquery()) {
            out.println(skip(step) + "(");
        }
        int selectStep = selectStatement.isSubquery() ? step + 1 : step;
        if (selectStatement.hasLetClauses()) {
            for (LetClause letClause : selectStatement.getLetList()) {
                letClause.accept(this, selectStep);
            }
        }
        selectStatement.getSelectSetOperation().accept(this, selectStep);
        if (selectStatement.hasOrderby()) {
            selectStatement.getOrderbyClause().accept(this, selectStep);
        }
        if (selectStatement.hasLimit()) {
            selectStatement.getLimitClause().accept(this, selectStep);
        }
        if (selectStatement.isSubquery()) {
            out.println(skip(step) + ")");
        }
        return null;
    }

    @Override
    public Void visit(UnnestClause unnestClause, Integer step) throws CompilationException {
        out.println(skip(step) + unnestClause.getJoinType() + " UNNEST");
        unnestClause.getRightExpression().accept(this, step + 1);
        out.print(skip(step + 1) + " AS ");
        unnestClause.getRightVariable().accept(this, 0);
        if (unnestClause.hasPositionalVariable()) {
            out.println(" AT");
            unnestClause.getPositionalVariable().accept(this, 0);
        }
        return null;
    }

    @Override
    public Void visit(HavingClause havingClause, Integer step) throws CompilationException {
        out.println(skip(step) + " HAVING");
        havingClause.getFilterExpression().accept(this, step + 1);
        return null;
    }

    @Override
    public Void visit(CallExpr pf, Integer step) throws CompilationException {
        FunctionSignature functionSignature = pf.getFunctionSignature();
        FunctionSignature normalizedFunctionSignature =
                FunctionMapUtil.normalizeBuiltinFunctionSignature(functionSignature, false);
        if (BuiltinFunctions.isBuiltinCompilerFunction(normalizedFunctionSignature, true)) {
            functionSignature = normalizedFunctionSignature;
        }
        out.println(skip(step) + "FunctionCall " + functionSignature.toString() + "[");
        for (Expression expr : pf.getExprList()) {
            expr.accept(this, step + 1);
        }
        out.println(skip(step) + "]");
        return null;
    }

    @Override
    public Void visit(GroupbyClause gc, Integer step) throws CompilationException {
        if (gc.isGroupAll()) {
            out.println(skip(step) + "Group All");
        } else {
            out.println(skip(step) + "Groupby");
            for (GbyVariableExpressionPair pair : gc.getGbyPairList()) {
                if (pair.getVar() != null) {
                    pair.getVar().accept(this, step + 1);
                    out.println(skip(step + 1) + ":=");
                }
                pair.getExpr().accept(this, step + 1);
            }
        }
        if (gc.hasDecorList()) {
            out.println(skip(step + 1) + "DECOR");
            for (GbyVariableExpressionPair pair : gc.getDecorPairList()) {
                if (pair.getVar() != null) {
                    pair.getVar().accept(this, step + 1);
                    out.println(skip(step + 1) + ":=");
                }
                pair.getExpr().accept(this, step + 1);
            }
        }
        if (gc.hasGroupVar()) {
            out.print(skip(step + 1) + "GROUP AS ");
            gc.getGroupVar().accept(this, 0);
            if (gc.hasGroupFieldList()) {
                out.println(skip(step + 1) + "(");
                for (Pair<Expression, Identifier> field : gc.getGroupFieldList()) {
                    out.print(skip(step + 2) + field.second + ":=");
                    field.first.accept(this, 0);
                }
                out.println(skip(step + 1) + ")");
            }
        }
        if (gc.hasWithMap()) {
            out.println(skip(step + 1) + "WITH");
            for (Map.Entry<Expression, VariableExpr> pair : gc.getWithVarMap().entrySet()) {
                pair.getValue().accept(this, step + 1);
                out.println(skip(step + 1) + ":=");
                pair.getKey().accept(this, step + 1);
            }
        }
        out.println();
        return null;
    }

    @Override
    public Void visit(CaseExpression caseExpr, Integer step) throws CompilationException {
        out.print(skip(step) + "CASE");
        caseExpr.getConditionExpr().accept(this, step + 2);
        out.println();
        List<Expression> whenExprs = caseExpr.getWhenExprs();
        List<Expression> thenExprs = caseExpr.getThenExprs();
        for (int index = 0; index < whenExprs.size(); ++index) {
            out.print(skip(step) + "WHEN ");
            whenExprs.get(index).accept(this, step + 2);
            out.print(skip(step) + "THEN ");
            thenExprs.get(index).accept(this, step + 2);
            out.println();
        }
        out.print(skip(step) + "ELSE ");
        caseExpr.getElseExpr().accept(this, step + 2);
        out.println();
        out.println(skip(step) + "END");
        return null;
    }

}

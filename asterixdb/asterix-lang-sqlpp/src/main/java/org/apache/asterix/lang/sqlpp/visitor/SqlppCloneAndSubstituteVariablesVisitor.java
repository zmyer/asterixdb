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

import java.util.ArrayList;
import java.util.List;

import org.apache.asterix.common.exceptions.CompilationException;
import org.apache.asterix.lang.common.base.Clause.ClauseType;
import org.apache.asterix.lang.common.base.Expression;
import org.apache.asterix.lang.common.base.ILangExpression;
import org.apache.asterix.lang.common.clause.GroupbyClause;
import org.apache.asterix.lang.common.clause.LetClause;
import org.apache.asterix.lang.common.clause.LimitClause;
import org.apache.asterix.lang.common.clause.OrderbyClause;
import org.apache.asterix.lang.common.clause.WhereClause;
import org.apache.asterix.lang.common.expression.VariableExpr;
import org.apache.asterix.lang.common.rewrites.LangRewritingContext;
import org.apache.asterix.lang.common.rewrites.VariableSubstitutionEnvironment;
import org.apache.asterix.lang.common.util.VariableCloneAndSubstitutionUtil;
import org.apache.asterix.lang.common.visitor.CloneAndSubstituteVariablesVisitor;
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
import org.apache.asterix.lang.sqlpp.struct.SetOperationInput;
import org.apache.asterix.lang.sqlpp.struct.SetOperationRight;
import org.apache.asterix.lang.sqlpp.visitor.base.ISqlppVisitor;
import org.apache.hyracks.algebricks.common.utils.Pair;

public class SqlppCloneAndSubstituteVariablesVisitor extends CloneAndSubstituteVariablesVisitor implements
        ISqlppVisitor<Pair<ILangExpression, VariableSubstitutionEnvironment>, VariableSubstitutionEnvironment> {

    private LangRewritingContext context;

    public SqlppCloneAndSubstituteVariablesVisitor(LangRewritingContext context) {
        super(context);
        this.context = context;
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(FromClause fromClause,
            VariableSubstitutionEnvironment env) throws CompilationException {
        VariableSubstitutionEnvironment currentEnv = new VariableSubstitutionEnvironment(env);
        List<FromTerm> newFromTerms = new ArrayList<>();
        for (FromTerm fromTerm : fromClause.getFromTerms()) {
            Pair<ILangExpression, VariableSubstitutionEnvironment> p = fromTerm.accept(this, currentEnv);
            newFromTerms.add((FromTerm) p.first);
            // A right from term could be correlated from a left from term,
            // therefore we propagate the substitution environment.
            currentEnv = p.second;
        }
        return new Pair<>(new FromClause(newFromTerms), currentEnv);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(FromTerm fromTerm,
            VariableSubstitutionEnvironment env) throws CompilationException {
        VariableExpr leftVar = fromTerm.getLeftVariable();
        VariableExpr newLeftVar = generateNewVariable(context, leftVar);
        VariableExpr newLeftPosVar = fromTerm.hasPositionalVariable()
                ? generateNewVariable(context, fromTerm.getPositionalVariable()) : null;
        Expression newLeftExpr = (Expression) visitUnnesBindingExpression(fromTerm.getLeftExpression(), env).first;
        List<AbstractBinaryCorrelateClause> newCorrelateClauses = new ArrayList<>();

        VariableSubstitutionEnvironment currentEnv = new VariableSubstitutionEnvironment(env);
        currentEnv.removeSubstitution(newLeftVar);
        if (newLeftPosVar != null) {
            currentEnv.removeSubstitution(newLeftPosVar);
        }

        for (AbstractBinaryCorrelateClause correlateClause : fromTerm.getCorrelateClauses()) {
            if (correlateClause.getClauseType() == ClauseType.UNNEST_CLAUSE) {
                // The right-hand-side of unnest could be correlated with the left side,
                // therefore we propagate the substitution environment of the left-side.
                Pair<ILangExpression, VariableSubstitutionEnvironment> p = correlateClause.accept(this, currentEnv);
                currentEnv = p.second;
                newCorrelateClauses.add((AbstractBinaryCorrelateClause) p.first);
            } else {
                // The right-hand-side of join and nest could not be correlated with the left side,
                // therefore we propagate the original substitution environment.
                newCorrelateClauses.add((AbstractBinaryCorrelateClause) correlateClause.accept(this, env).first);
                // Join binding variables should be removed for further traversal.
                currentEnv.removeSubstitution(correlateClause.getRightVariable());
                if (correlateClause.hasPositionalVariable()) {
                    currentEnv.removeSubstitution(correlateClause.getPositionalVariable());
                }
            }
        }
        return new Pair<>(new FromTerm(newLeftExpr, newLeftVar, newLeftPosVar, newCorrelateClauses), currentEnv);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(JoinClause joinClause,
            VariableSubstitutionEnvironment env) throws CompilationException {
        VariableExpr rightVar = joinClause.getRightVariable();
        VariableExpr newRightVar = generateNewVariable(context, rightVar);
        VariableExpr newRightPosVar = joinClause.hasPositionalVariable()
                ? generateNewVariable(context, joinClause.getPositionalVariable()) : null;

        // Visits the right expression.
        Expression newRightExpr = (Expression) visitUnnesBindingExpression(joinClause.getRightExpression(), env).first;

        // Visits the condition.
        VariableSubstitutionEnvironment currentEnv = new VariableSubstitutionEnvironment(env);
        currentEnv.removeSubstitution(newRightVar);
        if (newRightPosVar != null) {
            currentEnv.removeSubstitution(newRightPosVar);
        }
        // The condition can refer to the newRightVar and newRightPosVar.
        Expression conditionExpr = (Expression) joinClause.getConditionExpression().accept(this, currentEnv).first;

        JoinClause newJoinClause =
                new JoinClause(joinClause.getJoinType(), newRightExpr, newRightVar, newRightPosVar, conditionExpr);
        return new Pair<>(newJoinClause, currentEnv);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(NestClause nestClause,
            VariableSubstitutionEnvironment env) throws CompilationException {
        VariableExpr rightVar = nestClause.getRightVariable();
        VariableExpr newRightVar = generateNewVariable(context, rightVar);
        VariableExpr newRightPosVar = nestClause.hasPositionalVariable()
                ? generateNewVariable(context, nestClause.getPositionalVariable()) : null;

        // Visits the right expression.
        Expression rightExpr = (Expression) nestClause.getRightExpression().accept(this, env).first;

        // Visits the condition.
        VariableSubstitutionEnvironment currentEnv = new VariableSubstitutionEnvironment(env);
        currentEnv.removeSubstitution(newRightVar);
        if (newRightPosVar != null) {
            currentEnv.removeSubstitution(newRightPosVar);
        }
        // The condition can refer to the newRightVar and newRightPosVar.
        Expression conditionExpr = (Expression) nestClause.getConditionExpression().accept(this, currentEnv).first;

        NestClause newJoinClause =
                new NestClause(nestClause.getJoinType(), rightExpr, newRightVar, newRightPosVar, conditionExpr);
        return new Pair<>(newJoinClause, currentEnv);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(UnnestClause unnestClause,
            VariableSubstitutionEnvironment env) throws CompilationException {
        VariableExpr rightVar = unnestClause.getRightVariable();
        VariableExpr newRightVar = generateNewVariable(context, rightVar);
        VariableExpr newRightPosVar = unnestClause.hasPositionalVariable()
                ? generateNewVariable(context, unnestClause.getPositionalVariable()) : null;

        // Visits the right expression.
        Expression rightExpr = (Expression) visitUnnesBindingExpression(unnestClause.getRightExpression(), env).first;

        // Visits the condition.
        VariableSubstitutionEnvironment currentEnv = new VariableSubstitutionEnvironment(env);
        currentEnv.removeSubstitution(newRightVar);
        if (newRightPosVar != null) {
            currentEnv.removeSubstitution(newRightPosVar);
        }
        // The condition can refer to the newRightVar and newRightPosVar.
        UnnestClause newJoinClause =
                new UnnestClause(unnestClause.getJoinType(), rightExpr, newRightVar, newRightPosVar);
        return new Pair<>(newJoinClause, currentEnv);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(Projection projection,
            VariableSubstitutionEnvironment env) throws CompilationException {
        if (projection.star()) {
            return new Pair<>(projection, env);
        }
        Projection newProjection = new Projection((Expression) projection.getExpression().accept(this, env).first,
                projection.getName(), projection.star(), projection.exprStar());
        return new Pair<>(newProjection, env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(SelectBlock selectBlock,
            VariableSubstitutionEnvironment env) throws CompilationException {
        Pair<ILangExpression, VariableSubstitutionEnvironment> newFrom = null;
        Pair<ILangExpression, VariableSubstitutionEnvironment> newLet;
        Pair<ILangExpression, VariableSubstitutionEnvironment> newWhere = null;
        Pair<ILangExpression, VariableSubstitutionEnvironment> newGroupby = null;
        Pair<ILangExpression, VariableSubstitutionEnvironment> newHaving = null;
        Pair<ILangExpression, VariableSubstitutionEnvironment> newSelect;
        List<LetClause> newLetClauses = new ArrayList<>();
        List<LetClause> newLetClausesAfterGby = new ArrayList<>();
        VariableSubstitutionEnvironment currentEnv = new VariableSubstitutionEnvironment(env);

        if (selectBlock.hasFromClause()) {
            newFrom = selectBlock.getFromClause().accept(this, currentEnv);
            currentEnv = newFrom.second;
        }

        if (selectBlock.hasLetClauses()) {
            for (LetClause letClause : selectBlock.getLetList()) {
                newLet = letClause.accept(this, currentEnv);
                currentEnv = newLet.second;
                newLetClauses.add((LetClause) newLet.first);
            }
        }

        if (selectBlock.hasWhereClause()) {
            newWhere = selectBlock.getWhereClause().accept(this, currentEnv);
            currentEnv = newWhere.second;
        }

        if (selectBlock.hasGroupbyClause()) {
            newGroupby = selectBlock.getGroupbyClause().accept(this, currentEnv);
            currentEnv = newGroupby.second;
            if (selectBlock.hasLetClausesAfterGroupby()) {
                for (LetClause letClauseAfterGby : selectBlock.getLetListAfterGroupby()) {
                    newLet = letClauseAfterGby.accept(this, currentEnv);
                    currentEnv = newLet.second;
                    newLetClausesAfterGby.add(letClauseAfterGby);
                }
            }
        }

        if (selectBlock.hasHavingClause()) {
            newHaving = selectBlock.getHavingClause().accept(this, currentEnv);
            currentEnv = newHaving.second;
        }

        newSelect = selectBlock.getSelectClause().accept(this, currentEnv);
        currentEnv = newSelect.second;
        FromClause fromClause = newFrom == null ? null : (FromClause) newFrom.first;
        WhereClause whereClause = newWhere == null ? null : (WhereClause) newWhere.first;
        GroupbyClause groupbyClause = newGroupby == null ? null : (GroupbyClause) newGroupby.first;
        HavingClause havingClause = newHaving == null ? null : (HavingClause) newHaving.first;
        return new Pair<>(new SelectBlock((SelectClause) newSelect.first, fromClause, newLetClauses, whereClause,
                groupbyClause, newLetClausesAfterGby, havingClause), currentEnv);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(SelectClause selectClause,
            VariableSubstitutionEnvironment env) throws CompilationException {
        boolean distinct = selectClause.distinct();
        if (selectClause.selectElement()) {
            Pair<ILangExpression, VariableSubstitutionEnvironment> newSelectElement =
                    selectClause.getSelectElement().accept(this, env);
            return new Pair<>(new SelectClause((SelectElement) newSelectElement.first, null, distinct),
                    newSelectElement.second);
        } else {
            Pair<ILangExpression, VariableSubstitutionEnvironment> newSelectRegular =
                    selectClause.getSelectRegular().accept(this, env);
            return new Pair<>(new SelectClause(null, (SelectRegular) newSelectRegular.first, distinct),
                    newSelectRegular.second);
        }
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(SelectElement selectElement,
            VariableSubstitutionEnvironment env) throws CompilationException {
        Pair<ILangExpression, VariableSubstitutionEnvironment> newExpr =
                selectElement.getExpression().accept(this, env);
        return new Pair<>(new SelectElement((Expression) newExpr.first), newExpr.second);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(SelectRegular selectRegular,
            VariableSubstitutionEnvironment env) throws CompilationException {
        List<Projection> newProjections = new ArrayList<>();
        for (Projection projection : selectRegular.getProjections()) {
            newProjections.add((Projection) projection.accept(this, env).first);
        }
        return new Pair<>(new SelectRegular(newProjections), env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(SelectSetOperation selectSetOperation,
            VariableSubstitutionEnvironment env) throws CompilationException {
        SetOperationInput leftInput = selectSetOperation.getLeftInput();
        SetOperationInput newLeftInput;

        Pair<ILangExpression, VariableSubstitutionEnvironment> leftResult;
        // Sets the left input.
        if (leftInput.selectBlock()) {
            leftResult = leftInput.getSelectBlock().accept(this, env);
            newLeftInput = new SetOperationInput((SelectBlock) leftResult.first, null);
        } else {
            leftResult = leftInput.getSubquery().accept(this, env);
            newLeftInput = new SetOperationInput(null, (SelectExpression) leftResult.first);
        }

        // Sets the right input
        List<SetOperationRight> newRightInputs = new ArrayList<>();
        if (selectSetOperation.hasRightInputs()) {
            for (SetOperationRight right : selectSetOperation.getRightInputs()) {
                SetOperationInput newRightInput;
                SetOperationInput rightInput = right.getSetOperationRightInput();
                if (rightInput.selectBlock()) {
                    Pair<ILangExpression, VariableSubstitutionEnvironment> rightResult =
                            rightInput.getSelectBlock().accept(this, env);
                    newRightInput = new SetOperationInput((SelectBlock) rightResult.first, null);
                } else {
                    Pair<ILangExpression, VariableSubstitutionEnvironment> rightResult =
                            rightInput.getSubquery().accept(this, env);
                    newRightInput = new SetOperationInput(null, (SelectExpression) rightResult.first);
                }
                newRightInputs.add(new SetOperationRight(right.getSetOpType(), right.isSetSemantics(), newRightInput));
            }
        }
        SelectSetOperation newSelectSetOperation = new SelectSetOperation(newLeftInput, newRightInputs);
        return new Pair<>(newSelectSetOperation, selectSetOperation.hasRightInputs() ? env : leftResult.second);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(SelectExpression selectExpression,
            VariableSubstitutionEnvironment env) throws CompilationException {
        boolean subquery = selectExpression.isSubquery();
        List<LetClause> newLetList = new ArrayList<>();
        SelectSetOperation newSelectSetOperation;
        OrderbyClause newOrderbyClause = null;
        LimitClause newLimitClause = null;

        VariableSubstitutionEnvironment currentEnv = env;
        Pair<ILangExpression, VariableSubstitutionEnvironment> p;
        if (selectExpression.hasLetClauses()) {
            for (LetClause letClause : selectExpression.getLetList()) {
                p = letClause.accept(this, currentEnv);
                newLetList.add((LetClause) p.first);
                currentEnv = p.second;
            }
        }

        p = selectExpression.getSelectSetOperation().accept(this, env);
        newSelectSetOperation = (SelectSetOperation) p.first;
        currentEnv = p.second;

        if (selectExpression.hasOrderby()) {
            p = selectExpression.getOrderbyClause().accept(this, currentEnv);
            newOrderbyClause = (OrderbyClause) p.first;
            currentEnv = p.second;
        }

        if (selectExpression.hasLimit()) {
            p = selectExpression.getLimitClause().accept(this, currentEnv);
            newLimitClause = (LimitClause) p.first;
            currentEnv = p.second;
        }
        return new Pair<>(
                new SelectExpression(newLetList, newSelectSetOperation, newOrderbyClause, newLimitClause, subquery),
                currentEnv);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(HavingClause havingClause,
            VariableSubstitutionEnvironment env) throws CompilationException {
        Pair<ILangExpression, VariableSubstitutionEnvironment> p = havingClause.getFilterExpression().accept(this, env);
        HavingClause newHavingClause = new HavingClause((Expression) p.first);
        return new Pair<>(newHavingClause, p.second);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(CaseExpression caseExpr,
            VariableSubstitutionEnvironment env) throws CompilationException {
        Expression conditionExpr = (Expression) caseExpr.getConditionExpr().accept(this, env).first;
        List<Expression> whenExprList =
                VariableCloneAndSubstitutionUtil.visitAndCloneExprList(caseExpr.getWhenExprs(), env, this);
        List<Expression> thenExprList =
                VariableCloneAndSubstitutionUtil.visitAndCloneExprList(caseExpr.getThenExprs(), env, this);
        Expression elseExpr = (Expression) caseExpr.getElseExpr().accept(this, env).first;
        CaseExpression newCaseExpr = new CaseExpression(conditionExpr, whenExprList, thenExprList, elseExpr);
        return new Pair<>(newCaseExpr, env);
    }

}

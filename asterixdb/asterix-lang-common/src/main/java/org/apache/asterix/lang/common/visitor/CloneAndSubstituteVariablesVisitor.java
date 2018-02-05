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
package org.apache.asterix.lang.common.visitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.asterix.common.exceptions.CompilationException;
import org.apache.asterix.lang.common.base.Expression;
import org.apache.asterix.lang.common.base.Expression.Kind;
import org.apache.asterix.lang.common.base.ILangExpression;
import org.apache.asterix.lang.common.clause.GroupbyClause;
import org.apache.asterix.lang.common.clause.LetClause;
import org.apache.asterix.lang.common.clause.LimitClause;
import org.apache.asterix.lang.common.clause.OrderbyClause;
import org.apache.asterix.lang.common.clause.WhereClause;
import org.apache.asterix.lang.common.expression.CallExpr;
import org.apache.asterix.lang.common.expression.FieldAccessor;
import org.apache.asterix.lang.common.expression.FieldBinding;
import org.apache.asterix.lang.common.expression.GbyVariableExpressionPair;
import org.apache.asterix.lang.common.expression.IfExpr;
import org.apache.asterix.lang.common.expression.IndexAccessor;
import org.apache.asterix.lang.common.expression.ListConstructor;
import org.apache.asterix.lang.common.expression.LiteralExpr;
import org.apache.asterix.lang.common.expression.OperatorExpr;
import org.apache.asterix.lang.common.expression.QuantifiedExpression;
import org.apache.asterix.lang.common.expression.RecordConstructor;
import org.apache.asterix.lang.common.expression.UnaryExpr;
import org.apache.asterix.lang.common.expression.VariableExpr;
import org.apache.asterix.lang.common.rewrites.LangRewritingContext;
import org.apache.asterix.lang.common.rewrites.VariableSubstitutionEnvironment;
import org.apache.asterix.lang.common.statement.FunctionDecl;
import org.apache.asterix.lang.common.statement.Query;
import org.apache.asterix.lang.common.struct.Identifier;
import org.apache.asterix.lang.common.struct.QuantifiedPair;
import org.apache.asterix.lang.common.struct.VarIdentifier;
import org.apache.asterix.lang.common.util.VariableCloneAndSubstitutionUtil;
import org.apache.asterix.lang.common.visitor.base.AbstractQueryExpressionVisitor;
import org.apache.hyracks.algebricks.common.utils.Pair;

public class CloneAndSubstituteVariablesVisitor extends
        AbstractQueryExpressionVisitor<Pair<ILangExpression, VariableSubstitutionEnvironment>, VariableSubstitutionEnvironment> {

    private LangRewritingContext context;

    public CloneAndSubstituteVariablesVisitor(LangRewritingContext context) {
        this.context = context;
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(LetClause lc,
            VariableSubstitutionEnvironment env) throws CompilationException {
        Pair<ILangExpression, VariableSubstitutionEnvironment> p1 = lc.getBindingExpr().accept(this, env);
        VariableExpr varExpr = lc.getVarExpr();
        VariableExpr newVe = generateNewVariable(context, varExpr);
        LetClause newLet = new LetClause(newVe, (Expression) p1.first);
        return new Pair<>(newLet, VariableCloneAndSubstitutionUtil.eliminateSubstFromList(lc.getVarExpr(), env));
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(GroupbyClause gc,
            VariableSubstitutionEnvironment env) throws CompilationException {
        VariableSubstitutionEnvironment newSubs = env;
        List<GbyVariableExpressionPair> newGbyList =
                VariableCloneAndSubstitutionUtil.substInVarExprPair(context, gc.getGbyPairList(), newSubs, this);
        List<GbyVariableExpressionPair> newDecorList = gc.hasDecorList()
                ? VariableCloneAndSubstitutionUtil.substInVarExprPair(context, gc.getDecorPairList(), newSubs, this)
                : new ArrayList<>();

        VariableExpr newGroupVar = null;
        if (gc.hasGroupVar()) {
            newGroupVar = generateNewVariable(context, gc.getGroupVar());
        }
        Map<Expression, VariableExpr> newWithMap = new HashMap<>();
        if (gc.hasWithMap()) {
            for (Entry<Expression, VariableExpr> entry : gc.getWithVarMap().entrySet()) {
                Expression newKeyVar = (Expression) entry.getKey().accept(this, env).first;
                VariableExpr newValueVar = generateNewVariable(context, entry.getValue());
                newWithMap.put(newKeyVar, newValueVar);
            }
        }
        List<Pair<Expression, Identifier>> newGroupFieldList = new ArrayList<>();
        if (gc.hasGroupFieldList()) {
            for (Pair<Expression, Identifier> varId : gc.getGroupFieldList()) {
                Expression newExpr = (Expression) varId.first.accept(this, env).first;
                newGroupFieldList.add(new Pair<>(newExpr, varId.second));
            }
        }
        GroupbyClause newGroup = new GroupbyClause(newGbyList, newDecorList, newWithMap, newGroupVar, newGroupFieldList,
                gc.hasHashGroupByHint(), gc.isGroupAll());
        return new Pair<>(newGroup, newSubs);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(QuantifiedExpression qe,
            VariableSubstitutionEnvironment env) throws CompilationException {
        List<QuantifiedPair> oldPairs = qe.getQuantifiedList();
        List<QuantifiedPair> newPairs = new ArrayList<>(oldPairs.size());
        VariableSubstitutionEnvironment newSubs = env;
        for (QuantifiedPair t : oldPairs) {
            VariableExpr newVar = generateNewVariable(context, t.getVarExpr());
            newSubs = VariableCloneAndSubstitutionUtil.eliminateSubstFromList(newVar, newSubs);
            Pair<ILangExpression, VariableSubstitutionEnvironment> p1 =
                    visitUnnesBindingExpression(t.getExpr(), newSubs);
            QuantifiedPair t2 = new QuantifiedPair(newVar, (Expression) p1.first);
            newPairs.add(t2);
        }
        Pair<ILangExpression, VariableSubstitutionEnvironment> p2 = qe.getSatisfiesExpr().accept(this, newSubs);
        QuantifiedExpression qe2 = new QuantifiedExpression(qe.getQuantifier(), newPairs, (Expression) p2.first);
        return new Pair<>(qe2, newSubs);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(WhereClause wc,
            VariableSubstitutionEnvironment env) throws CompilationException {
        Pair<ILangExpression, VariableSubstitutionEnvironment> p1 = wc.getWhereExpr().accept(this, env);
        WhereClause newW = new WhereClause((Expression) p1.first);
        return new Pair<>(newW, p1.second);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(CallExpr pf,
            VariableSubstitutionEnvironment env) throws CompilationException {
        List<Expression> exprList = VariableCloneAndSubstitutionUtil.visitAndCloneExprList(pf.getExprList(), env, this);
        CallExpr f = new CallExpr(pf.getFunctionSignature(), exprList);
        return new Pair<>(f, env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(FunctionDecl fd,
            VariableSubstitutionEnvironment env) throws CompilationException {
        List<VarIdentifier> newList = new ArrayList<>(fd.getParamList().size());
        for (VarIdentifier vi : fd.getParamList()) {
            VariableExpr varExpr = new VariableExpr(vi);
            if (!env.constainsOldVar(varExpr)) {
                throw new CompilationException("Parameter " + vi + " does not appear in the substitution list.");
            }
            Expression newExpr = env.findSubstitution(varExpr);
            if (newExpr.getKind() != Kind.VARIABLE_EXPRESSION) {
                throw new CompilationException(
                        "Parameter " + vi + " cannot be substituted by a non-variable expression.");
            }
            newList.add(((VariableExpr) newExpr).getVar());
        }

        Pair<ILangExpression, VariableSubstitutionEnvironment> p1 = fd.getFuncBody().accept(this, env);
        FunctionDecl newF = new FunctionDecl(fd.getSignature(), newList, (Expression) p1.first);
        return new Pair<>(newF, env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(IfExpr ifexpr,
            VariableSubstitutionEnvironment env) throws CompilationException {
        Pair<ILangExpression, VariableSubstitutionEnvironment> p1 = ifexpr.getCondExpr().accept(this, env);
        Pair<ILangExpression, VariableSubstitutionEnvironment> p2 = ifexpr.getThenExpr().accept(this, env);
        Pair<ILangExpression, VariableSubstitutionEnvironment> p3 = ifexpr.getElseExpr().accept(this, env);
        IfExpr i = new IfExpr((Expression) p1.first, (Expression) p2.first, (Expression) p3.first);
        return new Pair<>(i, env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(LimitClause lc,
            VariableSubstitutionEnvironment env) throws CompilationException {
        Pair<ILangExpression, VariableSubstitutionEnvironment> p1 = lc.getLimitExpr().accept(this, env);
        Pair<ILangExpression, VariableSubstitutionEnvironment> p2;
        Expression lcOffsetExpr = lc.getOffset();
        if (lcOffsetExpr != null) {
            p2 = lcOffsetExpr.accept(this, env);
        } else {
            p2 = new Pair<>(null, null);
        }
        LimitClause c = new LimitClause((Expression) p1.first, (Expression) p2.first);
        return new Pair<>(c, env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(ListConstructor lc,
            VariableSubstitutionEnvironment env) throws CompilationException {
        List<Expression> oldExprList = lc.getExprList();
        List<Expression> exprs = VariableCloneAndSubstitutionUtil.visitAndCloneExprList(oldExprList, env, this);
        ListConstructor c = new ListConstructor(lc.getType(), exprs);
        return new Pair<>(c, env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(LiteralExpr l,
            VariableSubstitutionEnvironment env) throws CompilationException {
        return new Pair<>(l, env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(OperatorExpr op,
            VariableSubstitutionEnvironment env) throws CompilationException {
        List<Expression> oldExprList = op.getExprList();
        List<Expression> exprs = new ArrayList<>(oldExprList.size());
        for (Expression e : oldExprList) {
            Pair<ILangExpression, VariableSubstitutionEnvironment> p1 = e.accept(this, env);
            exprs.add((Expression) p1.first);
        }
        OperatorExpr oe = new OperatorExpr(exprs, op.getExprBroadcastIdx(), op.getOpList(), op.isCurrentop());
        return new Pair<>(oe, env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(OrderbyClause oc,
            VariableSubstitutionEnvironment env) throws CompilationException {
        List<Expression> exprList =
                VariableCloneAndSubstitutionUtil.visitAndCloneExprList(oc.getOrderbyList(), env, this);
        OrderbyClause oc2 = new OrderbyClause(exprList, oc.getModifierList());
        oc2.setNumFrames(oc.getNumFrames());
        oc2.setNumTuples(oc.getNumTuples());
        oc2.setRangeMap(oc.getRangeMap());
        return new Pair<>(oc2, env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(Query q, VariableSubstitutionEnvironment env)
            throws CompilationException {
        Query newQ = new Query(q.isExplain());
        Pair<ILangExpression, VariableSubstitutionEnvironment> p1 = q.getBody().accept(this, env);
        newQ.setBody((Expression) p1.first);
        return new Pair<>(newQ, p1.second);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(RecordConstructor rc,
            VariableSubstitutionEnvironment env) throws CompilationException {
        List<FieldBinding> oldFbs = rc.getFbList();
        ArrayList<FieldBinding> newFbs = new ArrayList<>(oldFbs.size());
        for (FieldBinding fb : oldFbs) {
            Pair<ILangExpression, VariableSubstitutionEnvironment> p1 = fb.getLeftExpr().accept(this, env);
            Pair<ILangExpression, VariableSubstitutionEnvironment> p2 = fb.getRightExpr().accept(this, env);
            FieldBinding fb2 = new FieldBinding((Expression) p1.first, (Expression) p2.first);
            newFbs.add(fb2);
        }
        RecordConstructor newRc = new RecordConstructor(newFbs);
        return new Pair<>(newRc, env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(UnaryExpr u,
            VariableSubstitutionEnvironment env) throws CompilationException {
        Pair<ILangExpression, VariableSubstitutionEnvironment> p1 = u.getExpr().accept(this, env);
        UnaryExpr newU = new UnaryExpr(u.getExprType(), (Expression) p1.first);
        return new Pair<>(newU, env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(IndexAccessor ia,
            VariableSubstitutionEnvironment env) throws CompilationException {
        Pair<ILangExpression, VariableSubstitutionEnvironment> p1 = ia.getExpr().accept(this, env);
        Expression indexExpr = null;
        if (!ia.isAny()) {
            Pair<ILangExpression, VariableSubstitutionEnvironment> p2 = ia.getIndexExpr().accept(this, env);
            indexExpr = (Expression) p2.first;
        }
        IndexAccessor i = new IndexAccessor((Expression) p1.first, indexExpr);
        i.setAny(ia.isAny());
        return new Pair<>(i, env);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(FieldAccessor fa,
            VariableSubstitutionEnvironment env) throws CompilationException {
        Pair<ILangExpression, VariableSubstitutionEnvironment> p = fa.getExpr().accept(this, env);
        FieldAccessor newF = new FieldAccessor((Expression) p.first, fa.getIdent());
        return new Pair<>(newF, p.second);
    }

    @Override
    public Pair<ILangExpression, VariableSubstitutionEnvironment> visit(VariableExpr v,
            VariableSubstitutionEnvironment env) throws CompilationException {
        return new Pair<>(rewriteVariableExpr(v, env), env);
    }

    // Replace a variable expression if the variable is to-be substituted.
    protected Expression rewriteVariableExpr(VariableExpr expr, VariableSubstitutionEnvironment env)
            throws CompilationException {
        if (env.constainsOldVar(expr)) {
            return env.findSubstitution(expr);
        } else {
            // it is a variable from the context
            VarIdentifier var = context.getRewrittenVar(expr.getVar().getId());
            if (var != null) {
                return new VariableExpr(var);
            }
        }
        return expr;
    }

    /**
     * Generates a new variable for an existing variable.
     *
     * @param context
     *            , the language rewriting context which keeps all the rewriting variable-int-id to variable-string-identifier mappings.
     * @param varExpr
     *            , the existing variable expression.
     * @return the new variable expression.
     */
    public VariableExpr generateNewVariable(LangRewritingContext context, VariableExpr varExpr) {
        VarIdentifier vi = varExpr.getVar();
        VarIdentifier newVar = context.mapOldId(vi.getId(), vi.getValue());
        return new VariableExpr(newVar);
    }

    /**
     * Visits an expression that is used for unnest binding.
     *
     * @param expr,
     *            the expression to consider.
     * @param env,
     *            the variable substitution environment.
     * @return a pair of an ILangExpression and a variable substitution environment.
     * @throws CompilationException
     */
    protected Pair<ILangExpression, VariableSubstitutionEnvironment> visitUnnesBindingExpression(Expression expr,
            VariableSubstitutionEnvironment env) throws CompilationException {
        return expr.accept(this, env);
    }

}

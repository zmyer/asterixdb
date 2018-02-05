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
package org.apache.asterix.lang.sqlpp.rewrites;

import java.util.List;

import org.apache.asterix.common.exceptions.CompilationException;
import org.apache.asterix.lang.common.base.IReturningStatement;
import org.apache.asterix.lang.common.rewrites.LangRewritingContext;
import org.apache.asterix.lang.common.statement.FunctionDecl;
import org.apache.asterix.metadata.declared.MetadataProvider;

class SqlppFunctionBodyRewriter extends SqlppQueryRewriter {

    @Override
    public void rewrite(List<FunctionDecl> declaredFunctions, IReturningStatement topStatement,
            MetadataProvider metadataProvider, LangRewritingContext context, boolean inlineUdfs)
            throws CompilationException {
        // Sets up parameters.
        setup(declaredFunctions, topStatement, metadataProvider, context);

        // Inlines column aliases.
        inlineColumnAlias();

        // Generates column names.
        generateColumnNames();

        // Substitutes group-by key expressions.
        substituteGroupbyKeyExpression();

        // Group-by core rewrites
        rewriteGroupBys();

        // Rewrites set operations.
        rewriteSetOperations();

        // Generate ids for variables (considering scopes) and replace global variable access with the dataset function.
        variableCheckAndRewrite();

        // Rewrites SQL-92 global aggregations.
        rewriteGroupByAggregationSugar();

        // Rewrites like/not-like expressions.
        rewriteOperatorExpression();

        // Rewrites several variable-arg functions into their corresponding internal list-input functions.
        rewriteListInputFunctions();

        // Inlines functions recursively.
        inlineDeclaredUdfs(inlineUdfs);
    }
}

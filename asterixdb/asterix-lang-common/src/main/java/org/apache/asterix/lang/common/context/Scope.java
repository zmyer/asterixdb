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
package org.apache.asterix.lang.common.context;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.asterix.common.functions.FunctionSignature;
import org.apache.asterix.lang.common.expression.VariableExpr;
import org.apache.asterix.lang.common.parser.ScopeChecker;
import org.apache.asterix.lang.common.struct.Identifier;
import org.apache.asterix.lang.common.struct.VarIdentifier;
import org.apache.commons.collections4.iterators.ReverseListIterator;

public final class Scope {
    private final ScopeChecker scopeChecker;
    private final Scope parent;
    private final LinkedHashMap<String, Identifier> symbols;
    private final boolean maskParentScope;
    private FunctionSignatures functionSignatures;

    public Scope(ScopeChecker scopeChecker) {
        this(scopeChecker, null);
    }

    public Scope(ScopeChecker scopeChecker, Scope parent) {
        this(scopeChecker, parent, false);
    }

    public Scope(ScopeChecker scopeChecker, Scope parent, boolean maskParentScope) {
        this.scopeChecker = scopeChecker;
        this.parent = parent;
        this.maskParentScope = maskParentScope;
        this.symbols = new LinkedHashMap<>();
    }

    /**
     * Find a symbol in the scope
     *
     * @param name
     * @return the Identifier of this symbol; otherwise null;
     */
    public Identifier findSymbol(String name) {
        Identifier ident = symbols.get(name);
        if (ident == null && !maskParentScope && parent != null) {
            ident = parent.findSymbol(name);
        }
        return ident;
    }

    public Identifier findLocalSymbol(String name) {
        return symbols.get(name);
    }

    /**
     * Add a symbol into scope
     *
     * @param ident
     */
    public void addSymbolToScope(Identifier ident) {
        symbols.put(ident.getValue(), ident);
    }

    public void addNewVarSymbolToScope(VarIdentifier ident) {
        scopeChecker.incVarCounter();
        ident.setId(scopeChecker.getVarCounter());
        addSymbolToScope(ident);
    }

    /**
     * Add a FunctionDescriptor into functionSignatures
     *
     * @param signature
     *            FunctionSignature
     * @param varargs
     *            whether this function has varargs
     */
    public void addFunctionDescriptor(FunctionSignature signature, boolean varargs) {
        if (functionSignatures == null) {
            functionSignatures = new FunctionSignatures();
        }
        functionSignatures.put(signature, varargs);
    }

    /**
     * find a function signature
     *
     * @param name
     *            name of the function
     * @param arity
     *            # of arguments
     * @return FunctionDescriptor of the function found; otherwise null
     */
    public FunctionSignature findFunctionSignature(String dataverse, String name, int arity) {
        FunctionSignature fd = null;
        if (functionSignatures != null) {
            fd = functionSignatures.get(dataverse, name, arity);
        }
        if (fd == null && parent != null) {
            fd = parent.findFunctionSignature(dataverse, name, arity);
        }
        return fd;
    }

    /**
     * Merge yet-another Scope instance into the current Scope instance.
     *
     * @param scope
     *            the other Scope instance.
     */
    public void merge(Scope scope) {
        symbols.putAll(scope.symbols);
        if (functionSignatures != null && scope.functionSignatures != null) {
            functionSignatures.addAll(scope.functionSignatures);
        }
    }

    /**
     * Retrieve all the visible symbols in the current scope.
     *
     * @return an iterator of visible symbols.
     */
    public Iterator<Identifier> liveSymbols(Scope stopAtExclusive) {
        final Iterator<Identifier> identifierIterator = new ReverseListIterator<>(new ArrayList<>(symbols.values()));
        final Iterator<Identifier> parentIterator =
                parent == null || parent == stopAtExclusive ? null : parent.liveSymbols(stopAtExclusive);
        return new Iterator<Identifier>() {
            private Identifier currentSymbol = null;

            @Override
            public boolean hasNext() {
                currentSymbol = null;
                if (identifierIterator.hasNext()) {
                    currentSymbol = identifierIterator.next();
                } else if (!maskParentScope && parentIterator != null && parentIterator.hasNext()) {
                    do {
                        Identifier symbolFromParent = parentIterator.next();
                        if (!symbols.containsKey(symbolFromParent.getValue())) {
                            currentSymbol = symbolFromParent;
                            break;
                        }
                    } while (parentIterator.hasNext());
                }

                // Return true if currentSymbol is set.
                if (currentSymbol == null) {
                    return false;
                } else {
                    return true;
                }
            }

            @Override
            public Identifier next() {
                if (currentSymbol == null) {
                    throw new IllegalStateException(
                            "Please make sure that hasNext() returns true before calling next().");
                } else {
                    return currentSymbol;
                }
            }

        };
    }

    public Set<VariableExpr> getLiveVariables() {
        return getLiveVariables(null);
    }

    public Set<VariableExpr> getLiveVariables(Scope stopAtExclusive) {
        LinkedHashSet<VariableExpr> vars = new LinkedHashSet<>();
        Iterator<Identifier> identifierIterator = liveSymbols(stopAtExclusive);
        while (identifierIterator.hasNext()) {
            Identifier identifier = identifierIterator.next();
            if (identifier instanceof VarIdentifier) {
                vars.add(new VariableExpr((VarIdentifier) identifier));
            }
        }
        return vars;
    }

    // Returns local symbols within the current scope.
    public Set<String> getLocalSymbols() {
        return symbols.keySet();
    }

    public Scope getParentScope() {
        return parent;
    }
}

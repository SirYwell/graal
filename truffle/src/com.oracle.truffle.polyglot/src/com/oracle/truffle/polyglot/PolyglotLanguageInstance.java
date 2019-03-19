/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.polyglot;

import static com.oracle.truffle.polyglot.VMAccessor.LANGUAGE;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextPolicy;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.polyglot.PolyglotImpl.VMObject;
import com.oracle.truffle.polyglot.PolyglotValue.InteropCodeCache;

final class PolyglotLanguageInstance implements VMObject {

    final PolyglotLanguage language;
    final TruffleLanguage<Object> spi;

    private final PolyglotSourceCache sourceCache;
    final Map<Class<?>, InteropCodeCache> valueCodeCache;
    final Map<Object, Object> hostInteropCodeCache;

    private volatile OptionValuesImpl firstOptionValues;
    private volatile boolean needsInitializeMultiContext;

    private final LanguageReference<TruffleLanguage<Object>> directLanguageSupplier;
    private final ContextReference<Object> directContextSupplier;
    final Assumption singleContext;

    @SuppressWarnings("unchecked")
    PolyglotLanguageInstance(PolyglotLanguage language) {
        this.language = language;
        this.sourceCache = new PolyglotSourceCache();
        this.valueCodeCache = new ConcurrentHashMap<>();
        this.hostInteropCodeCache = new ConcurrentHashMap<>();
        this.singleContext = Truffle.getRuntime().createAssumption("Single context per language instance.");
        try {
            this.spi = (TruffleLanguage<Object>) language.cache.loadLanguage();
            LANGUAGE.initializeLanguage(spi, language.info, language, this);
            if (!language.engine.singleContext.isValid()) {
                initializeMultiContext();
            } else {
                this.needsInitializeMultiContext = !language.engine.boundEngine;
            }
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Error initializing language '%s' using class '%s'.", language.cache.getId(), language.cache.getClassName()), e);
        }
        if (this.singleContext.isValid() && language.engine.noInnerContexts.isValid()) {
            this.directContextSupplier = PolyglotReferences.createAssumeSingleContext(language, singleContext, language.engine.noInnerContexts, language.getContextReference());
        } else {
            this.directContextSupplier = language.getContextReference();
        }
        this.directLanguageSupplier = PolyglotReferences.createAlwaysSingleLanguage(language, this);
    }

    public PolyglotEngineImpl getEngine() {
        return language.engine;
    }

    boolean areOptionsCompatible(OptionValuesImpl newOptionValues) {
        OptionValuesImpl firstOptions = this.firstOptionValues;
        if (firstOptionValues == null) {
            return true;
        } else {
            return VMAccessor.LANGUAGE.areOptionsCompatible(spi, firstOptions, newOptionValues);
        }
    }

    void claim(OptionValuesImpl optionValues) {
        assert Thread.holdsLock(language.engine);
        if (this.firstOptionValues == null) {
            this.firstOptionValues = optionValues;
        }
    }

    void ensureMultiContextInitialized() {
        assert Thread.holdsLock(language.engine);
        if (needsInitializeMultiContext) {
            needsInitializeMultiContext = false;
            language.engine.initializeMultiContext(null);
            initializeMultiContext();
        }
    }

    void initializeMultiContext() {
        assert !language.engine.singleContext.isValid();
        if (language.cache.getPolicy() != ContextPolicy.EXCLUSIVE) {
            this.singleContext.invalidate();
            LANGUAGE.initializeMultiContext(spi);
        }
    }

    PolyglotSourceCache getSourceCache() {
        return sourceCache;
    }

    /**
     * Direct context references can safely be shared within one AST of a language.
     */
    ContextReference<Object> getDirectContextSupplier() {
        return directContextSupplier;
    }

    /**
     * Looks up the context reference to use for a foreign language AST.
     */
    ContextReference<Object> lookupContextSupplier(PolyglotLanguageInstance sourceLanguage) {
        assert this != sourceLanguage;
        switch (getEffectiveContextPolicy(sourceLanguage)) {
            case EXCLUSIVE:
                return this.directContextSupplier;
            case REUSE:
            case SHARED:
                return this.language.getContextReference();
            default:
                throw new AssertionError();
        }
    }

    /**
     * Direct language references can safely be shared within one AST of a language.
     */
    LanguageReference<TruffleLanguage<Object>> getDirectLanguageReference() {
        return directLanguageSupplier;
    }

    /**
     * Looks up the language reference to use for a foreign language AST.
     */
    LanguageReference<TruffleLanguage<Object>> lookupLanguageSupplier(PolyglotLanguageInstance sourceLanguage) {
        assert this != sourceLanguage;
        switch (getEffectiveContextPolicy(sourceLanguage)) {
            case EXCLUSIVE:
                return this.directLanguageSupplier;
            case REUSE:
            case SHARED:
                return this.language.getLanguageReference();
            default:
                throw new AssertionError();
        }
    }

    ContextPolicy getEffectiveContextPolicy(PolyglotLanguageInstance sourceRootLanguage) {
        ContextPolicy sourcePolicy;
        if (language.engine.boundEngine) {
            // with a bound engine context policy is effectively always exclusive
            sourcePolicy = ContextPolicy.EXCLUSIVE;
        } else {
            if (sourceRootLanguage != null) {
                sourcePolicy = sourceRootLanguage.language.cache.getPolicy();
            } else {
                // null source language means shared policy
                sourcePolicy = ContextPolicy.SHARED;
            }
        }
        return sourcePolicy;
    }

}

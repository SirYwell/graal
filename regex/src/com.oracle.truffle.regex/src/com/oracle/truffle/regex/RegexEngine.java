/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.regex;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.regex.runtime.nodes.ToStringNode;
import com.oracle.truffle.regex.tregex.parser.RegexParser;
import com.oracle.truffle.regex.tregex.parser.flavors.RegexFlavor;
import com.oracle.truffle.regex.tregex.parser.flavors.RegexFlavorProcessor;

/**
 * {@link RegexEngine} is an executable {@link TruffleObject} that compiles regular expressions and
 * packages the results in {@link RegexObject}s. It takes the following arguments:
 * <ol>
 * <li>{@link String} {@code pattern}: the source of the regular expression to be compiled</li>
 * <li>{@link String} {@code flags} (optional): a textual representation of the flags to be passed
 * to the compiler (one letter per flag), see {@link RegexFlags} for the supported flags</li>
 * </ol>
 * Executing the {@link RegexEngine} can also lead to the following exceptions:
 * <ul>
 * <li>{@link RegexSyntaxException}: if the input regular expression is malformed</li>
 * <li>{@link UnsupportedRegexException}: if the input regular expression cannot be compiled by this
 * engine</li>
 * </ul>
 * <p>
 * A {@link RegexEngine} can be obtained by executing the {@link RegexEngineBuilder}.
 */
@ExportLibrary(InteropLibrary.class)
public class RegexEngine implements RegexLanguageObject {

    private final RegexCompiler compiler;
    private final RegexOptions options;

    public RegexEngine(RegexCompiler compiler, RegexOptions options) {
        this.compiler = compiler;
        this.options = options;
    }

    public RegexObject compile(RegexSource regexSource) throws RegexSyntaxException, UnsupportedRegexException {
        // Detect SyntaxErrors in regular expressions early.
        RegexFlavor flavor = options.getFlavor();
        RegexObject regexObject;
        if (flavor != null) {
            RegexFlavorProcessor flavorProcessor = flavor.forRegex(regexSource);
            flavorProcessor.validate();
            regexObject = new RegexObject(compiler, regexSource, flavorProcessor.getFlags(), flavorProcessor.isUnicodePattern(), flavorProcessor.getNamedCaptureGroups());
        } else {
            RegexFlags flags = RegexFlags.parseFlags(regexSource.getFlags());
            RegexParser regexParser = new RegexParser(regexSource, options);
            regexParser.validate();
            regexObject = new RegexObject(compiler, regexSource, flags, regexParser.getFlags().isUnicode(), regexParser.getNamedCaptureGroups());
        }
        if (options.isRegressionTestMode()) {
            // Force the compilation of the RegExp.
            regexObject.getCompiledRegexObject();
        }
        return regexObject;
    }

    @ExportMessage
    public boolean isExecutable() {
        return true;
    }

    @ExportMessage
    Object execute(Object[] args,
                    @Cached ToStringNode patternToStringNode,
                    @Cached ToStringNode flagsToStringNode) throws ArityException, UnsupportedTypeException {
        if (!(args.length == 1 || args.length == 2)) {
            CompilerDirectives.transferToInterpreter();
            throw ArityException.create(2, args.length);
        }
        String pattern = patternToStringNode.execute(args[0]);
        String flags = args.length == 2 ? flagsToStringNode.execute(args[1]) : "";
        RegexSource regexSource = new RegexSource(pattern, flags);
        return compile(regexSource);
    }
}

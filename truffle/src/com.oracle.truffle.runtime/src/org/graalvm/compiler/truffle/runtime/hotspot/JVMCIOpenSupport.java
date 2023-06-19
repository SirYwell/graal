/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.truffle.runtime.hotspot;

final class JVMCIOpenSupport {

    private static final Module truffleRuntimeModule;
    private static final Module jvmciModule;
    private static final boolean truffleAttachLibraryAvailable;
    static {
        truffleRuntimeModule = JVMCIOpenSupport.class.getModule();
        jvmciModule = truffleRuntimeModule.getLayer().findModule("jdk.internal.vm.ci").orElse(null);
        truffleAttachLibraryAvailable = jvmciModule != null && loadTruffleAttachLibrary();
    }

    private JVMCIOpenSupport() {
    }

    static boolean hasOpenJVMCI() {
        return jvmciModule != null && isJVMCIExportedOrOpenedToTruffleRuntime();
    }

    static boolean openJVMCI() {
        if (jvmciModule == null) {
            return false;
        }
        if (isJVMCIExportedOrOpenedToTruffleRuntime()) {
            return true;
        }
        if (truffleAttachLibraryAvailable) {
            truffleRuntimeModule.addReads(jvmciModule);
            openJVMCITo0(truffleRuntimeModule);
            return true;
        }
        return false;
    }

    private static boolean isJVMCIExportedOrOpenedToTruffleRuntime() {
        return jvmciModule.isExported("jdk.vm.ci.hotspot", truffleRuntimeModule);
    }

    private static boolean loadTruffleAttachLibrary() {
        /*
         * Support for unittests. Unittests are passing path to the truffleattach library using
         * truffle.attach.library system property.
         */
        String attachLib = System.getProperty("truffle.attach.library");
        try {
            if (attachLib == null) {
                try {
                    System.loadLibrary("truffleattach");
                } catch (UnsatisfiedLinkError invalidLibrary) {
                    return false;
                }
            } else {
                System.load(attachLib);
            }
            return true;
        } catch (Throwable throwable) {
            throw new InternalError(throwable);
        }
    }

    private static native void openJVMCITo0(Module toModule);
}

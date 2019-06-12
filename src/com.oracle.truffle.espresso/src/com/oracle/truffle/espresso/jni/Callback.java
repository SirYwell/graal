/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.truffle.espresso.jni;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.runtime.EspressoException;

@ExportLibrary(InteropLibrary.class)
public class Callback implements TruffleObject {

    private final int arity;
    private final Function function;

    public Callback(int arity, Function function) {
        this.arity = arity;
        this.function = function;
    }

    @ExportMessage
    public boolean isExecutable() {
        return true;
    }

    @ExportMessage
    public Object execute(Object... arguments) throws ArityException {
        if (arguments.length == arity) {
            Object ret = function.call(arguments);
            return ret;
        } else {
            CompilerDirectives.transferToInterpreter();
            throw ArityException.create(arity, arguments.length);
        }
    }

    public interface Function {
        Object call(Object... args);
    }

    private static Callback wrap(Object receiver, Method m) {
        assert m != null;
        return new Callback(m.getParameterCount(), new Function() {
            @Override
            public Object call(Object... args) {
                try {
                    return m.invoke(receiver, args);
                } catch (IllegalAccessException e) {
                    throw EspressoError.shouldNotReachHere(e);
                } catch (InvocationTargetException e) {
                    Throwable targetEx = e.getTargetException();
                    if (targetEx instanceof EspressoException) {
                        throw (EspressoException) targetEx;
                    }
                    if (targetEx instanceof RuntimeException) {
                        throw (RuntimeException) targetEx;
                    }
                    throw EspressoError.shouldNotReachHere(e);
                }
            }
        });
    }

    static Callback wrapStaticMethod(Class<?> clazz, String methodName, Class<?> parameterTypes) {
        Method m;
        try {
            m = clazz.getDeclaredMethod(methodName, parameterTypes);
            assert Modifier.isStatic(m.getModifiers());
        } catch (NoSuchMethodException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
        return wrap(clazz, m);
    }

    public static Callback wrapInstanceMethod(Object receiver, String methodName, Class<?> parameterTypes) {
        Method m;
        try {
            m = receiver.getClass().getDeclaredMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
        return wrap(receiver, m);
    }
}

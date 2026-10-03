/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2024 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is an opensource software: you can redistribute it
 * and/or modify it under the terms of the General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package io.github.qauxv.loader.zygisk;

import android.app.Application;
import android.app.Instrumentation;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;

import io.github.qauxv.loader.hookapi.IHookBridge;
import io.github.qauxv.loader.sbl.common.ModuleLoader;

/**
 * Java entry point invoked by the Zygisk injector.
 *
 * <p>The native side ({@code zygisk_entry.cpp}) loads every {@code classes*.dex} of the
 * module APK into an {@code InMemoryDexClassLoader} and then calls
 * {@link #init(String, String, String)}. At that moment the host application has not been
 * created yet, so there is no host {@link ClassLoader} available. This class therefore:
 *
 * <ol>
 *     <li>brings up the ART hook engine ({@code nativeArtInit});</li>
 *     <li>installs a bootstrap hook that fires once the host ClassLoader exists;</li>
 *     <li>hands control over to {@link ModuleLoader}, which starts the normal
 *         QAuxiliary startup chain with {@link ZygiskHookImpl} as the hook provider.</li>
 * </ol>
 */
@Keep
public class ZygiskEntry {

    private static final String TAG = "QAuxv.ZygiskEntry";

    private static volatile boolean sInitialized = false;

    private ZygiskEntry() {
    }

    // --------------------------------------------------------------------------------
    // native methods, registered by jni_bridge.cpp
    // --------------------------------------------------------------------------------

    static native boolean nativeArtInit();

    static native void nativeLog(@NonNull String tag, @NonNull String msg);

    static native boolean isCompatMode();

    static native boolean nativeIsCompatMode();

    // --------------------------------------------------------------------------------
    // entry point
    // --------------------------------------------------------------------------------

    /**
     * Called by the Zygisk injector right after the app process has been specialized.
     *
     * @param processName the process name, e.g. {@code com.tencent.mobileqq} or
     *                    {@code com.tencent.mobileqq:MSF}
     * @param dataDir     the host application data directory
     * @param apkPath     path of the module APK copy that lives in the host data directory
     */
    @Keep
    @SuppressWarnings("unused")
    public static void init(@NonNull String processName, @NonNull String dataDir, @NonNull String apkPath) {
        if (sInitialized) {
            return;
        }
        sInitialized = true;
        String pkg = processName.split(":")[0];
        try {
            nativeLog(TAG, "init: " + processName + " (zygisk mode, compat=" + isCompatMode() + ")");
            // Bring up the ART hook engine before anything else: everything below
            // (including the bootstrap) is itself implemented as a hook.
            if (!nativeArtInit()) {
                Log.e(TAG, "nativeArtInit failed, abort");
                nativeLog(TAG, "nativeArtInit failed, abort");
                return;
            }
            ZygiskHookImpl impl = ZygiskHookImpl.INSTANCE;
            impl.setMainModulePath(apkPath);
            impl.setHostDataDir(dataDir);
            impl.setHostPackageName(pkg);
            installHostBootstrap(pkg, dataDir, apkPath, processName);
            Log.i(TAG, "bootstrap installed for " + processName + " (apk=" + apkPath + ")");
            nativeLog(TAG, "init done: " + processName);
        } catch (Throwable t) {
            Log.e(TAG, "ZygiskEntry.init failed", t);
            nativeLog(TAG, "init failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // --------------------------------------------------------------------------------
    // bootstrap
    // --------------------------------------------------------------------------------

    private static void installHostBootstrap(@NonNull String pkg, @NonNull String dataDir,
                                            @NonNull String apkPath, @NonNull String processName) {
        if (installInNormalMode(pkg, dataDir, apkPath)) {
            return;
        }
        if (installInCompatMode(pkg, dataDir, apkPath)) {
            return;
        }
        Log.e(TAG, "unable to install host bootstrap for " + processName);
        nativeLog(TAG, "bootstrap failed: no usable hook point");
    }

    /**
     * Preferred path: hook the framework while the host ClassLoader is being created, so the
     * module is initialized before any host code runs.
     */
    private static boolean installInNormalMode(@NonNull String pkg, @NonNull String dataDir, @NonNull String apkPath) {
        try {
            Class<?> kLoadedApk = Class.forName("android.app.LoadedApk");
            Method createAppFactory = kLoadedApk.getDeclaredMethod(
                    "createAppFactory", ApplicationInfo.class, ClassLoader.class);
            createAppFactory.setAccessible(true);
            ZygiskHookImpl.INSTANCE.hookMethod(createAppFactory, new IHookBridge.IMemberHookCallback() {
                @Override
                public void beforeHookedMember(@NonNull IHookBridge.IMemberHookParam param) {
                }

                @Override
                public void afterHookedMember(@NonNull IHookBridge.IMemberHookParam param) {
                    if (param.getThrowable() != null) {
                        return;
                    }
                    Object arg0 = param.getArgs().length > 0 ? param.getArgs()[0] : null;
                    if (!(arg0 instanceof ApplicationInfo)) {
                        return;
                    }
                    ApplicationInfo appInfo = (ApplicationInfo) arg0;
                    if (!pkg.equals(appInfo.packageName)) {
                        return;
                    }
                    Object factory = param.getResult();
                    if (factory == null) {
                        return;
                    }
                    try {
                        Method instantiate = factory.getClass().getMethod(
                                "instantiateClassLoader", ClassLoader.class, ApplicationInfo.class);
                        instantiate.setAccessible(true);
                        ZygiskHookImpl.INSTANCE.hookMethod(instantiate, new IHookBridge.IMemberHookCallback() {
                            @Override
                            public void beforeHookedMember(@NonNull IHookBridge.IMemberHookParam param) {
                            }

                            @Override
                            public void afterHookedMember(@NonNull IHookBridge.IMemberHookParam param) {
                                if (param.getThrowable() != null) {
                                    return;
                                }
                                Object result = param.getResult();
                                if (!(result instanceof ClassLoader)) {
                                    return;
                                }
                                startModule(pkg, dataDir, apkPath, (ClassLoader) result);
                            }
                        }, IHookBridge.PRIORITY_DEFAULT);
                    } catch (Throwable t) {
                        Log.e(TAG, "failed to hook instantiateClassLoader", t);
                    }
                }
            }, IHookBridge.PRIORITY_DEFAULT);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "LoadedApk.createAppFactory hook unavailable, trying compat mode", t);
            return false;
        }
    }

    /**
     * Fallback: wait until the host Application is being created and read the ClassLoader from it.
     * Some hosts (or some Android versions) do not go through
     * {@code LoadedApk.createAppFactory}.
     */
    private static boolean installInCompatMode(@NonNull String pkg, @NonNull String dataDir, @NonNull String apkPath) {
        try {
            Method callApplicationOnCreate = Instrumentation.class.getDeclaredMethod(
                    "callApplicationOnCreate", Application.class);
            callApplicationOnCreate.setAccessible(true);
            ZygiskHookImpl.INSTANCE.hookMethod(callApplicationOnCreate, new IHookBridge.IMemberHookCallback() {
                @Override
                public void beforeHookedMember(@NonNull IHookBridge.IMemberHookParam param) {
                    if (param.getThrowable() != null) {
                        return;
                    }
                    Object arg0 = param.getArgs().length > 0 ? param.getArgs()[0] : null;
                    if (!(arg0 instanceof Application)) {
                        return;
                    }
                    Application app = (Application) arg0;
                    ClassLoader hostLoader = app.getClassLoader();
                    if (hostLoader == null || hostLoader == ZygiskEntry.class.getClassLoader()) {
                        return;
                    }
                    startModule(pkg, dataDir, apkPath, hostLoader);
                }

                @Override
                public void afterHookedMember(@NonNull IHookBridge.IMemberHookParam param) {
                }
            }, IHookBridge.PRIORITY_DEFAULT);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "failed to install compat bootstrap", t);
            return false;
        }
    }

    private static volatile boolean sModuleStarted = false;

    private static void startModule(@NonNull String pkg, @NonNull String dataDir,
                                    @NonNull String apkPath, @Nullable ClassLoader hostClassLoader) {
        if (hostClassLoader == null || sModuleStarted) {
            return;
        }
        sModuleStarted = true;
        try {
            ZygiskHookImpl impl = ZygiskHookImpl.INSTANCE;
            ModuleLoader.initialize(dataDir, hostClassLoader, impl, impl, apkPath, false);
            Log.i(TAG, "module started for " + pkg);
            nativeLog(TAG, "module started: " + pkg);
        } catch (Throwable t) {
            Log.e(TAG, "failed to start module for " + pkg, t);
            nativeLog(TAG, "module start failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}

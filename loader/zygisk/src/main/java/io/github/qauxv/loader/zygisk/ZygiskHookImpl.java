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

import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.qauxv.loader.hookapi.IClassLoaderHelper;
import io.github.qauxv.loader.hookapi.IHookBridge;
import io.github.qauxv.loader.hookapi.ILoaderService;

/**
 * The {@link IHookBridge} / {@link ILoaderService} implementation used when QAuxiliary
 * is injected as a Zygisk module.
 *
 * <p>It delegates all hooking work to {@link ZygiskHookBridge}, which patches ART methods
 * directly. From the module's point of view the behaviour is the same as the LSPosed and
 * legacy Xposed implementations, so all existing hooks work unchanged.
 */
@Keep
public class ZygiskHookImpl implements IHookBridge, ILoaderService {

    public static final ZygiskHookImpl INSTANCE = new ZygiskHookImpl();

    private static final String TAG = "QAuxv.Zygisk";

    private volatile String mMainModulePath = "";
    private volatile String mHostDataDir = "";
    private volatile String mHostPackageName = "";
    private IClassLoaderHelper mClassLoaderHelper;

    protected ZygiskHookImpl() {
    }

    void setMainModulePath(@NonNull String path) {
        mMainModulePath = path;
    }

    void setHostDataDir(@NonNull String dataDir) {
        mHostDataDir = dataDir;
    }

    void setHostPackageName(@NonNull String packageName) {
        mHostPackageName = packageName;
    }

    // --------------------------------------------------------------------------------
    // ILoaderService
    // --------------------------------------------------------------------------------

    @NonNull
    @Override
    public String getEntryPointName() {
        return "ZygiskEntry";
    }

    @NonNull
    @Override
    public String getLoaderVersionName() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public int getLoaderVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    @NonNull
    @Override
    public String getMainModulePath() {
        return mMainModulePath;
    }

    @Override
    public void log(@NonNull String msg) {
        Log.i(TAG, msg);
    }

    @Override
    public void log(@NonNull Throwable tr) {
        Log.e(TAG, tr.toString(), tr);
    }

    @Nullable
    @Override
    public Object queryExtension(@NonNull String key, @Nullable Object... args) {
        switch (key) {
            case "HOST_DATA_DIR":
                return mHostDataDir;
            case "HOST_PACKAGE_NAME":
                return mHostPackageName;
            case "NATIVE_LOG_DIR":
                return new File(mHostDataDir, "files/.qauxv").getAbsolutePath();
            default:
                return null;
        }
    }

    @Nullable
    @Override
    public IClassLoaderHelper getClassLoaderHelper() {
        return mClassLoaderHelper;
    }

    @Override
    public void setClassLoaderHelper(@Nullable IClassLoaderHelper helper) {
        mClassLoaderHelper = helper;
    }

    // --------------------------------------------------------------------------------
    // IHookBridge - framework info
    // --------------------------------------------------------------------------------

    @Override
    public int getApiLevel() {
        // Not an Xposed API level; 0 marks the Zygisk/ART implementation.
        return 0;
    }

    @NonNull
    @Override
    public String getFrameworkName() {
        return "Zygisk";
    }

    @NonNull
    @Override
    public String getFrameworkVersion() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public long getFrameworkVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    // --------------------------------------------------------------------------------
    // IHookBridge - hooking
    // --------------------------------------------------------------------------------

    @NonNull
    @Override
    public MemberUnhookHandle hookMethod(@NonNull Member member, @NonNull IMemberHookCallback callback, int priority) {
        if (member == null) {
            throw new IllegalArgumentException("member must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        ZygiskHookBridge.HookEntry entry = ZygiskHookBridge.getOrCreateHookEntry(member);
        if (entry == null) {
            // The native engine refused this member. Fail loudly: silently returning a
            // dead handle would make the caller believe the hook is installed.
            throw new RuntimeException("ZygiskHookBridge: failed to hook " + member);
        }
        CallbackAdapter adapter = new CallbackAdapter(callback);
        ZygiskHookBridge.CallbackEntry owner = new ZygiskHookBridge.CallbackEntry(adapter);
        entry.callbacks.add(new ZygiskHookBridge.Registration(priority, ZygiskHookBridge.Mode.BEFORE, owner));
        entry.callbacks.add(new ZygiskHookBridge.Registration(priority, ZygiskHookBridge.Mode.AFTER, owner));
        return new UnhookHandle(member, owner);
    }

    @Override
    public boolean isDeoptimizationSupported() {
        // Deoptimization requires an ART JIT API that the standalone engine does not drive.
        return false;
    }

    @Override
    public boolean deoptimize(@NonNull Member member) {
        return false;
    }

    @Nullable
    @Override
    public Object invokeOriginalMethod(@NonNull Method method, @Nullable Object thisObject, @NonNull Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        try {
            return ZygiskHookBridge.invokeOriginal(method, thisObject, args);
        } catch (InvocationTargetException e) {
            throw e;
        } catch (IllegalAccessException | IllegalArgumentException | NullPointerException e) {
            throw e;
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    @Override
    public <T> void invokeOriginalConstructor(@NonNull Constructor<T> ctor, @NonNull T thisObject, @NonNull Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        try {
            ZygiskHookBridge.invokeOriginal(ctor, thisObject, args);
        } catch (InvocationTargetException e) {
            throw e;
        } catch (IllegalAccessException | IllegalArgumentException | NullPointerException e) {
            throw e;
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T> T newInstanceOrigin(@NonNull Constructor<T> constructor, @NonNull Object... args)
            throws InvocationTargetException, IllegalArgumentException, IllegalAccessException, InstantiationException {
        try {
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw e;
        } catch (Throwable t) {
            throw new InvocationTargetException(t);
        }
    }

    @Override
    public long getHookCounter() {
        return ZygiskHookBridge.getHookedMethodCount();
    }

    @NonNull
    @Override
    public Set<Member> getHookedMethods() {
        return new LinkedHashSet<>(ZygiskHookBridge.getHookedMethods());
    }

    // --------------------------------------------------------------------------------
    // internals
    // --------------------------------------------------------------------------------

    /**
     * Adapts an {@link IMemberHookCallback} to the two-phase interface used by the bridge.
     */
    private static final class CallbackAdapter
            implements ZygiskHookBridge.BeforeCallback, ZygiskHookBridge.AfterCallback {

        private final IMemberHookCallback mCallback;

        CallbackAdapter(IMemberHookCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onBefore(ZygiskHookBridge.HookParamState state) throws Throwable {
            mCallback.beforeHookedMember(new ParamAdapter(state));
        }

        @Override
        public void onAfter(ZygiskHookBridge.HookParamState state) throws Throwable {
            mCallback.afterHookedMember(new ParamAdapter(state));
        }
    }

    /**
     * Adapts the bridge's mutable state object to {@link IMemberHookParam}.
     */
    private static final class ParamAdapter implements IMemberHookParam {

        private final ZygiskHookBridge.HookParamState mState;

        ParamAdapter(ZygiskHookBridge.HookParamState state) {
            mState = state;
        }

        @NonNull
        @Override
        public Member getMember() {
            return mState.member;
        }

        @Nullable
        @Override
        public Object getThisObject() {
            return Modifier.isStatic(mState.member.getModifiers()) ? null : mState.rawReceiver;
        }

        @NonNull
        @Override
        public Object[] getArgs() {
            return mState.args;
        }

        @Nullable
        @Override
        public Object getResult() {
            return mState.result;
        }

        @Override
        public void setResult(@Nullable Object result) {
            mState.result = result;
            mState.throwable = null;
            mState.earlyReturn = true;
        }

        @Nullable
        @Override
        public Throwable getThrowable() {
            return mState.throwable;
        }

        @Override
        public void setThrowable(@NonNull Throwable throwable) {
            mState.throwable = throwable;
            mState.result = null;
            mState.earlyReturn = true;
        }

        @Nullable
        @Override
        public Object getExtra() {
            return mState.getExtra();
        }

        @Override
        public void setExtra(@Nullable Object extra) {
            mState.setExtra(extra);
        }
    }

    /**
     * Handle returned by {@link #hookMethod}; removing it drops both hook phases.
     */
    private static final class UnhookHandle implements MemberUnhookHandle {

        private final Member mMember;
        private final ZygiskHookBridge.CallbackEntry mOwner;

        UnhookHandle(Member member, ZygiskHookBridge.CallbackEntry owner) {
            mMember = member;
            mOwner = owner;
        }

        @NonNull
        @Override
        public Member getMember() {
            return mMember;
        }

        @NonNull
        @Override
        public IMemberHookCallback getCallback() {
            Object cb = mOwner.callback;
            if (cb instanceof CallbackAdapter) {
                return ((CallbackAdapter) cb).mCallback;
            }
            throw new IllegalStateException("unexpected callback type: " + cb);
        }

        @Override
        public boolean isHookActive() {
            return mOwner.active;
        }

        @Override
        public void unhook() {
            mOwner.active = false;
            ZygiskHookBridge.removeRegistrations(mMember, mOwner);
        }
    }
}

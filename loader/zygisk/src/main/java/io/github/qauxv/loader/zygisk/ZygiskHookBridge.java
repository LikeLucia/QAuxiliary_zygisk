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

import com.android.dx.Code;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Local;
import com.android.dx.MethodId;
import com.android.dx.TypeId;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import dalvik.system.DexFile;
import dalvik.system.InMemoryDexClassLoader;

/**
 * The ART method hook engine used when QAuxiliary runs as a Zygisk module.
 *
 * <p>There is no Xposed framework in a Zygisk-injected process, so hooking is done
 * directly on ART: for every hooked member a pair of synthetic methods is generated
 * with DexMaker:
 *
 * <ul>
 *     <li>{@code bridge} — has the same signature as the hooked member. Its body packs
 *         the arguments into an {@code Object[]} and calls {@link #dispatch(long, Object, Object[])}.</li>
 *     <li>{@code backup} — a placeholder whose {@code ArtMethod} is overwritten with the
 *         original contents of the hooked member, so calling it runs the unhooked code.</li>
 * </ul>
 *
 * <p>Install then redirects the hooked member's entry point into the bridge, which
 * makes every invocation land in {@link #dispatch} where the registered callbacks run.
 *
 * <p>Callback ordering follows Xposed semantics: {@code before} callbacks run in
 * descending priority order, {@code after} callbacks run in ascending order (i.e. the
 * reverse). Setting a result or a throwable in {@code before} skips the original method
 * but does <em>not</em> skip the {@code after} callbacks, matching XposedBridge.
 */
@Keep
public final class ZygiskHookBridge {

    private static final String TAG = "QAuxv.ZygiskHookBridge";

    private ZygiskHookBridge() {
    }

    // --------------------------------------------------------------------------------
    // native methods, registered by jni_bridge.cpp
    // --------------------------------------------------------------------------------

    static native long nativeGetArtMethod(@NonNull Executable executable);

    static native int nativeHookMethod(long targetArt, long backupArt, long bridgeArt);

    static native int nativeUnhookMethod(long targetArt, long backupArt);

    static native boolean nativeTrustDexFile(@NonNull DexFile dexFile);

    static native Object nativeInvokeBackup(@NonNull Method backupMethod, @Nullable Object thisObject, @NonNull Object[] args);

    // --------------------------------------------------------------------------------
    // hook registry
    // --------------------------------------------------------------------------------

    enum Mode {
        BEFORE,
        AFTER
    }

    /**
     * One callback registration. A single {@code IHookBridge.IMemberHookCallback} produces
     * two registrations (BEFORE + AFTER) that share the same {@link HookParamState}.
     */
    static final class Registration {

        final int priority;
        final Mode mode;
        final CallbackEntry owner;

        Registration(int priority, Mode mode, CallbackEntry owner) {
            this.priority = priority;
            this.mode = mode;
            this.owner = owner;
        }
    }

    /**
     * Groups the BEFORE/AFTER pair created for one {@code IMemberHookCallback} so that
     * unhooking removes both, and so that extra data is shared between the two phases.
     */
    static final class CallbackEntry {

        final Object callback;
        final Object extraSlot;
        volatile boolean active = true;

        CallbackEntry(Object callback) {
            this.callback = callback;
            this.extraSlot = new Object[1];
        }
    }

    static final class HookEntry {

        final Member member;
        final Method backupMethod;
        final CopyOnWriteArrayList<Registration> callbacks = new CopyOnWriteArrayList<>();

        HookEntry(Member member, Method backupMethod) {
            this.member = member;
            this.backupMethod = backupMethod;
        }
    }

    private static final ConcurrentHashMap<Long, HookEntry> sHooks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Member, Long> sMemberToHookId = new ConcurrentHashMap<>();
    private static final AtomicLong sBridgeCounter = new AtomicLong(0);

    private static final Comparator<Registration> PRIORITY_DESC =
            (a, b) -> Integer.compare(b.priority, a.priority);

    // --------------------------------------------------------------------------------
    // hook installation
    // --------------------------------------------------------------------------------

    /**
     * Installs a hook on {@code member} unless one is already installed.
     *
     * @return the hook entry, or null if the member cannot be hooked by the native engine
     */
    @Nullable
    static HookEntry getOrCreateHookEntry(@NonNull Member member) {
        Long existing = sMemberToHookId.get(member);
        if (existing != null) {
            return sHooks.get(existing);
        }
        synchronized (ZygiskHookBridge.class) {
            existing = sMemberToHookId.get(member);
            if (existing != null) {
                return sHooks.get(existing);
            }
            if (!(member instanceof Executable)) {
                Log.e(TAG, "only Method and Constructor can be hooked: " + member);
                return null;
            }
            Executable target = (Executable) member;
            long targetArt = nativeGetArtMethod(target);
            if (targetArt == 0L) {
                Log.e(TAG, "failed to get ArtMethod for " + member);
                return null;
            }
            BridgePair pair;
            try {
                pair = generateBridgePair(target);
            } catch (Throwable t) {
                Log.e(TAG, "failed to generate bridge for " + member, t);
                return null;
            }
            long bridgeArt = nativeGetArtMethod(pair.bridgeMethod);
            long backupArt = nativeGetArtMethod(pair.backupMethod);
            if (bridgeArt == 0L || backupArt == 0L) {
                Log.e(TAG, "failed to get ArtMethod for generated bridge/backup of " + member);
                return null;
            }
            long hookId = sBridgeCounter.getAndIncrement();
            pair.setHookId(hookId);
            int rc = nativeHookMethod(targetArt, backupArt, bridgeArt);
            if (rc != 0) {
                Log.e(TAG, "nativeHookMethod failed (" + rc + ") for " + member + " - hook skipped");
                return null;
            }
            Log.d(TAG, "hooked #" + hookId + " " + member);
            HookEntry entry = new HookEntry(member, pair.backupMethod);
            sHooks.put(hookId, entry);
            sMemberToHookId.put(member, hookId);
            return entry;
        }
    }

    static void removeRegistrations(@NonNull Member member, @NonNull CallbackEntry owner) {
        Long hookId = sMemberToHookId.get(member);
        if (hookId == null) {
            return;
        }
        HookEntry entry = sHooks.get(hookId);
        if (entry == null) {
            return;
        }
        List<Registration> toRemove = new ArrayList<>(2);
        for (Registration r : entry.callbacks) {
            if (r.owner == owner) {
                toRemove.add(r);
            }
        }
        entry.callbacks.removeAll(toRemove);
        if (entry.callbacks.isEmpty()) {
            // nobody is listening any more: restore the original method
            long targetArt = nativeGetArtMethod((Executable) member);
            long backupArt = nativeGetArtMethod(entry.backupMethod);
            if (targetArt != 0L && backupArt != 0L && nativeUnhookMethod(targetArt, backupArt) == 0) {
                sHooks.remove(hookId);
                sMemberToHookId.remove(member);
            }
        }
    }

    // --------------------------------------------------------------------------------
    // dispatch
    // --------------------------------------------------------------------------------

    /**
     * Called by the generated bridge methods.
     */
    @Keep
    @SuppressWarnings("unused")
    public static Object dispatch(long hookId, Object thisObject, Object[] args) {
        HookEntry entry = sHooks.get(hookId);
        if (entry == null) {
            // Race with unhook: the entry is gone but the trampoline entry point may
            // still be hit. Throwing here would crash through the naked trampoline
            // frame, so return silently like the original method would have.
            Log.w(TAG, "dispatch: no entry for hookId=" + hookId + " (unhooked?)");
            return null;
        }
        if (args == null) {
            args = new Object[0];
        }
        HookParamState param = new HookParamState(entry, thisObject, args);
        List<Registration> snapshot = new ArrayList<>(entry.callbacks);
        Collections.sort(snapshot, PRIORITY_DESC);

        // ---- before phase, descending priority ----
        for (Registration reg : snapshot) {
            if (reg.mode != Mode.BEFORE || !reg.owner.active) {
                continue;
            }
            param.currentOwner = reg.owner;
            try {
                ((BeforeCallback) reg.owner.callback).onBefore(param);
            } catch (Throwable t) {
                Log.e(TAG, "before callback failed for " + entry.member, t);
                param.resetAfterBeforeFailure();
            }
            if (param.earlyReturn) {
                break;
            }
        }

        // ---- invoke the original method ----
        if (!param.earlyReturn) {
            try {
                Object self = Modifier.isStatic(entry.member.getModifiers()) ? null : param.rawReceiver;
                param.result = invokeBackup(entry, self, param.args);
            } catch (InvocationTargetException e) {
                param.throwable = e.getTargetException() != null ? e.getTargetException() : e;
            } catch (Throwable t) {
                param.throwable = t;
            }
        }

        // ---- after phase, ascending priority (reverse of before) ----
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Registration reg = snapshot.get(i);
            if (reg.mode != Mode.AFTER || !reg.owner.active) {
                continue;
            }
            param.currentOwner = reg.owner;
            Object savedResult = param.result;
            Throwable savedThrowable = param.throwable;
            try {
                ((AfterCallback) reg.owner.callback).onAfter(param);
            } catch (Throwable t) {
                Log.e(TAG, "after callback failed for " + entry.member, t);
                // restore the state this callback saw so the remaining ones still work
                param.result = savedResult;
                param.throwable = savedThrowable;
            }
        }

        Throwable finalThrowable = param.throwable;
        Object finalResult = sanitizeResult(entry.member, param.result);
        param.recycle();
        if (finalThrowable != null) {
            throw sneakilyThrow(finalThrowable);
        }
        return finalResult;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakilyThrow(Throwable t) throws T {
        throw (T) t;
    }

    private static Object invokeBackup(HookEntry entry, Object thisObject, Object[] args) throws Throwable {
        return nativeInvokeBackup(entry.backupMethod, thisObject, args);
    }

    /**
     * Invokes the original (unhooked) member, used for {@code IHookBridge.invokeOriginalMethod}.
     */
    @Nullable
    static Object invokeOriginal(@NonNull Member member, @Nullable Object thisObject, @NonNull Object[] args) throws Throwable {
        Long hookId = sMemberToHookId.get(member);
        if (hookId == null) {
            throw new IllegalArgumentException("member is not hooked: " + member);
        }
        HookEntry entry = sHooks.get(hookId);
        if (entry == null) {
            throw new IllegalArgumentException("member is not hooked: " + member);
        }
        Object self = Modifier.isStatic(member.getModifiers()) ? null : thisObject;
        return invokeBackup(entry, self, args);
    }

    private static Object sanitizeResult(Member member, Object value) {
        if (value == null) {
            return null;
        }
        Class<?> returnType;
        if (member instanceof Method) {
            returnType = ((Method) member).getReturnType();
        } else if (member instanceof Constructor) {
            returnType = Void.TYPE;
        } else {
            return value;
        }
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType.isPrimitive()) {
            if (returnType == Boolean.TYPE) {
                return value instanceof Boolean ? value : Boolean.FALSE;
            } else if (returnType == Integer.TYPE) {
                return value instanceof Number ? ((Number) value).intValue() : 0;
            } else if (returnType == Long.TYPE) {
                return value instanceof Number ? ((Number) value).longValue() : 0L;
            } else if (returnType == Float.TYPE) {
                return value instanceof Number ? ((Number) value).floatValue() : 0f;
            } else if (returnType == Double.TYPE) {
                return value instanceof Number ? ((Number) value).doubleValue() : 0.0d;
            } else if (returnType == Byte.TYPE) {
                return value instanceof Number ? ((Number) value).byteValue() : (byte) 0;
            } else if (returnType == Short.TYPE) {
                return value instanceof Number ? ((Number) value).shortValue() : (short) 0;
            } else if (returnType == Character.TYPE) {
                return value instanceof Character ? value : '\0';
            }
            return value;
        }
        return value;
    }

    // --------------------------------------------------------------------------------
    // hook param
    // --------------------------------------------------------------------------------

    interface BeforeCallback {
        void onBefore(HookParamState param) throws Throwable;
    }

    interface AfterCallback {
        void onAfter(HookParamState param) throws Throwable;
    }

    static final class HookParamState {

        final Member member;
        final Object rawReceiver;
        Object[] args;
        Object result;
        Throwable throwable;
        boolean earlyReturn;
        CallbackEntry currentOwner;

        HookParamState(HookEntry entry, Object rawReceiver, Object[] args) {
            this.member = entry.member;
            this.rawReceiver = rawReceiver;
            this.args = args;
        }

        Object getExtra() {
            return currentOwner != null ? ((Object[]) currentOwner.extraSlot)[0] : null;
        }

        void setExtra(Object extra) {
            if (currentOwner != null) {
                ((Object[]) currentOwner.extraSlot)[0] = extra;
            }
        }

        void resetAfterBeforeFailure() {
            result = null;
            throwable = null;
            earlyReturn = false;
        }

        void recycle() {
            earlyReturn = false;
            currentOwner = null;
        }
    }

    // --------------------------------------------------------------------------------
    // DexMaker bridge/backup generation
    // --------------------------------------------------------------------------------

    private static final class BridgePair {

        final Method bridgeMethod;
        final Method backupMethod;
        private final java.lang.reflect.Field hookIdField;

        BridgePair(Method bridgeMethod, Method backupMethod, java.lang.reflect.Field hookIdField) {
            this.bridgeMethod = bridgeMethod;
            this.backupMethod = backupMethod;
            this.hookIdField = hookIdField;
        }

        /**
         * Must be called before {@code nativeHookMethod}: the generated bridge reads this
         * static field to know which hook entry to dispatch to.
         */
        void setHookId(long hookId) {
            try {
                hookIdField.setLong(null, hookId);
            } catch (IllegalAccessException e) {
                // cannot happen: the field was made accessible right after we generated it
                throw new AssertionError("unable to write hookId", e);
            }
        }
    }

    private static final TypeId<Object> OBJECT_TYPE = TypeId.OBJECT;

    @SuppressWarnings("unchecked")
    private static final TypeId<Object[]> OBJECT_ARRAY_TYPE =
            (TypeId<Object[]>) (TypeId<?>) TypeId.get("[Ljava/lang/Object;");

    /**
     * DexMaker's {@code TypeId<T>} is invariant, which makes wildcard-captured types awkward
     * to thread through the code generator. Every descriptor we build is erased to
     * {@code TypeId<Object>} instead; the dex format does not care about the Java-level
     * parameterisation.
     */
    @SuppressWarnings("unchecked")
    private static TypeId<Object> asObjectTypeId(TypeId<?> type) {
        return (TypeId<Object>) (TypeId<?>) type;
    }

    private static BridgePair generateBridgePair(Executable target) throws Exception {
        Class<?>[] targetParams = target.getParameterTypes();
        Class<?> targetReturn = target instanceof Method
                ? ((Method) target).getReturnType()
                : Void.TYPE;

        String suffix = Long.toHexString(sBridgeCounter.incrementAndGet());
        String fqn = "io.github.qauxv.loader.zygisk.HkBr" + suffix;
        String descriptor = "L" + fqn.replace('.', '/') + ";";

        DexMaker dm = new DexMaker();
        TypeId<Object> classId = asObjectTypeId(TypeId.get(descriptor));
        dm.declare(classId, fqn, Modifier.PUBLIC, TypeId.OBJECT);

        // static long hookId
        FieldId<Object, Long> hookIdField = classId.getField(TypeId.LONG, "hookId");
        dm.declare(hookIdField, Modifier.PUBLIC | Modifier.STATIC, 0L);

        // ZygiskHookBridge.dispatch(long, Object, Object[]) -> Object
        TypeId<Object> bridgeType = asObjectTypeId(TypeId.get("Lio/github/qauxv/loader/zygisk/ZygiskHookBridge;"));
        MethodId<Object, Object> dispatchMethod = bridgeType.getMethod(
                OBJECT_TYPE, "dispatch", TypeId.LONG, OBJECT_TYPE, OBJECT_ARRAY_TYPE);

        boolean isStatic = target instanceof Method && Modifier.isStatic(target.getModifiers());

        // Reference parameters are always declared as Object: the generated class lives in
        // the module's class loader and must not reference host-private types.
        // Primitive parameters keep their exact type.
        Class<?>[] dexParamClasses = new Class<?>[targetParams.length];
        for (int i = 0; i < targetParams.length; i++) {
            dexParamClasses[i] = targetParams[i].isPrimitive() ? targetParams[i] : Object.class;
        }
        Class<?> dexReturnClass;
        if (targetReturn == Void.TYPE || targetReturn.isPrimitive()) {
            dexReturnClass = targetReturn;
        } else {
            dexReturnClass = Object.class;
        }

        TypeId<Object>[] allParamTypes = new TypeId[targetParams.length];
        for (int i = 0; i < targetParams.length; i++) {
            allParamTypes[i] = typeIdOf(dexParamClasses[i]);
        }
        TypeId<Object> returnType = typeIdOf(dexReturnClass);

        BoxInfo[] paramBoxes = new BoxInfo[targetParams.length];
        for (int i = 0; i < targetParams.length; i++) {
            paramBoxes[i] = boxInfo(typeIdOf(targetParams[i]));
        }
        BoxInfo returnBox = boxInfo(typeIdOf(targetReturn));

        declareBridgeOrBackup(dm, classId, hookIdField, dispatchMethod, "bridge", false,
                isStatic, allParamTypes, returnType, paramBoxes, returnBox);
        declareBridgeOrBackup(dm, classId, hookIdField, dispatchMethod, "backup", true,
                isStatic, allParamTypes, returnType, paramBoxes, returnBox);

        byte[] dexBytes = dm.generate();
        ClassLoader parentLoader = ZygiskHookBridge.class.getClassLoader();
        if (parentLoader == null) {
            parentLoader = ClassLoader.getSystemClassLoader();
        }
        Class<?> generated = new InMemoryDexClassLoader(ByteBuffer.wrap(dexBytes), parentLoader).loadClass(fqn);

        Method bridgeMethod = generated.getDeclaredMethod("bridge", dexParamClasses);
        Method backupMethod = generated.getDeclaredMethod("backup", dexParamClasses);
        bridgeMethod.setAccessible(true);
        backupMethod.setAccessible(true);
        java.lang.reflect.Field hookIdStaticField = generated.getDeclaredField("hookId");
        hookIdStaticField.setAccessible(true);

        return new BridgePair(bridgeMethod, backupMethod, hookIdStaticField);
    }

    private static void declareBridgeOrBackup(
            DexMaker dm,
            TypeId<Object> classId,
            FieldId<Object, Long> hookIdField,
            MethodId<Object, Object> dispatchMethod,
            String name,
            boolean isBackup,
            boolean isStatic,
            TypeId<Object>[] allParamTypes,
            TypeId<Object> returnType,
            BoxInfo[] paramBoxes,
            BoxInfo returnBox
    ) {
        MethodId<Object, Object> mid = classId.getMethod(returnType, name, allParamTypes);
        int modifiers = Modifier.PUBLIC | (isBackup || isStatic ? Modifier.STATIC : 0);
        Code code = dm.declare(mid, modifiers);

        if (isBackup) {
            // The body never runs: the native layer overwrites this ArtMethod with the
            // original contents of the hooked member.
            @SuppressWarnings("unchecked")
            TypeId<UnsupportedOperationException> usoeType = (TypeId<UnsupportedOperationException>) (TypeId<?>)
                    TypeId.get("Ljava/lang/UnsupportedOperationException;");
            Local<UnsupportedOperationException> exLocal = code.newLocal(usoeType);
            Local<String> msgLocal = code.newLocal(TypeId.STRING);
            code.loadConstant(msgLocal, "backup not initialized");
            MethodId<UnsupportedOperationException, Void> ctor = usoeType.getConstructor(TypeId.STRING);
            code.newInstance(exLocal, ctor, msgLocal);
            code.throwValue(exLocal);
            return;
        }

        Local<Object> receiver = isStatic ? null : code.getThis(classId);
        Local<Object>[] parameterLocals = new Local[allParamTypes.length];
        for (int i = 0; i < allParamTypes.length; i++) {
            parameterLocals[i] = code.getParameter(i, allParamTypes[i]);
        }
        Local<Long> hookIdLocal = code.newLocal(TypeId.LONG);
        Local<Object> selfLocal = code.newLocal(OBJECT_TYPE);
        Local<Integer> sizeLocal = code.newLocal(TypeId.INT);
        Local<Object[]> argsLocal = code.newLocal(OBJECT_ARRAY_TYPE);
        Local<Integer>[] indexLocals = new Local[allParamTypes.length];
        Local<Object>[] argumentLocals = new Local[allParamTypes.length];
        for (int i = 0; i < allParamTypes.length; i++) {
            indexLocals[i] = code.newLocal(TypeId.INT);
            argumentLocals[i] = code.newLocal(paramBoxes[i] != null ? paramBoxes[i].wrapperType : OBJECT_TYPE);
        }
        Local<Object> rawResultLocal = code.newLocal(OBJECT_TYPE);
        Local<Object> returnWrapperLocal = returnBox != null ? code.newLocal(returnBox.wrapperType) : null;
        Local<Object> primitiveResultLocal = returnBox != null ? code.newLocal(returnBox.primitiveType) : null;

        // 1. read hookId
        code.sget(hookIdField, hookIdLocal);

        // 2. self (null for static methods)
        if (receiver == null) {
            code.loadConstant(selfLocal, null);
        } else {
            code.cast(selfLocal, receiver);
        }

        // 3. new Object[paramCount]
        code.loadConstant(sizeLocal, allParamTypes.length);
        code.newArray(argsLocal, sizeLocal);

        // 4. box and store args[i]
        for (int i = 0; i < allParamTypes.length; i++) {
            Local<Integer> idxLocal = indexLocals[i];
            Local<Object> argumentLocal = argumentLocals[i];
            code.loadConstant(idxLocal, i);
            BoxInfo bi = paramBoxes[i];
            if (bi != null) {
                code.invokeStatic(bi.valueOfMethod, argumentLocal, parameterLocals[i]);
            } else {
                code.cast(argumentLocal, parameterLocals[i]);
            }
            code.aput(argsLocal, idxLocal, argumentLocal);
        }

        // 5. dispatch(hookId, self, args) -> Object
        code.invokeStatic(dispatchMethod, rawResultLocal, hookIdLocal, selfLocal, argsLocal);

        // 6. return, unboxing when needed
        if (returnType.equals(TypeId.VOID)) {
            code.returnVoid();
        } else if (returnBox != null) {
            code.cast(returnWrapperLocal, rawResultLocal);
            code.invokeVirtual(returnBox.unboxMethod, primitiveResultLocal, returnWrapperLocal);
            code.returnValue(primitiveResultLocal);
        } else {
            code.returnValue(rawResultLocal);
        }
    }

    // --------------------------------------------------------------------------------
    // primitive boxing helpers
    // --------------------------------------------------------------------------------

    private static final class BoxInfo {

        final TypeId<Object> wrapperType;
        final TypeId<Object> primitiveType;
        final MethodId<Object, Object> valueOfMethod;
        final MethodId<Object, Object> unboxMethod;

        BoxInfo(TypeId<Object> wrapperType, TypeId<Object> primitiveType,
                MethodId<Object, Object> valueOfMethod, MethodId<Object, Object> unboxMethod) {
            this.wrapperType = wrapperType;
            this.primitiveType = primitiveType;
            this.valueOfMethod = valueOfMethod;
            this.unboxMethod = unboxMethod;
        }
    }

    private static TypeId<Object> typeIdOf(Class<?> clazz) {
        if (clazz == Integer.TYPE) {
            return asObjectTypeId(TypeId.INT);
        } else if (clazz == Long.TYPE) {
            return asObjectTypeId(TypeId.LONG);
        } else if (clazz == Boolean.TYPE) {
            return asObjectTypeId(TypeId.BOOLEAN);
        } else if (clazz == Byte.TYPE) {
            return asObjectTypeId(TypeId.BYTE);
        } else if (clazz == Character.TYPE) {
            return asObjectTypeId(TypeId.CHAR);
        } else if (clazz == Short.TYPE) {
            return asObjectTypeId(TypeId.SHORT);
        } else if (clazz == Float.TYPE) {
            return asObjectTypeId(TypeId.FLOAT);
        } else if (clazz == Double.TYPE) {
            return asObjectTypeId(TypeId.DOUBLE);
        } else if (clazz == Void.TYPE) {
            return asObjectTypeId(TypeId.VOID);
        }
        return asObjectTypeId(TypeId.get(clazz));
    }

    private static BoxInfo boxInfo(TypeId<Object> primitiveType) {
        Class<?> wrapperClass;
        String unboxName;
        if (primitiveType.equals(TypeId.INT)) {
            wrapperClass = Integer.class;
            unboxName = "intValue";
        } else if (primitiveType.equals(TypeId.LONG)) {
            wrapperClass = Long.class;
            unboxName = "longValue";
        } else if (primitiveType.equals(TypeId.BOOLEAN)) {
            wrapperClass = Boolean.class;
            unboxName = "booleanValue";
        } else if (primitiveType.equals(TypeId.BYTE)) {
            wrapperClass = Byte.class;
            unboxName = "byteValue";
        } else if (primitiveType.equals(TypeId.CHAR)) {
            wrapperClass = Character.class;
            unboxName = "charValue";
        } else if (primitiveType.equals(TypeId.SHORT)) {
            wrapperClass = Short.class;
            unboxName = "shortValue";
        } else if (primitiveType.equals(TypeId.FLOAT)) {
            wrapperClass = Float.class;
            unboxName = "floatValue";
        } else if (primitiveType.equals(TypeId.DOUBLE)) {
            wrapperClass = Double.class;
            unboxName = "doubleValue";
        } else {
            return null;
        }
        TypeId<Object> wrapperType = asObjectTypeId(TypeId.get(wrapperClass));
        return new BoxInfo(
                wrapperType,
                primitiveType,
                wrapperType.getMethod(wrapperType, "valueOf", primitiveType),
                wrapperType.getMethod(primitiveType, unboxName)
        );
    }

    // --------------------------------------------------------------------------------
    // diagnostics
    // --------------------------------------------------------------------------------

    static int getHookedMethodCount() {
        return sMemberToHookId.size();
    }

    static List<Member> getHookedMethods() {
        return new ArrayList<>(sMemberToHookId.keySet());
    }
}

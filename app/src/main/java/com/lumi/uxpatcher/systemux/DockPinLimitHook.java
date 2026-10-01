package com.lumi.uxpatcher.systemux;

import android.util.Log;

import com.lumi.uxpatcher.Prefs;
import com.lumi.uxpatcher.firmware.DexScanner;
import com.lumi.uxpatcher.firmware.FirmwareNames;
import com.lumi.uxpatcher.firmware.StructuralResolvers;
import com.lumi.uxpatcher.firmware.StructuralResolvers.PinStore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.DOCK_PIN_LIMIT;
import static com.lumi.uxpatcher.Config.TAG;

/** Raises the dock's max-pins value in the pin store, found by shape ({@link StructuralResolvers#findPinStore}). Log: DOCK-MAX */
public final class DockPinLimitHook {
    private DockPinLimitHook() {}

    private static final Set<Class<?>> HOOKED =
            Collections.synchronizedSet(new HashSet<Class<?>>());

    public static void install(final LoadPackageParam lpparam) {
        try {
            final Class<?> binder = FirmwareNames.load(lpparam.classLoader, FirmwareNames.PIN_BINDER);
            final PinStore store = StructuralResolvers.findPinStore(binder);
            if (store == null) {
                Log.e(TAG, "DOCK-MAX: pin-store interface not found on binder -- hook not installed");
                return;
            }

            // 1) Hook the store implementations listed in the dex, so the limit is raised before anyone asks.
            int fromDex = 0;
            try {
                for (String name : DexScanner.findImplementers(lpparam.appInfo, store.iface.getName())) {
                    try {
                        hookMaxPins(Class.forName(name, false, lpparam.classLoader), store.maxMethod);
                        fromDex++;
                    } catch (Throwable t) {
                        Log.w(TAG, "DOCK-MAX: dex implementer " + name + " not hookable: " + t);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "DOCK-MAX: dex scan failed: " + t);
            }
            Log.i(TAG, "DOCK-MAX: dex scan found " + fromDex + " implementation(s) of "
                    + store.iface.getName());

            // 2) Fallback: find the live store on the binder (constructor args and fields, or on any binder call if injected later).
            final XC_MethodHook scan = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    findAndHookStore(binder, store, param.thisObject, null);
                }
            };
            XposedBridge.hookAllConstructors(binder, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    findAndHookStore(binder, store, param.thisObject, param.args);
                }
            });
            int armed = 0;
            for (Method m : binder.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || Modifier.isAbstract(m.getModifiers())) continue;
                try { XposedBridge.hookMethod(m, scan); armed++; } catch (Throwable ignored) { }
            }
            Log.i(TAG, "DOCK-MAX: installed (iface=" + store.iface.getName()
                    + ", max method=" + store.maxMethod + ", limit=" + DOCK_PIN_LIMIT
                    + ", store scan armed on " + armed + " binder methods)");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "DOCK-MAX: binder class not found -- firmware may have moved it");
        } catch (Throwable t) {
            Log.e(TAG, "DOCK-MAX: install failed", t);
        }
    }

    private static void findAndHookStore(Class<?> binder, PinStore store, Object binderInstance,
                                         Object[] ctorArgs) {
        try {
            if (!HOOKED.isEmpty()) return;     // already done
            if (ctorArgs != null) {
                for (Object a : ctorArgs) {
                    if (a != null && store.iface.isInstance(a)) {
                        hookMaxPins(a.getClass(), store.maxMethod);
                        return;
                    }
                }
            }
            for (Class<?> c = binder; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(binderInstance);
                    if (v != null && store.iface.isInstance(v)) {
                        hookMaxPins(v.getClass(), store.maxMethod);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "DOCK-MAX: store scan failed", t);
        }
    }

    /** max() feeds the can-pin gate, save() and the loader, so raising it fixes all of them. */
    private static void hookMaxPins(Class<?> impl, String maxMethod) {
        if (!HOOKED.add(impl)) return;
        try {
            Method m = null;
            for (Class<?> c = impl; c != null && m == null && c != Object.class; c = c.getSuperclass()) {
                try { m = c.getDeclaredMethod(maxMethod); } catch (NoSuchMethodException ignored) { }
            }
            if (m == null) throw new NoSuchMethodException(maxMethod);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    // "Raise pin limit" switch, read per call
                    if (r instanceof Integer && (Integer) r < DOCK_PIN_LIMIT && Prefs.pinLimit()) {
                        param.setResult(DOCK_PIN_LIMIT);
                        r = DOCK_PIN_LIMIT;
                    }
                    if (r instanceof Integer) DockStatus.publish((Integer) r);   // for the Dock Editor
                }
            });
            Log.i(TAG, "DOCK-MAX: hooked " + impl.getName() + "." + maxMethod + "() -> min "
                    + DOCK_PIN_LIMIT);
        } catch (Throwable t) {
            Log.e(TAG, "DOCK-MAX: could not hook " + impl.getName() + "." + maxMethod, t);
        }
    }
}

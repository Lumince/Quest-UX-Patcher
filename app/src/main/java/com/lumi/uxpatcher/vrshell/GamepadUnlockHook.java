package com.lumi.uxpatcher.vrshell;

import android.util.Log;

import com.lumi.uxpatcher.firmware.DexStringRefs;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Replaces VrShell's per-app gamepad-mode allowlist with a user-configurable whitelist. */
public final class GamepadUnlockHook {
    private GamepadUnlockHook() {}

    /** The anchor string we scan for — present in every firmware version with this feature. */
    private static final String ALLOWLIST_ANCHOR = "com.xbox.pwa";

    /** Settings.Global key for the user's whitelist (comma-separated package names). */
    static final String G_GAMEPAD_WHITELIST = "uxpatcher_gamepad_whitelist";

    /** Default allowed list */
    private static final Set<String> ALWAYS_ALLOWED = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.valvesoftware.steamlink",
                    "com.xbox.pwa"
            )));

    /** User-configured whitelist */
    private static volatile Set<String> sWhitelist = null;

    private static Set<String> loadWhitelist() {
        Set<String> cached = sWhitelist;
        if (cached != null) return cached;
        synchronized (GamepadUnlockHook.class) {
            if (sWhitelist != null) return sWhitelist;
            try {
                android.app.Application app = (android.app.Application)
                        de.robv.android.xposed.XposedHelpers.callStaticMethod(
                                Class.forName("android.app.ActivityThread"),
                                "currentApplication");
                if (app == null) {
                    sWhitelist = Collections.emptySet();
                    return sWhitelist;
                }
                android.content.ContentResolver cr = app.getContentResolver();
                String csv = android.provider.Settings.Global.getString(cr, G_GAMEPAD_WHITELIST);
                Set<String> s = new HashSet<>();
                if (csv != null && !csv.isEmpty()) {
                    for (String pkg : csv.split(",")) {
                        String p = pkg.trim();
                        if (!p.isEmpty()) s.add(p);
                    }
                }
                sWhitelist = Collections.unmodifiableSet(s);
                Log.i(TAG, "GAMEPAD-UNLOCK: whitelist loaded: " + sWhitelist);
            } catch (Throwable t) {
                Log.w(TAG, "GAMEPAD-UNLOCK: whitelist load failed: " + t);
                sWhitelist = Collections.emptySet();
            }
            return sWhitelist;
        }
    }

    /** Injects allowed list into VRShell */
    private static final Set<String> WHITELIST_SET = new AbstractSet<String>() {
        @Override
        public boolean contains(Object o) {
            if (!(o instanceof String)) return false;
            String pkg = (String) o;
            return ALWAYS_ALLOWED.contains(pkg) || loadWhitelist().contains(pkg);
        }

        @Override public int size()                  { return Integer.MAX_VALUE; }
        @Override public Iterator<String> iterator() { return Collections.emptyIterator(); }
    };

    public static void install(LoadPackageParam lp) {
        try {
            List<String> candidates = DexStringRefs.classesReferencing(lp.appInfo, ALLOWLIST_ANCHOR);
            if (candidates.isEmpty()) {
                Log.w(TAG, "GAMEPAD-UNLOCK: no class referencing '" + ALLOWLIST_ANCHOR
                        + "'; gamepad mode may not be present on this firmware");
                return;
            }

            for (String className : candidates) {
                try {
                    Class<?> cls = lp.classLoader.loadClass(className);
                    if (patchAllowlistField(cls, className)) return;
                } catch (ClassNotFoundException ignored) {
                } catch (Throwable t) {
                    Log.w(TAG, "GAMEPAD-UNLOCK: error patching " + className + ": " + t);
                }
            }

            Log.w(TAG, "GAMEPAD-UNLOCK: anchor class(es) found " + candidates
                    + " but no patchable static Set<String> field located");
        } catch (Throwable t) {
            Log.e(TAG, "GAMEPAD-UNLOCK: install failed", t);
        }
    }

    private static boolean patchAllowlistField(Class<?> cls, String className) throws Exception {
        for (Field f : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!Set.class.isAssignableFrom(f.getType())) continue;

            f.setAccessible(true);
            Object val = f.get(null);
            if (!(val instanceof Set)) continue;
            if (!((Set<?>) val).contains(ALLOWLIST_ANCHOR)) continue;

            f.set(null, WHITELIST_SET);

            Object after = f.get(null);
            if (after == WHITELIST_SET) {
                Log.i(TAG, "GAMEPAD-UNLOCK: patched " + className + "." + f.getName()
                        + " — gamepad mode now whitelist-controlled");
                return true;
            } else {
                Log.w(TAG, "GAMEPAD-UNLOCK: direct write rejected on " + className + "." + f.getName()
                        + ", attempting accessFlags clear");
                if (clearFinalAndSet(f)) {
                    Log.i(TAG, "GAMEPAD-UNLOCK: patched (via accessFlags) " + className + "." + f.getName());
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean clearFinalAndSet(Field f) {
        try {
            Field accessFlags = Field.class.getDeclaredField("accessFlags");
            accessFlags.setAccessible(true);
            accessFlags.setInt(f, f.getModifiers() & ~Modifier.FINAL);
            f.set(null, WHITELIST_SET);
            return f.get(null) == WHITELIST_SET;
        } catch (Throwable t) {
            Log.w(TAG, "GAMEPAD-UNLOCK: accessFlags fallback failed: " + t);
            return false;
        }
    }
}

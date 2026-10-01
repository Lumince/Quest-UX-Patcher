package com.lumi.uxpatcher.firmware;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Finds the obfuscated targets by SHAPE instead of by name */
public final class StructuralResolvers {
    private StructuralResolvers() {}

    // ── Pin store ─────────────────────────────────────────────────────────────────────────
    /** The dock's "pin store": max() / list() / save(). */
    public static final class PinStore {
        public final Class<?> iface;
        public final String maxMethod;
        PinStore(Class<?> iface, String maxMethod) { this.iface = iface; this.maxMethod = maxMethod; }
    }

    /** Shape: a binder field typed as an interface with one no-arg int method (max pins) and a no-arg ArrayList method (pinned list). */
    public static PinStore findPinStore(Class<?> binder) {
        for (Field f : binder.getDeclaredFields()) {
            Class<?> t = f.getType();
            if (!t.isInterface()) continue;
            String intName = null;
            int ints = 0;
            boolean hasList = false;
            for (Method m : t.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (m.getReturnType() == int.class) { intName = m.getName(); ints++; }
                if (m.getReturnType() == java.util.ArrayList.class) hasList = true;
            }
            if (ints == 1 && hasList) return new PinStore(t, intName);
        }
        return null;
    }

    // ── Dock scrolling ────────────────────────────────────────────────────────────────────
    /** Shape: an inner class DynamicAppsView$initializeContent$N (N = 1..8) extending LinearLayoutManager. */
    public static Class<?> findDockLayoutManager(ClassLoader cl) {
        for (int i = 1; i <= 8; i++) {
            try {
                Class<?> c = cl.loadClass(FirmwareNames.DYNAMIC_APPS_VIEW + "$initializeContent$" + i);
                Class<?> sup = c.getSuperclass();
                if (sup != null && "androidx.recyclerview.widget.LinearLayoutManager".equals(sup.getName())) {
                    return c;
                }
            } catch (ClassNotFoundException ignored) { }
        }
        return null;
    }

    /** The no-arg boolean method of {@code lm} (the canScrollHorizontally override); prefers the real name if there are several. */
    public static Method findCanScrollMethod(Class<?> lm) {
        Method first = null;
        for (Method m : lm.getDeclaredMethods()) {
            if (m.getReturnType() == boolean.class && m.getParameterTypes().length == 0) {
                if ("canScrollHorizontally".equals(m.getName())) return m;
                if (first == null) first = m;
            }
        }
        return first;
    }

    // ── String resources ──────────────────────────────────────────────────────────────────
    /** Current-locale text of every string whose default-locale text is in {@code defaults} (resource ids change per build). */
    public static Set<String> localizedStringsWithDefault(Context ctx, String... defaults) {
        Set<String> out = new HashSet<>();
        try {
            Resources cur = ctx.getResources();
            // a locale that does not exist, so Android falls back to the default strings
            Configuration cfg = new Configuration(cur.getConfiguration());
            cfg.setLocales(new LocaleList(new Locale("zz", "ZZ")));
            Resources def = ctx.createConfigurationContext(cfg).getResources();
            for (int type = 1; type <= 0x40; type++) {
                int base = 0x7f000000 | (type << 16);
                if (!isStringType(def, base)) continue;
                for (int entry = 0; entry < 0x1000; entry++) {
                    int id = base | entry;
                    try {
                        String d = def.getString(id);
                        for (String want : defaults) {
                            if (want.equals(d)) { out.add(cur.getString(id)); break; }
                        }
                    } catch (Resources.NotFoundException ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static boolean isStringType(Resources r, int base) {
        // a type's first entry id is not 0, so probe widely
        for (int entry = 0; entry < 0x400; entry++) {
            try {
                return "string".equals(r.getResourceTypeName(base | entry));
            } catch (Resources.NotFoundException ignored) { }
        }
        return false;
    }
}

package com.lumi.uxpatcher.amoled;

import android.graphics.Color;
import android.util.Log;

import com.lumi.uxpatcher.firmware.FirmwareNames;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.lumi.uxpatcher.Prefs;

import static com.lumi.uxpatcher.Config.TAG;

/** Draws the translucent "glass" background of OCPanel views as a solid fill. Used by apps and SystemUX (dock pill, Library). */
public final class PanelBackgroundHook {
    private PanelBackgroundHook() {}

    // ── OCPanelBackgroundDrawable: the translucent "glass" panel background ──

    private static final android.graphics.Paint PANEL_BLACK = new android.graphics.Paint();
    private static final android.graphics.RectF PANEL_TMP = new android.graphics.RectF();
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.lang.reflect.Field[]>
            PANEL_PATH_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean panelLogged = false;

    static {
        PANEL_BLACK.setColor(Color.BLACK);   // real colour is applied per draw (Prefs.bg())
        PANEL_BLACK.setStyle(android.graphics.Paint.Style.FILL);
        PANEL_BLACK.setAntiAlias(true);
    }

    /** Restricts the hook to some drawing (the VrShell keyboard); null = always. */
    public interface Gate { boolean active(android.graphics.drawable.Drawable d); }

    public static void install(final LoadPackageParam lpparam) {
        install(lpparam, null);
    }

    /** Replaces the drawable's draw() with a solid fill of its rounded path (no colour hook sees its gradient) */
    public static void install(final LoadPackageParam lpparam, final Gate gate) {
        for (String name : FirmwareNames.OC_PANEL_BACKGROUND) {
            try {
                Class<?> cls = lpparam.classLoader.loadClass(name);
                XposedHelpers.findAndHookMethod(cls, "draw", android.graphics.Canvas.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (gate != null && !gate.active((android.graphics.drawable.Drawable) param.thisObject)) return;
                                android.graphics.Canvas canvas =
                                        (android.graphics.Canvas) param.args[0];
                                PANEL_BLACK.setColor(Prefs.bg());
                                android.graphics.Path p = findFillPath(param.thisObject);
                                if (p != null) {
                                    canvas.drawPath(p, PANEL_BLACK);
                                    param.setResult(null);
                                    if (!panelLogged) {
                                        panelLogged = true;
                                        Log.i(TAG, "PANEL: drew solid black (path) in "
                                                + param.thisObject.getClass().getSimpleName());
                                    }
                                    return;
                                }
                                // no path found: fill the drawable's bounds
                                android.graphics.Rect b =
                                        ((android.graphics.drawable.Drawable) param.thisObject)
                                                .getBounds();
                                if (!b.isEmpty()) {
                                    canvas.drawRect(b, PANEL_BLACK);
                                    param.setResult(null);
                                    if (!panelLogged) {
                                        panelLogged = true;
                                        Log.i(TAG, "PANEL: drew solid black (bounds fallback) in "
                                                + param.thisObject.getClass().getSimpleName());
                                    }
                                }
                            }
                        });
                Log.i(TAG, "PANEL: hooked " + name + ".draw in " + lpparam.packageName);
                return;
            } catch (ClassNotFoundException ignored) {
                // try next name
            } catch (Throwable t) {
                Log.e(TAG, "PANEL: hook failed for " + name + " in " + lpparam.packageName, t);
                return;
            }
        }
    }

    /** The largest Path in the drawable's state object (found by type, names are obfuscated), or null */
    private static android.graphics.Path findFillPath(Object drawable) {
        try {
            // the state object: field "drawableState", else scan by type
            Object st = null;
            try {
                st = XposedHelpers.getObjectField(drawable, "drawableState");
            } catch (Throwable ignored) {
                // not named drawableState
            }

            if (st == null) {
                for (java.lang.reflect.Field f : drawable.getClass().getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    if (f.getType() == android.graphics.Matrix.class) continue;
                    if (f.getType() == android.graphics.Paint.class) continue;
                    f.setAccessible(true);
                    Object candidate;
                    try { candidate = f.get(drawable); } catch (Throwable ignored) { continue; }
                    if (candidate == null) continue;
                    // the state's class (or a superclass) has a Path field
                    Class<?> check = candidate.getClass();
                    boolean hasPath = false;
                    while (check != null && check != Object.class && !hasPath) {
                        for (java.lang.reflect.Field cf : check.getDeclaredFields()) {
                            if (cf.getType() == android.graphics.Path.class) {
                                hasPath = true;
                                break;
                            }
                        }
                        check = check.getSuperclass();
                    }
                    if (hasPath) { st = candidate; break; }
                }
            }

            if (st == null) return null;

            // the state's Path fields (cached): largest by area wins
            java.lang.reflect.Field[] fields = PANEL_PATH_FIELDS.get(st.getClass());
            if (fields == null) {
                java.util.ArrayList<java.lang.reflect.Field> l = new java.util.ArrayList<>();
                // include inherited fields
                Class<?> walk = st.getClass();
                while (walk != null && walk != Object.class) {
                    for (java.lang.reflect.Field f : walk.getDeclaredFields()) {
                        if (f.getType() == android.graphics.Path.class) {
                            f.setAccessible(true);
                            l.add(f);
                        }
                    }
                    walk = walk.getSuperclass();
                }
                fields = l.toArray(new java.lang.reflect.Field[0]);
                PANEL_PATH_FIELDS.put(st.getClass(), fields);
            }
            android.graphics.Path best = null;
            float bestArea = -1f;
            for (java.lang.reflect.Field f : fields) {
                android.graphics.Path p = (android.graphics.Path) f.get(st);
                if (p == null || p.isEmpty()) continue;
                p.computeBounds(PANEL_TMP, true);
                float area = PANEL_TMP.width() * PANEL_TMP.height();
                if (area > bestArea) { bestArea = area; best = p; }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }
}

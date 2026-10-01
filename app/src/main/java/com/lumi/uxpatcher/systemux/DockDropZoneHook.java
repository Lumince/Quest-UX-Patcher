package com.lumi.uxpatcher.systemux;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.firmware.DexStringRefs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Draws an "Unpin" box on the invisible drop-to-unpin layer below the dock. Logs: DROPZONE */
public final class DockDropZoneHook {
    private DockDropZoneHook() {}

    private static final String LAYER_NAME = "dropTargetLayer";
    private static boolean sLogged = false;

    // Set once the layer visibility call is hooked: then we draw only while stock shows the layer (v81 has no such call: always draw)
    static volatile boolean sGate = false;
    static volatile boolean sShown = false;    // last visibility stock gave the layer
    static volatile WeakReference<DropZoneView> sView = null;
    // Box on drag: stock's layer is 1x1, so at drag start we open a second, real-size presenter for it
    static volatile Method sOpener;
    static Object sOpenerThis;
    static Object[] sOpenerArgs;
    static int[] sOpenerIdx;
    static volatile int sBigW, sBigH;
    static volatile WeakReference<DropZoneView> sBigView;

    static void openBigBox() {
        MAIN.post(new Runnable() {
            @Override public void run() {
                try {
                    if (sBigW <= 8 || sBigH <= 8) {   // no drag seen yet: use stock's 1006x286 dp
                        sBigW = Math.round(1006 * sDensity); sBigH = Math.round(286 * sDensity);
                    }
                    if (sOpener == null || sBigW <= 8 || sBigH <= 8) {
                        Log.w(TAG, "DROPZONE: box-on-drag: nothing to open (opener=" + (sOpener != null) + " size=" + sBigW + "x" + sBigH + ")");
                        return;
                    }
                    WeakReference<DropZoneView> r = sBigView;
                    DropZoneView old = r == null ? null : r.get();
                    if (old != null && old.isAttachedToWindow()) {
                        sView = sBigView;
                        old.postInvalidate();
                        Log.i(TAG, "DROPZONE: box-on-drag: big view still attached, redrawing it");
                        return;
                    }
                    Context ctx = (Context) de.robv.android.xposed.XposedHelpers.callStaticMethod(
                            Class.forName("android.app.ActivityThread"), "currentApplication");
                    if (ctx == null) return;
                    DropZoneView big = new DropZoneView(ctx);
                    Object[] a = sOpenerArgs.clone();
                    a[sOpenerIdx[0]] = big;
                    a[sOpenerIdx[2]] = sBigW;
                    a[sOpenerIdx[3]] = sBigH;
                    sOpener.setAccessible(true);
                    sOpener.invoke(sOpenerThis, a);
                    sBigView = new WeakReference<DropZoneView>(big);
                    sView = sBigView;
                    Log.i(TAG, "DROPZONE: box-on-drag: opened a " + sBigW + "x" + sBigH + " presenter for the box");
                } catch (Throwable e) {
                    Throwable root = e;
                    while (root instanceof java.lang.reflect.InvocationTargetException && root.getCause() != null) root = root.getCause();
                    Log.w(TAG, "DROPZONE: box-on-drag failed: " + root, root);
                }
            }
        });
    }

    private static int sWarmupGen = 0;

    /** Opens the real-size presenter a few seconds after start-up, then hides the layer like a drag end does. */
    static void scheduleWarmup() {
        final int gen = ++sWarmupGen;      // a recreated activity restarts the timer
        sShown = false;
        MAIN.postDelayed(new Runnable() { @Override public void run() {
            if (gen != sWarmupGen) { Log.i(TAG, "DROPZONE: warm-up superseded by a newer layer open"); return; }
            if (sShown) return;
            WeakReference<DropZoneView> r = sBigView;
            DropZoneView old = r == null ? null : r.get();
            if (old != null && old.isAttachedToWindow()) {
                Log.i(TAG, "DROPZONE: warm-up: real-size presenter already there");
            } else {
                Log.i(TAG, "DROPZONE: warm-up: opening the real-size presenter");
                openBigBox();
            }
            // always hide, even if superseded: the layer may reopen meanwhile
            long[] at = { 700, 2500, 5000 };
            for (final long ms : at) {
                MAIN.postDelayed(new Runnable() { @Override public void run() { hideLayerIfIdle("warm-up " + ms + "ms"); } }, ms);
            }
        } }, 3500);
    }

    // ── drag end safety net ──
    // Inside a VR game stock skips its drag-end cleanup: the layer stays up and its "zone started" flag stays stuck.
    // Shortly after the drag-state message we hide the layer and clear the flag. If the controller is not found, sNeedRearm
    // makes the next drag-location event show the layer instead (see hookDragLocation).
    private static volatile boolean sNeedRearm = false;
    private static int sDragStateLogs = 0;

    private static void hookDragState(LoadPackageParam lp) {
        try {
            Class<?> c = Class.forName(
                    "com.oculus.vrshell.privateipc.DragDropIPCManager$setDragStateEventCallback$dragStateEventCallback$1",
                    false, lp.classLoader);
            for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                XposedBridge.hookMethod(k, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam q) { sCbRef = new WeakReference<Object>(q.thisObject); }
                });
            }
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!m.getName().equals("onTransact") || p.length != 4) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam q) { IN_CB.set(Boolean.TRUE); }
                    @Override protected void afterHookedMethod(MethodHookParam q) {
                        IN_CB.set(Boolean.FALSE);
                        try {
                            sCbRef = new WeakReference<Object>(q.thisObject);
                            if (!(q.args[0] instanceof Integer) || (Integer) q.args[0] != 1) return;   // code 1 = the drag state event
                            if (sDragStateLogs++ < 12) Log.i(TAG, "DROPZONE: drag state message (drop/cancel) arrived, layer shown=" + sShown);
                            MAIN.postDelayed(new Runnable() { @Override public void run() {
                                if (!sShown) { clearStaleFlags(); return; }     // already hidden
                                Log.i(TAG, "DROPZONE: stock left the drop layer up after the drag ended -> hiding it");
                                sShown = false;
                                WeakReference<DropZoneView> r = sView;
                                DropZoneView v = r == null ? null : r.get();
                                if (v != null) v.postInvalidate();
                                hideLayerIfIdle("drag ended");
                                // stock's cleanup was skipped, so its flag is stuck: clear it
                                if (clearStaleFlags() < 0) sNeedRearm = true;     // no controller: rearm on the next drag
                            } }, 400);
                        } catch (Throwable ignored) { }
                    }
                });
                Log.i(TAG, "DROPZONE: drag-end safety net on " + c.getName());
                return;
            }
        } catch (Throwable e) {
            Log.w(TAG, "DROPZONE: drag-end safety net not installed: " + e);
        }
    }

    // ── stuck "zone started" flag ──
    // The drag controller is reached from the drag-state callback. At each stock drag start (just before it sets its flag) we note
    // which of its boolean fields are false. After a drag whose cleanup was skipped, those that are true now are the stale ones.
    private static volatile WeakReference<Object> sCbRef = null;
    private static final Set<Field> sIdleFalse = java.util.Collections.synchronizedSet(new HashSet<Field>());
    private static volatile boolean sOwnVisCall = false;

    private static Object findController() {
        try {
            WeakReference<Object> cr = sCbRef;
            Object cb = cr == null ? null : cr.get();
            Object mgr = sMgr;
            if (cb == null || mgr == null) return null;
            Object o1 = null;
            for (Field f : cb.getClass().getDeclaredFields()) {
                if (f.getType() == WeakReference.class) { f.setAccessible(true); o1 = ((WeakReference<?>) f.get(cb)).get(); break; }
            }
            if (o1 == null) return null;
            java.util.List<Object> cands = new java.util.ArrayList<Object>();
            cands.add(o1);
            for (Class<?> k = o1.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (f.getType().isPrimitive() || Modifier.isStatic(f.getModifiers())) continue;
                    String tn = f.getType().getName();
                    if (tn.startsWith("java.") || tn.startsWith("android.") || tn.startsWith("kotlin.")) continue;
                    f.setAccessible(true);
                    Object v = f.get(o1);
                    if (v != null) cands.add(v);
                }
            }
            for (Object c : cands) {
                for (Class<?> k = c.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Field f : k.getDeclaredFields()) {
                        if (f.getType().isPrimitive() || Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType() == mgr.getClass()) return c;    // by type: a restart makes a new manager instance
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static java.util.List<Field> booleanFields(Object o) {
        java.util.List<Field> out = new java.util.ArrayList<Field>();
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            String kn = k.getName();
            if (kn.startsWith("java.") || kn.startsWith("android.") || kn.startsWith("kotlin.")) break;
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() == boolean.class && !Modifier.isStatic(f.getModifiers())) { f.setAccessible(true); out.add(f); }
            }
        }
        return out;
    }

    /** At a stock drag start: remember the controller's false boolean fields. */
    private static void noteIdleFlags() {
        try {
            Object c = findController();
            if (c == null) return;
            installEnvHooks(c);
            loadEnvMethod(c);
            for (Field f : booleanFields(c)) if (!f.getBoolean(c)) sIdleFalse.add(f);
        } catch (Throwable ignored) { }
    }

    // ── VR game check ──
    // Stock's drag callbacks first call a no-arg boolean on the controller, "in a VR game?". We hook every such method and keep the
    // one called inside those callbacks (IN_CB). In a game vrshell never sends the pointer to the drop layer, so we show no box.
    private static final ThreadLocal<Boolean> IN_CB = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() { return Boolean.FALSE; }
    };
    private static final Set<Class<?>> sEnvHooked = new HashSet<Class<?>>();
    private static volatile Method sEnvMethod = null;
    private static volatile boolean sInGame = false;
    private static int sGameLogs = 0;

    private static void installEnvHooks(Object controller) {
        try {
            for (Class<?> k = controller.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                String kn = k.getName();
                if (kn.startsWith("java.") || kn.startsWith("android.") || kn.startsWith("kotlin.")) break;
                synchronized (sEnvHooked) { if (!sEnvHooked.add(k)) continue; }
                int n = 0;
                for (Method m : k.getDeclaredMethods()) {
                    if (m.getParameterTypes().length != 0 || m.getReturnType() != boolean.class
                            || Modifier.isStatic(m.getModifiers()) || Modifier.isAbstract(m.getModifiers()) || n >= 30) continue;
                    n++;
                    final Method mm = m;
                    mm.setAccessible(true);
                    XposedBridge.hookMethod(mm, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam q) {
                            try {
                                if (!IN_CB.get() || !(q.getResult() instanceof Boolean)) return;
                                if (sEnvMethod != mm) { sEnvMethod = mm; saveEnvMethod(mm); }
                                boolean g = (Boolean) q.getResult();
                                if (g != sInGame || sGameLogs < 2) {
                                    sGameLogs++;
                                    Log.i(TAG, "DROPZONE: stock's home-environment check is " + mm.getDeclaringClass().getSimpleName() + "."
                                            + mm.getName() + " -> in a VR game = " + g);
                                }
                                sInGame = g;
                                if (g && Config.DOCK_DROP_ZONE_HIDE_IN_GAME) suppressBox("first drag event");
                            } catch (Throwable ignored) { }
                        }
                    });
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "DROPZONE: could not hook the home-environment check: " + e);
        }
    }

    // The check method is learned at the first drag end and saved per build, so the first grab after a restart already knows.
    private static android.app.Application currentApp() throws Exception {
        return (android.app.Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
    }

    private static String envKey() {
        try {
            android.app.Application app = currentApp();
            android.content.pm.PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return android.os.Build.FINGERPRINT + "#" + pi.getLongVersionCode() + "#" + pi.lastUpdateTime;
        } catch (Throwable e) { return null; }
    }

    private static android.content.SharedPreferences envPrefs() {
        try {
            return currentApp().getSharedPreferences("uxpatcher_dropzone", 0);
        } catch (Throwable e) { return null; }
    }

    private static void saveEnvMethod(Method m) {
        try {
            String k = envKey(); android.content.SharedPreferences sp = envPrefs();
            if (k == null || sp == null) return;
            sp.edit().putString("env_check", k + "|" + m.getDeclaringClass().getName() + "|" + m.getName()).apply();
        } catch (Throwable ignored) { }
    }

    private static void loadEnvMethod(Object controller) {
        try {
            if (sEnvMethod != null) return;
            String k = envKey(); android.content.SharedPreferences sp = envPrefs();
            String v = sp == null ? null : sp.getString("env_check", null);
            if (k == null || v == null || !v.startsWith(k + "|")) return;
            String[] parts = v.substring(k.length() + 1).split("\\|");
            if (parts.length != 2) return;
            for (Class<?> c = controller.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                if (!c.getName().equals(parts[0])) continue;
                Method m = c.getDeclaredMethod(parts[1]);
                if (m.getReturnType() != boolean.class) return;
                m.setAccessible(true);
                sEnvMethod = m;
                Log.i(TAG, "DROPZONE: home-environment check remembered from last run: " + c.getSimpleName() + "." + m.getName());
                return;
            }
        } catch (Throwable ignored) { }
    }

    /** In a VR game: hide the zone stock just raised and skip the box. */
    private static void suppressBox(final String why) {
        if (!sShown) return;
        sShown = false;
        MAIN.post(new Runnable() { @Override public void run() {
            if (sGameLogs++ < 6) Log.i(TAG, "DROPZONE: a VR game is running, unpinning is not available -> no Unpin box (" + why + ")");
            WeakReference<DropZoneView> r = sView;
            DropZoneView v = r == null ? null : r.get();
            if (v != null) v.postInvalidate();
            hideLayerIfIdle("in a VR game");
        } });
    }

    /** Asks stock's check directly, once known. */
    private static boolean inGameNow() {
        try {
            Method em = sEnvMethod;
            Object c = findController();
            if (em == null || c == null) return false;
            return (Boolean) em.invoke(c);
        } catch (Throwable e) { return false; }
    }

    private static int sNoStaleLogs = 0;

    /** Clears controller booleans that were false at a stock drag start and are true now.
     *  Returns 1 = cleared one, 0 = nothing stuck, -1 = controller not found. */
    private static int clearStaleFlags() {
        try {
            Object c = findController();
            if (c == null) { Log.w(TAG, "DROPZONE: stale-flag reset: drag controller not found"); return -1; }
            StringBuilder names = new StringBuilder();
            for (Field f : booleanFields(c)) {
                if (sIdleFalse.contains(f) && f.getBoolean(c)) {
                    f.setBoolean(c, false);
                    names.append(' ').append(f.getDeclaringClass().getSimpleName()).append('.').append(f.getName());
                }
            }
            if (names.length() == 0) {
                if (sNoStaleLogs++ < 3) Log.i(TAG, "DROPZONE: no drag flag was stuck (stock's own cleanup ran; only the layer's hidden state was not reported)");
                return 0;
            }
            Log.i(TAG, "DROPZONE: cleared the stale drag flag(s):" + names + " -> the next drag starts the zone like stock");
            return 1;
        } catch (Throwable e) {
            Log.w(TAG, "DROPZONE: stale-flag reset failed: " + e);
            return -1;
        }
    }

    // This callback also calls the game check, so mark it with IN_CB.
    private static void hookObjectDragCallback(LoadPackageParam lp) {
        try {
            Class<?> c = Class.forName(
                    "com.oculus.vrshell.privateipc.DragDropIPCManager$setObjectDragEventCallback$objectDragEventCallback$1",
                    false, lp.classLoader);
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!m.getName().equals("onTransact") || p.length != 4) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam q) { IN_CB.set(Boolean.TRUE); }
                    @Override protected void afterHookedMethod(MethodHookParam q) { IN_CB.set(Boolean.FALSE); }
                });
                return;
            }
        } catch (Throwable e) {
            Log.w(TAG, "DROPZONE: object-drag callback not hooked: " + e);
        }
    }

    /** First drag event after we hid the layer: stock's flag is stuck and will not start the zone, so show it ourselves. */
    private static void rearmLayer() {
        MAIN.post(new Runnable() { @Override public void run() {
            try {
                Object m = sMgr; Method v = sVisMethod; Method sz = sSizeMethod; Object[] lead = sSizeLead;
                if (m == null || v == null || sz == null || lead == null || sShown) return;
                Object[] a = new Object[lead.length + 4];
                System.arraycopy(lead, 0, a, 0, lead.length);
                a[lead.length] = m; a[lead.length + 1] = LAYER_NAME;
                a[lead.length + 2] = sBigW > 8 ? sBigW : Math.round(1006 * sDensity);
                a[lead.length + 3] = sBigH > 8 ? sBigH : Math.round(286 * sDensity);
                sz.invoke(null, a);
                sOwnVisCall = true;
                try { v.invoke(m, LAYER_NAME, Boolean.TRUE); } finally { sOwnVisCall = false; }
                Log.i(TAG, "DROPZONE: drag started again while stock's flag is stuck -> showed the drop layer");
            } catch (Throwable e) {
                Log.w(TAG, "DROPZONE: could not show the drop layer again: " + e);
            }
        } });
    }

    static volatile long sFlashStart = 0;      // last drag start, see onDraw
    private static final Set<Class<?>> sScanned = new HashSet<Class<?>>();
    // Layer manager and its  void (String, boolean)  visibility call
    private static volatile Object sMgr = null;
    private static volatile Field sMgrField = null;
    private static volatile WeakReference<Object> sOwnerRef = null;   // owner of the manager field
    private static volatile Method sVisMethod = null;
    private static volatile Method sSizeMethod = null;   // static void (ShapeEnum, StereoEnum, Self, String name, int w, int h)
    private static int sSizeLogged = 0;
    private static volatile float sDensity = 1.25f;
    private static volatile Object[] sSizeLead = null;   // leading enum args, all CURRENT
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Hides the layer after we open it at real size (stock opens it 1x1), or its invisible area would catch the laser. */
    static void hideLayerIfIdle(String why) {
        try {
            Object m = sMgr; Method v = sVisMethod;
            if (m == null) {   // still null while stock opens the layer at start-up: re-read
                Field mf = sMgrField; WeakReference<Object> ow = sOwnerRef; Object o = ow == null ? null : ow.get();
                if (mf != null && o != null) { try { m = mf.get(o); if (m != null) sMgr = m; } catch (Throwable ignored) {} }
            }
            if (m == null || v == null || sShown) {
                Log.i(TAG, "DROPZONE: idle drop layer not hidden (" + why + "): manager=" + (m != null) + " visCall=" + (v != null) + " dragging=" + sShown);
                return;
            }
            // Like stock's drag end: size to 1x1, then hide. Hiding alone leaves the big quad in reach of the laser.
            boolean shrunk = false;
            Method sz = sSizeMethod; Object[] lead = sSizeLead;
            if (sz != null && lead != null) {
                try {
                    // Stock probably ignores a resize to its recorded size (1x1): size to 1006x286 dp like a drag start, then to 1x1.
                    int[][] steps = { { Math.round(1006 * sDensity), Math.round(286 * sDensity) }, { 1, 1 } };
                    for (int[] st : steps) {
                        Object[] a = new Object[lead.length + 4];
                        System.arraycopy(lead, 0, a, 0, lead.length);
                        a[lead.length] = m; a[lead.length + 1] = LAYER_NAME; a[lead.length + 2] = st[0]; a[lead.length + 3] = st[1];
                        sz.invoke(null, a);
                    }
                    shrunk = true;
                } catch (Throwable e) {
                    Log.w(TAG, "DROPZONE: could not shrink the idle drop layer: " + e);
                }
            }
            v.invoke(m, LAYER_NAME, Boolean.FALSE);
            Log.i(TAG, "DROPZONE: hid the idle drop layer (" + why + (shrunk ? ", shrunk to 1x1" : ", NOT shrunk") + ")");
        } catch (Throwable e) {
            Log.w(TAG, "DROPZONE: could not hide the idle drop layer: " + e);
        }
    }

    // Notifications under the dock (Sharing to Shell, calls, media) are tracked so the box can move out of their way.
    private static final String NOTICE_PKG = "com.oculus.common.activetaskbarhelper.";
    private static final java.util.List<WeakReference<View>> sNotices = new java.util.ArrayList<WeakReference<View>>();

    private static void hookNotices() {
        try {
            XC_MethodHook h = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        View v = (View) p.thisObject;
                        if (!v.getClass().getName().startsWith(NOTICE_PKG)) return;
                        synchronized (sNotices) {
                            for (int i = sNotices.size() - 1; i >= 0; i--) {
                                View o = sNotices.get(i).get();
                                if (o == null || o == v) sNotices.remove(i);
                            }
                            if (p.method.getName().equals("onAttachedToWindow")) sNotices.add(new WeakReference<View>(v));
                        }
                        WeakReference<DropZoneView> r = sView;
                        DropZoneView z = r == null ? null : r.get();
                        if (z != null) z.postInvalidate();
                    } catch (Throwable ignored) {}
                }
            };
            XposedBridge.hookMethod(View.class.getDeclaredMethod("onAttachedToWindow"), h);
            XposedBridge.hookMethod(View.class.getDeclaredMethod("onDetachedFromWindow"), h);
        } catch (Throwable e) {
            Log.w(TAG, "DROPZONE: could not watch notifications: " + e);
        }
    }

    /** {left, right} of the notifications in layer pixels, or null. */
    static float[] noticeSpan(float layerW) {
        float l = Float.MAX_VALUE, r = -Float.MAX_VALUE;
        int[] loc = new int[2];
        synchronized (sNotices) {
            for (WeakReference<View> ref : sNotices) {
                View v = ref.get();
                if (v == null || !v.isAttachedToWindow() || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) continue;
                View root = v.getRootView();
                if (root == null || root.getWidth() <= 0) continue;
                v.getLocationInWindow(loc);
                float off = layerW / 2f - root.getWidth() / 2f;
                l = Math.min(l, loc[0] + off);
                r = Math.max(r, loc[0] + v.getWidth() + off);
            }
        }
        return r > l ? new float[]{l, r} : null;
    }

    /** Height in px of the tallest notification window, or 0. */
    static float noticeHeight() {
        float hmax = 0f;
        synchronized (sNotices) {
            for (WeakReference<View> ref : sNotices) {
                View v = ref.get();
                if (v == null || !v.isAttachedToWindow() || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) continue;
                View root = v.getRootView();
                if (root != null) hmax = Math.max(hmax, root.getHeight());
            }
        }
        return hmax;
    }

    public static void install(final LoadPackageParam lpparam) {
        if (!Config.DOCK_DROP_ZONE_X) return;
        hookNotices();
        // dex scan takes up to ~2 s: keep it off the load path
        // Hooks every void (.., View, .., String, int, int, int ..) method in classes using "dropTargetLayer"; acts only on that name with a null View.
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<String> classes = DexStringRefs.classesReferencing(lpparam.appInfo, LAYER_NAME);
                    int hooked = 0;
                    for (String cn : classes) {
                        Class<?> c;
                        try { c = Class.forName(cn, false, lpparam.classLoader); } catch (Throwable e) { continue; }
                        Method[] ms;
                        try { ms = c.getDeclaredMethods(); } catch (Throwable e) { continue; }
                        for (Method m : ms) {
                            final int[] idx = viewAndNameIndex(m);
                            if (idx == null) continue;
                            try {
                                XposedBridge.hookMethod(m, new XC_MethodHook() {
                                    @Override protected void afterHookedMethod(MethodHookParam param) {
                                        if (param.getObjectExtra("dz") == null) return;
                                        if (Config.DOCK_DROP_ZONE_BOX_ON_DRAG && Config.DOCK_DROP_ZONE_BOX_WARMUP) scheduleWarmup();
                                    }
                                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                                        try {
                                            if (!LAYER_NAME.equals(param.args[idx[1]])) return;
                                            if (param.args[idx[0]] != null) return;
                                            Context ctx = (Context) de.robv.android.xposed.XposedHelpers.callStaticMethod(
                                                    Class.forName("android.app.ActivityThread"), "currentApplication");
                                            if (ctx == null) return;
                                            DropZoneView dz = new DropZoneView(ctx);
                                            param.args[idx[0]] = dz;
                                            sView = new WeakReference<DropZoneView>(dz);
                                            param.setObjectExtra("dz", Boolean.TRUE);
                                            if (Config.DOCK_DROP_ZONE_BOX_ON_DRAG) {   // every call: the activity can be recreated
                                                Object[] cl = param.args.clone();
                                                cl[idx[0]] = null;
                                                sOpenerArgs = cl; sOpenerIdx = idx; sOpenerThis = param.thisObject;
                                                sOpener = (Method) param.method;
                                            }
                                            hookVisibility(param);
                                            if (!sLogged) {
                                                sLogged = true;
                                                Log.i(TAG, "DROPZONE: gave the drop layer an X view via "
                                                        + param.method.getDeclaringClass().getName() + "." + param.method.getName());
                                            }
                                        } catch (Throwable e) {
                                            Log.w(TAG, "DROPZONE: could not set the drop layer view: " + e);
                                        }
                                    }
                                });
                                hooked++;
                            } catch (Throwable e) {
                                Log.w(TAG, "DROPZONE: could not hook " + cn + "." + m.getName() + ": " + e);
                            }
                        }
                    }
                    hookDragLocation(lpparam);
                    hookDragState(lpparam);
                    hookObjectDragCallback(lpparam);
                    Log.i(TAG, "DROPZONE: " + classes.size() + " class(es) reference \"" + LAYER_NAME + "\", "
                            + hooked + " layer-opening method(s) hooked"
                            + (hooked == 0 ? " -- the drop zone stays invisible on this build" : ""));
                } catch (Throwable e) {
                    Log.e(TAG, "DROPZONE: install failed", e);
                }
            }
        }, "uxp-dropzone-scan");
        t.setDaemon(true);
        t.start();
    }

    // Fallback for "controller not found": stock parsing a drag location (String, float, float) means a new drag started.
    private static void hookDragLocation(LoadPackageParam lp) {
        try {
            Set<Class<?>> cands = new HashSet<Class<?>>();
            for (String cn : DexStringRefs.classesReferencing(lp.appInfo, "Invalid drag event location JSON.")) {
                Class<?> c;
                try { c = Class.forName(cn, false, lp.classLoader); } catch (Throwable e) { continue; }
                try {
                    for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) cands.addAll(java.util.Arrays.asList(k.getParameterTypes()));
                    for (Field f : c.getDeclaredFields()) cands.add(f.getType());
                } catch (Throwable ignored) {}
            }
            for (Class<?> t : cands) {
                if (t.isPrimitive() || t.isArray() || t.getName().startsWith("java.") || t.getName().startsWith("android.")) continue;
                java.lang.reflect.Constructor<?> k;
                try { k = t.getDeclaredConstructor(String.class, float.class, float.class); } catch (Throwable e) { continue; }
                XposedBridge.hookMethod(k, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam q) {
                        if (sNeedRearm && !sShown) { sNeedRearm = false; rearmLayer(); }
                    }
                });
            }
        } catch (Throwable e) {
            Log.w(TAG, "DROPZONE: drag-location hook failed: " + e);
        }
    }

    /** Finds the layer manager (a field of the object that opens layers) and hooks its  void (String, boolean)  visibility call. */
    private static void hookVisibility(XC_MethodHook.MethodHookParam param) {
        try {
            Method opener = (Method) param.method;
            Object owner = param.thisObject;
            if (owner == null) {
                for (Object a : param.args) {
                    if (a != null && a != param.args[0] && opener.getDeclaringClass().isInstance(a)) { owner = a; break; }
                }
            }
            if (owner == null) return;
            sOwnerRef = new WeakReference<Object>(owner);
            Field mf = sMgrField;
            if (mf != null && mf.getDeclaringClass().isInstance(owner)) {   // a recreated activity has a new layer manager
                try { Object inst = mf.get(owner); if (inst != null) sMgr = inst; } catch (Throwable ignored) {}
            }
            for (Class<?> k = owner.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    Class<?> t = f.getType();
                    String tn = t.getName();
                    if (t.isPrimitive() || t.isArray() || tn.startsWith("java.") || tn.startsWith("android.")
                            || tn.startsWith("androidx.") || tn.startsWith("kotlin.")) continue;
                    synchronized (sScanned) { if (!sScanned.add(t)) continue; }
                    if (!looksLikeLayerManager(t)) continue;
                    try { f.setAccessible(true); Object inst = f.get(owner); if (inst != null) sMgr = inst; sMgrField = f; } catch (Throwable ignored) {}
                    findSizeMethod(t);
                    for (Method mm : t.getDeclaredMethods()) {
                        Class<?>[] p = mm.getParameterTypes();
                        if (mm.getReturnType() == void.class && p.length == 2 && p[0] == String.class && p[1] == boolean.class
                                && !Modifier.isStatic(mm.getModifiers())) {
                            mm.setAccessible(true);
                            sVisMethod = mm;
                            XposedBridge.hookMethod(mm, new XC_MethodHook() {
                                @Override protected void afterHookedMethod(MethodHookParam q) {
                                    try {
                                        if (!LAYER_NAME.equals(q.args[0])) return;
                                        boolean on = (Boolean) q.args[1];
                                        sShown = on;
                                        if (on && !sOwnVisCall) {
                                            noteIdleFlags();
                                            if (Config.DOCK_DROP_ZONE_HIDE_IN_GAME && inGameNow()) { sInGame = true; suppressBox("drag start"); return; }
                                        }
                                        if (on) sFlashStart = android.os.SystemClock.uptimeMillis();
                                        if (on && Config.DOCK_DROP_ZONE_BOX_ON_DRAG) openBigBox();
                                        WeakReference<DropZoneView> r = sView;
                                        DropZoneView v = r == null ? null : r.get();
                                        Log.i(TAG, "DROPZONE: stock says drop layer visible=" + on + (v == null ? " (no view)"
                                                : " (view " + v.getWidth() + "x" + v.getHeight() + " attached=" + v.isAttachedToWindow() + ")"));
                                        if (v != null) v.postInvalidate();
                                    } catch (Throwable ignored) {}
                                }
                            });
                            sGate = true;
                            Log.i(TAG, "DROPZONE: hooked layer visibility call " + t.getName() + "." + mm.getName()
                                    + " -- the zone is drawn only while a drag is active");
                        }
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "DROPZONE: could not hook the layer visibility call: " + e);
        }
    }

    /** Finds the static layer size call (enums, Self, String, int, int) and its CURRENT enum arguments. */
    private static void findSizeMethod(Class<?> t) {
        try {
            for (Method mm : t.getDeclaredMethods()) {
                Class<?>[] p = mm.getParameterTypes();
                if (!Modifier.isStatic(mm.getModifiers()) || p.length < 4) continue;
                if (p[p.length - 1] != int.class || p[p.length - 2] != int.class || p[p.length - 3] != String.class || p[p.length - 4] != t) continue;
                Object[] lead = new Object[p.length - 4];
                boolean ok = true;
                for (int i = 0; i < lead.length && ok; i++) {
                    ok = false;
                    if (p[i].isEnum()) {
                        for (Object c : p[i].getEnumConstants()) {
                            if ("CURRENT".equals(((Enum<?>) c).name())) { lead[i] = c; ok = true; break; }
                        }
                    }
                }
                if (!ok) { Log.w(TAG, "DROPZONE: layer size call " + mm.getName() + " has no CURRENT enum arguments"); continue; }
                mm.setAccessible(true);
                sSizeMethod = mm; sSizeLead = lead;
                final int nl = lead.length;
                XposedBridge.hookMethod(mm, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam q) {
                        try {
                            if (!LAYER_NAME.equals(q.args[nl + 1])) return;
                            int sw = (Integer) q.args[nl + 2], sh = (Integer) q.args[nl + 3];
                            if (sw > 8 && sh > 8) { sBigW = sw; sBigH = sh; }
                            if (sSizeLogged++ >= 16) return;
                            Log.i(TAG, "DROPZONE: stock/own layer size call " + q.args[nl + 2] + "x" + q.args[nl + 3]);
                        } catch (Throwable ignored) {}
                    }
                });
                Log.i(TAG, "DROPZONE: found the layer size call " + t.getName() + "." + mm.getName());
                return;
            }
        } catch (Throwable e) {
            Log.w(TAG, "DROPZONE: could not find the layer size call: " + e);
        }
    }

    /** The layer manager has  static void x(.., Self, String, int, int)  (set layer size) next to  void x(String, boolean). */
    private static boolean looksLikeLayerManager(Class<?> t) {
        try {
            boolean vis = false, size = false;
            for (Method mm : t.getDeclaredMethods()) {
                Class<?>[] p = mm.getParameterTypes();
                if (mm.getReturnType() != void.class) continue;
                if (!Modifier.isStatic(mm.getModifiers()) && p.length == 2 && p[0] == String.class && p[1] == boolean.class) vis = true;
                if (Modifier.isStatic(mm.getModifiers()) && p.length >= 4 && p[p.length - 1] == int.class && p[p.length - 2] == int.class
                        && p[p.length - 3] == String.class && p[p.length - 4] == t) size = true;
            }
            return vis && size;
        } catch (Throwable e) { return false; }
    }

    /** {viewArgIndex, stringArgIndex, widthIndex, heightIndex} for a void method (.., View, .., String, int, int, int ..), else null. */
    static int[] viewAndNameIndex(Method m) {
        if (m.getReturnType() != void.class) return null;
        Class<?>[] p = m.getParameterTypes();
        int view = -1, str = -1, ints = 0, i1 = -1, i2 = -1;
        for (int i = 0; i < p.length; i++) {
            if (p[i] == View.class && view < 0) view = i;
            else if (p[i] == String.class && str < 0) str = i;
            else if (p[i] == int.class) {
                ints++;
                if (str >= 0) { if (i1 < 0) i1 = i; else if (i2 < 0) i2 = i; }
            }
        }
        return (view >= 0 && str > view && ints >= 3 && i2 >= 0) ? new int[]{view, str, i1, i2} : null;
    }

    /** A rounded pill with an X and "Unpin" */
    static final class DropZoneView extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        DropZoneView(Context ctx) {
            super(ctx);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(0xFFFFFFFF);
            pillFill.setStyle(Paint.Style.FILL);
            pillFill.setColor(0xB3202020);             // dark pill so the X reads on any background
            text.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!sGate) sFlashStart = android.os.SystemClock.uptimeMillis();   // v81: no visibility call, start the redraw loop here
        }

        private float curTop = -1f;

        /** Top of the box. A notification window lies in front of the layer's upper part, so the box slides down below it (animated). */
        private float boxTop(float u) {
            float target = 24 * u;
            float nh = noticeHeight();
            if (nh > 0f) target = Math.max(target, nh + 34 * u);
            if (curTop < 0f) curTop = target;
            else if (Math.abs(curTop - target) > 1f) { curTop += (target - curTop) * 0.3f; postInvalidateOnAnimation(); }
            else curTop = target;
            return curTop;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (sGate && !sShown) { curTop = -1f; return; }   // stock keeps the layer up before the first drag
            if (w <= 8) return;                                 // stock's own 1x1 px view: nothing to draw
            // keep redrawing 2 s after a drag starts: a frame drawn before the compositor attaches the surface is lost
            if (android.os.SystemClock.uptimeMillis() - sFlashStart < 2000) postInvalidateOnAnimation();
            // size everything from the layer height (286 units). Only this box is drawn, the whole layer still accepts drops.
            float u = h / 286f;

            float bw = 84 * u, bh = 60 * u;
            // below the dock, under the pinned apps
            float cx = w * BOX_X, cy = boxTop(u) + bh / 2f;
            rect.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
            float corner = 14 * u;
            c.drawRoundRect(rect, corner, corner, pillFill);
            line.setColor(0x99FFFFFF);
            line.setStrokeWidth(1f * u);
            c.drawRoundRect(rect, corner, corner, line);

            float ix = cx, iy = rect.top + bh * 0.36f, d = 11 * u;
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(3.5f * u);
            c.drawLine(ix - d, iy - d, ix + d, iy + d, line);
            c.drawLine(ix - d, iy + d, ix + d, iy - d, line);

            text.setTextSize(14 * u);
            c.drawText("Unpin", cx, rect.bottom - 9 * u, text);
        }
    }

    /** Box centre as a fraction of the layer width, under the pinned apps */
    static final float BOX_X = 0.62f;
}

package com.lumi.uxpatcher.amoled;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import com.lumi.uxpatcher.firmware.FirmwareNames;

import java.lang.reflect.Constructor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.lumi.uxpatcher.Prefs;

import static com.lumi.uxpatcher.Config.TAG;

/** Forces the OCUI left sidebar (Settings, Library, ...) and its list black, with no grey edge fade. No-op without OCUI. */
public final class SideNavHooks {
    private SideNavHooks() {}

    // For isInsideSideNav (instanceof catches subclasses)
    private static volatile Class<?> sOCSideNavClass = null;
    private static final java.util.WeakHashMap<View, Boolean> SIDENAV_LISTS =
            new java.util.WeakHashMap<>();

    public static void install(final LoadPackageParam lpparam) {
        installSideNav(lpparam);
        installSideNavList(lpparam);
    }

    // ── OCSideNav sidebar hook ────────────────────────────────────────────────

    /** Forces OCSideNav black after each constructor (its background is a layer drawable, not a colour) */
    private static void installSideNav(final LoadPackageParam lpparam) {
        try {
            Class<?> cls = FirmwareNames.load(lpparam.classLoader, FirmwareNames.OC_SIDE_NAV);
            sOCSideNavClass = cls;
            for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View nav = (View) param.thisObject;
                        if (!Prefs.bgEnabled()) return;
                        nav.setBackgroundColor(Prefs.bg());
                        nav.setVerticalFadingEdgeEnabled(false);
                        // the inflated container has a translucent white background drawn above ours
                        blackenSideNavChildren(nav);

                        nav.post(() -> {
                            blackenSideNavChildren(nav);
                            quietScrollEffects(nav);
                            Log.i(TAG, "SIDENAV: post() quietScrollEffects → "
                                    + dumpViewTree(nav, 0));
                        });
                        nav.getViewTreeObserver().addOnGlobalLayoutListener(
                                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                                    @Override
                                    public void onGlobalLayout() {
                                        nav.getViewTreeObserver()
                                                .removeOnGlobalLayoutListener(this);
                                        quietScrollEffects(nav);
                                        Log.i(TAG, "SIDENAV: GlobalLayout quietScrollEffects applied");
                                    }
                                });
                        Log.i(TAG, "SIDENAV: OCSideNav constructed, forced black");
                    }
                });
            }
            Log.i(TAG, "SIDENAV: black background hooked on OCSideNav in " + lpparam.packageName);
        } catch (ClassNotFoundException ignored) {
            // no OCUI in this package
        } catch (Throwable t) {
            Log.e(TAG, "SIDENAV: hook installation failed in " + lpparam.packageName, t);
        }
    }

    /** Turns off fading edge, overscroll and scrollbar on v and its descendants (they can draw a white shape while scrolling) */
    static void quietScrollEffects(View v) {
        // every view, since the list's exact class is unknown (harmless on the rest)
        v.setVerticalFadingEdgeEnabled(false);
        v.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (v.getClass().getName().contains("RecyclerView")
                || v.getClass().getName().contains("ScrollView")
                || v.getClass().getName().contains("ListView")) {
            v.setVerticalScrollBarEnabled(false);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) quietScrollEffects(g.getChildAt(i));
        }
    }

    // ── Sidebar list: kill the grey fade at the top/bottom edges ─────────────


    /** Keeps the list's fading edge off (it shows as a grey band) and its background black. Only lists inside an OCSideNav. */
    private static void installSideNavList(final LoadPackageParam lpparam) {
        try {
            Class<?> rv = FirmwareNames.load(lpparam.classLoader, FirmwareNames.OC_RECYCLER_VIEW);
            XposedHelpers.findAndHookMethod(rv, "draw", android.graphics.Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            View v = (View) param.thisObject;
                            Boolean inNav;
                            synchronized (SIDENAV_LISTS) {
                                inNav = SIDENAV_LISTS.get(v);
                            }
                            if (inNav == null) {
                                inNav = isInsideSideNav(v);
                                StringBuilder chain = new StringBuilder();
                                android.view.ViewParent pp = v.getParent();
                                for (int i = 0; pp != null && i < 6; i++, pp = pp.getParent()) {
                                    chain.append(pp.getClass().getSimpleName()).append(" < ");
                                }
                                Log.i(TAG, "SIDENAV: OCRecyclerView first draw inNav=" + inNav
                                        + " parents=" + chain);
                                synchronized (SIDENAV_LISTS) {
                                    SIDENAV_LISTS.put(v, inNav);
                                }
                                if (inNav && Prefs.bgEnabled()) {
                                    v.setBackgroundColor(Prefs.bg()); // once
                                    Log.i(TAG, "SIDENAV: list fade disabled");
                                }
                            }
                            if (inNav && v.isVerticalFadingEdgeEnabled()) {
                                v.setVerticalFadingEdgeEnabled(false);
                            }
                        }
                    });
            Log.i(TAG, "SIDENAV: hooked OCRecyclerView.draw in " + lpparam.packageName);
        } catch (ClassNotFoundException ignored) {
            // no OCUI list in this package
        } catch (Throwable t) {
            Log.e(TAG, "SIDENAV: list hook failed in " + lpparam.packageName, t);
        }
    }

    private static boolean isInsideSideNav(View v) {
        android.view.ViewParent p = v.getParent();
        while (p != null) {
            // by name until the class is resolved
            Class<?> knownCls = sOCSideNavClass;
            if (knownCls != null ? knownCls.isInstance(p)
                                 : FirmwareNames.isNamed(p.getClass(), FirmwareNames.OC_SIDE_NAV)) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }

    /** Replaces the translucent backgrounds on OCSideNav's direct children with solid black */
    private static void blackenSideNavChildren(View nav) {
        if (!(nav instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) nav;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            Drawable bg = c.getBackground();
            if (bg != null) {
                Log.i(TAG, "SIDENAV: child " + c.getClass().getSimpleName()
                        + " bg=" + bg.getClass().getSimpleName() + " → black");
                c.setBackground(new ColorDrawable(Prefs.bg()));
            }
        }
    }

    /** View tree as text, for logging */
    private static String dumpViewTree(View v, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName())
          .append("[").append(v instanceof android.view.ViewGroup
                  ? ((android.view.ViewGroup) v).getChildCount() : 0).append("]")
          .append(" fading=").append(v.isVerticalFadingEdgeEnabled());
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < Math.min(g.getChildCount(), 8); i++) {
                sb.append("\n").append(dumpViewTree(g.getChildAt(i), depth + 1));
            }
        }
        return sb.toString();
    }
}

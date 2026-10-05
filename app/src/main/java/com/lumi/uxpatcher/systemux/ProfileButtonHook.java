package com.lumi.uxpatcher.systemux;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.lumi.uxpatcher.Prefs;
import com.lumi.uxpatcher.firmware.FirmwareNames;
import com.lumi.uxpatcher.firmware.StructuralResolvers;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Hides the dock's profile button and the avatar views drawn over it when {@link Prefs#hideProfile()} is on. Log: PROFILE */
public final class ProfileButtonHook {
    private ProfileButtonHook() {}

    /** Default-locale text of the profile button's content description. */
    private static final String[] DEFAULT_TEXTS = {"Profile"};

    private static volatile Set<String> sProfileTexts = null;   // current-locale texts
    private static boolean sResolveTried = false;
    private static final java.util.WeakHashMap<View, Boolean> HANDLED = new java.util.WeakHashMap<>();

    public static void install(final LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(View.class, "setContentDescription",
                    CharSequence.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            CharSequence cd = (CharSequence) param.args[0];
                            if (cd == null || cd.length() == 0) return;
                            View v = (View) param.thisObject;
                            if (!v.getClass().getName().contains("OCButton")) return;
                            if (!Prefs.hideProfile()) return;

                            Set<String> texts = profileTexts(v);
                            if (texts == null || !texts.contains(cd.toString())) return;
                            if (!isInStatusView(v)) return;            // some other "Profile" button
                            synchronized (HANDLED) {
                                if (HANDLED.put(v, Boolean.TRUE) != null) return;
                            }
                            Log.i(TAG, "PROFILE: found profile button: " + v.getClass().getName()
                                    + " parent=" + (v.getParent() != null
                                            ? v.getParent().getClass().getSimpleName() : "null")
                                    + " cd='" + cd + "' -> hiding (setting is on)");
                            hideWhenLaidOut(v);
                        }
                    });

            Log.i(TAG, "PROFILE: hooked View.setContentDescription (hide profile icon = "
                    + Prefs.hideProfile() + ")");
        } catch (Throwable t) {
            Log.e(TAG, "PROFILE: install failed: " + t);
        }
        // Find the profile icon by id: with no account its description differs
        try {
            Class<?> sv = lpparam.classLoader.loadClass(FirmwareNames.SYSTEM_STATUS_VIEW);
            XposedBridge.hookAllConstructors(sv, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final View v = (View) param.thisObject;
                    if (!(v instanceof ViewGroup)) return;
                    v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        private boolean done, diagnosed;
                        private int passes;
                        @Override
                        public void onLayoutChange(View view, int l, int t, int r, int b,
                                                   int ol, int ot, int or, int ob) {
                            if (done || !Prefs.hideProfile() || !(view instanceof ViewGroup)) return;
                            ViewGroup root = (ViewGroup) view;
                            View btn = findProfileById(root);
                            if (btn != null) {
                                synchronized (HANDLED) {
                                    if (HANDLED.put(btn, Boolean.TRUE) != null) { done = true; return; }
                                }
                                done = true;
                                Log.i(TAG, "PROFILE: found profile button by id (" + btn.getClass().getSimpleName()
                                        + ", description '" + btn.getContentDescription() + "') -> hiding (setting is on)");
                                hideWhenLaidOut(btn);
                            } else if (!diagnosed && root.getWidth() > 0 && ++passes >= 3) {
                                diagnosed = true;
                                logChildren(root);
                            }
                        }
                    });
                }
            });
            Log.i(TAG, "PROFILE: hooked SystemStatusView (find the profile button by id)");
        } catch (Throwable t) {
            Log.e(TAG, "PROFILE: install failed: " + t);
        }
    }

    /** Profile icon targeted by id name. */
    private static View findProfileById(ViewGroup root) {
        try {
            for (String name : new String[]{"profile_button_hit_target", "profile_button"}) {
                int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
                if (id == 0) continue;
                View v = root.findViewById(id);
                if (v != null) return v;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Logs the dock's child views so we can find the profile button */
    private static void logChildren(ViewGroup root) {
        try {
            StringBuilder sb = new StringBuilder("PROFILE: no profile button found by id; status view children: ");
            for (int i = 0; i < root.getChildCount() && i < 30; i++) {
                View c = root.getChildAt(i);
                String idName = "-";
                try { if (c.getId() > 0) idName = root.getResources().getResourceEntryName(c.getId()); } catch (Throwable ignored) { }
                sb.append(c.getClass().getSimpleName()).append('#').append(idName);
                CharSequence cd = c.getContentDescription();
                if (cd != null && cd.length() > 0) sb.append("[cd='").append(cd.length() > 40 ? cd.subSequence(0, 40) : cd).append("']");
                if (c.getVisibility() != View.VISIBLE) sb.append("(vis=").append(c.getVisibility()).append(')');
                sb.append(' ');
            }
            Log.i(TAG, sb.toString());
        } catch (Throwable t) {
            Log.w(TAG, "PROFILE: child list failed: " + t);
        }
    }

    /** The button is only a touch target; the avatar is drawn by overlapping siblings, so hide those too (and keep them hidden). */
    private static void hideWhenLaidOut(final View button) {
        if (!(button.getParent() instanceof ViewGroup)) return;
        final ViewGroup parent = (ViewGroup) button.getParent();
        final List<WeakReference<View>> group = new ArrayList<>();
        final boolean[] computed = {false};
        final boolean[] pending = {false};

        final Runnable apply = new Runnable() {
            @Override public void run() {
                pending[0] = false;
                try {
                    if (!computed[0]) {
                        // needs real bounds
                        if (button.getWidth() == 0 || button.getVisibility() == View.GONE) return;
                        int[] bl = new int[2];
                        button.getLocationInWindow(bl);
                        int btnL = bl[0], btnR = bl[0] + button.getWidth();
                        int btnT = bl[1], btnB = bl[1] + button.getHeight();
                        group.add(new WeakReference<>(button));
                        for (int i = 0; i < parent.getChildCount(); i++) {
                            View sib = parent.getChildAt(i);
                            if (sib == button || sib.getWidth() == 0 || sib.getVisibility() == View.GONE) continue;
                            int[] sl = new int[2];
                            sib.getLocationInWindow(sl);
                            boolean overlaps = sl[0] < btnR && sl[0] + sib.getWidth() > btnL
                                    && sl[1] < btnB && sl[1] + sib.getHeight() > btnT;
                            if (overlaps) group.add(new WeakReference<>(sib));
                        }
                        computed[0] = true;
                        Log.i(TAG, "PROFILE: hiding " + group.size() + " views (button + overlapping avatar views)");
                    }
                    for (WeakReference<View> r : group) {
                        View g = r.get();
                        if (g != null && g.getVisibility() != View.GONE) g.setVisibility(View.GONE);
                    }
                    padStartToMatchEnd(parent);
                } catch (Throwable t) {
                    Log.w(TAG, "PROFILE: hide failed: " + t);
                }
            }
        };
        final View.OnLayoutChangeListener relayout = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (pending[0]) return;
                pending[0] = true;
                parent.post(apply);      // run after the layout pass
            }
        };
        parent.addOnLayoutChangeListener(relayout);
        // App icons load after the status view's layout, so re-measure the padding when the bar or apps list lays out too.
        if (parent.getParent() instanceof ViewGroup) {
            ViewGroup bar = (ViewGroup) parent.getParent();
            bar.addOnLayoutChangeListener(relayout);
            for (int i = 0; i < bar.getChildCount(); i++) {
                View c = bar.getChildAt(i);
                if (c.getClass().getName().equals(FirmwareNames.DYNAMIC_APPS_VIEW)) c.addOnLayoutChangeListener(relayout);
            }
        }
        parent.post(apply);
        for (int ms : new int[]{400, 1500, 4000}) parent.postDelayed(apply, ms);
    }

    /** Status view that already got the default left padding. */
    private static java.lang.ref.WeakReference<ViewGroup> sDefaultFor;

    /** Pads the status view's left to match the gap after the last app icon (measured only while the list is not scrolled). */
    private static void padStartToMatchEnd(ViewGroup status) {
        String why = null;
        try {
            // Default padding at once; refined below once the app icons load (can take ~10 s).
            if (sDefaultFor == null || sDefaultFor.get() != status) {
                sDefaultFor = new java.lang.ref.WeakReference<ViewGroup>(status);
                float d = status.getResources().getDisplayMetrics().density;
                int def = Math.round((4 + com.lumi.uxpatcher.Config.DOCK_LEFT_PAD_EXTRA_DP) * d);
                if (status.getPaddingLeft() < def) {
                    status.setPadding(def, status.getPaddingTop(), status.getPaddingRight(), status.getPaddingBottom());
                    Log.i(TAG, "PROFILE: dock left padding = " + def + "px (default, refined once the app icons are laid out)");
                }
            }
            if (!(status.getParent() instanceof ViewGroup)) { padLog("status view has no ViewGroup parent"); return; }
            ViewGroup bar = (ViewGroup) status.getParent();
            ViewGroup apps = null;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View c = bar.getChildAt(i);
                if (c.getClass().getName().equals(FirmwareNames.DYNAMIC_APPS_VIEW) && c instanceof ViewGroup) {
                    apps = (ViewGroup) c;
                    break;
                }
            }
            if (apps == null) { padLog("DynamicAppsView not found next to the status view"); return; }
            if (apps.getWidth() == 0 || apps.getChildCount() == 0) {
                padLog("apps list not laid out yet (width=" + apps.getWidth() + " children=" + apps.getChildCount() + ")");
                return;
            }
            // Only measure when the whole list fits.
            int content = DockScrollHook.contentRange(apps);
            if (content > apps.getWidth() + 1) {
                padLog("apps list wider than itself (content=" + content + " width=" + apps.getWidth() + "), not measuring");
                return;
            }
            int maxRight = 0;
            for (int i = 0; i < apps.getChildCount(); i++) {
                View c = apps.getChildAt(i);
                if (c.getVisibility() == View.GONE) continue;
                int r = c.getRight();
                if (c.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    r += ((ViewGroup.MarginLayoutParams) c.getLayoutParams()).rightMargin;
                }
                if (r > maxRight) maxRight = r;
            }
            int gap = apps.getWidth() - maxRight;
            float density = status.getResources().getDisplayMetrics().density;
            if (gap <= 0 || gap > 80 * density) {
                padLog("right gap " + gap + "px (appsWidth=" + apps.getWidth() + " lastIconRight=" + maxRight
                        + " padRight=" + apps.getPaddingRight() + ") is outside 0..80dp, left padding not applied");
                return;
            }
            // add a little so both ends look even
            int pad = gap + Math.round(com.lumi.uxpatcher.Config.DOCK_LEFT_PAD_EXTRA_DP * density);
            if (status.getPaddingLeft() == pad) return;
            status.setPadding(pad, status.getPaddingTop(), status.getPaddingRight(), status.getPaddingBottom());
            Log.i(TAG, "PROFILE: dock left padding = " + pad + "px (right gap " + gap + "px + extra; appsWidth=" + apps.getWidth() + " lastIconRight="
                    + maxRight + " children=" + apps.getChildCount() + ")");
        } catch (Throwable t) {
            Log.w(TAG, "PROFILE: left padding failed: " + t);
        }
    }

    private static String sLastPadReason;
    private static int sPadLogs;

    /** Logs why the padding could not be applied; only when the reason changes, at most 12 lines. */
    private static void padLog(String reason) {
        if (reason.equals(sLastPadReason) || sPadLogs >= 12) return;
        sLastPadReason = reason;
        sPadLogs++;
        Log.i(TAG, "PROFILE: left padding waiting: " + reason);
    }

    /** Resolved once, on the first OCButton description seen (needs a Context). */
    private static synchronized Set<String> profileTexts(View v) {
        if (sProfileTexts == null && !sResolveTried) {
            sResolveTried = true;
            Set<String> s = StructuralResolvers.localizedStringsWithDefault(v.getContext(), DEFAULT_TEXTS);
            if (s.isEmpty()) {
                Log.w(TAG, "PROFILE: no string with default text " + java.util.Arrays.toString(DEFAULT_TEXTS)
                        + " found -- profile hide is disabled on this build");
            } else {
                sProfileTexts = s;
                Log.i(TAG, "PROFILE: profile button description is one of " + s);
            }
        }
        return sProfileTexts;
    }

    private static boolean isInStatusView(View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p.getClass().getName().equals(FirmwareNames.SYSTEM_STATUS_VIEW)) return true;
        }
        return false;
    }
}

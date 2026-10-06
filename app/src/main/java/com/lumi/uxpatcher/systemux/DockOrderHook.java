package com.lumi.uxpatcher.systemux;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.Prefs;
import com.lumi.uxpatcher.firmware.FirmwareNames;
import com.lumi.uxpatcher.firmware.StructuralResolvers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Reorders the dock's profile, quick settings, notifications and passthrough buttons */
public final class DockOrderHook {
    private DockOrderHook() {}

    private static final String[] TOKENS = {"qs", "notif", "pt", "profile"};
    private static final int PT = 2, PROFILE = 3;
    private static final String[] ID_NAMES = {"quick_settings_button_hit_target", "notifications_button_hit_target", "mrvr_switcher_button_hit_target", "profile_button_hit_target"};
    private static final String[][] DEFAULT_DESCRIPTIONS = {{"Quick settings"}, {"Notifications"}, {"Enter Passthrough", "Exit Passthrough"}, {"Profile"}, {"Library"}};

    private static final WeakHashMap<View, Boolean> DONE = new WeakHashMap<View, Boolean>();
    private static final WeakHashMap<View, Boolean> BARS = new WeakHashMap<View, Boolean>();
    private static String sLastBarLog = "";
    private static volatile int sGroupsEnd, sLastInset, sStatusPadLeft;
    private static int sLibId = -1;
    private static int sLogs = 0;
    private static String sLastLogged = "";
    private static Set<String>[] sDescriptions;

    public static void install(final LoadPackageParam lp) {
        try {
            Class<?> sv = lp.classLoader.loadClass(FirmwareNames.SYSTEM_STATUS_VIEW);
            XposedBridge.hookAllConstructors(sv, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    final View v = (View) param.thisObject;
                    synchronized (DONE) { if (DONE.containsKey(v)) return; DONE.put(v, Boolean.TRUE); }
                    v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                            if (view instanceof ViewGroup) apply((ViewGroup) view);
                        }
                    });
                }
            });
            Class<?> bar = lp.classLoader.loadClass(FirmwareNames.BAR_VIEW);
            XposedBridge.hookAllConstructors(bar, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    final View v = (View) param.thisObject;
                    synchronized (BARS) { if (BARS.containsKey(v)) return; BARS.put(v, Boolean.TRUE); }
                    v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                            if (view instanceof ViewGroup) applyBar((ViewGroup) view);
                        }
                    });
                    // item animations reset translations, so reapply before each frame
                    v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        private ViewTreeObserver.OnPreDrawListener pre;
                        @Override public void onViewAttachedToWindow(final View view) {
                            if (pre != null) return;
                            pre = new ViewTreeObserver.OnPreDrawListener() {
                                @Override public boolean onPreDraw() {
                                    if (view instanceof ViewGroup) {
                                        // The status buttons too: the location indicator animation resets their shifts without a layout
                                        View status = statusView((ViewGroup) view);
                                        if (status instanceof ViewGroup && status.getWidth() > 0) apply((ViewGroup) status);
                                        applyBar((ViewGroup) view);
                                    }
                                    return true;
                                }
                            };
                            view.getViewTreeObserver().addOnPreDrawListener(pre);
                        }
                        @Override public void onViewDetachedFromWindow(View view) {
                            if (pre == null) return;
                            try { view.getViewTreeObserver().removeOnPreDrawListener(pre); } catch (Throwable ignored) { }
                            pre = null;
                        }
                    });
                }
            });
            Log.i(TAG, "ORDER: hooked SystemStatusView + BarView (order = " + Prefs.dockOrder() + ", appsLeft = " + Prefs.appsLeft()
                    + ", libraryFirst = " + Prefs.libraryFirst() + ", hidePassthrough = " + Prefs.hidePassthrough() + ")");
        } catch (Throwable t) {
            Log.w(TAG, "ORDER: install failed: " + t);
        }
    }

    /** Buttons in the desired order, or null for the stock order */
    private static String[] wantedOrder() {
        String s = Prefs.dockOrder();
        if (s == null) return null;
        String[] parts = s.trim().split(",");
        boolean[] seen = new boolean[TOKENS.length];
        List<String> out = new ArrayList<String>();
        for (String part : parts) {
            int k = indexOf(part.trim());
            if (k < 0 || seen[k]) return null;
            seen[k] = true;
            out.add(TOKENS[k]);
        }
        if (!seen[PROFILE]) out.add(0, TOKENS[PROFILE]);
        for (int k = 0; k < TOKENS.length; k++) if (!seen[k] && k != PROFILE) out.add(TOKENS[k]);
        return out.toArray(new String[0]);
    }

    private static int indexOf(String token) {
        for (int i = 0; i < TOKENS.length; i++) if (TOKENS[i].equals(token)) return i;
        return -1;
    }

    private static final class Group {
        int kind; // index into TOKENS
        View hit;
        int left, right; // hit target bounds from the layout (no translation)
        final List<View> members = new ArrayList<View>();
    }

    private static void apply(ViewGroup root) {
        try {
            boolean hidePt = Prefs.hidePassthrough();
            if (hidePt) enforcePassthroughHidden(root);
            String[] want = wantedOrder();
            List<Group> groups = findGroups(root, hidePt, Prefs.hideProfile());
            if (groups == null) return;
            if (want != null) {
                List<String> keep = new ArrayList<String>();
                for (String t : want) for (Group g : groups) if (TOKENS[g.kind].equals(t)) { keep.add(t); break; }
                want = keep.toArray(new String[0]);
            }
            // current layout order, left to right
            Collections.sort(groups, new Comparator<Group>() {
                @Override public int compare(Group a, Group b) { return a.left - b.left; }
            });
            int n = groups.size();
            int[] gap = new int[n - 1];
            for (int i = 0; i < n - 1; i++) gap[i] = groups.get(i + 1).left - groups.get(i).right;
            // Layout neighbours keep their measured gap; new neighbours get the usual gap for their kind (pill 8px, round buttons 0)
            boolean[][] has = new boolean[TOKENS.length][TOKENS.length];
            int[][] measured = new int[TOKENS.length][TOKENS.length];
            for (int i = 0; i < n - 1; i++) {
                int a = groups.get(i).kind, b = groups.get(i + 1).kind;
                has[a][b] = has[b][a] = true;
                measured[a][b] = measured[b][a] = gap[i];
            }
            int qi = -1, pi = -1;
            for (int i = 0; i < n; i++) { if (groups.get(i).kind == 0) qi = i; if (groups.get(i).kind == PROFILE) pi = i; }
            final int gapQs = qi < 0 || n < 2 ? 0 : (qi < n - 1 ? gap[qi] : gap[qi - 1]);
            int gr = gapQs;
            for (int i = 0; i < n - 1; i++) {
                int a = groups.get(i).kind, b = groups.get(i + 1).kind;
                if (a != 0 && b != 0 && a != PROFILE && b != PROFILE) gr = gap[i];
            }
            final int gapRound = gr;
            final int gapProf = pi < 0 || n < 2 ? gapQs : (pi < n - 1 ? gap[pi] : gap[pi - 1]);

            List<Group> ordered = new ArrayList<Group>();
            if (want == null) ordered.addAll(groups);
            else {
                for (String tok : want) {
                    for (Group g : groups) if (TOKENS[g.kind].equals(tok)) { ordered.add(g); break; }
                }
                if (ordered.size() != n) ordered = groups;
            }
            sGroupsEnd = groups.get(n - 1).right;
            sLastInset = visibleInset(ordered.get(n - 1));
            sStatusPadLeft = root.getPaddingLeft();
            int x = groups.get(0).left;
            StringBuilder sb = new StringBuilder();
            StringBuilder detail = new StringBuilder();
            for (int i = 0; i < n; i++) {
                Group g = ordered.get(i);
                int delta = x - g.left;
                for (View m : g.members) if (m.getTranslationX() != delta) m.setTranslationX(delta);
                sb.append(TOKENS[g.kind]).append(delta >= 0 ? "+" : "").append(delta).append(' ');
                detail.append(TOKENS[g.kind]).append("{hit ").append(g.left).append("..").append(g.right).append(" en=").append(g.hit.isEnabled())
                        .append(" clk=").append(g.hit.isClickable()).append(" a=").append(g.hit.getAlpha()).append(" members=").append(g.members.size())
                        .append(" z=").append(root.indexOfChild(g.hit)).append("} ");
                int between = 0;
                if (i < n - 1) {
                    int a = g.kind, b = ordered.get(i + 1).kind;
                    if (has[a][b]) between = measured[a][b];
                    else if (a == 0 || b == 0) between = gapQs;
                    else if (a == PROFILE || b == PROFILE) between = gapProf;
                    else between = gapRound;
                }
                x += (g.right - g.left) + between;
            }
            String line = sb.toString();
            if (!line.equals(sLastLogged) && sLogs++ < 8) {
                sLastLogged = line;
                Log.i(TAG, "ORDER: " + (want == null ? "default" : Prefs.dockOrder()) + " -> shifts " + line.trim());
                Log.i(TAG, "ORDER: groups " + detail.toString().trim() + " | status view " + root.getWidth() + "px, translation " + root.getTranslationX());
            }
        } catch (Throwable t) {
            if (sLogs++ < 8) Log.w(TAG, "ORDER: apply failed: " + t);
        }
    }

    private static View statusView(ViewGroup bar) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            if (c.getClass().getName().equals(FirmwareNames.SYSTEM_STATUS_VIEW)) return c;
        }
        return null;
    }

    /** Bar level: swaps the apps and status sides. Uses layout positions only, so it is safe to run every frame. */
    private static void applyBar(ViewGroup bar) {
        try {
            View status = null;
            ViewGroup apps = null;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View c = bar.getChildAt(i);
                String n = c.getClass().getName();
                if (n.equals(FirmwareNames.SYSTEM_STATUS_VIEW)) status = c;
                else if (n.equals(FirmwareNames.DYNAMIC_APPS_VIEW) && c instanceof ViewGroup) apps = (ViewGroup) c;
            }
            if (status == null || apps == null || status.getWidth() == 0 || apps.getWidth() == 0) return;
            if (Prefs.hidePassthrough() && status instanceof ViewGroup) enforcePassthroughHidden((ViewGroup) status);

            // apps on the left / right of the status buttons
            boolean left = Prefs.appsLeft();
            float appsShift = 0, statusShift = 0;
            if (left && sGroupsEnd > 0) {
                float d = bar.getResources().getDisplayMetrics().density;
                // left edge: the Library button's margin; right edge: the status padding
                View libView = findLibrary(apps);
                int libMargin = libView != null && libView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                        ? ((ViewGroup.MarginLayoutParams) libView.getLayoutParams()).rightMargin : Math.round(8 * d);
                int pad = sStatusPadLeft > 0 ? sStatusPadLeft : Math.round(20 * d);
                appsShift = libMargin + Math.round(Config.DOCK_SWAPPED_LEFT_NUDGE_DP * d) - apps.getLeft();
                statusShift = bar.getWidth() - pad - Math.round(Config.DOCK_SWAPPED_RIGHT_NUDGE_DP * d) + sLastInset
                        - (status.getLeft() + sGroupsEnd);
            }
            if (apps.getTranslationX() != appsShift) apps.setTranslationX(appsShift);
            if (status.getTranslationX() != statusShift) status.setTranslationX(statusShift);

            // Library on the left
            View lib = findLibrary(apps);
            boolean first = Prefs.libraryFirst();
            int moved = 0;
            if (lib != null && lib.getWidth() > 0) {
                float libShift = 0, othersShift = 0;
                if (first) {
                    int firstLeft = Integer.MAX_VALUE;
                    View neighbour = null;
                    for (int i = 0; i < apps.getChildCount(); i++) {
                        View c = apps.getChildAt(i);
                        if (c == lib || c.getVisibility() == View.GONE || c.getWidth() == 0) continue;
                        if (c.getLeft() < firstLeft) firstLeft = c.getLeft();
                        if (c.getLeft() < lib.getLeft() && (neighbour == null || c.getLeft() > neighbour.getLeft())) neighbour = c;
                    }
                    if (neighbour != null) {
                        int nbMargin = neighbour.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                                ? ((ViewGroup.MarginLayoutParams) neighbour.getLayoutParams()).rightMargin : 0;
                        int libMargin = lib.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                                ? ((ViewGroup.MarginLayoutParams) lib.getLayoutParams()).rightMargin : 0;
                        int stride = lib.getWidth() + nbMargin;
                        int trail = left ? 0 : Math.max(0, libMargin - nbMargin);
                        libShift = firstLeft - lib.getLeft() + trail;
                        othersShift = stride + trail;
                    }
                }
                for (int i = 0; i < apps.getChildCount(); i++) {
                    View c = apps.getChildAt(i);
                    float want = c == lib ? libShift : (c.getLeft() < lib.getLeft() ? othersShift : 0);
                    if (c.getTranslationX() != want) { c.setTranslationX(want); moved++; }
                }
            }
            String line = (left ? "apps-left " : "apps-right ") + (first ? "library-first " : "library-last ") + (lib == null ? "(no library button found)" : "");
            if (!line.equals(sLastBarLog) && sLogs++ < 12) {
                sLastBarLog = line;
                Log.i(TAG, "ORDER: bar " + line.trim() + " [appsShift " + appsShift + ", statusShift " + statusShift + ", moved " + moved + " items]");
            }
        } catch (Throwable t) {
            if (sLogs++ < 12) Log.w(TAG, "ORDER: bar apply failed: " + t);
        }
    }

    /** The Library button targeted by id name or description */
    private static View findLibrary(ViewGroup apps) {
        try {
            if (sLibId == -1) sLibId = apps.getResources().getIdentifier("navigation_button_library", "id", apps.getContext().getPackageName());
            if (sLibId > 0) { View v = apps.findViewById(sLibId); if (v != null) return v; }
        } catch (Throwable ignored) { }
        Set<String> descs = descriptions(apps, 4);
        if (descs != null && !descs.isEmpty()) {
            for (int i = 0; i < apps.getChildCount(); i++) {
                CharSequence cd = apps.getChildAt(i).getContentDescription();
                if (cd != null && descs.contains(cd.toString())) return apps.getChildAt(i);
            }
        }
        return null;
    }


    /** How far a button's visible shape ends inside its hit target (0 for the pill) */
    private static int visibleInset(Group g) {
        int maxRight = -1;
        for (View m : g.members) if (m != g.hit) maxRight = Math.max(maxRight, m.getRight());
        return maxRight < 0 ? 0 : Math.max(0, g.right - maxRight);
    }

    /** Passthrough views found while laid out, kept GONE while the setting is on */
    private static final WeakHashMap<View, List<java.lang.ref.WeakReference<View>>> PT_VIEWS =
            new WeakHashMap<View, List<java.lang.ref.WeakReference<View>>>();
    private static boolean sPtLogged;

    private static void enforcePassthroughHidden(ViewGroup root) {
        try {
            List<java.lang.ref.WeakReference<View>> list;
            synchronized (PT_VIEWS) { list = PT_VIEWS.get(root); }
            if (list == null) {
                View hit = findHit(root, PT);
                if (hit == null || hit.getWidth() == 0 || hit.getVisibility() == View.GONE) return;
                list = new ArrayList<java.lang.ref.WeakReference<View>>();
                int l = hit.getLeft(), r = hit.getRight();
                for (int i = 0; i < root.getChildCount(); i++) {
                    View c = root.getChildAt(i);
                    if (c.getVisibility() == View.GONE || c.getWidth() == 0) continue;
                    int cx = (c.getLeft() + c.getRight()) / 2;
                    if (c == hit || (cx >= l && cx <= r)) list.add(new java.lang.ref.WeakReference<View>(c));
                }
                synchronized (PT_VIEWS) { PT_VIEWS.put(root, list); }
                if (!sPtLogged) { sPtLogged = true; Log.i(TAG, "ORDER: hiding the passthrough button (" + list.size() + " views at " + l + ".." + r + ")"); }
            }
            for (java.lang.ref.WeakReference<View> w : list) {
                View v = w.get();
                if (v != null && v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            if (sLogs++ < 12) Log.w(TAG, "ORDER: hide passthrough failed: " + t);
        }
    }

    /** The buttons and the views that move with them, or null while not laid out */
    private static List<Group> findGroups(ViewGroup root, boolean skipPt, boolean skipProfile) {
        List<Group> groups = new ArrayList<Group>();
        for (int k = 0; k < TOKENS.length; k++) {
            if (skipPt && k == PT) continue;
            if (skipProfile && k == PROFILE) continue;
            View hit = findHit(root, k);
            if (k == PROFILE && (hit == null || hit.getVisibility() == View.GONE)) continue;
            if (hit == null || hit.getVisibility() == View.GONE || hit.getWidth() == 0) return null;
            Group g = new Group();
            g.kind = k;
            g.hit = hit;
            g.left = hit.getLeft();
            g.right = hit.getRight();
            groups.add(g);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c.getVisibility() == View.GONE || c.getWidth() == 0) continue;
            int cx = (c.getLeft() + c.getRight()) / 2;
            Group best = null;
            int bestDist = Integer.MAX_VALUE;
            for (Group g : groups) {
                if (cx < g.left || cx > g.right) continue;
                int dist = Math.abs(cx - (g.left + g.right) / 2);
                if (dist < bestDist) { bestDist = dist; best = g; }
            }
            if (best != null) best.members.add(c);
        }
        return groups;
    }

    private static View findHit(ViewGroup root, int kind) {
        try {
            int id = root.getResources().getIdentifier(ID_NAMES[kind], "id", root.getContext().getPackageName());
            if (id != 0) { View v = root.findViewById(id); if (v != null) return v; }
        } catch (Throwable ignored) { }
        Set<String> descs = descriptions(root, kind);
        if (descs == null || descs.isEmpty()) return null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            CharSequence cd = c.getContentDescription();
            if (cd != null && c.isClickable() && descs.contains(cd.toString())) return c;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static synchronized Set<String> descriptions(ViewGroup root, int kind) {
        if (sDescriptions == null) sDescriptions = (Set<String>[]) new Set[DEFAULT_DESCRIPTIONS.length];
        if (sDescriptions[kind] == null) {
            sDescriptions[kind] = StructuralResolvers.localizedStringsWithDefault(root.getContext(), DEFAULT_DESCRIPTIONS[kind]);
        }
        return sDescriptions[kind];
    }
}

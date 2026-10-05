package com.lumi.uxpatcher.systemux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.Prefs;
import com.lumi.uxpatcher.firmware.FirmwareNames;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Shows the battery percentage next to the battery icon in the dock's status area (Config.DOCK_BATTERY_PERCENT). Log: BATTERY */
public final class BatteryPercentHook {
    private BatteryPercentHook() {}

    private static final WeakHashMap<View, Boolean> DONE = new WeakHashMap<View, Boolean>();
    private static int sLogs = 0, sCalls = 0, sCtor = 0;

    public static void install(final LoadPackageParam lp) {
        if (!Config.DOCK_BATTERY_PERCENT) return;
        try {
            Class<?> sv = lp.classLoader.loadClass(FirmwareNames.SYSTEM_STATUS_VIEW);
            XposedBridge.hookAllConstructors(sv, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    final View v = (View) param.thisObject;
                    if (sCtor++ < 3) Log.i(TAG, "BATTERY: SystemStatusView constructed (" + v.getClass().getSimpleName() + ")");
                    v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                            synchronized (DONE) { if (DONE.containsKey(view)) return; }
                            if (!(view instanceof ViewGroup)) return;
                            View found = findBattery((ViewGroup) view);
                            if (sCalls++ < 3 || (sCalls == 8 && found == null)) diag((ViewGroup) view, found);
                            if (found == null) return;
                            synchronized (DONE) { DONE.put(view, Boolean.TRUE); }
                            final ViewGroup g = (ViewGroup) view;
                            g.post(new Runnable() { @Override public void run() { setup(g); } });
                        }
                    });
                }
            });
            Log.i(TAG, "BATTERY: hooked SystemStatusView constructors");
        } catch (Throwable t) {
            Log.w(TAG, "BATTERY: install failed: " + t);
        }
    }

    /** The battery container, by id name or else the ConstraintLayout child with >= 3 ImageViews. */
    private static View findBattery(ViewGroup root) {
        try {
            int id = root.getResources().getIdentifier("battery_icon_container", "id", root.getContext().getPackageName());
            if (id != 0) { View v = root.findViewById(id); if (v != null) return v; }
        } catch (Throwable ignored) { }
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (!(c instanceof ViewGroup) || !c.getClass().getName().endsWith("ConstraintLayout")) continue;
            ViewGroup g = (ViewGroup) c;
            int images = 0;
            for (int k = 0; k < g.getChildCount(); k++) if (g.getChildAt(k) instanceof android.widget.ImageView) images++;
            if (images >= 3) return c;
        }
        return null;
    }

    /** The clock: the TextView whose text has ':', else the first TextView with text. */
    private static View findTime(ViewGroup root) {
        View first = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (!(c instanceof TextView)) continue;
            CharSequence t = ((TextView) c).getText();
            if (t == null || t.length() == 0) continue;
            if (t.toString().indexOf(':') >= 0) return c;
            if (first == null) first = c;
        }
        return first;
    }

    private static void diag(ViewGroup root, View found) {
        StringBuilder sb = new StringBuilder("BATTERY: layout pass, status view " + root.getWidth() + "x" + root.getHeight() + ", "
                + root.getChildCount() + " children, battery " + (found == null ? "NOT found" : "found") + ": ");
        for (int i = 0; i < root.getChildCount() && i < 16; i++) {
            View c = root.getChildAt(i);
            sb.append(c.getClass().getSimpleName());
            if (c instanceof ViewGroup) sb.append('[').append(((ViewGroup) c).getChildCount()).append(']');
            sb.append(' ');
        }
        Log.i(TAG, sb.toString());
    }

    private static void setup(final ViewGroup root) {
        try {
            final Context ctx = root.getContext();
            View bat = findBattery(root);
            if (bat == null || bat.getLayoutParams() == null) { Log.w(TAG, "BATTERY: battery container not found"); return; }
            int idBat = bat.getId();
            if (idBat == View.NO_ID) { idBat = View.generateViewId(); bat.setId(idBat); }
            // The constraint fields are obfuscated, so classify them by what they point at:
            // ImageView = start, plain View (pill) = end, OCButton (hit target) = top / bottom.
            ViewGroup.LayoutParams blp0 = bat.getLayoutParams();
            Field fStart = null, fEnd = null;
            java.util.ArrayList<Field> fHit = new java.util.ArrayList<Field>();
            View pill = null, hit = null;
            StringBuilder cons = new StringBuilder();
            for (Field f : blp0.getClass().getDeclaredFields()) {
                if (f.getType() != int.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                int v = f.getInt(blp0);
                if (v <= 0x01000000) continue;                               // too small to be a view id
                View t = root.findViewById(v);
                if (t == null || t == bat) continue;
                cons.append(f.getName()).append("->").append(t.getClass().getSimpleName()).append(' ');
                if (t instanceof android.widget.ImageView) fStart = f;
                else if (t.getClass() == View.class) { fEnd = f; pill = t; }
                else { fHit.add(f); hit = t; }
            }
            View time = findTime(root);
            Log.i(TAG, "BATTERY: battery constraints: " + cons + "| pill=" + (pill == null ? "-" : "w=" + pill.getLayoutParams().width)
                    + " hit=" + (hit == null ? "-" : hit.getClass().getSimpleName() + " w=" + hit.getLayoutParams().width)
                    + " time=" + (time == null ? "-" : String.valueOf(((TextView) time).getText())));
            if (fStart == null || fEnd == null || fHit.isEmpty()) { Log.w(TAG, "BATTERY: could not classify the battery's constraints, nothing changed"); return; }

            // Hide the icon: shrink it to 1px and narrow the pill by the same amount so the spacing stays
            final boolean hideIcon = Prefs.hideBatteryIcon();
            int iconW = blp0.width > 0 ? blp0.width : bat.getWidth();
            final int iconCut = hideIcon ? Math.max(0, iconW - 1) : 0;
            if (hideIcon) Log.i(TAG, "BATTERY: hiding the battery icon (icon " + iconW + "px, pill gets " + iconCut + "px narrower)");

            final TextView pct = new TextView(ctx);
            int pctId = View.generateViewId();
            pct.setId(pctId);
            pct.setText("100%");
            pct.setSingleLine(true);
            pct.setIncludeFontPadding(false);
            if (time instanceof TextView) {
                TextView tv = (TextView) time;
                pct.setTextColor(tv.getTextColors());
                pct.setTypeface(tv.getTypeface());
                pct.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize() * 0.85f);
            } else {
                pct.setTextColor(0xFFFFFFFF);
                pct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            }
            float density = root.getResources().getDisplayMetrics().density;
            int gap = Math.round(3 * density);
            int textW = (int) Math.ceil(pct.getPaint().measureText("100%"));

            ViewGroup.LayoutParams blp = bat.getLayoutParams();
            Constructor<?> ctor = blp.getClass().getConstructor(int.class, int.class);
            ViewGroup.MarginLayoutParams plp = (ViewGroup.MarginLayoutParams) ctor.newInstance(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // The text goes between the battery and the pill end. The battery's end margin grows by the text's room
            // (and the pill by the same), so the icon stays where it was.
            int extra = textW + gap;
            fStart.setInt(plp, idBat);
            fEnd.setInt(plp, fEnd.getInt(blp));
            for (Field f : fHit) f.setInt(plp, f.getInt(blp));
            int endPad = Math.round(Config.DOCK_BATTERY_PERCENT_END_PAD_DP * density);
            ViewGroup.MarginLayoutParams bm = (ViewGroup.MarginLayoutParams) blp;
            int oldEndMargin = bm.getMarginEnd();
            plp.setMarginStart(gap);
            plp.setMarginEnd(oldEndMargin + endPad);
            bm.setMarginEnd(oldEndMargin + extra + endPad);

            if (hideIcon && iconCut > 0) {
                blp.width = 1;
                bat.setVisibility(View.INVISIBLE);
            }
            root.addView(pct, plp);
            pct.setLayoutParams(plp);                 // resolves start/end for the layout direction
            bat.setLayoutParams(blp);

            // Widen the pill, hit target (and status view if fixed width) by the text, plus any later clock growth (the
            // chain is spread_inside in a fixed width, so a wider clock would squeeze it).
            final int loosen = Math.round(8 * density);
            final int grow = extra + loosen;
            final View timeV = time;
            final int baseTimeW = time == null ? 0 : time.getWidth();
            final java.util.ArrayList<View> tv = new java.util.ArrayList<View>();
            final java.util.ArrayList<Integer> tb = new java.util.ArrayList<Integer>();
            for (View x : new View[]{pill, hit, root}) {
                if (x == null) continue;
                ViewGroup.LayoutParams xl = x.getLayoutParams();
                int w = xl == null ? -99 : xl.width;
                Log.i(TAG, "BATTERY: " + (x == root ? "status view" : x == pill ? "pill" : "hit target") + " lp.width=" + w + " laid out " + x.getWidth());
                if (w > 0) { tv.add(x); tb.add(w); }
            }
            if (tv.isEmpty()) Log.w(TAG, "BATTERY: no fixed-width view to widen (all wrap / match constraint)");
            final int[] passes = {0};
            final View batV = bat, pillV = pill;
            final int textNeed = textW;
            final int[] reserved = {textW};       // text width the pill has room for
            final int[] extraGrow = {0};          // pill width change as the digits change
            final int[] leftGrow = {0};           // extra room added left of the clock
            final boolean[] leftDone = {false};
            final int rightPad = oldEndMargin + endPad;   // pill end to the percentage text
            final Runnable apply = new Runnable() { @Override public void run() {
                int delta = timeV == null ? 0 : Math.max(0, timeV.getWidth() - baseTimeW);
                for (int k = 0; k < tv.size(); k++) {
                    View x = tv.get(k);
                    ViewGroup.LayoutParams xl = x.getLayoutParams();
                    int want = tb.get(k) + grow + delta + extraGrow[0] + leftGrow[0] - iconCut;
                    if (xl != null && xl.width != want) { xl.width = want; x.setLayoutParams(xl); }
                }
            }};
            apply.run();
            pct.setTag(new Runnable() { @Override public void run() {
                int w = (int) Math.ceil(pct.getPaint().measureText(pct.getText().toString()));
                int d = w - reserved[0];
                if (d == 0) return;
                reserved[0] = w;
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) batV.getLayoutParams();
                m.setMarginEnd(m.getMarginEnd() + d);
                batV.setLayoutParams(m);
                extraGrow[0] += d;
                apply.run();
            }});
            root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    apply.run();
                    if (iconCut > 0 && batV.getVisibility() != View.INVISIBLE) batV.setVisibility(View.INVISIBLE);
                    if (!leftDone[0] && timeV != null && pillV != null && pillV.getWidth() > 0
                            && pillV.getLayoutParams().width == pillV.getWidth() && timeV.getLeft() > pillV.getLeft()) {
                        leftDone[0] = true;
                        int left0 = timeV.getLeft() - pillV.getLeft();
                        int target = Math.min(rightPad, Math.round(Config.DOCK_CLOCK_LEFT_PAD_MAX_DP * view.getResources().getDisplayMetrics().density));
                        int more = target - left0;
                        Log.i(TAG, "BATTERY: pill padding left " + left0 + "px, right " + rightPad + "px, target " + target + "px");
                        if (more > 0 && timeV.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                            ViewGroup.MarginLayoutParams tm = (ViewGroup.MarginLayoutParams) timeV.getLayoutParams();
                            tm.setMarginStart(tm.getMarginStart() + more);
                            timeV.setLayoutParams(tm);
                            leftGrow[0] = more;
                            apply.run();
                        }
                    }
                    if (passes[0]++ < 4) {
                        StringBuilder sb = new StringBuilder("BATTERY: widths after setup: status " + view.getWidth());
                        for (int k = 0; k < tv.size(); k++) sb.append(" | ").append(tv.get(k).getClass().getSimpleName()).append(' ')
                                .append(tv.get(k).getWidth()).append('/').append(tv.get(k).getLayoutParams().width);
                        sb.append(" | time ").append(timeV == null ? -1 : timeV.getWidth()).append(" (base ").append(baseTimeW)
                                .append(") | pct ").append(pct.getWidth()).append(" (text needs ").append(textNeed).append(")");
                        sb.append(" | pct ").append(pct.getLeft()).append("..").append(pct.getRight())
                                .append(" battery right ").append(batV.getRight())
                                .append(" pill right ").append(pillV == null ? -1 : pillV.getRight())
                                .append(" status width ").append(view.getWidth());
                        Log.i(TAG, sb.toString());
                    }
                }
            });
            Log.i(TAG, "BATTERY: added the percentage next to the battery icon (text " + textW + "px + gap " + gap + "px + end pad " + endPad
                    + "px, battery end margin " + oldEndMargin + " -> " + (oldEndMargin + extra + endPad) + ", widening " + tv.size() + " view(s) by " + grow + "px + clock growth)");

            final BroadcastReceiver rcv = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { update(pct, i); }
            };
            final IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            final boolean[] reg = {false};
            Runnable register = new Runnable() { @Override public void run() {
                if (reg[0]) return;
                try { update(pct, ctx.registerReceiver(rcv, f)); reg[0] = true; } catch (Throwable t) { Log.w(TAG, "BATTERY: register failed: " + t); }
            }};
            register.run();
            // register the receiver only while attached
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    if (!reg[0]) try { update(pct, ctx.registerReceiver(rcv, f)); reg[0] = true; } catch (Throwable ignored) { }
                }
                @Override public void onViewDetachedFromWindow(View v) {
                    if (reg[0]) try { ctx.unregisterReceiver(rcv); } catch (Throwable ignored) { }
                    reg[0] = false;
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "BATTERY: setup failed: " + t);
        }
    }

    private static void update(TextView pct, Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        String s = (level * 100 / scale) + "%";
        if (!s.contentEquals(pct.getText())) {
            pct.setText(s);
            Object resize = pct.getTag();
            if (resize instanceof Runnable) pct.post((Runnable) resize);
            if (sLogs++ < 4) Log.i(TAG, "BATTERY: " + s);
        }
    }
}

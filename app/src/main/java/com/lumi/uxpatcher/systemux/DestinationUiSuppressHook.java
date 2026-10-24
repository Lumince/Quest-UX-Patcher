package com.lumi.uxpatcher.systemux;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Suppresses the blank DestinationUiActivity panel that appears */
public final class DestinationUiSuppressHook {
    private DestinationUiSuppressHook() {}

    private static final String DESTINATION_UI_ACTIVITY =
            "com.oculus.panelapp.destinationui.DestinationUiActivity";

    public static void install(LoadPackageParam lpparam) {
        Class<?> activityClass;
        try {
            activityClass = lpparam.classLoader.loadClass(DESTINATION_UI_ACTIVITY);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "DEST-SUPPRESS: DestinationUiActivity not found in "
                    + lpparam.packageName, e);
            return;
        }

        // --- onCreate(Bundle) ---
        Class<?> onCreateClass = findDeclaringClass(activityClass, "onCreate", Bundle.class);
        if (onCreateClass == null) {
            Log.e(TAG, "DEST-SUPPRESS: could not find onCreate(Bundle) in hierarchy");
            return;
        }

        Log.i(TAG, "DEST-SUPPRESS: onCreate(Bundle) declared on " + onCreateClass.getName());

        XposedHelpers.findAndHookMethod(onCreateClass, "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!DESTINATION_UI_ACTIVITY.equals(
                                param.thisObject.getClass().getName())) return;
                        Activity act = (Activity) param.thisObject;
                        suppressWindow(act, "before-onCreate");
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!DESTINATION_UI_ACTIVITY.equals(
                                param.thisObject.getClass().getName())) return;
                        Activity activity = (Activity) param.thisObject;
                        // Re-apply 1x1 in case onCreate overrode our layout params.
                        suppressWindow(activity, "after-onCreate");
                        activity.finishAndRemoveTask();
                        Log.i(TAG, "DEST-SUPPRESS: finished activity after onCreate "
                                + "— navigator signal sent, 1x1 window, task removed");
                    }
                });

        // --- onNewIntent(Intent) ---
        Class<?> onNewIntentClass =
                findDeclaringClass(activityClass, "onNewIntent", android.content.Intent.class);
        if (onNewIntentClass != null) {
            XposedHelpers.findAndHookMethod(onNewIntentClass,
                    "onNewIntent", android.content.Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!DESTINATION_UI_ACTIVITY.equals(
                                    param.thisObject.getClass().getName())) return;
                            Activity activity = (Activity) param.thisObject;
                            activity.finishAndRemoveTask();
                            Log.i(TAG, "DEST-SUPPRESS: finished activity after onNewIntent "
                                    + "— navigator signal sent, task removed from stack");
                        }
                    });
            Log.i(TAG, "DEST-SUPPRESS: also armed on "
                    + onNewIntentClass.getSimpleName() + ".onNewIntent");
        }

        Log.i(TAG, "DEST-SUPPRESS: armed on " + onCreateClass.getSimpleName() + ".onCreate");
    }

    /** Force 1×1 layout params */
    private static void suppressWindow(Activity activity, String fromHook) {
        try {
            android.view.Window w = activity.getWindow();

            WindowManager.LayoutParams params = w.getAttributes();
            params.alpha  = 0f;
            params.width  = 1;
            params.height = 1;
            w.setAttributes(params);

            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

            Log.i(TAG, "DEST-SUPPRESS: window suppressed (" + fromHook
                    + ") — alpha=0 + non-touchable (belt-and-suspenders)");
        } catch (Throwable t) {
            Log.w(TAG, "DEST-SUPPRESS: suppressWindow failed (" + fromHook + ")", t);
        }
    }

    /** Walk superclass chain to find the class that declares the given method. */
    private static Class<?> findDeclaringClass(Class<?> start, String name,
                                               Class<?>... params) {
        Class<?> c = start;
        while (c != null) {
            try {
                c.getDeclaredMethod(name, params);
                return c;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}

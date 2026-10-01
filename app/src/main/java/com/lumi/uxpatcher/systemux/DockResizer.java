package com.lumi.uxpatcher.systemux;

import android.util.Log;

import com.lumi.uxpatcher.firmware.FirmwareNames;

import static com.lumi.uxpatcher.Config.TAG;

    /** Asks vrshell's IResizeIPCService to resize the dock window, the same call SystemUX makes itself */
    final class DockResizer {
        private static final String DESC = FirmwareNames.RESIZE_IPC_DESCRIPTOR;
        private static final int TX_RESIZE = FirmwareNames.RESIZE_IPC_TRANSACTION;
        private static android.os.IBinder binder;
        private static boolean binding;
        private static int pendingW = -1, pendingH = -1;
        /** Weak, so a destroyed activity can be collected */
        private static java.lang.ref.WeakReference<android.app.Activity> owner;
        private static android.content.Context appCtx;
        private static int deadRetries;
        private static android.content.ServiceConnection conn;

        /** Binds early so the first resize does not wait. Uses the application context, an Activity would leak the connection. */
        static synchronized void prebind(android.content.Context ctx) {
            if (appCtx == null) {
                android.content.Context a = ctx.getApplicationContext();
                appCtx = a != null ? a : ctx;
            }
            if (binder != null && binder.isBinderAlive()) return;
            bind();
        }

        static synchronized void request(android.app.Activity act, int w, int h) {
            owner = new java.lang.ref.WeakReference<>(act);
            pendingW = w;
            pendingH = h;
            if (appCtx == null) {
                android.content.Context a = act.getApplicationContext();
                appCtx = a != null ? a : act;
            }
            if (binder != null && binder.isBinderAlive()) {
                flush();
                return;
            }
            bind();   // flush() runs from onServiceConnected
        }

        private static void bind() {
            if (binding) return;
            binding = true;
            try {
                if (conn != null) {   // replace a connection whose service process died
                    try { appCtx.unbindService(conn); } catch (Throwable ignored) { }
                    conn = null;
                }
                android.content.Intent i = new android.content.Intent(DESC);
                i.setPackage("com.oculus.vrshell");
                conn = new android.content.ServiceConnection() {
                    @Override public void onServiceConnected(android.content.ComponentName n,
                                                             android.os.IBinder b) {
                        synchronized (DockResizer.class) {
                            binder = b;
                            binding = false;
                        }
                        Log.i(TAG, "DOCK-WIDEN: IResizeIPCService connected");
                        flush();
                    }
                    @Override public void onServiceDisconnected(android.content.ComponentName n) {
                        synchronized (DockResizer.class) { binder = null; }
                        Log.w(TAG, "DOCK-WIDEN: IResizeIPCService disconnected (vrshell restarted?) "
                                + "-- will reconnect");
                    }
                };
                boolean ok = appCtx.bindService(i, conn, android.content.Context.BIND_AUTO_CREATE);
                Log.i(TAG, "DOCK-WIDEN: bindService(IResizeIPCService) -> " + ok);
                if (!ok) binding = false;
            } catch (Throwable t) {
                binding = false;
                Log.e(TAG, "DOCK-WIDEN: bind failed", t);
            }
        }

        private static void flush() {
            final android.app.Activity act;
            final android.os.IBinder b;
            final int w, h;
            synchronized (DockResizer.class) {
                act = owner != null ? owner.get() : null;
                b = binder; w = pendingW; h = pendingH;
            }
            if (act == null || b == null || w <= 0 || h <= 0) return;
            new Thread(new Runnable() {
                @Override public void run() {
                    android.os.Parcel data = android.os.Parcel.obtain();
                    android.os.Parcel reply = android.os.Parcel.obtain();
                    try {
                        Object token = token(act);
                        if (!(token instanceof android.os.Parcelable)) {
                            Log.e(TAG, "DOCK-WIDEN: no VolumetricWindowToken (" + token + ")");
                            return;
                        }
                        data.writeInterfaceToken(DESC);
                        data.writeInt(1);
                        ((android.os.Parcelable) token).writeToParcel(data, 0);
                        data.writeInt(w);
                        data.writeInt(h);
                        boolean ok = b.transact(TX_RESIZE, data, reply, 0);
                        reply.readException();
                        Log.i(TAG, "DOCK-WIDEN: resize " + w + "x" + h + " sent, transact=" + ok);
                        synchronized (DockResizer.class) { deadRetries = 0; }
                    } catch (android.os.DeadObjectException dead) {
                        // vrshell died: drop the binder and rebind (onServiceConnected re-sends the size)
                        boolean retry;
                        synchronized (DockResizer.class) {
                            if (binder == b) binder = null;
                            retry = deadRetries++ < 3;
                        }
                        Log.w(TAG, "DOCK-WIDEN: vrshell binder was dead, rebinding (retry=" + retry + ")");
                        if (retry) {
                            synchronized (DockResizer.class) { bind(); }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "DOCK-WIDEN: resize IPC failed", t);
                    } finally {
                        reply.recycle();
                        data.recycle();
                    }
                }
            }, "UXPatcher-resize").start();
        }

        private static Object token(android.app.Activity act) throws Throwable {
            Class<?> ext = Class.forName("horizonos.app.ActivityExt", false, act.getClassLoader());
            for (java.lang.reflect.Method m : ext.getDeclaredMethods()) {
                if (m.getName().equals("getVolumetricWindowToken")
                        && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0].isAssignableFrom(act.getClass())) {
                    m.setAccessible(true);
                    return m.invoke(null, act);
                }
            }
            throw new NoSuchMethodException("ActivityExt.getVolumetricWindowToken");
        }
    }

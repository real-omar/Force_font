package com.mrx7014.s25ultraspoofer;

import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module replicating the Oplus/OPPO UDFPS fix.
 *
 * frameworks/base patch touches three classes:
 *
 *  1. AuthController (SystemUI)
 *     - Adds oplusFpUiReady(boolean) as a method on the OUTER AuthController class.
 *     - The anonymous UdfpsController.Callback calls AuthController.this.oplusFpUiReady().
 *     - Correct hook: intercept UdfpsController's finger event dispatchers so we can
 *       call sysPropSet from the same moment the callback fires. We hook
 *       UdfpsController.onFingerDown() / onFingerUp() directly since those are what
 *       trigger the callback. Alternatively we hook AuthController's own
 *       onUdfpsPointerDown/Up if present, falling back to the callback inner class scan.
 *
 *  2. AuthService (system_server)
 *     - getUdfpsProps() reads persist.vendor.fingerprint.optical.sensorlocation (x::y)
 *       and iconsize, returns int[]{x, y, radius}.
 *
 *  3. FingerprintProvider (system_server)
 *     - addSensor() forces sensorType=TYPE_UDFPS_OPTICAL and halHandlesDisplayTouches=true
 *       when sensorLocations[0].sensorLocationX > 0.
 *
 * REQUIRED scope: com.android.systemui  +  android (system_server)
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PHH-OplusUdfpsFix";

    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String PKG_SYSTEM   = "android";

    private static final String CLS_AUTH_CONTROLLER =
            "com.android.systemui.biometrics.AuthController";
    private static final String CLS_UDFPS_CONTROLLER =
            "com.android.systemui.biometrics.UdfpsController";
    private static final String CLS_AUTH_SERVICE =
            "com.android.server.biometrics.AuthService";
    private static final String CLS_FP_PROVIDER =
            "com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider";
    private static final String CLS_FP_SENSOR_PROPS =
            "android.hardware.fingerprint.FingerprintSensorPropertiesInternal";

    private static final int TYPE_UDFPS_OPTICAL = 3;

    // -----------------------------------------------------------------------
    // SystemProperties via reflection (hidden API)
    // -----------------------------------------------------------------------
    private static String sysPropGet(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, def);
        } catch (Throwable t) {
            Log.e(TAG, "SystemProperties.get failed key=" + key, t);
            return def;
        }
    }

    private static void sysPropSet(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, key, value);
        } catch (Throwable t) {
            Log.e(TAG, "SystemProperties.set failed key=" + key + " val=" + value, t);
        }
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        Log.d(TAG, "handleLoadPackage: " + lpparam.packageName
                + " pid=" + android.os.Process.myPid());

        if (PKG_SYSTEMUI.equals(lpparam.packageName)) {
            hookAuthController(lpparam.classLoader);
        } else if (PKG_SYSTEM.equals(lpparam.packageName)) {
            hookAuthService(lpparam.classLoader);
            hookFingerprintProvider(lpparam.classLoader);
        }
    }

    // -----------------------------------------------------------------------
    // 1. AuthController – replicate oplusFpUiReady()
    //
    // The patch structure is:
    //
    //   class AuthController {
    //       private void oplusFpUiReady(boolean fingerDown) {
    //           SystemProperties.set("sys.phh.oplus.fppress", fingerDown ? "1" : "0");
    //       }
    //
    //       // inside some method that registers the callback:
    //       mUdfpsController.addCallback(new UdfpsController.Callback() {
    //           public void onFingerUp()   { oplusFpUiReady(false); }
    //           public void onFingerDown() { oplusFpUiReady(true); ... }
    //       });
    //   }
    //
    // oplusFpUiReady() is a method on AuthController itself. The anonymous
    // Callback inner class calls it via the implicit AuthController.this capture.
    //
    // STRATEGY: Hook UdfpsController.onFingerDown() / onFingerUp() directly.
    // These are the methods UdfpsController calls internally which then invoke
    // the registered callbacks. Hooking here is equivalent to hooking the
    // callback, but doesn't require locating anonymous inner classes at all.
    //
    // FALLBACK A: Hook AuthController directly for onUdfpsPointerDown/Up (Android 13+
    // refactor moved callback logic into AuthController itself in some branches).
    //
    // FALLBACK B: Scan AuthController$N inner classes for the callback (original
    // approach, kept as last resort).
    // -----------------------------------------------------------------------
    private void hookAuthController(ClassLoader cl) {

        // --- Primary: hook UdfpsController.onFingerDown / onFingerUp ---
        boolean hookedViaUdfpsController = false;
        try {
            Class<?> udfpsCls = XposedHelpers.findClass(CLS_UDFPS_CONTROLLER, cl);

            // onFingerDown fires when the HAL reports a touch on the sensor area
            int downCount = XposedBridge.hookAllMethods(udfpsCls, "onFingerDown",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sysPropSet("sys.phh.oplus.fppress", "1");
                            Log.d(TAG, "UdfpsController.onFingerDown → fppress=1");
                        }
                    });

            // onFingerUp fires when the finger lifts
            int upCount = XposedBridge.hookAllMethods(udfpsCls, "onFingerUp",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sysPropSet("sys.phh.oplus.fppress", "0");
                            Log.d(TAG, "UdfpsController.onFingerUp → fppress=0");
                        }
                    });

            if (downCount > 0 && upCount > 0) {
                hookedViaUdfpsController = true;
                Log.i(TAG, "Hooked UdfpsController onFingerDown/Up ("
                        + downCount + "/" + upCount + " methods)");
            } else {
                Log.w(TAG, "UdfpsController found but onFingerDown/Up count was 0 "
                        + "(down=" + downCount + " up=" + upCount + ")");
            }
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.w(TAG, "UdfpsController not found, trying fallbacks");
        }

        // --- Fallback A: AuthController.onUdfpsPointerDown / onUdfpsPointerUp ---
        // Android 13+ QPR2 and some vendor branches moved the logic into
        // AuthController directly under these method names.
        if (!hookedViaUdfpsController) {
            try {
                Class<?> authCls = XposedHelpers.findClass(CLS_AUTH_CONTROLLER, cl);

                int downCount = XposedBridge.hookAllMethods(authCls, "onUdfpsPointerDown",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                sysPropSet("sys.phh.oplus.fppress", "1");
                                Log.d(TAG, "AuthController.onUdfpsPointerDown → fppress=1");
                            }
                        });

                int upCount = XposedBridge.hookAllMethods(authCls, "onUdfpsPointerUp",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                sysPropSet("sys.phh.oplus.fppress", "0");
                                Log.d(TAG, "AuthController.onUdfpsPointerUp → fppress=0");
                            }
                        });

                if (downCount > 0 || upCount > 0) {
                    hookedViaUdfpsController = true;
                    Log.i(TAG, "Hooked AuthController onUdfpsPointerDown/Up");
                }
            } catch (XposedHelpers.ClassNotFoundError e) {
                Log.w(TAG, "AuthController not found for fallback A");
            }
        }

        // --- Fallback B: scan AuthController$N anonymous callback inner classes ---
        // Last resort: the original approach. We scan $1..$15 for the anonymous
        // UdfpsController.Callback that has both onFingerDown and onFingerUp.
        if (!hookedViaUdfpsController) {
            Log.w(TAG, "Falling back to AuthController inner class scan");
            boolean innerHooked = false;

            for (int i = 1; i <= 15; i++) {
                try {
                    Class<?> candidate = XposedHelpers.findClass(
                            CLS_AUTH_CONTROLLER + "$" + i, cl);
                    candidate.getDeclaredMethod("onFingerUp");
                    candidate.getDeclaredMethod("onFingerDown");

                    XposedBridge.hookAllMethods(candidate, "onFingerDown", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sysPropSet("sys.phh.oplus.fppress", "1");
                            Log.d(TAG, "AuthController$N.onFingerDown → fppress=1");
                        }
                    });

                    XposedBridge.hookAllMethods(candidate, "onFingerUp", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sysPropSet("sys.phh.oplus.fppress", "0");
                            Log.d(TAG, "AuthController$N.onFingerUp → fppress=0");
                        }
                    });

                    innerHooked = true;
                    Log.i(TAG, "Hooked AuthController inner callback: "
                            + CLS_AUTH_CONTROLLER + "$" + i);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // this $i doesn't have both methods
                } catch (XposedHelpers.ClassNotFoundError ignored) {
                    // $i doesn't exist
                }
            }

            if (!innerHooked) {
                Log.e(TAG, "ALL AuthController hook strategies failed. "
                        + "sys.phh.oplus.fppress will NOT be set.");
            }
        }
    }

    // -----------------------------------------------------------------------
    // 2. AuthService – inject Oplus UDFPS coords before getUdfpsProps() runs
    // RUNS IN: system_server (package "android")
    // -----------------------------------------------------------------------
    private void hookAuthService(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_AUTH_SERVICE, cl);
            int count = XposedBridge.hookAllMethods(cls, "getUdfpsProps",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String fpLocation = sysPropGet(
                                        "persist.vendor.fingerprint.optical.sensorlocation", "");
                                if (TextUtils.isEmpty(fpLocation) || !fpLocation.contains("::"))
                                    return;

                                String[] coords = fpLocation.split("::");
                                if (coords.length < 2) return;

                                int x = Integer.parseInt(coords[0].trim());
                                int y = Integer.parseInt(coords[1].trim());

                                String iconSizeStr = sysPropGet(
                                        "persist.vendor.fingerprint.optical.iconsize", "0");
                                int radius = Integer.parseInt(iconSizeStr.trim()) / 2;

                                int[] udfpsProps = {x, y, radius};
                                Log.d(TAG, "Oplus UDFPS props: " + Arrays.toString(udfpsProps));
                                param.setResult(udfpsProps);
                            } catch (Throwable t) {
                                Log.e(TAG, "getUdfpsProps hook error", t);
                            }
                        }
                    });
            Log.i(TAG, "Hooked AuthService#getUdfpsProps (" + count + " methods)");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.e(TAG, "AuthService not found – is 'android' in module scope?", e);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AuthService", t);
        }
    }

    // -----------------------------------------------------------------------
    // 3. FingerprintProvider – force TYPE_UDFPS_OPTICAL when sensorLocationX > 0
    // RUNS IN: system_server (package "android")
    // -----------------------------------------------------------------------
    private void hookFingerprintProvider(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_FP_PROVIDER, cl);
            int count = XposedBridge.hookAllMethods(cls, "addSensor",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object prop = null;
                                for (Object arg : param.args) {
                                    if (arg != null && arg.getClass().getName()
                                            .equals(CLS_FP_SENSOR_PROPS)) {
                                        prop = arg;
                                        break;
                                    }
                                }
                                if (prop == null) return;

                                Object[] sensorLocations = (Object[])
                                        XposedHelpers.getObjectField(prop, "sensorLocations");
                                if (sensorLocations == null || sensorLocations.length != 1)
                                    return;

                                int sensorLocationX = XposedHelpers.getIntField(
                                        sensorLocations[0], "sensorLocationX");
                                if (sensorLocationX <= 0) return;

                                Log.e(TAG, "Set fingerprint sensor type UDFPS Optical"
                                        + " (sensorLocationX=" + sensorLocationX + ")");
                                XposedHelpers.setIntField(prop, "sensorType", TYPE_UDFPS_OPTICAL);
                                XposedHelpers.setBooleanField(
                                        prop, "halHandlesDisplayTouches", true);
                            } catch (Throwable t) {
                                Log.e(TAG, "addSensor hook error", t);
                            }
                        }
                    });
            Log.i(TAG, "Hooked FingerprintProvider#addSensor (" + count + " methods)");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.e(TAG, "FingerprintProvider not found – is 'android' in module scope?", e);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook FingerprintProvider", t);
        }
    }
}

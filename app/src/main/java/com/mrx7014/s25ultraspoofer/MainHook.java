package com.mrx7014.s25ultraspoofer;

import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module replicating the Oplus/OPPO UDFPS fix originally applied as a
 * frameworks/base patch (PHH treble / phh-treble-based GSIs).
 *
 * Targets three classes:
 *  1. com.android.systemui.biometrics.AuthController
 *       – onFingerUp / onFingerDown  →  sets sys.phh.oplus.fppress
 *  2. com.android.server.biometrics.AuthService  (getUdfpsProps)
 *       – parses persist.vendor.fingerprint.optical.sensorlocation / iconsize
 *  3. com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider
 *       – forces sensorType = TYPE_UDFPS_OPTICAL and halHandlesDisplayTouches = true
 */
public class OplusUdfpsFix implements IXposedHookLoadPackage {

    private static final String TAG = "PHH-OplusUdfpsFix";

    // -----------------------------------------------------------------------
    // Package / class constants
    // -----------------------------------------------------------------------
    private static final String PKG_SYSTEMUI  = "com.android.systemui";
    private static final String PKG_SYSTEM    = "android"; // system_server runs as "android"

    private static final String CLS_AUTH_CONTROLLER =
            "com.android.systemui.biometrics.AuthController";
    private static final String CLS_UDFPS_CALLBACK =
            "com.android.systemui.biometrics.UdfpsController$Callback";

    private static final String CLS_AUTH_SERVICE =
            "com.android.server.biometrics.AuthService";

    private static final String CLS_FP_PROVIDER =
            "com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider";
    private static final String CLS_FP_SENSOR_PROPS =
            "android.hardware.fingerprint.FingerprintSensorPropertiesInternal";

    /** android.hardware.fingerprint.FingerprintSensorProperties.TYPE_UDFPS_OPTICAL = 3 */
    private static final int TYPE_UDFPS_OPTICAL = 3;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        switch (lpparam.packageName) {
            case PKG_SYSTEMUI:
                hookAuthController(lpparam.classLoader);
                break;
            case PKG_SYSTEM:
                hookAuthService(lpparam.classLoader);
                hookFingerprintProvider(lpparam.classLoader);
                break;
        }
    }

    // -----------------------------------------------------------------------
    // 1. AuthController – set sys.phh.oplus.fppress on finger up / down
    // -----------------------------------------------------------------------
    private void hookAuthController(ClassLoader cl) {
        /*
         * The patch adds calls to oplusFpUiReady() inside the anonymous
         * UdfpsController.Callback that AuthController registers.
         * Because the anonymous class is compiled into AuthController, we hook
         * the two callback methods directly on whatever class implements
         * UdfpsController$Callback and is instantiated by AuthController.
         *
         * Strategy: hook the concrete anonymous class that AuthController
         * creates. The easiest reliable approach is to hook the interface
         * methods on AuthController itself when it is used as the callback
         * implementor, OR to find the anonymous inner class.
         *
         * On AOSP the anonymous class is AuthController$3 (numbering varies).
         * We take a robust approach: iterate candidate inner classes, or
         * alternatively hook via the known method signatures on the outer class.
         *
         * Simplest & most compatible: Hook the two interface methods declared
         * in the anonymous class by scanning $1..$9 inner classes.
         */
        boolean hooked = false;
        for (int i = 1; i <= 10; i++) {
            try {
                Class<?> candidateCls = XposedHelpers.findClass(
                        CLS_AUTH_CONTROLLER + "$" + i, cl);

                // Check it implements UdfpsController.Callback by looking for
                // onFingerUp and onFingerDown methods
                candidateCls.getDeclaredMethod("onFingerUp");
                candidateCls.getDeclaredMethod("onFingerDown");

                hookUdfpsCallbackClass(candidateCls);
                hooked = true;
                Log.i(TAG, "Hooked UdfpsController.Callback impl: "
                        + candidateCls.getName());
                break;
            } catch (NoSuchMethodException | ClassNotFoundException ignored) {
                // not this one
            }
        }

        if (!hooked) {
            Log.w(TAG, "Could not find AuthController inner class implementing "
                    + "UdfpsController.Callback – falling back to interface hook");
            // Fallback: hook any class that has both methods via XposedBridge
            try {
                Class<?> callbackIface = XposedHelpers.findClass(CLS_UDFPS_CALLBACK, cl);
                // XposedBridge can hook interface default methods or we warn the user
                Log.w(TAG, "Interface-level hook not directly supported; "
                        + "AuthController sysprop hook may be inactive.");
            } catch (Throwable t) {
                Log.e(TAG, "Fallback also failed", t);
            }
        }
    }

    private void hookUdfpsCallbackClass(Class<?> cls) {
        // onFingerDown → sys.phh.oplus.fppress = "1"
        XposedBridge.hookAllMethods(cls, "onFingerDown", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    SystemProperties.set("sys.phh.oplus.fppress", "1");
                    Log.d(TAG, "onFingerDown: set sys.phh.oplus.fppress=1");
                } catch (Throwable t) {
                    Log.e(TAG, "onFingerDown hook error", t);
                }
            }
        });

        // onFingerUp → sys.phh.oplus.fppress = "0"
        XposedBridge.hookAllMethods(cls, "onFingerUp", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    SystemProperties.set("sys.phh.oplus.fppress", "0");
                    Log.d(TAG, "onFingerUp: set sys.phh.oplus.fppress=0");
                } catch (Throwable t) {
                    Log.e(TAG, "onFingerUp hook error", t);
                }
            }
        });
    }

    // -----------------------------------------------------------------------
    // 2. AuthService – inject Oplus UDFPS sensor location into getUdfpsProps()
    // -----------------------------------------------------------------------
    private void hookAuthService(ClassLoader cl) {
        /*
         * The patch inserts an early-return block inside getUdfpsProps() that
         * reads persist.vendor.fingerprint.optical.sensorlocation (format
         * "x::y") and persist.vendor.fingerprint.optical.iconsize, and returns
         * int[]{x, y, iconSize/2} before the existing generic logic runs.
         *
         * We replicate this by hooking the method and returning early when
         * those props are present.
         */
        try {
            Class<?> authServiceCls = XposedHelpers.findClass(CLS_AUTH_SERVICE, cl);

            XposedBridge.hookAllMethods(authServiceCls, "getUdfpsProps",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String fpLocation = SystemProperties.get(
                                        "persist.vendor.fingerprint.optical.sensorlocation", "");

                                if (!TextUtils.isEmpty(fpLocation)
                                        && fpLocation.contains("::")) {

                                    String[] coords = fpLocation.split("::");
                                    if (coords.length < 2) return;

                                    int x = Integer.parseInt(coords[0].trim());
                                    int y = Integer.parseInt(coords[1].trim());

                                    String iconSizeStr = SystemProperties.get(
                                            "persist.vendor.fingerprint.optical.iconsize", "0");
                                    int radius = Integer.parseInt(iconSizeStr.trim()) / 2;

                                    int[] udfpsProps = new int[]{x, y, radius};
                                    Log.d(TAG, "Oplus/OPPO UDFPS detected. Props: "
                                            + Arrays.toString(udfpsProps));

                                    // Return our result, skipping the original method
                                    param.setResult(udfpsProps);
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "getUdfpsProps hook error", t);
                            }
                        }
                    });

            Log.i(TAG, "Hooked AuthService#getUdfpsProps");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AuthService", t);
        }
    }

    // -----------------------------------------------------------------------
    // 3. FingerprintProvider – force TYPE_UDFPS_OPTICAL when location X > 0
    // -----------------------------------------------------------------------
    private void hookFingerprintProvider(ClassLoader cl) {
        /*
         * The patch runs just after the sensor location debug-log loop inside
         * addSensor() (or equivalent). It checks:
         *   prop.sensorLocations.length == 1 && prop.sensorLocations[0].sensorLocationX > 0
         * and if true sets:
         *   prop.sensorType = TYPE_UDFPS_OPTICAL (3)
         *   prop.halHandlesDisplayTouches = true
         *
         * We hook the method that constructs the Sensor object and mutate the
         * FingerprintSensorPropertiesInternal before it is consumed.
         *
         * The target method signature (AOSP android-14 / 15):
         *   private void addSensor(int sensorId,
         *       FingerprintSensorPropertiesInternal prop, ...)
         *
         * We use hookAllMethods to be version-agnostic.
         */
        try {
            Class<?> fpProviderCls =
                    XposedHelpers.findClass(CLS_FP_PROVIDER, cl);

            XposedBridge.hookAllMethods(fpProviderCls, "addSensor",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // Find the FingerprintSensorPropertiesInternal arg
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
                                        XposedHelpers.getObjectField(
                                                prop, "sensorLocations");

                                if (sensorLocations == null
                                        || sensorLocations.length != 1) return;

                                int sensorLocationX = XposedHelpers.getIntField(
                                        sensorLocations[0], "sensorLocationX");

                                if (sensorLocationX > 0) {
                                    Log.e(TAG, "Set fingerprint sensor type UDFPS Optical");
                                    XposedHelpers.setIntField(
                                            prop, "sensorType", TYPE_UDFPS_OPTICAL);
                                    XposedHelpers.setBooleanField(
                                            prop, "halHandlesDisplayTouches", true);
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "addSensor hook error", t);
                            }
                        }
                    });

            Log.i(TAG, "Hooked FingerprintProvider#addSensor");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook FingerprintProvider", t);
        }
    }
}

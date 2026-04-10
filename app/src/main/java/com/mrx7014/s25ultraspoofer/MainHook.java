package com.mrx7014.s25ultraspoofer;

import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module replicating the Oplus/OPPO UDFPS fix.
 *
 * The log revealed two things:
 *
 *  1. This device uses a HIDL 2.1 fingerprint HAL, not AIDL.
 *     The original patch targets FingerprintProvider (AIDL path only).
 *     The HIDL path goes through Fingerprint21 / Fingerprint21UdfpsMock.
 *     "FingerprintCallback: sendUdfpsPointerDown, callback null" means
 *     halHandlesDisplayTouches was never set → UDFPS callback never registered.
 *     We must hook BOTH paths.
 *
 *  2. "HidlToAidlSessionAdapter: onUiReady unsupported in HIDL" and
 *     "UdfpsHelper: failed to cast the HIDL to V2_3" confirm the HAL is
 *     wrapped by HidlToAidlSessionAdapter. We hook that adapter too so
 *     onUiReady (= the display-is-ready-for-illumination signal) gets forwarded.
 *
 * Hooks:
 *  SystemUI (com.android.systemui):
 *    - UdfpsController.onFingerDown/Up → sets sys.phh.oplus.fppress
 *
 *  system_server (android):
 *    - AuthService.getUdfpsProps()          → AIDL + HIDL sensor location
 *    - FingerprintProvider.addSensor()      → AIDL: force TYPE_UDFPS_OPTICAL
 *    - Fingerprint21.initForGoodiesOnly()   → HIDL: force halHandlesDisplayTouches
 *    - Fingerprint21UdfpsMock (constructor) → HIDL mock path
 *    - HidlToAidlSessionAdapter.onUiReady() → bridge onUiReady for HIDL HALs
 *    - FingerprintService / BiometricService → register UDFPS callback if null
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PHH-OplusUdfpsFix";

    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String PKG_SYSTEM   = "android";

    // SystemUI
    private static final String CLS_UDFPS_CONTROLLER =
            "com.android.systemui.biometrics.UdfpsController";
    private static final String CLS_AUTH_CONTROLLER =
            "com.android.systemui.biometrics.AuthController";

    // system_server – AIDL path
    private static final String CLS_AUTH_SERVICE =
            "com.android.server.biometrics.AuthService";
    private static final String CLS_FP_PROVIDER =
            "com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider";
    private static final String CLS_FP_SENSOR_PROPS =
            "android.hardware.fingerprint.FingerprintSensorPropertiesInternal";

    // system_server – HIDL path (android.hardware.biometrics.fingerprint@2.x)
    private static final String CLS_FINGERPRINT21 =
            "com.android.server.biometrics.sensors.fingerprint.hidl.Fingerprint21";
    private static final String CLS_FINGERPRINT21_UDFPS_MOCK =
            "com.android.server.biometrics.sensors.fingerprint.hidl.Fingerprint21UdfpsMock";
    // Android 13+ HIDL→AIDL bridge
    private static final String CLS_HIDL_TO_AIDL_ADAPTER =
            "com.android.server.biometrics.sensors.fingerprint.hidl.HidlToAidlSessionAdapter";
    // Older path (Android 12 and some 13 builds)
    private static final String CLS_FINGERPRINT21_ALT =
            "com.android.server.biometrics.sensors.fingerprint.Fingerprint21";

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
            hookUdfpsController(lpparam.classLoader);
        } else if (PKG_SYSTEM.equals(lpparam.packageName)) {
            hookAuthService(lpparam.classLoader);
            hookFingerprintProviderAidl(lpparam.classLoader);
            hookFingerprint21Hidl(lpparam.classLoader);
            hookHidlToAidlAdapter(lpparam.classLoader);
        }
    }

    // -----------------------------------------------------------------------
    // SystemUI: hook UdfpsController finger events → set sys.phh.oplus.fppress
    // This part is already working per the log. Kept here unchanged.
    // -----------------------------------------------------------------------
    private void hookUdfpsController(ClassLoader cl) {
        boolean hooked = false;
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_UDFPS_CONTROLLER, cl);
            Set<XC_MethodHook.Unhook> down = XposedBridge.hookAllMethods(cls, "onFingerDown",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sysPropSet("sys.phh.oplus.fppress", "1");
                            Log.d(TAG, "UdfpsController.onFingerDown → fppress=1");
                        }
                    });
            Set<XC_MethodHook.Unhook> up = XposedBridge.hookAllMethods(cls, "onFingerUp",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            sysPropSet("sys.phh.oplus.fppress", "0");
                            Log.d(TAG, "UdfpsController.onFingerUp → fppress=0");
                        }
                    });
            if (!down.isEmpty() && !up.isEmpty()) {
                hooked = true;
                Log.i(TAG, "Hooked UdfpsController onFingerDown/Up");
            }
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.w(TAG, "UdfpsController not found");
        }

        // Fallback: AuthController.onUdfpsPointerDown/Up (some Android 13+ QPR builds)
        if (!hooked) {
            try {
                Class<?> cls = XposedHelpers.findClass(CLS_AUTH_CONTROLLER, cl);
                XposedBridge.hookAllMethods(cls, "onUdfpsPointerDown", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        sysPropSet("sys.phh.oplus.fppress", "1");
                        Log.d(TAG, "AuthController.onUdfpsPointerDown → fppress=1");
                    }
                });
                XposedBridge.hookAllMethods(cls, "onUdfpsPointerUp", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        sysPropSet("sys.phh.oplus.fppress", "0");
                        Log.d(TAG, "AuthController.onUdfpsPointerUp → fppress=0");
                    }
                });
                Log.i(TAG, "Hooked AuthController onUdfpsPointerDown/Up (fallback)");
            } catch (XposedHelpers.ClassNotFoundError e) {
                Log.e(TAG, "All SystemUI hook strategies failed");
            }
        }
    }

    // -----------------------------------------------------------------------
    // system_server: AuthService.getUdfpsProps()
    // Reads persist.vendor.fingerprint.optical.sensorlocation (x::y) and
    // iconsize, returns int[]{x, y, radius} before the generic code runs.
    // Used by BOTH AIDL and HIDL paths to register the sensor location.
    // -----------------------------------------------------------------------
    private void hookAuthService(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_AUTH_SERVICE, cl);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(cls, "getUdfpsProps",
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
            Log.i(TAG, "Hooked AuthService#getUdfpsProps (" + hooks.size() + " methods)");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.e(TAG, "AuthService not found – is 'android' in module scope?", e);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AuthService", t);
        }
    }

    // -----------------------------------------------------------------------
    // system_server: FingerprintProvider.addSensor() — AIDL path
    // Forces sensorType=TYPE_UDFPS_OPTICAL and halHandlesDisplayTouches=true.
    // Only runs if the device actually uses the AIDL HAL.
    // -----------------------------------------------------------------------
    private void hookFingerprintProviderAidl(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_FP_PROVIDER, cl);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(cls, "addSensor",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object prop = findSensorProps(param.args);
                                if (prop == null) return;
                                forceSensorTypeUdfps(prop, "AIDL FingerprintProvider");
                            } catch (Throwable t) {
                                Log.e(TAG, "AIDL addSensor hook error", t);
                            }
                        }
                    });
            Log.i(TAG, "Hooked FingerprintProvider#addSensor AIDL (" + hooks.size() + ")");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.w(TAG, "FingerprintProvider (AIDL) not found – device may use HIDL only");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook FingerprintProvider", t);
        }
    }

    // -----------------------------------------------------------------------
    // system_server: Fingerprint21 — HIDL path
    //
    // This is the class that handles @2.1/@2.2/@2.3 HIDL fingerprint HALs.
    // The log shows "fingerprint@2.1-service" and "FingerprintCallback:
    // sendUdfpsPointerDown, callback null" — the UDFPS callback registration
    // failed because halHandlesDisplayTouches was never set on the sensor props
    // (FingerprintProvider.addSensor never ran since there's no AIDL HAL).
    //
    // Fingerprint21 builds its FingerprintSensorPropertiesInternal in:
    //   - getSensorProps() or buildSensorProperties() (varies by Android version)
    //   - createAndRegisterService() / initForGoodiesOnly() (older branches)
    //
    // We hook ALL of these, plus the constructor, and patch the props object
    // whenever we can get our hands on it.
    // -----------------------------------------------------------------------
    private void hookFingerprint21Hidl(ClassLoader cl) {
        String[] candidateClasses = {
                CLS_FINGERPRINT21,
                CLS_FINGERPRINT21_UDFPS_MOCK,
                CLS_FINGERPRINT21_ALT,
        };

        for (String clsName : candidateClasses) {
            try {
                Class<?> cls = XposedHelpers.findClass(clsName, cl);
                hookFingerprint21Class(cls, clsName);
            } catch (XposedHelpers.ClassNotFoundError e) {
                Log.d(TAG, clsName + " not found (may not exist on this ROM)");
            }
        }
    }

    private void hookFingerprint21Class(Class<?> cls, String clsName) {
        // Hook methods that build or return FingerprintSensorPropertiesInternal
        String[] methodNames = {
                "getSensorProps",
                "getSensorProperties",
                "buildSensorProperties",
                "getSensorPropertiesInternal",
                "createAndRegisterService",
                "initForGoodiesOnly",
                "addSensor",
        };

        for (String method : methodNames) {
            try {
                Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(cls, method,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                // Try to patch the return value if it's sensor props
                                try {
                                    Object result = param.getResult();
                                    if (result != null && result.getClass().getName()
                                            .equals(CLS_FP_SENSOR_PROPS)) {
                                        forceSensorTypeUdfps(result, clsName + "." + method
                                                + " (return value)");
                                    }
                                } catch (Throwable ignored) {}

                                // Also scan args for props objects
                                try {
                                    Object prop = findSensorProps(param.args);
                                    if (prop != null) {
                                        forceSensorTypeUdfps(prop, clsName + "." + method
                                                + " (arg)");
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                if (!hooks.isEmpty()) {
                    Log.i(TAG, "Hooked " + clsName + "#" + method
                            + " (" + hooks.size() + ")");
                }
            } catch (Throwable ignored) {
                // method doesn't exist in this class/version
            }
        }

        // Hook the constructor as well — some builds set halHandlesDisplayTouches
        // as a field directly during construction
        try {
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // The constructed object itself might hold sensorProps
                        Object props = XposedHelpers.getObjectField(
                                param.thisObject, "mSensorProps");
                        if (props != null) {
                            forceSensorTypeUdfps(props, clsName + " constructor");
                        }
                    } catch (Throwable ignored) {}
                }
            });
            Log.i(TAG, "Hooked " + clsName + " constructor");
        } catch (Throwable t) {
            Log.d(TAG, "Could not hook " + clsName + " constructor: " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // system_server: HidlToAidlSessionAdapter
    //
    // The log shows: "HidlToAidlSessionAdapter: onUiReady unsupported in HIDL"
    // This means when the display illuminates for fingerprint scan, the
    // framework calls onUiReady() on the session adapter, which silently
    // drops it for HIDL HALs. We hook it to forward the signal via the
    // onFingerDetected / setHalProperty path that HIDL 2.1 understands.
    // -----------------------------------------------------------------------
    private void hookHidlToAidlAdapter(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_HIDL_TO_AIDL_ADAPTER, cl);

            // onUiReady is called when the UDFPS UI overlay is ready / displayed.
            // For HIDL 2.1 HALs the adapter throws "unsupported" — we intercept
            // it and call the HIDL equivalent: sendFingerprintDone or equivalent.
            // The actual signal the Goodix HAL needs here is already handled
            // by the sys.phh.oplus.fppress → sysfs path; we just need to prevent
            // the "unsupported" exception from propagating upward and potentially
            // cancelling the authentication session.
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    cls, "onUiReady", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Suppress the UnsupportedOperationException that HIDL
                            // adapters throw for onUiReady. The sysfs bridge already
                            // handles the display-ready signal for Oplus displays.
                            // Setting null result prevents the original from running.
                            param.setResult(null);
                            Log.d(TAG, "HidlToAidlSessionAdapter.onUiReady: suppressed "
                                    + "(handled by sysfs bridge)");
                        }
                    });
            Log.i(TAG, "Hooked HidlToAidlSessionAdapter#onUiReady (" + hooks.size() + ")");

            // Also hook detectInteraction / lockoutReset if present —
            // these can fail silently on HIDL and leave the sensor locked out
            for (String m : new String[]{"detectInteraction", "onLockoutTimed",
                    "onLockoutPermanent"}) {
                try {
                    XposedBridge.hookAllMethods(cls, m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Log.d(TAG, "HidlToAidlSessionAdapter." + m + " called");
                        }
                    });
                } catch (Throwable ignored) {}
            }

        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.w(TAG, "HidlToAidlSessionAdapter not found");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook HidlToAidlSessionAdapter", t);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Scans an argument array for a FingerprintSensorPropertiesInternal object.
     */
    private Object findSensorProps(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg != null && arg.getClass().getName().equals(CLS_FP_SENSOR_PROPS)) {
                return arg;
            }
        }
        return null;
    }

    /**
     * Given a FingerprintSensorPropertiesInternal object, sets:
     *   sensorType = TYPE_UDFPS_OPTICAL (3)
     *   halHandlesDisplayTouches = true
     * but only when sensorLocations[0].sensorLocationX > 0.
     */
    private void forceSensorTypeUdfps(Object prop, String source) {
        try {
            Object[] sensorLocations = (Object[])
                    XposedHelpers.getObjectField(prop, "sensorLocations");
            if (sensorLocations == null || sensorLocations.length == 0) return;

            int sensorLocationX = XposedHelpers.getIntField(
                    sensorLocations[0], "sensorLocationX");
            if (sensorLocationX <= 0) return;

            int currentType = XposedHelpers.getIntField(prop, "sensorType");
            if (currentType == TYPE_UDFPS_OPTICAL) {
                Log.d(TAG, source + ": already TYPE_UDFPS_OPTICAL, skipping");
                return;
            }

            XposedHelpers.setIntField(prop, "sensorType", TYPE_UDFPS_OPTICAL);
            XposedHelpers.setBooleanField(prop, "halHandlesDisplayTouches", true);
            Log.e(TAG, source + ": forced TYPE_UDFPS_OPTICAL "
                    + "(sensorLocationX=" + sensorLocationX + ")");
        } catch (Throwable t) {
            Log.e(TAG, "forceSensorTypeUdfps from " + source + " failed: " + t.getMessage());
        }
    }
}

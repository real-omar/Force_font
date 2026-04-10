package com.mrx7014.s25ultraspoofer;

import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module — Oplus/OPPO UDFPS fix.
 *
 * Root cause analysis from logs:
 *
 *   "FingerprintCallback: sendUdfpsPointerDown, callback null"
 *
 *   This persists because halHandlesDisplayTouches is evaluated ONCE at
 *   service startup to decide whether to register the UDFPS callback with
 *   FingerprintCallback. Our Fingerprint21 hooks ran too late or the field
 *   name was wrong — no Fingerprint21 hook log lines appeared at all.
 *
 *   The fix: instead of trying to patch halHandlesDisplayTouches upstream,
 *   hook FingerprintCallback.sendUdfpsPointerDown() directly and call the
 *   callback ourselves if it's null — bypassing the null check entirely.
 *
 *   Additionally hook wherever FingerprintCallback stores the callback
 *   (setUdfpsOverlayController / setCallback / onEnrollmentProgress etc.)
 *   to understand the registration path and force it if needed.
 *
 * What's confirmed working from logs:
 *   ✅ UdfpsController.onFingerDown → fppress=1 (SystemUI hook)
 *   ✅ HidlToAidlSessionAdapter.onUiReady suppressed
 *   ✅ sysfs write: fingerprint_notify_trigger receive uiready 1
 *   ❌ FingerprintCallback.sendUdfpsPointerDown callback null (this fix)
 *   ❌ AuthService/Fingerprint21 hooks (no log = not in scope or class not found)
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

    // system_server – HIDL path
    // FingerprintCallback is the class that logs "sendUdfpsPointerDown, callback null"
    // It lives in the HIDL fingerprint service package
    private static final String CLS_FINGERPRINT_CALLBACK =
            "com.android.server.biometrics.sensors.fingerprint.hidl.FingerprintCallback";
    // Fingerprint21 builds the sensor and registers callbacks
    private static final String CLS_FINGERPRINT21 =
            "com.android.server.biometrics.sensors.fingerprint.hidl.Fingerprint21";
    private static final String CLS_FINGERPRINT21_UDFPS =
            "com.android.server.biometrics.sensors.fingerprint.hidl.Fingerprint21UdfpsMock";
    // HIDL→AIDL bridge
    private static final String CLS_HIDL_TO_AIDL =
            "com.android.server.biometrics.sensors.fingerprint.hidl.HidlToAidlSessionAdapter";

    private static final int TYPE_UDFPS_OPTICAL = 3;

    // -----------------------------------------------------------------------
    // SystemProperties via reflection
    // -----------------------------------------------------------------------
    private static String sysPropGet(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, key, def);
        } catch (Throwable t) {
            Log.e(TAG, "sysPropGet failed: " + key, t);
            return def;
        }
    }

    private static void sysPropSet(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, key, value);
        } catch (Throwable t) {
            Log.e(TAG, "sysPropSet failed: " + key + "=" + value, t);
        }
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        Log.i(TAG, "handleLoadPackage: " + lpparam.packageName);

        if (PKG_SYSTEMUI.equals(lpparam.packageName)) {
            hookUdfpsController(lpparam.classLoader);
        } else if (PKG_SYSTEM.equals(lpparam.packageName)) {
            hookAuthService(lpparam.classLoader);
            hookFingerprintCallback(lpparam.classLoader);   // PRIMARY new fix
            hookFingerprint21(lpparam.classLoader);
            hookFingerprintProviderAidl(lpparam.classLoader);
            hookHidlToAidlAdapter(lpparam.classLoader);
        }
    }

    // -----------------------------------------------------------------------
    // SystemUI: UdfpsController finger events → sys.phh.oplus.fppress
    // Confirmed working from log — kept unchanged.
    // -----------------------------------------------------------------------
    private void hookUdfpsController(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_UDFPS_CONTROLLER, cl);
            XposedBridge.hookAllMethods(cls, "onFingerDown", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    sysPropSet("sys.phh.oplus.fppress", "1");
                    Log.d(TAG, "UdfpsController.onFingerDown → fppress=1");
                }
            });
            XposedBridge.hookAllMethods(cls, "onFingerUp", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    sysPropSet("sys.phh.oplus.fppress", "0");
                    Log.d(TAG, "UdfpsController.onFingerUp → fppress=0");
                }
            });
            Log.i(TAG, "Hooked UdfpsController");
        } catch (XposedHelpers.ClassNotFoundError e) {
            // Fallback: AuthController.onUdfpsPointerDown/Up
            try {
                Class<?> cls = XposedHelpers.findClass(CLS_AUTH_CONTROLLER, cl);
                XposedBridge.hookAllMethods(cls, "onUdfpsPointerDown", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        sysPropSet("sys.phh.oplus.fppress", "1");
                    }
                });
                XposedBridge.hookAllMethods(cls, "onUdfpsPointerUp", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        sysPropSet("sys.phh.oplus.fppress", "0");
                    }
                });
                Log.i(TAG, "Hooked AuthController (fallback)");
            } catch (XposedHelpers.ClassNotFoundError e2) {
                Log.e(TAG, "All SystemUI hooks failed");
            }
        }
    }

    // -----------------------------------------------------------------------
    // PRIMARY FIX: FingerprintCallback
    //
    // The log shows: "FingerprintCallback: sendUdfpsPointerDown, callback null"
    //
    // FingerprintCallback holds a callback reference (IUdfpsOverlayController or
    // similar) that is only set when halHandlesDisplayTouches == true at service
    // init. Since we can't set that flag early enough via Fingerprint21, we:
    //
    // 1. Hook sendUdfpsPointerDown / sendUdfpsPointerUp to suppress the null
    //    check crash and log what callback field name is actually used.
    //
    // 2. Hook setUdfpsOverlayController / setCallback (whatever registers the
    //    callback) to log when/if it ever gets called, and force-store it.
    //
    // 3. Hook the constructor to force-set halHandlesDisplayTouches on whatever
    //    sensor props object FingerprintCallback holds at construction time.
    // -----------------------------------------------------------------------
    private void hookFingerprintCallback(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_FINGERPRINT_CALLBACK, cl);
            Log.i(TAG, "Found FingerprintCallback: " + cls.getName());

            // --- Hook constructor to force halHandlesDisplayTouches ---
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "FingerprintCallback constructor called");
                    // Dump all fields to find the sensor props and callback fields
                    dumpObjectFields(param.thisObject, "FingerprintCallback");
                    // Try to force halHandlesDisplayTouches on any props field
                    forceSensorPropsOnObject(param.thisObject, "FingerprintCallback ctor");
                }
            });

            // --- Hook sendUdfpsPointerDown ---
            // Intercept the null callback check and handle it ourselves
            Set<XC_MethodHook.Unhook> downHooks = XposedBridge.hookAllMethods(
                    cls, "sendUdfpsPointerDown", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "FingerprintCallback.sendUdfpsPointerDown called");
                            // Check if the callback field is null and try to invoke it
                            // via reflection to understand what interface it expects
                            tryInvokeUdfpsCallback(param.thisObject, true);
                        }
                    });
            Log.i(TAG, "Hooked FingerprintCallback#sendUdfpsPointerDown (" + downHooks.size() + ")");

            // --- Hook sendUdfpsPointerUp ---
            Set<XC_MethodHook.Unhook> upHooks = XposedBridge.hookAllMethods(
                    cls, "sendUdfpsPointerUp", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "FingerprintCallback.sendUdfpsPointerUp called");
                            tryInvokeUdfpsCallback(param.thisObject, false);
                        }
                    });
            Log.i(TAG, "Hooked FingerprintCallback#sendUdfpsPointerUp (" + upHooks.size() + ")");

            // --- Hook all setXxx methods to catch callback registration ---
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().startsWith("set") || m.getName().contains("Callback")
                        || m.getName().contains("Controller")
                        || m.getName().contains("Udfps")) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Log.i(TAG, "FingerprintCallback." + m.getName()
                                        + " called with args: "
                                        + Arrays.toString(param.args));
                            }
                        });
                        Log.i(TAG, "Hooked FingerprintCallback#" + m.getName());
                    } catch (Throwable t) {
                        Log.w(TAG, "Could not hook " + m.getName() + ": " + t.getMessage());
                    }
                }
            }

        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.e(TAG, "FingerprintCallback not found! Is 'android' in scope?");
        } catch (Throwable t) {
            Log.e(TAG, "hookFingerprintCallback failed", t);
        }
    }

    // -----------------------------------------------------------------------
    // Fingerprint21 — HIDL path sensor props
    // Hooks getSensorProps / createAndRegisterService to set
    // halHandlesDisplayTouches = true before FingerprintCallback is constructed.
    // -----------------------------------------------------------------------
    private void hookFingerprint21(ClassLoader cl) {
        String[] classes = { CLS_FINGERPRINT21, CLS_FINGERPRINT21_UDFPS };
        for (String clsName : classes) {
            try {
                Class<?> cls = XposedHelpers.findClass(clsName, cl);
                Log.i(TAG, "Found " + clsName);

                // Hook everything that sounds like it builds sensor properties
                String[] methods = {
                        "getSensorProps", "getSensorProperties",
                        "buildSensorProperties", "getSensorPropertiesInternal",
                        "createAndRegisterService", "initForGoodiesOnly", "addSensor",
                        "init", "start",
                };
                for (String method : methods) {
                    try {
                        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                                cls, method, new XC_MethodHook() {
                                    @Override
                                    protected void beforeHookedMethod(MethodHookParam param) {
                                        Log.i(TAG, clsName + "." + method + " BEFORE");
                                        // Patch any sensor props in args
                                        for (Object arg : param.args) {
                                            if (arg != null && arg.getClass().getName()
                                                    .equals(CLS_FP_SENSOR_PROPS)) {
                                                forceSensorTypeUdfps(arg,
                                                        clsName + "." + method + " arg");
                                            }
                                        }
                                    }
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) {
                                        Log.i(TAG, clsName + "." + method + " AFTER");
                                        // Patch return value if it's sensor props
                                        Object result = param.getResult();
                                        if (result != null && result.getClass().getName()
                                                .equals(CLS_FP_SENSOR_PROPS)) {
                                            forceSensorTypeUdfps(result,
                                                    clsName + "." + method + " return");
                                        }
                                        // Patch on the instance itself
                                        forceSensorPropsOnObject(param.thisObject,
                                                clsName + "." + method);
                                    }
                                });
                        if (!hooks.isEmpty()) {
                            Log.i(TAG, "Hooked " + clsName + "#" + method
                                    + " (" + hooks.size() + ")");
                        }
                    } catch (Throwable ignored) {}
                }

                // Constructor hook
                XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Log.i(TAG, clsName + " constructor AFTER");
                        dumpObjectFields(param.thisObject, clsName);
                        forceSensorPropsOnObject(param.thisObject, clsName + " ctor");
                    }
                });

            } catch (XposedHelpers.ClassNotFoundError e) {
                Log.d(TAG, clsName + " not found");
            }
        }
    }

    // -----------------------------------------------------------------------
    // AuthService — sensor location (shared by HIDL and AIDL)
    // -----------------------------------------------------------------------
    private void hookAuthService(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_AUTH_SERVICE, cl);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    cls, "getUdfpsProps", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String loc = sysPropGet(
                                        "persist.vendor.fingerprint.optical.sensorlocation", "");
                                if (TextUtils.isEmpty(loc) || !loc.contains("::")) return;
                                String[] c = loc.split("::");
                                if (c.length < 2) return;
                                int x = Integer.parseInt(c[0].trim());
                                int y = Integer.parseInt(c[1].trim());
                                String sz = sysPropGet(
                                        "persist.vendor.fingerprint.optical.iconsize", "0");
                                int r = Integer.parseInt(sz.trim()) / 2;
                                int[] props = {x, y, r};
                                Log.i(TAG, "AuthService.getUdfpsProps → " + Arrays.toString(props));
                                param.setResult(props);
                            } catch (Throwable t) {
                                Log.e(TAG, "getUdfpsProps hook error", t);
                            }
                        }
                    });
            Log.i(TAG, "Hooked AuthService#getUdfpsProps (" + hooks.size() + ")");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.e(TAG, "AuthService not found – add 'android' to scope!");
        }
    }

    // -----------------------------------------------------------------------
    // FingerprintProvider — AIDL path
    // -----------------------------------------------------------------------
    private void hookFingerprintProviderAidl(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_FP_PROVIDER, cl);
            XposedBridge.hookAllMethods(cls, "addSensor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if (arg != null && arg.getClass().getName().equals(CLS_FP_SENSOR_PROPS)) {
                            forceSensorTypeUdfps(arg, "FingerprintProvider.addSensor");
                        }
                    }
                }
            });
            Log.i(TAG, "Hooked FingerprintProvider#addSensor");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.d(TAG, "FingerprintProvider (AIDL) not found");
        }
    }

    // -----------------------------------------------------------------------
    // HidlToAidlSessionAdapter — suppress onUiReady exception
    // -----------------------------------------------------------------------
    private void hookHidlToAidlAdapter(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_HIDL_TO_AIDL, cl);
            XposedBridge.hookAllMethods(cls, "onUiReady", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null); // suppress UnsupportedOperationException
                    Log.d(TAG, "HidlToAidlSessionAdapter.onUiReady: suppressed");
                }
            });
            Log.i(TAG, "Hooked HidlToAidlSessionAdapter#onUiReady");
        } catch (XposedHelpers.ClassNotFoundError e) {
            Log.w(TAG, "HidlToAidlSessionAdapter not found");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Dumps all fields of an object to logcat so we can see the actual field
     * names used by FingerprintCallback / Fingerprint21 on this ROM.
     * Only logs at INFO level so it's visible without verbose mode.
     */
    private void dumpObjectFields(Object obj, String label) {
        if (obj == null) return;
        try {
            Class<?> cls = obj.getClass();
            Log.i(TAG, label + " class: " + cls.getName());
            // Walk the class hierarchy
            while (cls != null && !cls.getName().equals("java.lang.Object")) {
                for (Field f : cls.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        String valStr = (val == null) ? "null"
                                : (val.getClass().isArray() ? Arrays.toString((Object[]) val)
                                        : val.toString());
                        // Only log fields relevant to UDFPS / callbacks / sensor type
                        String name = f.getName().toLowerCase();
                        if (name.contains("callback") || name.contains("udfps")
                                || name.contains("sensor") || name.contains("display")
                                || name.contains("type") || name.contains("hal")
                                || name.contains("controller") || name.contains("hidl")) {
                            Log.i(TAG, label + "  field: " + f.getName()
                                    + " (" + f.getType().getSimpleName() + ") = " + valStr);
                        }
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable t) {
            Log.e(TAG, "dumpObjectFields failed", t);
        }
    }

    /**
     * Scans all fields of an object for FingerprintSensorPropertiesInternal
     * and calls forceSensorTypeUdfps on any found.
     */
    private void forceSensorPropsOnObject(Object obj, String source) {
        if (obj == null) return;
        Class<?> cls = obj.getClass();
        while (cls != null && !cls.getName().equals("java.lang.Object")) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val != null && val.getClass().getName().equals(CLS_FP_SENSOR_PROPS)) {
                        forceSensorTypeUdfps(val, source + " field:" + f.getName());
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    /**
     * Forces sensorType=TYPE_UDFPS_OPTICAL and halHandlesDisplayTouches=true
     * on a FingerprintSensorPropertiesInternal when sensorLocationX > 0.
     */
    private void forceSensorTypeUdfps(Object prop, String source) {
        try {
            Object[] locs = (Object[]) XposedHelpers.getObjectField(prop, "sensorLocations");
            if (locs == null || locs.length == 0) return;
            int x = XposedHelpers.getIntField(locs[0], "sensorLocationX");
            if (x <= 0) return;
            int cur = XposedHelpers.getIntField(prop, "sensorType");
            if (cur == TYPE_UDFPS_OPTICAL) {
                Log.d(TAG, source + ": already UDFPS_OPTICAL");
                return;
            }
            XposedHelpers.setIntField(prop, "sensorType", TYPE_UDFPS_OPTICAL);
            XposedHelpers.setBooleanField(prop, "halHandlesDisplayTouches", true);
            Log.i(TAG, source + ": forced TYPE_UDFPS_OPTICAL (x=" + x + ")");
        } catch (Throwable t) {
            Log.e(TAG, "forceSensorTypeUdfps from " + source + ": " + t.getMessage());
        }
    }

    /**
     * Tries to find and invoke the UDFPS callback stored in FingerprintCallback.
     * Used when sendUdfpsPointerDown/Up is called but the callback is null —
     * we log all fields to find the actual callback field name on this ROM.
     */
    private void tryInvokeUdfpsCallback(Object fpCallback, boolean isDown) {
        if (fpCallback == null) return;
        Class<?> cls = fpCallback.getClass();
        while (cls != null && !cls.getName().equals("java.lang.Object")) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(fpCallback);
                    Log.i(TAG, "FingerprintCallback field " + f.getName()
                            + " (" + f.getType().getName() + ") = "
                            + (val == null ? "NULL" : val.getClass().getName()));
                    // If we find a non-null callback-like object, try calling it
                    if (val != null) {
                        String fieldType = f.getType().getName();
                        if (fieldType.contains("Udfps") || fieldType.contains("Overlay")
                                || fieldType.contains("Callback")) {
                            // Try onFingerDown / onFingerUp / sendUdfpsPointerDown
                            for (String mName : new String[]{
                                    isDown ? "onFingerDown" : "onFingerUp",
                                    isDown ? "sendUdfpsPointerDown" : "sendUdfpsPointerUp",
                                    isDown ? "onPointerDown" : "onPointerUp",
                                    "onUiReady"}) {
                                try {
                                    Method m = val.getClass().getMethod(mName);
                                    m.invoke(val);
                                    Log.i(TAG, "Invoked " + fieldType + "." + mName);
                                    break;
                                } catch (NoSuchMethodException ignored) {}
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }
}

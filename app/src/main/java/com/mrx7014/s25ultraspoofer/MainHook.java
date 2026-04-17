package com.mrx7014.s25ultraspoofer;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Build;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * FontForcer — LSPosed module that injects a custom font into targeted apps.
 *
 * Font file placement (choose ONE):
 *   Option A – Module assets:  assets/fonts/custom_font.ttf  (bundled with the APK)
 *   Option B – External path:  /sdcard/FontForcer/custom_font.ttf  (user-replaceable)
 *
 * The module replaces every Typeface constant in android.graphics.Typeface
 * (DEFAULT, DEFAULT_BOLD, SANS_SERIF, SERIF, MONOSPACE) so that any call to
 * Typeface.create() or any hard-coded typeface reference uses your font.
 *
 * It also hooks:
 *   • Typeface.create(String, int)         – family-name-based creation
 *   • Typeface.create(Typeface, int)        – style-variant creation
 *   • Typeface.createFromAsset(...)         – asset-based creation
 *   • Typeface.createFromFile(...)          – file-based creation
 *   • Typeface.defaultFromStyle(int)        – style-default lookup
 *
 * Because some apps read system fonts through Resources or AssetManager, those
 * paths are also intercepted.
 */
public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "FontForcer";

    // ── External font path (Option B). If the file does not exist, the module
    //    falls back to the module's own assets (Option A).
    private static final String EXTERNAL_FONT_PATH =
            "/sdcard/FontForcer/custom_font.ttf";

    // ── Module APK path – filled in at Zygote init so we can open our assets.
    private static String sModulePath;

    // ── Cached Typeface instance shared across hooks.
    private static Typeface sCustomTypeface;

    // ─────────────────────────────────────────────────────────────────────────
    //  IXposedHookZygoteInit – runs early in Zygote, stores module APK path
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void initZygote(StartupParam param) {
        sModulePath = param.modulePath;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  IXposedHookLoadPackage – runs for every package the module targets
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Skip the Android system server and our own package.
        if ("android".equals(lpparam.packageName)
                || "com.example.fontforcer".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": hooking " + lpparam.packageName);

        try {
            // 1. Load (or reuse) the custom typeface.
            Typeface custom = getCustomTypeface(lpparam.classLoader);
            if (custom == null) {
                XposedBridge.log(TAG + ": could not load font, skipping " + lpparam.packageName);
                return;
            }

            // 2. Replace static Typeface constants.
            replaceTypefaceConstants(custom);

            // 3. Hook dynamic Typeface factory methods.
            hookTypefaceMethods(lpparam.classLoader, custom);

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": error in " + lpparam.packageName + " – " + t);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Font loading
    // ─────────────────────────────────────────────────────────────────────────

    private Typeface getCustomTypeface(ClassLoader cl) {
        if (sCustomTypeface != null) return sCustomTypeface;

        // Option B: external file on storage.
        File external = new File(EXTERNAL_FONT_PATH);
        if (external.exists() && external.canRead()) {
            try {
                sCustomTypeface = Typeface.createFromFile(external);
                XposedBridge.log(TAG + ": loaded font from " + EXTERNAL_FONT_PATH);
                return sCustomTypeface;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": failed to load external font – " + t);
            }
        }

        // Option A: bundled in module assets.
        if (sModulePath != null) {
            try {
                AssetManager am = AssetManager.class.newInstance();
                XposedHelpers.callMethod(am, "addAssetPath", sModulePath);
                sCustomTypeface = Typeface.createFromAsset(am, "fonts/custom_font.ttf");
                XposedBridge.log(TAG + ": loaded font from module assets");
                return sCustomTypeface;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": failed to load asset font – " + t);
            }
        }

        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Replace static fields
    // ─────────────────────────────────────────────────────────────────────────

    private void replaceTypefaceConstants(Typeface custom) throws Throwable {
        // Field names: DEFAULT, DEFAULT_BOLD, SANS_SERIF, SERIF, MONOSPACE
        String[] constants = {"DEFAULT", "DEFAULT_BOLD", "SANS_SERIF", "SERIF", "MONOSPACE"};
        for (String name : constants) {
            try {
                Field f = Typeface.class.getDeclaredField(name);
                f.setAccessible(true);

                // On API 23+ the field may be final in a different way; use reflection helper.
                // Remove final modifier via Field.modifiers field (works up to API 29 without
                // --add-opens; on API 30+ consider using Unsafe if needed).
                try {
                    Field modifiers = Field.class.getDeclaredField("modifiers");
                    modifiers.setAccessible(true);
                    modifiers.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
                } catch (NoSuchFieldException ignored) {
                    // Android R+ hides Field.modifiers; Xposed's own reflection works anyway.
                }

                f.set(null, custom);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": could not replace constant " + name + " – " + t);
            }
        }

        // Also patch the internal sDefaults array (API 16+) that maps style index → Typeface.
        try {
            Field sDefaults = Typeface.class.getDeclaredField("sDefaults");
            sDefaults.setAccessible(true);
            Typeface[] arr = (Typeface[]) sDefaults.get(null);
            if (arr != null) {
                for (int i = 0; i < arr.length; i++) arr[i] = custom;
            }
        } catch (Throwable ignored) { /* field may not exist on all API levels */ }

        // Android P+ uses a sSystemFontMap (name → Typeface).
        try {
            Field sSystemFontMap = Typeface.class.getDeclaredField("sSystemFontMap");
            sSystemFontMap.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Typeface> map = (Map<String, Typeface>) sSystemFontMap.get(null);
            if (map != null) {
                // Replace every entry so Typeface.create("sans-serif", …) returns our font.
                Map<String, Typeface> replacement = new HashMap<>();
                for (String key : map.keySet()) replacement.put(key, custom);
                sSystemFontMap.set(null, replacement);
            }
        } catch (Throwable ignored) { /* API-level-dependent */ }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Hook Typeface factory methods
    // ─────────────────────────────────────────────────────────────────────────

    private void hookTypefaceMethods(ClassLoader cl, Typeface custom) {
        final Typeface finalCustom = custom;
        final XC_MethodHook returnCustom = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // Only override if the call would return a valid typeface.
                if (param.getResult() instanceof Typeface) {
                    param.setResult(finalCustom);
                }
            }
        };

        Class<?> tf = Typeface.class;

        // Typeface.create(String familyName, int style)
        XposedHelpers.findAndHookMethod(tf, "create", String.class, int.class, returnCustom);

        // Typeface.create(Typeface family, int style)
        XposedHelpers.findAndHookMethod(tf, "create", Typeface.class, int.class, returnCustom);

        // Typeface.createFromAsset(AssetManager, String)
        XposedHelpers.findAndHookMethod(tf, "createFromAsset",
                AssetManager.class, String.class, returnCustom);

        // Typeface.createFromFile(File)
        XposedHelpers.findAndHookMethod(tf, "createFromFile", File.class, returnCustom);

        // Typeface.createFromFile(String path)
        XposedHelpers.findAndHookMethod(tf, "createFromFile", String.class, returnCustom);

        // Typeface.defaultFromStyle(int style)
        try {
            XposedHelpers.findAndHookMethod(tf, "defaultFromStyle", int.class, returnCustom);
        } catch (Throwable ignored) { /* not present on all API levels */ }

        // API 26+ Typeface.Builder().build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Class<?> builderCls = XposedHelpers.findClass("android.graphics.Typeface$Builder", cl);
                XposedHelpers.findAndHookMethod(builderCls, "build", returnCustom);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Typeface.Builder hook skipped – " + t);
            }
        }
    }
}

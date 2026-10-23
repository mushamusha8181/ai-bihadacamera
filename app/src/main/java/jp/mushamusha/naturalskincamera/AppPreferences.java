package jp.mushamusha.naturalskincamera;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.KeyStore;

public final class AppPreferences {
    private static final String PREFS = "camera_preferences";
    private static final String LEGACY_APP_PREFS = "app";
    private static final String LEGACY_SECURE_PREFS = "secure_settings";
    private static final String LEGACY_DEEPSEEK_PREF_KEY = "deepseek_api_key";
    private static final String LEGACY_DEEPSEEK_KEYSTORE_ALIAS = "natural_skin_camera_deepseek_key";
    private static final String LEGACY_AI_CLEANUP_DONE = "legacy_ai_cleanup_done";

    public static final String SAVE_ORIGINAL = "save_original";
    public static final String GRID = "grid";
    public static final String MIRROR_FRONT = "mirror_front";
    public static final String VOLUME_SHUTTER = "volume_shutter";
    public static final String TIMER_SECONDS = "timer_seconds";
    public static final String BEAUTY_STRENGTH = "beauty_strength";
    public static final String BEAUTY_ENABLED = "beauty_enabled";
    public static final String ASPECT_RATIO = "aspect_ratio";
    public static final String ONBOARDING_DONE = "onboarding_done";
    public static final String CAMERA_PERMISSION_REQUESTED = "camera_permission_requested";
    public static final String LAST_SAVED_URI = "last_saved_uri";

    public static final String RATIO_4_3 = "4:3";
    public static final String RATIO_16_9 = "16:9";
    public static final String RATIO_1_1 = "1:1";

    private AppPreferences() {}

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean getBoolean(Context context, String key, boolean defaultValue) {
        return prefs(context).getBoolean(key, defaultValue);
    }

    public static int getInt(Context context, String key, int defaultValue) {
        return prefs(context).getInt(key, defaultValue);
    }

    public static String getString(Context context, String key, String defaultValue) {
        String value = prefs(context).getString(key, defaultValue);
        return value == null ? defaultValue : value;
    }

    public static void putBoolean(Context context, String key, boolean value) {
        prefs(context).edit().putBoolean(key, value).apply();
    }

    public static void putInt(Context context, String key, int value) {
        prefs(context).edit().putInt(key, value).apply();
    }

    public static void putString(Context context, String key, String value) {
        prefs(context).edit().putString(key, value).apply();
    }

    /**
     * Removes secret material left by pre-v1.3 builds that supported an optional
     * external AI API. The current app has no network/API-key feature, so keeping
     * the old encrypted blob or its Android Keystore key serves no purpose.
     *
     * The migration is idempotent. It is marked complete only after both the old
     * SharedPreferences entry and Keystore alias have been handled successfully.
     */
    public static void migrateAndClearLegacyState(Context context) {
        SharedPreferences current = prefs(context);
        if (current.getBoolean(LEGACY_AI_CLEANUP_DONE, false)) return;

        SharedPreferences legacyApp = context.getSharedPreferences(LEGACY_APP_PREFS, Context.MODE_PRIVATE);
        boolean legacyOnboarding = legacyApp.getBoolean(ONBOARDING_DONE, false);
        boolean onboardingMigrated = current.contains(ONBOARDING_DONE) || !legacyOnboarding;
        if (!current.contains(ONBOARDING_DONE) && legacyOnboarding) {
            // Commit before deleting the legacy file so a process death cannot lose the migrated flag.
            onboardingMigrated = current.edit().putBoolean(ONBOARDING_DONE, true).commit();
        }
        if (onboardingMigrated) context.deleteSharedPreferences(LEGACY_APP_PREFS);

        boolean prefsClean = false;
        boolean keystoreClean = false;

        try {
            SharedPreferences legacy = context.getSharedPreferences(LEGACY_SECURE_PREFS, Context.MODE_PRIVATE);
            prefsClean = legacy.edit().remove(LEGACY_DEEPSEEK_PREF_KEY).commit();
            // Remove the now-empty legacy file when possible. Failure is harmless;
            // the sensitive entry itself has already been synchronously removed.
            if (prefsClean) context.deleteSharedPreferences(LEGACY_SECURE_PREFS);
        } catch (RuntimeException ignored) {
            // Retry on the next launch.
        }

        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(LEGACY_DEEPSEEK_KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(LEGACY_DEEPSEEK_KEYSTORE_ALIAS);
            }
            keystoreClean = true;
        } catch (Exception ignored) {
            // Retry on the next launch.
        }

        if (prefsClean && keystoreClean) {
            current.edit().putBoolean(LEGACY_AI_CLEANUP_DONE, true).apply();
        }
    }

    public static boolean isOnboardingDone(Context context) {
        SharedPreferences current = prefs(context);
        if (current.contains(ONBOARDING_DONE)) {
            return current.getBoolean(ONBOARDING_DONE, false);
        }

        boolean legacy = context.getSharedPreferences(LEGACY_APP_PREFS, Context.MODE_PRIVATE)
                .getBoolean(ONBOARDING_DONE, false);
        if (legacy) {
            current.edit().putBoolean(ONBOARDING_DONE, true).apply();
        }
        return legacy;
    }

    public static void markOnboardingDone(Context context) {
        putBoolean(context, ONBOARDING_DONE, true);
    }
}

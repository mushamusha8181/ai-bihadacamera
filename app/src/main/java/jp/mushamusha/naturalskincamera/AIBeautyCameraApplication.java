package jp.mushamusha.naturalskincamera;

import android.app.Application;

import java.io.File;

public final class AIBeautyCameraApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppPreferences.migrateAndClearLegacyState(this);
        clearStaleCaptureFiles();
    }

    private void clearStaleCaptureFiles() {
        File[] files = getCacheDir().listFiles((dir, name) ->
                name.startsWith("capture_") && name.endsWith(".jpg"));
        if (files == null) return;
        for (File file : files) {
            try {
                file.delete();
            } catch (SecurityException ignored) {
                // Best effort. The cache is app-private and will be retried next launch.
            }
        }
    }
}

package jp.mushamusha.naturalskincamera;

import android.view.View;

/** Shared system-bar inset handling for targetSdk 35 edge-to-edge behavior. */
public final class SystemBarInsets {
    private SystemBarInsets() {}

    @SuppressWarnings("deprecation")
    public static void apply(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        view.requestApplyInsets();
    }
}

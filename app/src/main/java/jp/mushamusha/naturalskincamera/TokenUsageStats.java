package jp.mushamusha.naturalskincamera;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Local-only accounting for token based AI usage.
 *
 * The current app never calls an external AI, so the counters remain zero.
 * Keeping the accounting local makes the Settings screen useful without
 * network polling, analytics, or usage-monitoring services. If a token-based
 * feature is ever reintroduced, that feature must explicitly call recordUsage
 * with the usage returned by its provider.
 */
public final class TokenUsageStats {
    private static final String KEY_MONTH = "ai_usage_month";
    private static final String KEY_MONTH_TOKENS = "ai_usage_month_tokens";
    private static final String KEY_MONTH_COST_MICROYEN = "ai_usage_month_cost_microyen";
    private static final String KEY_TOTAL_TOKENS = "ai_usage_total_tokens";
    private static final String KEY_TOTAL_COST_MICROYEN = "ai_usage_total_cost_microyen";
    private static final String KEY_LAST_RESET_EPOCH_MS = "ai_usage_last_reset_epoch_ms";
    private static final long MICROYEN_PER_YEN = 1_000_000L;

    private TokenUsageStats() {}

    public static synchronized Snapshot getSnapshot(Context context) {
        SharedPreferences prefs = AppPreferences.prefs(context);
        String currentMonth = currentMonthKey();
        String storedMonth = prefs.getString(KEY_MONTH, null);

        if (!currentMonth.equals(storedMonth)) {
            // Month rollover is lazy: no timer, AlarmManager, service, or background watcher.
            prefs.edit()
                    .putString(KEY_MONTH, currentMonth)
                    .putLong(KEY_MONTH_TOKENS, 0L)
                    .putLong(KEY_MONTH_COST_MICROYEN, 0L)
                    .apply();
        }

        return new Snapshot(
                currentMonth,
                prefs.getLong(KEY_MONTH_TOKENS, 0L),
                prefs.getLong(KEY_MONTH_COST_MICROYEN, 0L),
                prefs.getLong(KEY_TOTAL_TOKENS, 0L),
                prefs.getLong(KEY_TOTAL_COST_MICROYEN, 0L),
                prefs.getLong(KEY_LAST_RESET_EPOCH_MS, 0L));
    }

    /**
     * Records usage explicitly reported by a token-based provider.
     * Current v1.4.5 has no caller because it performs no external AI request.
     */
    public static synchronized void recordUsage(Context context, long tokens, double estimatedYen) {
        if (tokens < 0 || !Double.isFinite(estimatedYen) || estimatedYen < 0) return;

        Snapshot snapshot = getSnapshot(context);
        long costMicroYen = safeCostToMicroYen(estimatedYen);
        SharedPreferences prefs = AppPreferences.prefs(context);
        prefs.edit()
                .putString(KEY_MONTH, snapshot.monthKey)
                .putLong(KEY_MONTH_TOKENS, safeAdd(snapshot.monthTokens, tokens))
                .putLong(KEY_MONTH_COST_MICROYEN, safeAdd(snapshot.monthCostMicroYen, costMicroYen))
                .putLong(KEY_TOTAL_TOKENS, safeAdd(snapshot.totalTokens, tokens))
                .putLong(KEY_TOTAL_COST_MICROYEN, safeAdd(snapshot.totalCostMicroYen, costMicroYen))
                .apply();
    }

    /** Resets both current-month and lifetime counters. */
    public static synchronized void resetAll(Context context) {
        AppPreferences.prefs(context).edit()
                .putString(KEY_MONTH, currentMonthKey())
                .putLong(KEY_MONTH_TOKENS, 0L)
                .putLong(KEY_MONTH_COST_MICROYEN, 0L)
                .putLong(KEY_TOTAL_TOKENS, 0L)
                .putLong(KEY_TOTAL_COST_MICROYEN, 0L)
                .putLong(KEY_LAST_RESET_EPOCH_MS, System.currentTimeMillis())
                .apply();
    }

    private static String currentMonthKey() {
        return YearMonth.now(ZoneId.systemDefault()).toString();
    }

    private static long safeCostToMicroYen(double yen) {
        double value = yen * MICROYEN_PER_YEN;
        if (value >= Long.MAX_VALUE) return Long.MAX_VALUE;
        return Math.round(value);
    }

    private static long safeAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }

    public static final class Snapshot {
        public final String monthKey;
        public final long monthTokens;
        public final long monthCostMicroYen;
        public final long totalTokens;
        public final long totalCostMicroYen;
        public final long lastResetEpochMs;

        private Snapshot(String monthKey, long monthTokens, long monthCostMicroYen,
                         long totalTokens, long totalCostMicroYen, long lastResetEpochMs) {
            this.monthKey = monthKey;
            this.monthTokens = monthTokens;
            this.monthCostMicroYen = monthCostMicroYen;
            this.totalTokens = totalTokens;
            this.totalCostMicroYen = totalCostMicroYen;
            this.lastResetEpochMs = lastResetEpochMs;
        }

        public double monthCostYen() {
            return monthCostMicroYen / (double) MICROYEN_PER_YEN;
        }

        public double totalCostYen() {
            return totalCostMicroYen / (double) MICROYEN_PER_YEN;
        }

        public Instant lastResetInstant() {
            return lastResetEpochMs <= 0 ? null : Instant.ofEpochMilli(lastResetEpochMs);
        }
    }
}

package jp.mushamusha.naturalskincamera;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class SettingsActivity extends Activity {
    private TextView tokenUsageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("設定");
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTokenUsage();
    }

    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        SystemBarInsets.apply(scroll);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        scroll.addView(root);

        addTitle(root, "撮影設定");
        addSwitch(root, "補正前JPEGも残す", "美肌処理を実際に行った写真だけ、CameraXの未加工JPEGも追加保存します。美肌OFFや顔未検出では重複保存しません。",
                AppPreferences.SAVE_ORIGINAL, false);
        addSwitch(root, "3分割グリッド", "構図を合わせやすいガイド線を表示します。",
                AppPreferences.GRID, false);
        addSwitch(root, "インカメラを見たまま保存", "保存写真をプレビューと同じ鏡向きにします。補正前JPEGも同じ向き・同じ撮影比率で保存します。",
                AppPreferences.MIRROR_FRONT, true);
        addSwitch(root, "音量ボタンで撮影", "音量＋／－のどちらでもシャッターを切れます。",
                AppPreferences.VOLUME_SHUTTER, true);

        addTitle(root, "AI・通信コスト");
        tokenUsageView = bodyText("");
        tokenUsageView.setPadding(0, dp(8), 0, dp(6));
        root.addView(tokenUsageView);
        refreshTokenUsage();

        Button reset = new Button(this);
        reset.setText("トークン累計をリセット");
        reset.setAllCaps(false);
        reset.setMinHeight(dp(48));
        reset.setOnClickListener(v -> confirmResetTokenUsage());
        root.addView(reset, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView usageNote = bodyText("利用量はこの端末内だけで管理します。バックグラウンド監視や通信は行いません。今月は端末の日付が月をまたいだ時、設定画面を開くなどして利用量を読むタイミングで自動的に0から始まります。\n\n現在のバージョンは外部AIを使わないため、トークン数と料金は0のままです。過去バージョンのDeepSeek利用量は保存していなかったため、後から復元・推計はしません。");
        usageNote.setPadding(0, dp(6), 0, dp(8));
        root.addView(usageNote);

        TextView note = bodyText("撮影音は端末・地域の仕様に合わせるため、アプリ側ではOFFにできない設計です。位置情報・マイク・写真ライブラリ権限は使いません。外部AIやAPI通信もありません。");
        note.setPadding(0, dp(8), 0, 0);
        root.addView(note);
        return scroll;
    }

    private void refreshTokenUsage() {
        if (tokenUsageView == null) return;
        TokenUsageStats.Snapshot usage = TokenUsageStats.getSnapshot(this);
        String monthLabel;
        try {
            YearMonth month = YearMonth.parse(usage.monthKey);
            monthLabel = month.getYear() + "年" + month.getMonthValue() + "月";
        } catch (RuntimeException e) {
            monthLabel = "今月";
        }

        String resetLabel = "まだリセットしていません";
        if (usage.lastResetInstant() != null) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.JAPAN)
                    .withZone(ZoneId.systemDefault());
            resetLabel = formatter.format(usage.lastResetInstant());
        }

        tokenUsageView.setText(
                "外部AI：使用していません\n\n" +
                "【" + monthLabel + "】\n" +
                "トークン：" + formatTokens(usage.monthTokens) + " tokens\n" +
                "推定料金：" + formatYen(usage.monthCostYen()) + "円\n\n" +
                "【累計】\n" +
                "トークン：" + formatTokens(usage.totalTokens) + " tokens\n" +
                "推定料金：" + formatYen(usage.totalCostYen()) + "円\n\n" +
                "最終リセット：" + resetLabel);
    }

    private void confirmResetTokenUsage() {
        new AlertDialog.Builder(this)
                .setTitle("トークン利用量をリセット")
                .setMessage("今月と累計のトークン数・推定料金を0に戻します。よろしいですか？")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("リセット", (dialog, which) -> {
                    TokenUsageStats.resetAll(this);
                    refreshTokenUsage();
                })
                .show();
    }

    private String formatTokens(long tokens) {
        return String.format(Locale.JAPAN, "%,d", tokens);
    }

    private String formatYen(double yen) {
        if (yen == 0d) return "0";
        if (yen >= 1d) return String.format(Locale.JAPAN, "%,.2f", yen).replaceAll("\\.?0+$", "");
        return String.format(Locale.JAPAN, "%.6f", yen).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void addTitle(LinearLayout root, String value) {
        TextView title = new TextView(this);
        title.setText(value);
        title.setTextSize(20);
        title.setTextColor(Color.rgb(39, 69, 58));
        root.addView(title);
    }

    private TextView bodyText(String value) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(13);
        t.setTextColor(Color.DKGRAY);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, dp(8), 0, dp(12));
        return t;
    }

    private void addSwitch(LinearLayout root, String title, String note, String key, boolean defaultValue) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(4));
        root.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Switch s = new Switch(this);
        s.setText(title);
        s.setTextSize(15);
        s.setTextColor(Color.rgb(39, 69, 58));
        s.setMinHeight(dp(48));
        s.setChecked(AppPreferences.getBoolean(this, key, defaultValue));
        box.addView(s);

        TextView n = bodyText(note);
        n.setPadding(dp(4), dp(2), 0, 0);
        box.addView(n);
        s.setOnCheckedChangeListener((buttonView, checked) -> AppPreferences.putBoolean(this, key, checked));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}

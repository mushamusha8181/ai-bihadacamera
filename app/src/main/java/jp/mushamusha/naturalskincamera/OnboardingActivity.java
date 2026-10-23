package jp.mushamusha.naturalskincamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;

public class OnboardingActivity extends ComponentActivity {
    private static final int REQ_CAMERA = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 初回案内と権限確認を飛ばして未初期化のカメラ画面へ入らない。
                moveTaskToBack(true);
            }
        });
        setTitle("はじめに");
        setContentView(buildContent());
    }

    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(246, 249, 247));
        SystemBarInsets.apply(scroll);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(22), dp(18), dp(24));
        scroll.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("AI美肌カメラ", 26, Color.rgb(39, 69, 58));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(title);
        addGap(box, 10);
        TextView lead = text("顔を作り替えず、肌だけをほんの少し整えて撮るカメラです。", 16, Color.DKGRAY);
        lead.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(lead);

        addCard(box, "1. 自然な美肌だけ", "輪郭変更や目の拡大はしません。ナチュラル・やや美肌・しっかりの3段階と0〜30の微調整だけです。");
        addCard(box, "2. 外部AI・外部送信なし", "外部AIやAPIキー機能はありません。写真も顔データも外部サービスへ送りません。美肌処理と顔検出は端末内で完結します。");
        addCard(box, "3. 比率を選んで撮影", "4:3・16:9・1:1を切り替えられます。1:1は中央を正方形に切り出して保存します。");
        addCard(box, "4. 小さいスマホでも使いやすく", "タイマー、音量ボタン撮影、グリッド、左右反転保存、補正前JPEG保存に対応。主要ボタンは押しやすさを残しつつコンパクトにしています。");
        addCard(box, "5. 初回に必要な権限", "必要なのはカメラ権限だけです。この案内の最後で必ず確認します。位置情報・マイク・写真ライブラリ権限は使いません。");

        Button start = new Button(this);
        start.setText("カメラ権限を許可して始める");
        start.setTextSize(16);
        start.setMinHeight(dp(48));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        p.topMargin = dp(18);
        box.addView(start, p);
        start.setOnClickListener(v -> requestCameraAndFinish());
        return scroll;
    }

    private void requestCameraAndFinish() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            AppPreferences.markOnboardingDone(this);
            finish();
            return;
        }

        AppPreferences.putBoolean(this, AppPreferences.CAMERA_PERMISSION_REQUESTED, true);
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) return;

        AppPreferences.markOnboardingDone(this);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this, "カメラ権限は後から設定画面で許可できます", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void addCard(LinearLayout parent, String heading, String body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.rgb(222, 232, 227));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(card, lp);
        card.addView(text(heading, 16, Color.rgb(39, 69, 58)));
        addGap(card, 5);
        card.addView(text(body, 14, Color.DKGRAY));
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.18f);
        return t;
    }

    private void addGap(LinearLayout parent, int amount) {
        TextView gap = new TextView(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(amount)));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}

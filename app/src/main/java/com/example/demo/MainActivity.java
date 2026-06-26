package com.example.demo;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private SpeedMeterView speedMeterView;
    private TextView tvDownload;
    private TextView tvUpload;
    private TextView tvLatency;
    private TextView tvStatus;
    private ProgressBar progressBar;
    private MaterialButton btnStart;

    private FastSpeedReader fastSpeedReader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Views
        webView       = findViewById(R.id.webView);
        speedMeterView = findViewById(R.id.speedMeterView);
        tvDownload    = findViewById(R.id.tvDownload);
        tvUpload      = findViewById(R.id.tvUpload);
        tvLatency     = findViewById(R.id.tvLatency);
        tvStatus      = findViewById(R.id.tvStatus);
        progressBar   = findViewById(R.id.progressBar);
        btnStart      = findViewById(R.id.btnStart);

        // Init reader
        fastSpeedReader = new FastSpeedReader(this, webView, new FastSpeedReader.SpeedCallback() {
            @Override
            public void onSpeedUpdate(SpeedResult result) {
                runOnUiThread(() -> updateUI(result));
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> tvStatus.setText("Error: " + error));
            }
        });

        btnStart.setOnClickListener(v -> startTest());
    }

    private void startTest() {
        btnStart.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Connecting to Fast.com…");
        tvDownload.setText("— Mbps");
        tvUpload.setText("— Mbps");
        tvLatency.setText("— ms");
        speedMeterView.reset();
        fastSpeedReader.startTest();
    }

    private void updateUI(SpeedResult result) {
        // Parse download speed for the meter
        float downloadVal = 0f;
        try {
            String raw = result.getDownloadSpeed().replaceAll("[^0-9.]", "");
            if (!raw.isEmpty()) downloadVal = Float.parseFloat(raw);
        } catch (NumberFormatException ignored) {}

        speedMeterView.setSpeed(downloadVal);

        String dl = result.getDownloadSpeed();
        String ul = result.getUploadSpeed();
        String lat = result.getLatency();

        tvDownload.setText((dl.isEmpty() || dl.equals("0")) ? "— Mbps" : dl + " Mbps");
        tvUpload.setText((ul.isEmpty() || ul.equals("0"))   ? "— Mbps" : ul + " Mbps");
        tvLatency.setText((lat.isEmpty() || lat.equals("0")) ? "— ms"  : lat + " ms");

        if (result.isCompleted()) {
            tvStatus.setText("✓ Test Completed");
            tvStatus.setTextColor(Color.parseColor("#00FF88"));
            progressBar.setVisibility(View.GONE);
            speedMeterView.stopAnimation();
            btnStart.setEnabled(true);
        } else {
            tvStatus.setText("● Testing…");
            tvStatus.setTextColor(Color.parseColor("#00C9FF"));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fastSpeedReader != null) {
            fastSpeedReader.destroy();
        }
    }
}
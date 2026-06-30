package com.example.demo;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {

    // WebView field removed — no longer needed
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

        // Views — IDs unchanged
        speedMeterView = findViewById(R.id.speedMeterView);
        tvDownload     = findViewById(R.id.tvDownload);
        tvUpload       = findViewById(R.id.tvUpload);
        tvLatency      = findViewById(R.id.tvLatency);
        tvStatus       = findViewById(R.id.tvStatus);
        progressBar    = findViewById(R.id.progressBar);
        btnStart       = findViewById(R.id.btnStart);

        // FastSpeedReader — pass null for the webView param (ignored by new implementation)
        fastSpeedReader = new FastSpeedReader(this, null, new FastSpeedReader.SpeedCallback() {
            @Override
            public void onSpeedUpdate(SpeedResult result) {
                // Already on main thread — SpeedTestManager guarantees this
                updateUI(result);
            }

            @Override
            public void onError(String error) {
                // Already on main thread
                tvStatus.setText("Error: " + error);
                progressBar.setVisibility(View.GONE);
                btnStart.setEnabled(true);
            }
        });

        btnStart.setOnClickListener(v -> startTest());
    }

    private void startTest() {
        btnStart.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Connecting to Fast.com…");
        tvStatus.setTextColor(Color.parseColor("#00C9FF"));
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

        String dl  = result.getDownloadSpeed();
        String ul  = result.getUploadSpeed();
        String lat = result.getLatency();

        tvDownload.setText((dl.isEmpty()  || dl.equals("0"))  ? "— Mbps" : dl  + " Mbps");
        tvUpload.setText(  (ul.isEmpty()  || ul.equals("0"))  ? "— Mbps" : ul  + " Mbps");
        tvLatency.setText( (lat.isEmpty() || lat.equals("0")) ? "— ms"   : lat + " ms");

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
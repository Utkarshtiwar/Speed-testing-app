package com.example.demo;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    WebView webView;
    TextView txtSpeed;
    Handler handler = new Handler(Looper.getMainLooper());
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        txtSpeed = findViewById(R.id.txtSpeed);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient(){

            @Override
            public void onPageFinished(WebView view, String url) {

                super.onPageFinished(view, url);

                startReadingSpeed();

            }

        });

        webView.loadUrl("https://fast.com");
    }

    private void startReadingSpeed(){

        handler.postDelayed(new Runnable() {

            @Override
            public void run() {

                webView.evaluateJavascript(
                        "document.getElementById('speed-value').innerText;",
                        value -> {

                            value = value.replace("\"","");

                            txtSpeed.setText("Speed : "+value+" Mbps");

                            handler.postDelayed(this,1000);

                        });

            }

        },3000);

    }
}
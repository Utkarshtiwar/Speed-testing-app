package com.example.demo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public class FastSpeedReader {

    public interface SpeedCallback {
        void onSpeedUpdate(SpeedResult result);
        void onError(String error);
    }

    private WebView webView;
    private Handler handler;
    private Runnable pollRunnable;
    private SpeedCallback callback;
    private boolean isPolling = false;
    private static final long POLL_INTERVAL_MS = 1500;

    private static final String JS_EXTRACT =
            "(function() {" +
                    "  try {" +
                    "    var dl = document.getElementById('speed-value');" +
                    "    var ul = document.getElementById('upload-value');" +
                    "    var lat = document.getElementById('latency-value');" +
                    "    var status = dl ? dl.className : '';" +
                    "    return JSON.stringify({" +
                    "      download: dl ? dl.innerText.trim() : '0'," +
                    "      upload:   ul ? ul.innerText.trim() : '0'," +
                    "      latency:  lat ? lat.innerText.trim() : '0'," +
                    "      status:   status" +
                    "    });" +
                    "  } catch(e) {" +
                    "    return JSON.stringify({download:'0',upload:'0',latency:'0',status:''});" +
                    "  }" +
                    "})()";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public FastSpeedReader(Context context, WebView webView, SpeedCallback callback) {
        this.webView = webView;
        this.callback = callback;
        this.handler = new Handler(Looper.getMainLooper());

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 10; Mobile) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
        );

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                startPolling();
            }
        });
    }

    public void startTest() {
        stopPolling();
        webView.loadUrl("https://fast.com");
    }

    private void startPolling() {
        isPolling = true;
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPolling) return;
                pollValues();
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void pollValues() {
        if (webView == null) return;
        webView.evaluateJavascript(JS_EXTRACT, value -> {
            if (value == null || value.equals("null")) return;
            try {
                // Strip surrounding quotes if present (evaluateJavascript wraps string in quotes)
                String json = value;
                if (json.startsWith("\"") && json.endsWith("\"")) {
                    json = json.substring(1, json.length() - 1);
                    json = json.replace("\\\"", "\"").replace("\\\\", "\\");
                }
                JSONObject obj = new JSONObject(json);
                String download = obj.optString("download", "0");
                String upload   = obj.optString("upload",   "0");
                String latency  = obj.optString("latency",  "0");
                String status   = obj.optString("status",   "");

                boolean completed = status.contains("succeeded");

                SpeedResult result = new SpeedResult(download, upload, latency, completed);

                if (callback != null) {
                    callback.onSpeedUpdate(result);
                }

                if (completed) {
                    stopPolling();
                }
            } catch (Exception e) {
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    public void stopPolling() {
        isPolling = false;
        if (handler != null && pollRunnable != null) {
            handler.removeCallbacks(pollRunnable);
        }
    }

    public void destroy() {
        stopPolling();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        handler = null;
        callback = null;
    }
}
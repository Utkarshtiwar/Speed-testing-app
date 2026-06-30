package com.example.demo;

import android.content.Context;

/**
 * FastSpeedReader — native replacement for the old WebView-based implementation.
 *
 * The public API is IDENTICAL to the original class so MainActivity.java requires
 * zero changes:
 *
 *   new FastSpeedReader(context, webView, callback)  ← webView param now unused but accepted
 *   fastSpeedReader.startTest()
 *   fastSpeedReader.destroy()
 *
 * Internally this delegates to SpeedTestManager which runs the full
 * token → latency → download → upload pipeline using OkHttp.
 *
 * The WebView parameter is kept in the constructor signature so that
 * MainActivity does not need to be modified at all — it is simply ignored.
 */
public class FastSpeedReader {

    public interface SpeedCallback {
        void onSpeedUpdate(SpeedResult result);
        void onError(String error);
    }

    private SpeedTestManager manager;
    private final Context context;
    private final SpeedCallback callback;

    /**
     * @param context  Android context (not used directly, kept for API compat)
     * @param webView  IGNORED — parameter kept so MainActivity compiles unchanged
     * @param callback result/error callbacks, always delivered on the main thread
     */
    public FastSpeedReader(Context context, Object webView, SpeedCallback callback) {
        this.context  = context;
        this.callback = callback;
    }

    /** Starts the speed test. Non-blocking. */
    public void startTest() {
        // Cancel any previous test still running
        if (manager != null) {
            manager.destroy();
        }

        manager = new SpeedTestManager(new SpeedTestManager.Listener() {
            @Override
            public void onUpdate(SpeedResult result) {
                if (callback != null) {
                    callback.onSpeedUpdate(result);
                }
            }

            @Override
            public void onError(String message) {
                if (callback != null) {
                    callback.onError(message);
                }
            }
        });

        manager.start();
    }

    /** Releases all resources. Safe to call from onDestroy(). */
    public void destroy() {
        if (manager != null) {
            manager.destroy();
            manager = null;
        }
    }
}
package com.example.demo;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads from a single Netflix OCA CDN URL, continuously accumulating
 * received bytes into the shared SpeedCalculator.
 *
 * Each worker runs on its own thread (submitted to an ExecutorService).
 * Multiple workers run in parallel (typically 5) to saturate the connection,
 * matching what Fast.com's own JS client does.
 *
 * The download does NOT need to complete — the calling SpeedTestManager
 * calls cancel() after the measurement window (default 15 s) elapses.
 * OkHttp cancels the in-flight call and the InputStream read loop exits.
 *
 * Buffer size: 32 KB — large enough to minimise syscall overhead,
 * small enough for frequent SpeedCalculator.addDownloadBytes() updates.
 */
public class DownloadWorker implements Runnable {

    private static final int BUFFER_SIZE = 32 * 1024;  // 32 KB

    private final OkHttpClient    client;
    private final String          url;
    private final Speedcalculator calculator;

    private volatile Call activeCall;
    private volatile boolean cancelled = false;

    public DownloadWorker(OkHttpClient client, String url, Speedcalculator calculator) {
        this.client     = client;
        this.url        = url;
        this.calculator = calculator;
    }

    @Override
    public void run() {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; Mobile) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .header("Origin", "https://fast.com")
                .header("Referer", "https://fast.com/")
                .build();

        activeCall = client.newCall(request);

        try (Response response = activeCall.execute()) {
            if (!response.isSuccessful() && response.code() != 206) {
                return;  // Non-fatal — other workers continue
            }

            ResponseBody body = response.body();
            if (body == null) return;

            try (InputStream is = body.byteStream()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while (!cancelled && (bytesRead = is.read(buffer)) != -1) {
                    calculator.addDownloadBytes(bytesRead);
                }
            }
        } catch (IOException e) {
            // Expected when cancel() is called or network drops — not an error
        }
    }

    /**
     * Cancels the in-flight OkHttp call.
     * The run() loop exits within one read() timeout cycle.
     */
    public void cancel() {
        cancelled = true;
        Call call = activeCall;
        if (call != null) {
            call.cancel();
        }
    }
}
/*
SpeedTestManager
       │
       │ creates 5 DownloadWorkers
       ▼
ExecutorService
       │
       ▼
DownloadWorker.run()
       │
       ▼
Create HTTP Request
       │
       ▼
Execute Request
       │
       ▼
Receive InputStream
       │
       ▼
Read 32 KB chunks repeatedly
       │
       ▼
calculator.addDownloadBytes(bytesRead)
       │
       ▼
More bytes...
       │
       ▼
After test duration (e.g. 15 seconds)
       │
       ▼
SpeedTestManager calls cancel()
       │
       ▼
Loop exits
       │
       ▼
Thread finishes
*/

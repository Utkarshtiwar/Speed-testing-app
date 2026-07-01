package com.example.demo;

import android.util.Log;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * Uploads a synthetic payload to a Netflix OCA CDN endpoint, accumulating
 * sent bytes into the shared SpeedCalculator.
 *
 * Fast.com performs upload via POST to the same CDN URL used for download,
 * with a binary body. The server accepts the data and returns 200 (it discards
 * the payload server-side).
 *
 * Implementation:
 *   - Uses OkHttp streaming RequestBody (no in-memory allocation of full payload)
 *   - Writes 64 KB chunks in a loop until cancelled
 *   - writeTo() keeps writing until cancel() is called, at which point
 *     the call is cancelled and an IOException terminates the loop
 *
 * Multiple UploadWorkers run in parallel just like DownloadWorkers.
 */
public class UploadWorker implements Runnable {

    private static final String TAG                 = "UploadWorker";
    private static final int    CHUNK_SIZE          = 64 * 1024;        // 64 KB per write
    private static final long   MAX_UPLOAD_BYTES    = 200 * 1024 * 1024; // 200 MB safety cap
    // Declared, finite content length. contentLength() == -1 forces OkHttp to send
    // Transfer-Encoding: chunked, and Netflix's OCA speedtest/upload route returns
    // an immediate error for chunked bodies — this is what was silently killing
    // every upload before any bytes were even counted.
    private static final long   DECLARED_CONTENT_LENGTH = MAX_UPLOAD_BYTES;
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

    private final OkHttpClient    client;
    private final String          url;
    private final Speedcalculator calculator;

    private volatile Call activeCall;
    private volatile boolean cancelled = false;

    // Pre-allocated zero-byte chunk (reused every write — no GC pressure)
    private static final byte[] CHUNK = new byte[CHUNK_SIZE];

    public UploadWorker(OkHttpClient client, String url, Speedcalculator calculator) {
        this.client     = client;
        this.url        = url;
        this.calculator = calculator;
    }

    @Override
    public void run() {
        Log.d(TAG, "UPLOAD WORKER START url=" + url);

        RequestBody body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return OCTET_STREAM;
            }

            @Override
            public long contentLength() {
                // Finite, declared length avoids chunked Transfer-Encoding, which
                // some CDN upload endpoints reject outright.
                return DECLARED_CONTENT_LENGTH;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                long totalSent = 0;
                while (!cancelled && totalSent < DECLARED_CONTENT_LENGTH) {
                    long remaining = DECLARED_CONTENT_LENGTH - totalSent;
                    int toWrite = (int) Math.min(CHUNK_SIZE, remaining);
                    sink.write(CHUNK, 0, toWrite);
                    sink.flush();
                    calculator.addUploadBytes(toWrite);
                    totalSent += toWrite;
                }
            }
        };

        // Append /upload to the target URL (Fast.com CDN accepts POST at this path)
        String uploadUrl = buildUploadUrl(url);
        Log.d(TAG, "UPLOADING to " + uploadUrl);

        Request request = new Request.Builder()
                .url(uploadUrl)
                .post(body)
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; Mobile) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Content-Type", "application/octet-stream")
                .header("Origin", "https://fast.com")
                .header("Referer", "https://fast.com/")
                .build();

        activeCall = client.newCall(request);

        try (Response response = activeCall.execute()) {
            Log.d(TAG, "UPLOAD response code=" + response.code());
            if (!response.isSuccessful()) {
                Log.w(TAG, "UPLOAD non-2xx response: " + response.code() + " " + response.message());
            }
            // Response body doesn't matter — we only care about bytes sent
            if (response.body() != null) {
                response.body().close();
            }
        } catch (IOException e) {
            if (cancelled) {
                Log.d(TAG, "UPLOAD WORKER cancelled (expected): " + e.getMessage());
            } else {
                // This was previously swallowed silently — it is NOT expected
                // when cancelled == false, and is almost certainly the reason
                // uploads appeared to "never complete".
                Log.e(TAG, "UPLOAD WORKER FAILED (not a cancellation): " + e, e);
            }
        }

        Log.d(TAG, "UPLOAD WORKER EXIT cancelled=" + cancelled);
    }

    public void cancel() {
        cancelled = true;
        Call call = activeCall;
        if (call != null) {
            call.cancel();
        }
    }

    /**
     * Constructs the upload URL.
     * Fast.com CDN targets look like:
     *   https://ipv4-c001-lax001-ix.1.oca.nflxvideo.net/speedtest?c=...&n=...&v=...
     * Upload is sent to:
     *   https://ipv4-c001-lax001-ix.1.oca.nflxvideo.net/speedtest/upload?...
     */
    private String buildUploadUrl(String downloadUrl) {
        // Insert /upload before the query string
        int queryIndex = downloadUrl.indexOf('?');
        if (queryIndex == -1) {
            return downloadUrl + "/upload";
        }
        return downloadUrl.substring(0, queryIndex)
                + "/upload"
                + downloadUrl.substring(queryIndex);
    }
}
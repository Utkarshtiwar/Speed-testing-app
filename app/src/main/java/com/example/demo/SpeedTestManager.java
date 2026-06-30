package com.example.demo;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * Orchestrates the complete Fast.com-style speed test pipeline:
 *
 *   Phase 0  ─ Token acquisition (fetch fast.com HTML + JS bundle)
 *   Phase 1  ─ Latency measurement (HEAD requests to CDN targets)
 *   Phase 2  ─ Download measurement (parallel streaming GET, 15 s window)
 *   Phase 3  ─ Upload measurement   (parallel streaming POST, 15 s window)
 *
 * Threading model:
 *   ┌─ workExecutor (cached thread pool)
 *   │    Token fetch, API call, LatencyTester, DownloadWorkers, UploadWorkers
 *   └─ samplerExecutor (single-thread scheduled)
 *        Fires every 200 ms → SpeedCalculator.sample() → UI update via Handler
 *
 * All UI callbacks are delivered on the main thread via mainHandler.
 *
 * Cancellation:
 *   destroy() cancels all in-flight OkHttp calls, shuts down executors,
 *   and suppresses any pending callbacks. Safe to call from any thread.
 */
public class SpeedTestManager {

    // ── Tunables ──────────────────────────────────────────────────────────────
    private static final int  PARALLEL_CONNECTIONS  = 5;    // simultaneous streams
    private static final long DOWNLOAD_DURATION_MS  = 15_000;
    private static final long UPLOAD_DURATION_MS    = 12_000;
    private static final long UI_UPDATE_INTERVAL_MS = Speedcalculator.SAMPLE_INTERVAL_MS;
    private static final long CONNECT_TIMEOUT_MS    = 10_000;
    private static final long READ_TIMEOUT_MS       = 30_000;
    private static final long WRITE_TIMEOUT_MS      = 30_000;

    // ── Callback ──────────────────────────────────────────────────────────────
    public interface Listener {
        /** Called on main thread whenever any value changes. */
        void onUpdate(SpeedResult result);
        /** Called on main thread on unrecoverable error. */
        void onError(String message);
    }

    // ── Internal state ────────────────────────────────────────────────────────
    private final OkHttpClient        httpClient;
    private final Speedcalculator calculator;
    private final Handler             mainHandler;
    private final Listener            listener;

    private ExecutorService           workExecutor;
    private ScheduledExecutorService  samplerExecutor;
    private ScheduledFuture<?>        samplerFuture;

    private final List<DownloadWorker> downloadWorkers = new ArrayList<>();
    private final List<UploadWorker>   uploadWorkers   = new ArrayList<>();

    private volatile boolean destroyed = false;

    // ── Live result (mutated only on sampler thread, read on main) ────────────
    private String currentLatency  = "0";
    private String currentDownload = "0";
    private String currentUpload   = "0";

    public SpeedTestManager(Listener listener) {
        this.listener   = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.calculator  = new Speedcalculator();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS,       TimeUnit.MILLISECONDS)
                .writeTimeout(WRITE_TIMEOUT_MS,     TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Begins the full speed-test pipeline. Non-blocking. */
    public void start() {
        if (destroyed) return;

        calculator.reset();
        currentLatency  = "0";
        currentDownload = "0";
        currentUpload   = "0";

        workExecutor    = Executors.newCachedThreadPool();
        samplerExecutor = Executors.newSingleThreadScheduledExecutor();

        workExecutor.submit(this::runFullTest);
    }

    /** Cancels everything and releases resources. Safe to call multiple times. */
    public void destroy() {
        destroyed = true;
        cancelAllWorkers();
        stopSampler();
        shutdownExecutor(workExecutor);
        shutdownExecutor(samplerExecutor);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private void runFullTest() {
        try {
            // ── Phase 0: Token ────────────────────────────────────────────────
            postStatus("Fetching configuration…", false);

            TokenFetcher tokenFetcher = new TokenFetcher(httpClient);
            String token;
            try {
                token = tokenFetcher.fetchToken();
            } catch (IOException e) {
                postError("Could not load Fast.com configuration: " + e.getMessage());
                return;
            }

            if (destroyed) return;

            // ── Phase 0b: Get CDN targets ─────────────────────────────────────
            postStatus("Connecting to test servers…", false);
            FastApiClient apiClient = new FastApiClient(httpClient, token);
            FastApiClient.SpeedTestTargets targets;
            try {
                targets = apiClient.getTargets(PARALLEL_CONNECTIONS);
            } catch (IOException | JSONException e) {
                postError("Could not reach Fast.com API: " + e.getMessage());
                return;
            }

            if (destroyed) return;

            // ── Phase 1: Latency ──────────────────────────────────────────────
            postStatus("Measuring latency…", false);
            LatencyTester latencyTester = new LatencyTester(httpClient);
            long latencyMs = latencyTester.measureLatency(targets.urls);
            if (latencyMs > 0) {
                currentLatency = String.valueOf(latencyMs);
                postUpdate(false);
            }

            if (destroyed) return;

            // ── Phase 2: Download ─────────────────────────────────────────────
            postStatus("Testing download speed…", false);
            runDownloadPhase(targets.urls);

            if (destroyed) return;

            // ── Phase 3: Upload ───────────────────────────────────────────────
            postStatus("Testing upload speed…", false);
            calculator.resetUpload();
            runUploadPhase(targets.urls);

            if (destroyed) return;

            // ── Complete ──────────────────────────────────────────────────────
            stopSampler();
            postFinalResult();

        } catch (Exception e) {
            if (!destroyed) {
                postError("Speed test failed: " + e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Download phase
    // ─────────────────────────────────────────────────────────────────────────

    private void runDownloadPhase(List<String> urls) throws InterruptedException {
        synchronized (downloadWorkers) { downloadWorkers.clear(); }

        // Start sampler — updates UI every 200 ms
        startSampler(Speedcalculator.Mode.DOWNLOAD);

        // Launch parallel download workers
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < Math.min(PARALLEL_CONNECTIONS, urls.size()); i++) {
            DownloadWorker worker = new DownloadWorker(httpClient, urls.get(i), calculator);
            synchronized (downloadWorkers) { downloadWorkers.add(worker); }
            futures.add(workExecutor.submit(worker));
        }

        // Wait for measurement window
        Thread.sleep(DOWNLOAD_DURATION_MS);

        // Cancel all workers
        synchronized (downloadWorkers) {
            for (DownloadWorker w : downloadWorkers) w.cancel();
        }

        // Wait for workers to exit (brief, OkHttp cancellation is fast)
        for (Future<?> f : futures) {
            try { f.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }

        stopSampler();

        // Capture final download speed
        currentDownload = Speedcalculator.format(calculator.getCurrentDownloadMbps());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Upload phase
    // ─────────────────────────────────────────────────────────────────────────

    private void runUploadPhase(List<String> urls) throws InterruptedException {
        synchronized (uploadWorkers) { uploadWorkers.clear(); }

        startSampler(Speedcalculator.Mode.UPLOAD);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < Math.min(PARALLEL_CONNECTIONS, urls.size()); i++) {
            UploadWorker worker = new UploadWorker(httpClient, urls.get(i), calculator);
            synchronized (uploadWorkers) { uploadWorkers.add(worker); }
            futures.add(workExecutor.submit(worker));
        }

        Thread.sleep(UPLOAD_DURATION_MS);

        synchronized (uploadWorkers) {
            for (UploadWorker w : uploadWorkers) w.cancel();
        }

        for (Future<?> f : futures) {
            try { f.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }

        stopSampler();

        currentUpload = Speedcalculator.format(calculator.getCurrentUploadMbps());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Sampler
    // ─────────────────────────────────────────────────────────────────────────

    private void startSampler(Speedcalculator.Mode mode) {
        stopSampler();
        if (destroyed) return;

        samplerFuture = samplerExecutor.scheduleAtFixedRate(() -> {
            if (destroyed) return;
            calculator.sample(mode);

            if (mode == Speedcalculator.Mode.DOWNLOAD) {
                currentDownload = Speedcalculator.format(calculator.getCurrentDownloadMbps());
            } else {
                currentUpload = Speedcalculator.format(calculator.getCurrentUploadMbps());
            }
            postUpdate(false);
        }, UI_UPDATE_INTERVAL_MS, UI_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopSampler() {
        ScheduledFuture<?> f = samplerFuture;
        if (f != null && !f.isCancelled()) {
            f.cancel(false);
        }
        samplerFuture = null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI posting helpers — always deliver on main thread
    // ─────────────────────────────────────────────────────────────────────────

    private void postUpdate(boolean completed) {
        if (destroyed) return;
        SpeedResult result = new SpeedResult(
                currentDownload, currentUpload, currentLatency, completed);
        mainHandler.post(() -> {
            if (!destroyed) listener.onUpdate(result);
        });
    }

    private void postFinalResult() {
        if (destroyed) return;
        SpeedResult result = new SpeedResult(
                currentDownload, currentUpload, currentLatency, true);
        mainHandler.post(() -> {
            if (!destroyed) listener.onUpdate(result);
        });
    }

    private void postStatus(String status, boolean completed) {
        // Status text is shown by MainActivity based on SpeedResult.isCompleted()
        // We use a lightweight update with current values
        postUpdate(completed);
    }

    private void postError(String message) {
        if (destroyed) return;
        mainHandler.post(() -> {
            if (!destroyed) listener.onError(message);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Cleanup helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void cancelAllWorkers() {
        synchronized (downloadWorkers) {
            for (DownloadWorker w : downloadWorkers) w.cancel();
            downloadWorkers.clear();
        }
        synchronized (uploadWorkers) {
            for (UploadWorker w : uploadWorkers) w.cancel();
            uploadWorkers.clear();
        }
    }

    private void shutdownExecutor(ExecutorService executor) {
        if (executor == null) return;
        executor.shutdownNow();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }
}
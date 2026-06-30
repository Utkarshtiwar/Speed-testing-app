package com.example.demo;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe throughput calculator.
 *
 * Each DownloadWorker / UploadWorker atomically adds bytes received/sent.
 * A ScheduledExecutorService samples the counter every SAMPLE_INTERVAL_MS,
 * computes the delta bytes per second, converts to Mbps, and stores the
 * result in a circular buffer.
 *
 * Callers read getAverageMbps() at any time from any thread.
 *
 * Circular buffer design:
 *   - Holds the last BUFFER_SLOTS samples
 *   - Smooths out burst variance without adding large latency
 *   - Old samples drop out automatically as new ones arrive
 */
public class Speedcalculator {

    private static final int    BUFFER_SLOTS       = 8;    // ≈ 1.6 s of history at 200ms interval
    public  static final long   SAMPLE_INTERVAL_MS = 200;  // sample every 200 ms

    // Shared byte counters — workers call addBytes() from their threads
    private final AtomicLong totalBytesDownload = new AtomicLong(0);
    private final AtomicLong totalBytesUpload   = new AtomicLong(0);

    // Last snapshot (for delta computation)
    private long lastDownloadSnapshot = 0;
    private long lastUploadSnapshot   = 0;

    // Circular buffers (Mbps samples)
    private final double[] downloadBuffer = new double[BUFFER_SLOTS];
    private final double[] uploadBuffer   = new double[BUFFER_SLOTS];
    private int            bufferIndex    = 0;
    private int            filledSlots    = 0;

    // Latest computed averages (read by UI thread)
    private volatile double currentDownloadMbps = 0.0;
    private volatile double currentUploadMbps   = 0.0;

    /**
     * Called by DownloadWorker threads — thread-safe.
     */
    public void addDownloadBytes(long bytes) {
        totalBytesDownload.addAndGet(bytes);
    }

    /**
     * Called by UploadWorker threads — thread-safe.
     */
    public void addUploadBytes(long bytes) {
        totalBytesUpload.addAndGet(bytes);
    }

    /**
     * Called by the sampler (ScheduledExecutorService) every SAMPLE_INTERVAL_MS.
     * NOT called from UI thread.
     *
     * @param mode DOWNLOAD or UPLOAD — which counter to sample
     */
    public synchronized void sample(Mode mode) {
        double mbps;

        if (mode == Mode.DOWNLOAD) {
            long current = totalBytesDownload.get();
            long delta   = current - lastDownloadSnapshot;
            lastDownloadSnapshot = current;

            // bytes/intervalSec → bits/sec → Mbps
            double bytesPerSec = delta / (SAMPLE_INTERVAL_MS / 1000.0);
            mbps = (bytesPerSec * 8.0) / 1_000_000.0;

            downloadBuffer[bufferIndex % BUFFER_SLOTS] = mbps;
            currentDownloadMbps = averageOf(downloadBuffer);
        } else {
            long current = totalBytesUpload.get();
            long delta   = current - lastUploadSnapshot;
            lastUploadSnapshot = current;

            double bytesPerSec = delta / (SAMPLE_INTERVAL_MS / 1000.0);
            mbps = (bytesPerSec * 8.0) / 1_000_000.0;

            uploadBuffer[bufferIndex % BUFFER_SLOTS] = mbps;
            currentUploadMbps = averageOf(uploadBuffer);
        }

        bufferIndex++;
        filledSlots = Math.min(filledSlots + 1, BUFFER_SLOTS);
    }

    /** Resets all counters and buffers. Call before starting a new test. */
    public synchronized void reset() {
        totalBytesDownload.set(0);
        totalBytesUpload.set(0);
        lastDownloadSnapshot = 0;
        lastUploadSnapshot   = 0;

        for (int i = 0; i < BUFFER_SLOTS; i++) {
            downloadBuffer[i] = 0;
            uploadBuffer[i]   = 0;
        }
        bufferIndex    = 0;
        filledSlots    = 0;
        currentDownloadMbps = 0;
        currentUploadMbps   = 0;
    }

    /** Reset only upload-related state when switching from download → upload phase. */
    public synchronized void resetUpload() {
        totalBytesUpload.set(0);
        lastUploadSnapshot = 0;
        for (int i = 0; i < BUFFER_SLOTS; i++) {
            uploadBuffer[i] = 0;
        }
        currentUploadMbps = 0;
    }

    public double getCurrentDownloadMbps() { return currentDownloadMbps; }
    public double getCurrentUploadMbps()   { return currentUploadMbps;   }

    /** Converts Mbps to a formatted string with 1 decimal place. */
    public static String format(double mbps) {
        return String.format("%.1f", mbps);
    }

    private double averageOf(double[] buf) {
        if (filledSlots == 0) return 0.0;
        double sum = 0;
        int slots = Math.min(filledSlots, BUFFER_SLOTS);
        for (int i = 0; i < slots; i++) sum += buf[i];
        return sum / slots;
    }

    public enum Mode { DOWNLOAD, UPLOAD }
}
package com.example.demo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Measures network latency by timing HTTP HEAD requests to the Netflix OCA CDN
 * endpoints returned by FastApiClient.
 *
 * Method:
 *   - Send 4 HEAD requests to each CDN target URL
 *   - Measure round-trip time (RTT) for each
 *   - Discard the first sample (warm-up / TCP setup)
 *   - Return the median of remaining samples across all targets
 *
 * The HEAD request hits the same physical server that will serve the download,
 * so RTT closely reflects actual test-path latency.
 */
public class LatencyTester {

    private static final int PINGS_PER_TARGET  = 4;
    private static final int WARMUP_DISCARD     = 1;   // discard first ping (connection setup)
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS    = 4000;

    private final OkHttpClient client;

    public LatencyTester(OkHttpClient client) {
        // Use a fresh client with tight timeouts for latency measurement
        this.client = client.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Synchronous. Call from a background thread.
     *
     * @param targetUrls list of CDN URLs from FastApiClient
     * @return latency in milliseconds (median), or -1 if measurement failed
     */
    public long measureLatency(List<String> targetUrls) {
        if (targetUrls == null || targetUrls.isEmpty()) return -1;

        List<Long> allSamples = new ArrayList<>();

        for (String url : targetUrls) {
            List<Long> samples = pingUrl(url, PINGS_PER_TARGET);
            // Discard warm-up pings
            if (samples.size() > WARMUP_DISCARD) {
                allSamples.addAll(samples.subList(WARMUP_DISCARD, samples.size()));
            } else {
                allSamples.addAll(samples);
            }
        }

        if (allSamples.isEmpty()) return -1;

        Collections.sort(allSamples);
        return allSamples.get(allSamples.size() / 2);  // median
    }

    /**
     * Sends multiple HEAD requests to a single URL and returns RTTs in ms.
     */
    private List<Long> pingUrl(String targetUrl, int count) {
        List<Long> results = new ArrayList<>();

        // Strip query parameters that are for download content;
        // the base URL with ?c=... still accepts HEAD cleanly
        for (int i = 0; i < count; i++) {
            long start = System.currentTimeMillis();
            try {
                Request request = new Request.Builder()
                        .url(targetUrl)
                        .head()
                        .header("User-Agent",
                                "Mozilla/5.0 (Linux; Android 10; Mobile) "
                                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                        + "Chrome/120.0.0.0 Mobile Safari/537.36")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    long elapsed = System.currentTimeMillis() - start;
                    // Accept any HTTP response — even 405 Method Not Allowed proves connectivity
                    results.add(elapsed);
                }
            } catch (IOException e) {
                // Skip failed pings — don't add to results
            }
        }

        return results;
    }
}
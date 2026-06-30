package com.example.demo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Calls api.fast.com to retrieve Netflix OCA (Open Connect Appliance)
 * CDN target URLs for download measurement, and the upload/latency targets.
 *
 * Endpoint:  GET https://api.fast.com/netflix/speedtest/v2
 *              ?https=true
 *              &token=TOKEN
 *              &urlCount=5
 *
 * Response JSON shape:
 * {
 *   "client": { "ip": "...", "asn": "...", "location": { "city": "...", "country": "..." } },
 *   "targets": [
 *     { "name": "...", "url": "https://ipv4-c001-lax001-ix.1.oca.nflxvideo.net/speedtest?..." },
 *     ...
 *   ]
 * }
 *
 * Each target URL is used for both download and upload measurement.
 * Latency is measured by timing a HEAD request to the same URL.
 */
public class FastApiClient {

    // v2 endpoint — returns targets array identical to v1 but also has client info
    private static final String API_BASE = "https://api.fast.com/netflix/speedtest/v2";
    private static final int DEFAULT_URL_COUNT = 5;

    private final OkHttpClient client;
    private final String token;

    public FastApiClient(OkHttpClient client, String token) {
        this.client = client;
        this.token  = token;
    }

    /**
     * Holds result of the /speedtest/v2 call.
     */
    public static class SpeedTestTargets {
        /** Download/upload CDN URLs */
        public final List<String> urls;
        /** Client IP as reported by Fast.com */
        public final String clientIp;

        SpeedTestTargets(List<String> urls, String clientIp) {
            this.urls     = urls;
            this.clientIp = clientIp;
        }
    }

    /**
     * Synchronous. Call from a background thread.
     *
     * @param urlCount number of CDN URLs to request (1–25)
     * @return SpeedTestTargets containing the list of CDN URLs
     * @throws IOException   on network failure
     * @throws JSONException on unexpected response format
     */
    public SpeedTestTargets getTargets(int urlCount) throws IOException, JSONException {
        String url = API_BASE
                + "?https=true"
                + "&token=" + token
                + "&urlCount=" + urlCount;

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; Mobile) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/json")
                .header("Origin", "https://fast.com")
                .header("Referer", "https://fast.com/")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 403) {
                throw new IOException("BAD_TOKEN: API returned 403. Token may have expired.");
            }
            if (!response.isSuccessful()) {
                throw new IOException("API error HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response from API");

            String json = body.string();
            JSONObject root = new JSONObject(json);

            // Extract client IP (optional, best-effort)
            String clientIp = "";
            if (root.has("client")) {
                clientIp = root.getJSONObject("client").optString("ip", "");
            }

            // Extract target URLs
            JSONArray targets = root.optJSONArray("targets");
            List<String> urls = new ArrayList<>();
            if (targets != null) {
                for (int i = 0; i < targets.length(); i++) {
                    JSONObject t = targets.getJSONObject(i);
                    String targetUrl = t.optString("url", "");
                    if (!targetUrl.isEmpty()) {
                        urls.add(targetUrl);
                    }
                }
            }

            if (urls.isEmpty()) {
                throw new IOException("API returned no target URLs — token may be invalid");
            }

            return new SpeedTestTargets(urls, clientIp);
        }
    }

    public int getDefaultUrlCount() {
        return DEFAULT_URL_COUNT;
    }
}
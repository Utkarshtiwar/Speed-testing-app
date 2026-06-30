package com.example.demo;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches the Fast.com API token dynamically by:
 *   1. GETting https://fast.com (HTML)
 *   2. Extracting the app bundle filename from a <script src="app-HASH.js"> tag
 *   3. GETting https://fast.com/app-HASH.js (minified JS)
 *   4. Regex-extracting the token literal: token:"VALUE"
 *
 * The token is a static string hardcoded by Netflix into their JS bundle.
 * It changes only when Netflix redeploys the frontend (every few weeks/months).
 * Call refresh() when the API returns BAD_TOKEN errors.
 */
public class TokenFetcher {

    private static final String FAST_COM_BASE = "https://fast.com";

    // Matches:  src="app-abc123.js"  or  src='app-abc123.js'
    private static final Pattern SCRIPT_PATTERN =
            Pattern.compile("src=[\"']([^\"']*app-[^\"']*\\.js)[\"']", Pattern.CASE_INSENSITIVE);

    // Matches:  token:"VALUE"  or  token:'VALUE'
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("token:\\s*[\"']([^\"']{6,80})[\"']", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient client;

    public TokenFetcher(OkHttpClient client) {
        this.client = client;
    }

    /**
     * Synchronous. Must be called from a background thread.
     *
     * @return the API token string
     * @throws IOException if network fails or token cannot be found
     */
    public String fetchToken() throws IOException {
        // Step 1: fetch fast.com HTML
        String html = get(FAST_COM_BASE);

        // Step 2: extract JS bundle filename
        Matcher scriptMatcher = SCRIPT_PATTERN.matcher(html);
        if (!scriptMatcher.find()) {
            throw new IOException("Could not find app JS bundle in fast.com HTML");
        }
        String scriptPath = scriptMatcher.group(1);

        // Make sure path starts with /
        if (!scriptPath.startsWith("/") && !scriptPath.startsWith("http")) {
            scriptPath = "/" + scriptPath;
        }
        String scriptUrl = scriptPath.startsWith("http") ? scriptPath : (FAST_COM_BASE + scriptPath);

        // Step 3: fetch the JS bundle
        String js = get(scriptUrl);

        // Step 4: extract token
        Matcher tokenMatcher = TOKEN_PATTERN.matcher(js);
        if (!tokenMatcher.find()) {
            throw new IOException("Could not find token in fast.com JS bundle");
        }
        return tokenMatcher.group(1);
    }

    private String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; Mobile) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/javascript,*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " fetching " + url);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response body from " + url);
            }
            return body.string();
        }
    }
}
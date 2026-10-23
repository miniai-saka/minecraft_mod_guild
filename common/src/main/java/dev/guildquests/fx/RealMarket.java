package dev.guildquests.fx;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Reads one real-market spot price from a public HTTP API; keeps the last good value on failure. */
public final class RealMarket {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("guildquests");
    private static volatile double price;
    private static volatile long fetchedAt;
    private static volatile long lastAttempt;

    private RealMarket() {
    }

    /** Schedules a refresh on a daemon thread, at most once per interval. Safe to call every tick. */
    public static void maybeRefreshAsync(boolean enabled, String apiUrl, int seconds) {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAttempt < Math.max(10, seconds) * 1000L) {
            return;
        }
        lastAttempt = now;
        Thread thread = new Thread(() -> refresh(apiUrl), "GuildQuests-FX-Real");
        thread.setDaemon(true);
        thread.start();
    }

    public static double price() {
        return price;
    }

    public static long fetchedAt() {
        return fetchedAt;
    }

    /** Blocking fetch; call from a background thread. Accepts Coinbase and Binance spot payloads. */
    public static void refresh(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl.trim()))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "GuildQuests-Mod")
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return;
            }
            double value = parse(response.body());
            if (value > 0 && Double.isFinite(value)) {
                price = value;
                fetchedAt = System.currentTimeMillis();
            }
        } catch (Exception e) {
            LOGGER.debug("[FX] Real market refresh failed", e);
        }
    }

    private static double parse(String body) {
        try {
            var root = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (root.has("data") && root.get("data").isJsonObject()) {
                var data = root.getAsJsonObject("data");
                if (data != null && data.has("amount")) {
                    return Double.parseDouble(data.get("amount").getAsString());
                }
            }
            if (root.has("price")) {
                return Double.parseDouble(root.get("price").getAsString());
            }
        } catch (RuntimeException ignored) {
        }
        return 0;
    }
}

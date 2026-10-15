package dev.guildquests.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks GitHub Releases for a newer version and auto-downloads it. */
public final class UpdateChecker {
    private static final String API_URL =
            "https://api.github.com/repos/takoyaki150141/minecraft_mod_guild/releases/latest";

    private UpdateChecker() {
    }

    /** Result of a successful update check. */
    public record UpdateInfo(String latestVersion, String downloadUrl) {
    }

    /**
     * Queries GitHub in a background thread. If a newer version exists,
     * invokes onUpdate with the result (on that background thread).
     */
    public static void checkAsync(String currentVersion, String loader, Consumer<UpdateInfo> onUpdate) {
        new Thread(() -> {
            try {
                dev.guildquests.GuildQuestsMod.LOGGER.info("[Update] Checking GitHub for updates... (current={}, loader={})", currentVersion, loader);
                UpdateInfo info = check(currentVersion, loader);
                if (info != null) {
                    dev.guildquests.GuildQuestsMod.LOGGER.info("[Update] Found: v{}", info.latestVersion());
                    if (onUpdate != null) {
                        onUpdate.accept(info);
                    }
                } else {
                    dev.guildquests.GuildQuestsMod.LOGGER.info("[Update] No update found or check failed");
                }
            } catch (Exception e) {
                dev.guildquests.GuildQuestsMod.LOGGER.warn("[Update] Check failed with exception", e);
            }
        }, "GuildQuests-UpdateCheck").start();
    }

    /**
     * Queries GitHub for the latest release. Returns null if up-to-date,
     * on network error, or if no matching JAR asset is found.
     */
    public static UpdateInfo check(String currentVersion, String loader) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "GuildQuests-Mod/" + currentVersion)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            dev.guildquests.GuildQuestsMod.LOGGER.info("[Update] GitHub API response: {}", response.statusCode());
            if (response.statusCode() != 200) {
                return null;
            }

            String body = response.body();
            String tagName = extractString(body, "tag_name");
            dev.guildquests.GuildQuestsMod.LOGGER.info("[Update] tag_name extracted: {}", tagName);
            if (tagName == null) {
                return null;
            }

            String latest = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            int cmp = compareVersions(currentVersion, latest);
            dev.guildquests.GuildQuestsMod.LOGGER.info("[Update] Version compare: current={} latest={} cmp={}", currentVersion, latest, cmp);
            if (cmp >= 0) {
                return null;
            }

            String jarName = "guildquests_" + loader + "-1.20.1_" + latest + ".jar";
            dev.guildquests.GuildQuestsMod.LOGGER.info("[Update] Looking for JAR: {}", jarName);
            String url = extractAssetUrl(body, jarName);
            dev.guildquests.GuildQuestsMod.LOGGER.info("[Update] JAR URL result: {}", url == null ? "NULL" : url);
            if (url == null) {
                dev.guildquests.GuildQuestsMod.LOGGER.warn("[Update] Could not find JAR asset in release body. Body length={}", body.length());
                return null;
            }

            return new UpdateInfo(latest, url);
        } catch (Exception e) {
            dev.guildquests.GuildQuestsMod.LOGGER.warn("[Update] Exception during check", e);
            return null;
        }
    }

    /** Downloads the JAR to the mods folder. Returns true on success. */
    public static boolean download(UpdateInfo info, Path modsDir, String loader) {
        try {
            String fileName = "guildquests_" + loader + "-1.20.1_" + info.latestVersion() + ".jar";
            Path target = modsDir.resolve(fileName);
            dev.guildquests.GuildQuestsMod.LOGGER.info("[Update] Downloading to: {}", target);
            dev.guildquests.GuildQuestsMod.LOGGER.info("[Update] Download URL: {}", info.downloadUrl());

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(info.downloadUrl()))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
            dev.guildquests.GuildQuestsMod.LOGGER.info("[Update] Download response: {}", response.statusCode());
            return response.statusCode() == 200;
        } catch (Exception e) {
            dev.guildquests.GuildQuestsMod.LOGGER.error("[Update] Download failed", e);
            return false;
        }
    }

    /**
     * Deletes old guildquests JARs in the mods folder (previous auto-update leftovers).
     * The currently-running JAR (matching keepVersion) is left alone; Fabric/Forge only
     * load the newest version, so older copies are not locked and can be removed directly.
     * Also deletes any leftover .jar.old files from earlier cleanup schemes.
     * Returns the number of files deleted.
     */
    public static int cleanupOldJars(Path modsDir, String keepVersion) {
        if (modsDir == null || keepVersion == null) {
            return 0;
        }
        int removed = 0;
        try (var stream = java.nio.file.Files.list(modsDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName().toString();
                if (!name.startsWith("guildquests_")) {
                    continue;
                }

                // Leftover .old from the previous two-stage scheme
                if (name.endsWith(".jar.old")) {
                    removed += tryDelete(p, name);
                    continue;
                }

                // Old version JAR (not the one we're running) — safe to delete directly
                if (name.endsWith(".jar") && !name.contains(keepVersion)) {
                    removed += tryDelete(p, name);
                }
            }
        } catch (Exception ignored) {
        }
        return removed;
    }

    private static int tryDelete(Path p, String name) {
        try {
            java.nio.file.Files.delete(p);
            dev.guildquests.GuildQuestsMod.LOGGER.info("[Update] Deleted old file: {}", name);
            return 1;
        } catch (Exception e) {
            dev.guildquests.GuildQuestsMod.LOGGER.warn("[Update] Could not delete {}: {}", name, e.toString());
            return 0;
        }
    }

    /** Compares dotted version strings numerically. Returns negative if a < b. */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int na = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int nb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        return 0;
    }

    private static String extractString(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String extractAssetUrl(String json, String fileName) {
        Matcher m = Pattern.compile(
                "\"name\"\\s*:\\s*\"" + Pattern.quote(fileName) + "\"[\\s\\S]*?\"browser_download_url\"\\s*:\\s*\"([^\"]+)\""
        ).matcher(json);
        return m.find() ? m.group(1) : null;
    }
}

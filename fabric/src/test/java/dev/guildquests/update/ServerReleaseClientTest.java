package dev.guildquests.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class ServerReleaseClientTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private HttpServer http;

    private static final ServerReleaseClient.MetadataValidator VALIDATOR = (jar, version, server) ->
            FabricUpdateMetadata.validate(jar, version, server,
                    java.util.Map.of("minecraft", "1.20.1", "java", "17", "fabricloader", "0.17.2"));

    @After public void stop() { if (http != null) http.stop(0); }

    private String serve(byte[] body, int status) throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/asset", exchange -> {
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        http.start();
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/asset";
    }

    private static byte[] jar(String id, String version, String environment, String minecraft) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"environment\":\"" + environment
                    + "\",\"depends\":{\"minecraft\":\"" + minecraft + "\",\"java\":\">=17\"}} ").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("dev/guildquests/GuildQuestsMod.class"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private String release(String extra, String name) {
        return "{\"tag_name\":\"v26.7.2\"," + extra + "\"assets\":[{\"name\":\"" + name
                + "\",\"size\":123,\"digest\":\"sha256:" + "a".repeat(64)
                + "\",\"browser_download_url\":\"https://example.com/mod.jar\"}]}";
    }

    private static String sha256(byte[] body) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test public void findsOnlyExactFabricAssetAndIgnoresBodyText() {
        String json = release("\"body\":\"fake tag_name and browser_download_url\",", "guildquests_fabric-1.20.1_26.7.2.jar");
        assertEquals("26.7.2", ServerReleaseClient.parseRelease(json, "26.7.1", "fabric").latestVersion());
        assertNull(ServerReleaseClient.parseRelease(json, "26.7.2", "fabric"));
        assertNull(ServerReleaseClient.parseRelease(release("", "guildquests_forge-1.20.1_26.7.2.jar"), "26.7.1", "fabric"));
        assertNull(ServerReleaseClient.parseRelease(release("\"prerelease\":true,", "guildquests_fabric-1.20.1_26.7.2.jar"), "26.7.1", "fabric"));
        assertNull(ServerReleaseClient.parseRelease(release("\"draft\":true,", "guildquests_fabric-1.20.1_26.7.2.jar"), "26.7.1", "fabric"));
    }

    @Test public void comparesNumericallyAndRejectsUnsafeVersions() {
        assertTrue(ServerReleaseClient.compareVersions("26.9.0", "26.10.0") < 0);
        assertEquals(0, ServerReleaseClient.compareVersions("26.7", "26.7.0"));
        assertTrue(ServerReleaseClient.compareVersions("99999999999999999999999", "26") > 0);
        assertThrows(IllegalArgumentException.class, () -> ServerReleaseClient.jarName("fabric", "../../evil"));
        assertThrows(IllegalArgumentException.class, () -> ServerReleaseClient.requireHttpUri("http://example.com/mod.jar"));
    }

    @Test public void stagesValidatedJarOutsideMods() throws Exception {
        byte[] body = jar("guildquests", "26.7.2", "*", "~1.20.1");
        String url = serve(body, 200);
        Path dir = temporary.newFolder("update").toPath();
        Path staged = ServerReleaseClient.downloadToStage(
                new ServerReleaseClient.UpdateInfo("26.7.2", url, body.length, sha256(body)),
                dir, "fabric", 2, 2, true, VALIDATOR);
        assertTrue(staged.toString().endsWith(".jar.download"));
        assertArrayEquals(body, Files.readAllBytes(staged));
        try (var files = Files.list(dir)) { assertEquals(1, files.count()); }
    }

    @Test public void httpFailureLeavesNoJarOrPartialFile() throws Exception {
        String url = serve("not found".getBytes(StandardCharsets.UTF_8), 404);
        Path dir = temporary.newFolder("update").toPath();
        assertThrows(IOException.class, () -> ServerReleaseClient.downloadToStage(
                new ServerReleaseClient.UpdateInfo("26.7.2", url, 9, "a".repeat(64)),
                dir, "fabric", 2, 2, true, VALIDATOR));
        try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
    }

    @Test public void rejectsWrongSizeAndDigest() throws Exception {
        byte[] body = jar("guildquests", "26.7.2", "*", "~1.20.1");
        String url = serve(body, 200);
        Path dir = temporary.newFolder("update").toPath();
        String hash = sha256(body);
        assertThrows(IOException.class, () -> ServerReleaseClient.downloadToStage(
                new ServerReleaseClient.UpdateInfo("26.7.2", url, body.length + 1, hash),
                dir, "fabric", 2, 2, true, VALIDATOR));
        assertThrows(IOException.class, () -> ServerReleaseClient.downloadToStage(
                new ServerReleaseClient.UpdateInfo("26.7.2", url, body.length, "0".repeat(64)),
                dir, "fabric", 2, 2, true, VALIDATOR));
        assertThrows(IOException.class, () -> ServerReleaseClient.downloadToStage(
                new ServerReleaseClient.UpdateInfo("26.7.2", url, body.length, null),
                dir, "fabric", 2, 2, true, VALIDATOR));
        try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
    }

    @Test public void rejectsReleaseAssetWithoutDigest() {
        String json = "{\"tag_name\":\"v26.7.2\",\"assets\":[{\"name\":\"guildquests_fabric-1.20.1_26.7.2.jar\","
                + "\"size\":123,\"browser_download_url\":\"https://example.com/mod.jar\"}]}";
        assertThrows(IllegalArgumentException.class,
                () -> ServerReleaseClient.parseRelease(json, "26.7.1", "fabric"));
    }

    @Test public void rejectsInvalidIncompatibleAndClientOnlyJars() throws Exception {
        Path path = temporary.newFile("asset.jar").toPath();
        for (byte[] bad : new byte[][]{
                "<html>error</html>".getBytes(StandardCharsets.UTF_8),
                jar("othermod", "26.7.2", "*", "~1.20.1"),
                jar("guildquests", "26.7.1", "*", "~1.20.1"),
                jar("guildquests", "26.7.2", "client", "~1.20.1"),
                jar("guildquests", "26.7.2", "*", "~1.21")}) {
            Files.write(path, bad);
            assertThrows(IOException.class, () -> ServerReleaseClient.validateJar(path, "26.7.2", true, VALIDATOR));
        }
    }

    @Test public void rejectsCorruptClassEntryEvenWhenMetadataIsValid() throws Exception {
        byte[] body = jar("guildquests", "26.7.2", "*", "~1.20.1");
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(body).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        boolean corrupted = false;
        for (int i = 0; i < body.length - 46; i++) {
            if (buffer.getInt(i) != 0x02014b50) continue;
            int nameLength = Short.toUnsignedInt(buffer.getShort(i + 28));
            String name = new String(body, i + 46, nameLength, StandardCharsets.UTF_8);
            if (name.endsWith(".class")) {
                buffer.putInt(i + 16, 0);
                corrupted = true;
                break;
            }
        }
        assertTrue(corrupted);
        Path path = temporary.newFile("corrupt-class.jar").toPath();
        Files.write(path, body);
        assertThrows(IOException.class, () -> ServerReleaseClient.validateJar(path, "26.7.2", true, VALIDATOR));
    }

    @Test public void apiRateLimitIsFailureButNoReleaseIsNot() throws Exception {
        String failedUrl = serve("rate limited".getBytes(StandardCharsets.UTF_8), 403);
        assertThrows(IOException.class, () -> ServerReleaseClient.check("26.7.1", "fabric", failedUrl, 2, 2));
        http.stop(0);
        String absentUrl = serve("no releases".getBytes(StandardCharsets.UTF_8), 404);
        assertNull(ServerReleaseClient.check("26.7.1", "fabric", absentUrl, 2, 2));
    }
}

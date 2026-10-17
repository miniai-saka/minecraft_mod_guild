package dev.guildquests.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipFile;

/** Fabric metadata validation, isolated from the shared updater and Forge runtime. */
public final class FabricUpdateMetadata {
    private FabricUpdateMetadata() { }

    public static void validate(ZipFile jar, String version, boolean dedicatedServer, Map<String, String> installed) throws IOException {
        try {
            var entry = jar.getEntry("fabric.mod.json");
            if (entry == null) throw new IOException("Missing fabric.mod.json (expected a Fabric update)");
            JsonObject mod;
            try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                mod = JsonParser.parseReader(reader).getAsJsonObject();
            }
            if (!"guildquests".equals(mod.get("id").getAsString()) || !version.equals(mod.get("version").getAsString())) {
                throw new IOException("JAR mod ID/version does not match the release");
            }
            if (dedicatedServer && mod.has("environment") && "client".equals(mod.get("environment").getAsString())) {
                throw new IOException("Client-only JAR cannot be installed on a server");
            }
            if (mod.has("depends")) {
                for (var dependency : mod.getAsJsonObject("depends").entrySet()) {
                    String available = installed.get(dependency.getKey());
                    if (available == null || !matches(dependency.getValue(), available)) {
                        throw new IOException("Incompatible " + dependency.getKey() + " dependency: " + dependency.getValue());
                    }
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid Fabric metadata", e);
        }
    }

    private static boolean matches(JsonElement predicate, String installed) throws Exception {
        if (predicate.isJsonArray()) {
            for (JsonElement value : predicate.getAsJsonArray()) {
                if (VersionPredicate.parse(value.getAsString()).test(Version.parse(installed))) return true;
            }
            return false;
        }
        return VersionPredicate.parse(predicate.getAsString()).test(Version.parse(installed));
    }
}

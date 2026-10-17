package dev.guildquests.update;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** Uses Forge's TOML and Maven version syntax without loading the downloaded Mod. */
public final class ForgeUpdateMetadata {
    private ForgeUpdateMetadata() { }

    public static void validate(ZipFile jar, String version, boolean dedicatedServer, Map<String, String> installed) throws IOException {
        try {
            var entry = jar.getEntry("META-INF/mods.toml");
            if (entry == null) throw new IOException("Missing META-INF/mods.toml (expected a Forge update)");
            UnmodifiableConfig metadata;
            try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                metadata = new TomlParser().parse(reader);
            }
            if (!"javafml".equals(metadata.get("modLoader"))) throw new IOException("Unsupported Forge Mod loader");
            requireVersion("Forge loader", metadata.get("loaderVersion"), installed.get("forge"));
            List<? extends UnmodifiableConfig> mods = metadata.get("mods");
            UnmodifiableConfig guild = null;
            if (mods != null) {
                for (UnmodifiableConfig mod : mods) {
                    if ("guildquests".equals(mod.get("modId"))) { guild = mod; break; }
                }
            }
            if (guild == null) throw new IOException("JAR does not contain the guildquests Mod");
            String declaredVersion = guild.get("version");
            if ("${file.jarVersion}".equals(declaredVersion)) {
                var manifestEntry = jar.getEntry("META-INF/MANIFEST.MF");
                if (manifestEntry == null) throw new IOException("Missing version manifest");
                try (var input = jar.getInputStream(manifestEntry)) {
                    declaredVersion = new Manifest(input).getMainAttributes().getValue("Implementation-Version");
                }
            }
            if (!version.equals(declaredVersion)) throw new IOException("JAR Mod version does not match the release");
            String javaRange = metadata.get("features.guildquests.javaVersion");
            if (javaRange != null) requireVersion("Java", javaRange, installed.get("java"));
            List<? extends UnmodifiableConfig> dependencies = metadata.get("dependencies.guildquests");
            if (dependencies != null) {
                for (UnmodifiableConfig dependency : dependencies) {
                    String side = dependency.get("side");
                    if (dedicatedServer && "CLIENT".equals(side)) continue;
                    if (!dedicatedServer && "SERVER".equals(side)) continue;
                    String modId = dependency.get("modId");
                    if (Boolean.FALSE.equals(dependency.get("mandatory")) && !installed.containsKey(modId)) continue;
                    requireVersion(modId, dependency.get("versionRange"), installed.get(modId));
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid Forge metadata", e);
        }
    }

    private static void requireVersion(String name, String range, String installed) throws Exception {
        if (range == null || installed == null) throw new IOException("Missing required " + name + " dependency");
        VersionRange predicate = VersionRange.createFromVersionSpec(range);
        DefaultArtifactVersion available = new DefaultArtifactVersion(installed);
        boolean matches = predicate.hasRestrictions() ? predicate.containsVersion(available)
                : predicate.getRecommendedVersion() != null && predicate.getRecommendedVersion().compareTo(available) == 0;
        if (!matches) throw new IOException("Incompatible " + name + " dependency: " + range + " (installed: " + installed + ")");
    }
}

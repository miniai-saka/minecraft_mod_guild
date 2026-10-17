package dev.guildquests.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class ForgeUpdateMetadataTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Map<String, String> INSTALLED = Map.of("forge", "47.2.0", "minecraft", "1.20.1", "java", "17");
    private static final String METADATA = """
            modLoader = "javafml"
            loaderVersion = "[47,)"
            [[mods]]
            modId = "guildquests"
            version = "26.12.2"
            description = '''Valid Forge server update.'''
            [[dependencies.guildquests]]
            modId = "forge"
            mandatory = true
            versionRange = "[47,)"
            side = "BOTH"
            [[dependencies.guildquests]]
            modId = "minecraft"
            mandatory = true
            versionRange = "[1.20.1,1.20.2)"
            side = "BOTH"
            [[dependencies.guildquests]]
            modId = "create"
            mandatory = false
            versionRange = "[6,7)"
            side = "BOTH"
            """;

    private Path jar(String metadata, boolean forge, String manifestVersion) throws IOException {
        Path path = Files.createTempFile(temporary.getRoot().toPath(), "test-", ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(forge ? "META-INF/mods.toml" : "fabric.mod.json"));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (manifestVersion != null) {
                zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                zip.write(("Manifest-Version: 1.0\r\nImplementation-Version: " + manifestVersion + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }

    private void validate(String metadata, Map<String, String> installed) throws IOException {
        try (ZipFile zip = new ZipFile(jar(metadata, true, null).toFile())) {
            ForgeUpdateMetadata.validate(zip, "26.12.2", true, installed);
        }
    }

    @Test public void acceptsForgeUpdateWithoutOptionalCreate() throws Exception {
        validate(METADATA, INSTALLED);
    }

    @Test public void rejectsAnotherLoaderOrModOrVersion() throws Exception {
        try (ZipFile zip = new ZipFile(jar("{}", false, null).toFile())) {
            assertThrows(IOException.class, () -> ForgeUpdateMetadata.validate(zip, "26.12.2", true, INSTALLED));
        }
        assertThrows(IOException.class, () -> validate(METADATA.replace("modId = \"guildquests\"", "modId = \"othermod\""), INSTALLED));
        assertThrows(IOException.class, () -> validate(METADATA.replace("version = \"26.12.2\"", "version = \"26.12.3\""), INSTALLED));
        assertThrows(IOException.class, () -> validate(METADATA.replace("javafml", "anotherloader"), INSTALLED));
    }

    @Test public void rejectsIncompatibleMinecraftAndForge() {
        assertThrows(IOException.class, () -> validate(METADATA.replace("[1.20.1,1.20.2)", "[1.21,1.22)"), INSTALLED));
        assertThrows(IOException.class, () -> validate(METADATA.replace("[47,)", "[48,)"), INSTALLED));
    }

    @Test public void validatesOptionalDependencyWhenItIsInstalled() throws Exception {
        validate(METADATA, Map.of("forge", "47.2.0", "minecraft", "1.20.1", "java", "17", "create", "6.0.8"));
        assertThrows(IOException.class, () -> validate(METADATA,
                Map.of("forge", "47.2.0", "minecraft", "1.20.1", "java", "17", "create", "5.1.0")));
    }

    @Test public void rejectsMissingMandatoryDependency() {
        assertThrows(IOException.class, () -> validate(METADATA.replace("mandatory = false", "mandatory = true"), INSTALLED));
    }

    @Test public void skipsClientOnlyDependencyOnDedicatedServer() throws Exception {
        String metadata = METADATA.replace("mandatory = false", "mandatory = true");
        int side = metadata.lastIndexOf("side = \"BOTH\"");
        metadata = metadata.substring(0, side) + "side = \"CLIENT\"\n";
        validate(metadata, INSTALLED);
    }

    @Test public void rejectsJava21FeatureOnJava17() {
        assertThrows(IOException.class, () -> validate(METADATA + "\n[features.guildquests]\njavaVersion = \"[21,)\"\n", INSTALLED));
    }

    @Test public void resolvesManifestVersionPlaceholder() throws Exception {
        String metadata = METADATA.replace("version = \"26.12.2\"", "version = \"${file.jarVersion}\"");
        try (ZipFile zip = new ZipFile(jar(metadata, true, "26.12.2").toFile())) {
            ForgeUpdateMetadata.validate(zip, "26.12.2", true, INSTALLED);
        }
        try (ZipFile zip = new ZipFile(jar(metadata, true, "26.12.1").toFile())) {
            assertThrows(IOException.class, () -> ForgeUpdateMetadata.validate(zip, "26.12.2", true, INSTALLED));
        }
    }

    @Test public void parsesTomlInsteadOfMatchingDescriptionText() throws Exception {
        validate(METADATA.replace("Valid Forge server update.", "modId = \"wrong\"\nversion = \"999.0.0\""), INSTALLED);
        assertThrows(IOException.class, () -> validate("not = [valid toml", INSTALLED));
    }
}

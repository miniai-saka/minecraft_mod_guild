package dev.guildquests.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class UpdateInstallerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path current;
    private Path staged;
    private Path mods;

    private Path prepare() throws IOException {
        return prepare("fabric");
    }

    private Path prepare(String loader) throws IOException {
        mods = temporary.newFolder("mods with spaces").toPath();
        Path dir = temporary.newFolder("config", "guildquests", "update").toPath();
        current = mods.resolve("renamed-current.jar");
        staged = dir.resolve("new.jar.download");
        Files.writeString(current, "original Mod");
        Files.writeString(staged, "verified replacement");
        return UpdateInstallation.prepare(current, mods, dir, staged, "26.7.2", loader);
    }

    @Test public void installsBacksUpAndIsIdempotent() throws Exception {
        Path plan = prepare();
        Path unrelated = mods.resolve("guildquests-unrelated.jar");
        Files.writeString(unrelated, "leave alone");
        Properties p = UpdateInstaller.readPlan(plan);
        UpdateInstaller.install(plan);
        UpdateInstaller.install(plan);
        assertFalse(Files.exists(current));
        assertFalse(Files.exists(plan));
        assertFalse(Files.exists(staged));
        assertEquals("verified replacement", Files.readString(mods.resolve(p.getProperty("targetFile"))));
        assertEquals("original Mod", Files.readString(plan.getParent().resolve("backups").resolve(p.getProperty("backupFile"))));
        assertEquals("leave alone", Files.readString(unrelated));
    }

    @Test public void rejectsTamperedStageWithoutRemovingCurrent() throws Exception {
        Path plan = prepare();
        Files.writeString(staged, "tampered");
        assertThrows(IOException.class, () -> UpdateInstaller.install(plan));
        assertEquals("original Mod", Files.readString(current));
        assertTrue(Files.exists(plan));
    }

    @Test public void installsForgeAndPreservesItsLoaderName() throws Exception {
        Path plan = prepare("forge");
        Properties p = UpdateInstaller.readPlan(plan);
        assertEquals("forge", p.getProperty("loader"));
        UpdateInstaller.install(plan);
        assertEquals("verified replacement", Files.readString(mods.resolve("guildquests_forge-1.20.1_26.7.2.jar")));
        assertFalse(Files.exists(current));
    }

    @Test public void acceptsLegacyFabricPlanWithoutLoaderField() throws Exception {
        Path plan = prepare();
        Properties p = UpdateInstaller.readPlan(plan);
        p.remove("loader");
        try (var out = Files.newOutputStream(plan)) { p.store(out, "legacy plan"); }
        UpdateInstaller.install(plan);
        assertTrue(Files.exists(mods.resolve("guildquests_fabric-1.20.1_26.7.2.jar")));
    }

    @Test public void rejectsChangedCurrentAndConflictingTarget() throws Exception {
        Path plan = prepare();
        Files.writeString(current, "manually replaced");
        assertThrows(IOException.class, () -> UpdateInstaller.install(plan));
        assertEquals("manually replaced", Files.readString(current));
        Files.writeString(current, "original Mod");
        Files.writeString(mods.resolve("guildquests_fabric-1.20.1_26.7.2.jar"), "unrelated contents");
        assertThrows(IOException.class, () -> UpdateInstaller.install(plan));
        assertEquals("original Mod", Files.readString(current));
    }

    @Test public void rejectsPathsOutsideMods() throws Exception {
        Path plan = prepare();
        Properties p = UpdateInstaller.readPlan(plan);
        p.setProperty("currentFile", "../outside.jar");
        try (var out = Files.newOutputStream(plan)) { p.store(out, "test"); }
        assertThrows(IOException.class, () -> UpdateInstaller.install(plan));
        assertTrue(Files.exists(current));
    }

    @Test public void extractedHelperRunsWithJava17AndNoModDependencies() throws Exception {
        Path plan = prepare();
        Properties p = UpdateInstaller.readPlan(plan);
        p.setProperty("parentPid", "2147483647");
        try (var out = Files.newOutputStream(plan)) { p.store(out, "test"); }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path log = plan.resolveSibling("test-installer.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", plan.resolveSibling("installer.jar").toString(), UpdateInstaller.class.getName(), plan.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(child.waitFor(15, TimeUnit.SECONDS));
        assertEquals(Files.readString(log), 0, child.exitValue());
        assertFalse(Files.exists(current));
        assertFalse(Files.exists(plan));
    }
}

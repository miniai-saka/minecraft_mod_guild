package dev.guildquests.fx;

import dev.guildquests.client.FxUiPreferences;
import dev.guildquests.platform.Platform;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class FxUiPreferencesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path config;
    @Before public void bindConfig() {
        config = temporary.getRoot().toPath();
        Platform.bind(new Platform.Backend() {
            public Path configDir() { return config; }
            public String getLoaderName() { return "test"; }
            public Path getModsDir() { return config; }
            public void onOpenGuildRequest(java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> c) { }
            public void onServerStarted(java.util.function.Consumer<net.minecraft.server.MinecraftServer> c) { }
            public void onServerStopped(java.util.function.Consumer<net.minecraft.server.MinecraftServer> c) { }
            public void onPlayerKill(java.util.function.BiConsumer<net.minecraft.server.level.ServerPlayer, net.minecraft.world.entity.LivingEntity> c) { }
            public void onServerTick(java.util.function.Consumer<net.minecraft.server.MinecraftServer> c) { }
            public void onCommands(Platform.CommandHandler c) { }
            public void onAllowDamage(Platform.AllowDamageHandler c) { }
            public void onEntityDeath(java.util.function.BiConsumer<net.minecraft.world.entity.LivingEntity, net.minecraft.world.damagesource.DamageSource> c) { }
            public void onBlockBreak(Platform.BlockBreakHandler c) { }
            public boolean canSend(net.minecraft.server.level.ServerPlayer p, net.minecraft.resources.ResourceLocation r) { return false; }
            public void send(net.minecraft.server.level.ServerPlayer p, net.minecraft.resources.ResourceLocation r, net.minecraft.network.FriendlyByteBuf b) { }
        });
    }
    @Test public void firstLaunchCreatesConfigWithHotkeyDisabled() throws Exception {
        FxUiPreferences.load(); assertFalse(FxUiPreferences.hotkeyEnabled()); assertEquals("SERVER", FxUiPreferences.style());
        assertTrue(Files.readString(config.resolve("fx-client.json")).contains("\"hotkeyEnabled\":false"));
    }
    @Test public void oldUiConfigKeepsStyleAndDisablesHotkey() throws Exception {
        Files.writeString(config.resolve("fx-client.json"), "{\"uiStyle\":\"WHITE\"}");
        FxUiPreferences.load(); assertEquals("WHITE", FxUiPreferences.style()); assertFalse(FxUiPreferences.hotkeyEnabled());
    }
    @Test public void hotkeyAndStyleChangesPersistWithoutOverwritingEachOther() throws Exception {
        FxUiPreferences.load(); FxUiPreferences.setHotkeyEnabled(true); FxUiPreferences.set("WHITE"); FxUiPreferences.load();
        assertTrue(FxUiPreferences.hotkeyEnabled()); assertEquals("WHITE", FxUiPreferences.style());
        FxUiPreferences.setHotkeyEnabled(false); FxUiPreferences.load();
        assertFalse(FxUiPreferences.hotkeyEnabled()); assertEquals("WHITE", FxUiPreferences.style());
    }
    @Test public void hotkeyOnlyConfigUsesDefaultStyle() throws Exception {
        Files.writeString(config.resolve("fx-client.json"), "{\"hotkeyEnabled\":true}");
        FxUiPreferences.load(); assertTrue(FxUiPreferences.hotkeyEnabled()); assertEquals("SERVER", FxUiPreferences.style());
    }
}

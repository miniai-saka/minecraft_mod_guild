package dev.guildquests.client;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.block.ModBlocks;
import dev.guildquests.platform.Platform;
import dev.guildquests.platform.PlatformClient;
import dev.guildquests.update.UpdateChecker;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import org.lwjgl.glfw.GLFW;

public final class GuildQuestsClient implements ClientModInitializer {
    private static volatile UpdateChecker.UpdateInfo pendingUpdate;
    private static boolean updateScreenShown = false;

    @Override
    public void onInitializeClient() {
        FxClient.init();
        PlatformClient.onBlockRenderCutout(ModBlocks.GUILD_BANK);

        PlatformClient.registerKeyBinding("key.guildquests.openguild", GLFW.GLFW_KEY_G,
                PlatformClient::sendOpenGuild);

        BossPinRenderer.init();
        PlatformClient.onBossPin(BossPinRenderer::set);
        PlatformClient.onDisconnect(BossPinRenderer::clear);

        // Check GitHub for a newer version (async).
        String currentVer = GuildQuestsMod.VERSION;
        String loader = Platform.getLoaderName();
        GuildQuestsMod.LOGGER.info("[Client] Starting update check: current=v{}, loader={}", currentVer, loader);
        if (!Boolean.getBoolean("guildquests.disableUpdateCheck")) UpdateChecker.checkAsync(currentVer, loader, info -> {
            pendingUpdate = info;
            GuildQuestsMod.LOGGER.info("[Client] Update available: v{} (current: v{})", info.latestVersion(), currentVer);
        });

        // Show the update popup once when the title screen appears.
        PlatformClient.onClientTick(screen -> {
            if (updateScreenShown) return;
            if (screen instanceof TitleScreen && pendingUpdate != null) {
                updateScreenShown = true;
                GuildQuestsMod.LOGGER.info("[Client] Showing update screen for v{}", pendingUpdate.latestVersion());
                MinecraftClient.getInstance().setScreen(new UpdateScreen(screen, currentVer, pendingUpdate));
            }
        });
    }
}

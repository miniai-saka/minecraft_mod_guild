package dev.guildquests.client;

import dev.guildquests.block.ModBlocks;
import dev.guildquests.platform.PlatformClient;
import net.fabricmc.api.ClientModInitializer;
import org.lwjgl.glfw.GLFW;

public final class GuildQuestsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PlatformClient.onBlockRenderCutout(ModBlocks.GUILD_BANK);

        PlatformClient.registerKeyBinding("key.guildquests.openguild", GLFW.GLFW_KEY_G,
                PlatformClient::sendOpenGuild);

        BossPinRenderer.init();
        PlatformClient.onBossPin(BossPinRenderer::set);
        PlatformClient.onDisconnect(BossPinRenderer::clear);
    }
}

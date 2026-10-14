package dev.guildquests.client;

import dev.guildquests.block.ModBlocks;
import dev.guildquests.platform.PlatformClient;
import org.lwjgl.glfw.GLFW;

public final class GuildQuestsClient {
    private GuildQuestsClient() {
    }

    public static void init() {
        PlatformClient.onBlockRenderCutout(ModBlocks.GUILD_BANK);

        PlatformClient.registerKeyBinding("key.guildquests.openguild", GLFW.GLFW_KEY_G,
                PlatformClient::sendOpenGuild);

        BossPinRenderer.init();
        PlatformClient.onBossPin(BossPinRenderer::set);
        PlatformClient.onDisconnect(BossPinRenderer::clear);
    }
}

package dev.guildquests.forge;

import dev.guildquests.client.GuildQuestsClient;
import dev.guildquests.platform.PlatformClient;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Client-only bootstrap. Referenced from {@link GuildQuestsForge} through
 * {@code DistExecutor} so this class (and everything it touches) is only loaded on the client.
 */
final class ForgeClientBootstrap {
    private ForgeClientBootstrap() {
    }

    static void init(IEventBus modBus) {
        PlatformClient.bind(new ForgeClientBackend(modBus));
        GuildQuestsClient.init();
    }
}

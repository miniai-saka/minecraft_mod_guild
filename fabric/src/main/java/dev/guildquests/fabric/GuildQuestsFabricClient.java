package dev.guildquests.fabric;

import dev.guildquests.client.GuildQuestsClient;
import dev.guildquests.platform.PlatformClient;
import net.fabricmc.api.ClientModInitializer;

/** Fabric client entrypoint: binds the client backend, then runs shared client init. */
public final class GuildQuestsFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PlatformClient.bind(new FabricClientBackend());
        GuildQuestsClient.init();
    }
}

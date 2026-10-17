package dev.guildquests.fabric;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.platform.Platform;
import net.fabricmc.api.ModInitializer;

/** Fabric main entrypoint: binds the platform backend, then runs shared init. */
public final class GuildQuestsFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Platform.bind(new FabricBackend());
        FabricFxNetwork.init();
        GuildQuestsMod.registerContent(FabricContentRegistrar.INSTANCE);
        GuildQuestsMod.init();
        dev.guildquests.update.ServerUpdateService.register();
    }
}

package dev.guildquests.forge;

import dev.guildquests.client.GuildQuestsClient;
import dev.guildquests.platform.PlatformClient;
import dev.guildquests.fx.FxModels;
import dev.guildquests.fx.FxNetwork;
import java.util.function.Consumer;
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
        FxNetwork.client = new FxNetwork.ClientTransport() {
            @Override public void listen(Consumer<FxModels.Snapshot> receiver) { ForgeNetwork.setFxSnapshotSink(receiver); }
            @Override public void send(FxModels.Request request) { ForgeNetwork.sendFxRequest(request); }
        };
        GuildQuestsClient.init();
    }
}

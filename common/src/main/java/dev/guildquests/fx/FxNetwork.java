package dev.guildquests.fx;

import dev.guildquests.fx.FxModels.Request;
import dev.guildquests.fx.FxModels.Snapshot;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.Consumer;

/** Small loader boundary; exchange, persistence and both UIs are shared across loaders. */
public final class FxNetwork {
    private FxNetwork() { }
    public interface ServerTransport {
        boolean canSend(ServerPlayer player);
        void send(ServerPlayer player, Snapshot snapshot);
    }
    public interface ClientTransport {
        void listen(Consumer<Snapshot> receiver);
        void send(Request request);
    }
    public static ServerTransport server;
    public static ClientTransport client;
}

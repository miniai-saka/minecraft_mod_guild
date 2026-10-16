package dev.guildquests.fabric;

import dev.guildquests.fx.*;
import dev.guildquests.fx.FxModels.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import java.util.function.Consumer;

public final class FabricFxClientNetwork implements FxNetwork.ClientTransport {
    public void listen(Consumer<Snapshot> receiver) {
        ClientPlayNetworking.registerGlobalReceiver(FxService.SNAPSHOT, (client, handler, buffer, sender) -> {
            Snapshot snapshot = FxSettings.JSON.fromJson(buffer.readUtf(1_000_000), Snapshot.class);
            client.execute(() -> receiver.accept(snapshot));
        });
    }
    public void send(Request request) {
        if (!ClientPlayNetworking.canSend(FxService.REQUEST)) return;
        var buffer = PacketByteBufs.create(); buffer.writeUtf(FxSettings.JSON.toJson(request), 4096);
        ClientPlayNetworking.send(FxService.REQUEST, buffer);
    }
}

package dev.guildquests.fabric;

import dev.guildquests.fx.*;
import dev.guildquests.fx.FxModels.*;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public final class FabricFxNetwork {
    private FabricFxNetwork() { }
    public static void init() {
        FxNetwork.server = new FxNetwork.ServerTransport() {
            public boolean canSend(ServerPlayer player) { return ServerPlayNetworking.canSend(player, FxService.SNAPSHOT); }
            public void send(ServerPlayer player, Snapshot snapshot) {
                var buffer = PacketByteBufs.create(); buffer.writeUtf(FxSettings.JSON.toJson(snapshot), 1_000_000);
                ServerPlayNetworking.send(player, FxService.SNAPSHOT, buffer);
            }
        };
        ServerPlayNetworking.registerGlobalReceiver(FxService.REQUEST, (server, player, handler, buffer, sender) -> {
            final Request request;
            try { request = FxSettings.JSON.fromJson(buffer.readUtf(4096), Request.class); }
            catch (RuntimeException ignored) { return; }
            if (request != null) server.execute(() -> FxService.handle(server, player, request));
        });
    }
}

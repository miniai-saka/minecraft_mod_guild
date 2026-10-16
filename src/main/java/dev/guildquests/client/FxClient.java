package dev.guildquests.client;

import dev.guildquests.fx.FxModels.Request;
import dev.guildquests.fx.FxModels.Snapshot;
import dev.guildquests.fx.FxService;
import dev.guildquests.fx.FxSettings;
import dev.guildquests.platform.PlatformClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import org.lwjgl.glfw.GLFW;

public final class FxClient {
    private FxClient() { }
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(FxService.SNAPSHOT, (client, handler, buf, sender) -> {
            Snapshot snapshot = FxSettings.JSON.fromJson(buf.readString(1_000_000), Snapshot.class);
            client.execute(() -> {
                if (snapshot.open && !(client.currentScreen instanceof FxScreen)) client.setScreen(new FxScreen(snapshot));
                else if (client.currentScreen instanceof FxScreen screen) screen.accept(snapshot);
            });
        });
        PlatformClient.registerKeyBinding("key.guildquests.openfx", GLFW.GLFW_KEY_F8, () -> {
            Request r = new Request(); r.op = "OPEN"; send(r);
        });
    }
    public static void send(Request request) {
        if (MinecraftClient.getInstance().player == null || !ClientPlayNetworking.canSend(FxService.REQUEST)) return;
        PacketByteBuf buffer = PacketByteBufs.create(); buffer.writeString(FxSettings.JSON.toJson(request), 4096);
        ClientPlayNetworking.send(FxService.REQUEST, buffer);
    }
}

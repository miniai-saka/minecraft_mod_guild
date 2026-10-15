package dev.guildquests.platform;

import dev.guildquests.service.BossEvent;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side loader entry points (keybinding, S2C packets, render layer).
 * Keep Fabric imports confined here so a future loader port only rewrites this class.
 */
public final class PlatformClient {
    private PlatformClient() {
    }

    public static void onBlockRenderCutout(Block block) {
        BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getCutout());
    }

    public static KeyBinding registerKeyBinding(String translationKey, int defaultKey, Runnable onPress) {
        KeyBinding key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                translationKey,
                InputUtil.Type.KEYSYM,
                defaultKey,
                "key.categories.guildquests"
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (key.wasPressed()) {
                onPress.run();
            }
        });
        return key;
    }

    public static void sendOpenGuild() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (!ClientPlayNetworking.canSend(Platform.OPEN_GUILD)) return;
        ClientPlayNetworking.send(Platform.OPEN_GUILD, PacketByteBufs.empty());
    }

    public static void onBossPin(BossPinHandler handler) {
        ClientPlayNetworking.registerGlobalReceiver(BossEvent.BOSS_PIN, (client, networkHandler, buf, responseSender) -> {
            int x = buf.readVarInt();
            int y = buf.readVarInt();
            int z = buf.readVarInt();
            int life = buf.readVarInt();
            client.execute(() -> handler.accept(x, y, z, life));
        });
    }

    public static void onDisconnect(Runnable handler) {
        ClientPlayConnectionEvents.DISCONNECT.register((networkHandler, client) -> handler.run());
    }

    /** Called every client tick. currentScreen is the active screen, or null. */
    public static void onClientTick(java.util.function.Consumer<net.minecraft.client.gui.screen.Screen> handler) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> handler.accept(client.currentScreen));
    }

    @FunctionalInterface
    public interface BossPinHandler {
        void accept(int x, int y, int z, int lifetimeTicks);
    }
}

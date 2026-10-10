package dev.guildquests.client;

import dev.guildquests.block.ModBlocks;
import dev.guildquests.service.BossEvent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class GuildQuestsClient implements ClientModInitializer {
    /** C2S: ask the server to open the guild quest screen. */
    public static final Identifier OPEN_GUILD = new Identifier("guildquests", "open_guild");

    private static KeyBinding openGuildKey;

    @Override
    public void onInitializeClient() {
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.GUILD_BANK, RenderLayer.getCutout());

        openGuildKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.guildquests.openguild",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "key.categories.guildquests"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuildKey.wasPressed()) {
                if (client.player != null && ClientPlayNetworking.canSend(OPEN_GUILD)) {
                    ClientPlayNetworking.send(OPEN_GUILD, PacketByteBufs.empty());
                }
            }
        });

        BossPinRenderer.init();
        ClientPlayNetworking.registerGlobalReceiver(BossEvent.BOSS_PIN, (client, handler, buf, responseSender) -> {
            int x = buf.readVarInt();
            int y = buf.readVarInt();
            int z = buf.readVarInt();
            int life = buf.readVarInt();
            client.execute(() -> BossPinRenderer.set(x, y, z, life));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> BossPinRenderer.clear());
    }
}


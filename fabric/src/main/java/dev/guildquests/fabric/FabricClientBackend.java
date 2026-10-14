package dev.guildquests.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.guildquests.platform.PlatformClient;
import dev.guildquests.platform.WorldRenderContext;
import dev.guildquests.service.BossEvent;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import com.mojang.blaze3d.vertex.PoseStack;

/** Fabric implementation of the loader-neutral {@link PlatformClient.ClientBackend}. */
public final class FabricClientBackend implements PlatformClient.ClientBackend {
    @Override
    public void onBlockRenderCutout(Block block) {
        BlockRenderLayerMap.INSTANCE.putBlock(block, RenderType.cutout());
    }

    @Override
    public KeyMapping registerKeyBinding(String translationKey, int defaultKey, Runnable onPress) {
        KeyMapping key = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                translationKey,
                InputConstants.Type.KEYSYM,
                defaultKey,
                "key.categories.guildquests"
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (key.consumeClick()) {
                onPress.run();
            }
        });
        return key;
    }

    @Override
    public void sendOpenGuild(ResourceLocation channel) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        if (!ClientPlayNetworking.canSend(channel)) return;
        ClientPlayNetworking.send(channel, PacketByteBufs.empty());
    }

    @Override
    public void onBossPin(PlatformClient.BossPinHandler handler) {
        ClientPlayNetworking.registerGlobalReceiver(BossEvent.BOSS_PIN, (client, listener, buf, sender) -> {
            int x = buf.readVarInt();
            int y = buf.readVarInt();
            int z = buf.readVarInt();
            int life = buf.readVarInt();
            client.execute(() -> handler.accept(x, y, z, life));
        });
    }

    @Override
    public void onDisconnect(Runnable handler) {
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> handler.run());
    }

    @Override
    public void onWorldRender(PlatformClient.WorldRenderHandler handler) {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context ->
                handler.render(new FabricWorldRenderContext(context.camera(), context.matrixStack())));
    }

    private record FabricWorldRenderContext(Camera camera, PoseStack poseStack) implements WorldRenderContext {
    }
}

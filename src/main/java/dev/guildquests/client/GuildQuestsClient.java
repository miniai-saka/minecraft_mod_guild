package dev.guildquests.client;

import dev.guildquests.block.ModBlocks;
import dev.guildquests.client.gui.ClientGui;
import dev.guildquests.net.GuiState;
import dev.guildquests.net.NetCodec;
import dev.guildquests.net.NetworkIds;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GuildQuestsClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("guildquests-client");

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
                if (client.player != null) ClientGui.send("guild|open");
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(NetworkIds.STATE, (client, handler, buf, responseSender) -> {
            GuiState state;
            try {
                state = NetCodec.decode(buf.readString());
            } catch (RuntimeException e) {
                LOGGER.warn("Dropping malformed GUI state packet", e);
                return;
            }
            if (state == null || state.view == null || state.view.isBlank()) {
                LOGGER.warn("Dropping GUI state packet without a view");
                return;
            }
            ClientGui.onState(state);
        });
    }
}

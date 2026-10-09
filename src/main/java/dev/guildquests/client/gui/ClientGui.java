package dev.guildquests.client.gui;

import dev.guildquests.net.GuiState;
import dev.guildquests.net.NetworkIds;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point of the button-GUI protocol: receives {@link GuiState} pushes, opens or
 * refreshes the matching screen, and sends {@code "view|action|arg..."} action payloads.
 */
public final class ClientGui {
    private static final Logger LOGGER = LoggerFactory.getLogger("guildquests");

    private ClientGui() {
    }

    /** Invoked from the network thread; marshals onto the client thread. */
    public static void onState(GuiState state) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            switch (state.view) {
                // Screens are wired up as they land on this branch (GUI-B..D).
                case "guild", "board", "publish", "bank", "transfer" ->
                        LOGGER.debug("GUI state for '{}' (open={}, not yet rendered)", state.view, state.openScreen);
                default -> LOGGER.warn("GUI state with unknown view: {}", state.view);
            }
        });
    }

    /** Sends an action payload; no-op when the server does not run the mod. */
    public static void send(String payload) {
        if (MinecraftClient.getInstance().player == null) return;
        if (!ClientPlayNetworking.canSend(NetworkIds.ACTION)) return;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(payload);
        ClientPlayNetworking.send(NetworkIds.ACTION, buf);
    }
}

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
        client.execute(() -> apply(client, state));
    }

    private static void apply(MinecraftClient client, GuiState state) {
        switch (state.view) {
            case "guild" -> {
                if (state.openScreen) {
                    client.setScreen(new GuildScreen(state));
                } else if (client.currentScreen instanceof GuildScreen screen) {
                    screen.applyState(state);
                }
            }
            case "bank" -> {
                if (state.openScreen) {
                    client.setScreen(new BankScreen(state));
                } else if (client.currentScreen instanceof BankScreen screen) {
                    screen.applyState(state);
                }
            }
            case "transfer" -> {
                if (state.openScreen) {
                    client.setScreen(new TransferScreen(state));
                } else if (client.currentScreen instanceof TransferScreen screen) {
                    screen.applyState(state);
                }
            }
            default -> LOGGER.warn("GUI state with unknown view: {}", state.view);
        }
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

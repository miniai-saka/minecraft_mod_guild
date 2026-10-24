package dev.guildquests.fabric;

import dev.guildquests.gui.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry;
import net.minecraft.resources.ResourceLocation;

/** Only amount editing needs a custom packet; actions use validated vanilla menu clicks. */
public final class FabricGuildGui {
    public static final ResourceLocation AMOUNT = new ResourceLocation("guildquests", "gui_amount");
    private FabricGuildGui() { }
    @SuppressWarnings("deprecation")
    public static void init() {
        for (GuildMenuKind kind : GuildMenuKind.values()) {
            GuildMenus.bind(kind, ScreenHandlerRegistry.registerSimple(
                    new ResourceLocation("guildquests", "gui_" + kind.id()),
                    (id, inventory) -> new GuildClientMenu(kind, id, inventory)));
        }
        ServerPlayNetworking.registerGlobalReceiver(AMOUNT, (server, player, listener, buf, sender) -> {
            if (buf.readableBytes() != 12) return;
            int id = buf.readInt();
            long amount = buf.readLong();
            server.execute(() -> {
                if (amount < 0 || player.isRemoved() || player.connection != listener) return;
                if (player.containerMenu instanceof GuildMenu menu && menu.containerId == id
                        && menu.stillValid(player) && menu.setGuiAmount(amount)) menu.broadcastChanges();
            });
        });
    }
}

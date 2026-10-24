package dev.guildquests.fabric;

import dev.guildquests.client.gui.GuildGuiActions;
import dev.guildquests.client.gui.GuildMenuScreen;
import dev.guildquests.gui.GuildMenuKind;
import dev.guildquests.gui.GuildMenus;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.screens.MenuScreens;

public final class FabricGuildGuiClient {
    private FabricGuildGuiClient() { }
    public static void init() {
        for (GuildMenuKind kind : GuildMenuKind.values())
            MenuScreens.register(GuildMenus.clientType(kind), GuildMenuScreen::new);
        GuildGuiActions.amountSender = (id, amount) -> {
            if (!ClientPlayNetworking.canSend(FabricGuildGui.AMOUNT)) return;
            var buf = PacketByteBufs.create();
            buf.writeInt(id);
            buf.writeLong(amount);
            ClientPlayNetworking.send(FabricGuildGui.AMOUNT, buf);
        };
    }
}

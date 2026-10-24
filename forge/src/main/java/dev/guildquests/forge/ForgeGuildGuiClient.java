package dev.guildquests.forge;

import dev.guildquests.client.gui.GuildGuiActions;
import dev.guildquests.client.gui.GuildMenuScreen;
import dev.guildquests.gui.GuildMenuKind;
import dev.guildquests.gui.GuildMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Kept separate so dedicated servers never load Minecraft client classes. */
final class ForgeGuildGuiClient {
    private ForgeGuildGuiClient() { }

    static void init(IEventBus bus) {
        bus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() -> {
            for (GuildMenuKind kind : GuildMenuKind.values())
                MenuScreens.register(GuildMenus.clientType(kind), GuildMenuScreen::new);
            GuildGuiActions.amountSender = ForgeNetwork::sendGuiAmount;
        }));
    }
}

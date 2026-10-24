package dev.guildquests.forge;

import dev.guildquests.gui.GuildClientMenu;
import dev.guildquests.gui.GuildMenuKind;
import dev.guildquests.gui.GuildMenus;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/** Registers the same server-owned menus as the Fabric backend. */
public final class ForgeGuildGui {
    private static final DeferredRegister<MenuType<?>> TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, GuildQuestsForge.MOD_ID);

    private ForgeGuildGui() { }

    public static void register(IEventBus bus) {
        for (GuildMenuKind kind : GuildMenuKind.values()) {
            TYPES.register("gui_" + kind.id(), () -> {
                MenuType<GuildClientMenu> type = IForgeMenuType.create(
                        (id, inventory, data) -> new GuildClientMenu(kind, id, inventory));
                GuildMenus.bind(kind, type);
                return type;
            });
        }
        TYPES.register(bus);
    }
}

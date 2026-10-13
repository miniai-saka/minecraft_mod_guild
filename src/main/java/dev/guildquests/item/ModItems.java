package dev.guildquests.item;

import dev.guildquests.GuildQuestsMod;
import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModItems {
    public static final ItemGroup GUILD_TAB = FabricItemGroupBuilder.build(
            new Identifier(GuildQuestsMod.MOD_ID, "guild"), () -> new ItemStack(ModItems.COIN));

    public static final Item COIN = new Item(new Item.Settings().group(GUILD_TAB));

    private ModItems() {
    }

    public static void register() {
        Registry.register(Registry.ITEM, new Identifier(GuildQuestsMod.MOD_ID, "coin"), COIN);
    }

    public static void addItemToTab(Item entry) {
        // Items are added to the dedicated guild tab at registration; this method kept for compatibility.
    }
}

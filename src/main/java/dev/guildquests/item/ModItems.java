package dev.guildquests.item;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.block.ModBlocks;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Optional;

public final class ModItems {
    public static final Item COIN = new Item(new Item.Settings());
    public static final RegistryKey<ItemGroup> GUILD_TAB_KEY =
            RegistryKey.of(Registries.ITEM_GROUP.getKey(), new Identifier(GuildQuestsMod.MOD_ID, "guild"));

    private ModItems() {
    }

    public static void register() {
        Registry.register(Registries.ITEM, new Identifier(GuildQuestsMod.MOD_ID, "coin"), COIN);

        ItemGroup guildTab = FabricItemGroup.builder()
                .icon(() -> new ItemStack(COIN))
                .displayName(Text.literal("GuildQuests"))
                .entries((display, entries) -> {
                    entries.add(COIN);
                    entries.add(ModBlocks.GUILD_BANK.asItem());
                    entries.add(ModBlocks.GUILD_DEPOSIT.asItem());
                })
                .build();
        Registry.register(Registries.ITEM_GROUP, GUILD_TAB_KEY.getValue(), guildTab);
    }

    public static void addItemToTab(Item entry) {
        // Items are added to the dedicated guild tab at registration; this method kept for compatibility.
    }

    private static RegistryKey<ItemGroup> findTab() {
        return GUILD_TAB_KEY;
    }
}

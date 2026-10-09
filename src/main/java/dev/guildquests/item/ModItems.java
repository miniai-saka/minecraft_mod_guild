package dev.guildquests.item;

import dev.guildquests.GuildQuestsMod;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;

import java.util.Optional;

public final class ModItems {
    public static final Item COIN = new Item(new Item.Settings());

    private ModItems() {
    }

    public static void register() {
        Registry.register(Registries.ITEM, new Identifier(GuildQuestsMod.MOD_ID, "coin"), COIN);
        addItemToTab(COIN);
    }

    public static void addItemToTab(Item entry) {
        RegistryKey<ItemGroup> tab = findTab();
        if (tab != null) {
            ItemGroupEvents.modifyEntriesEvent(tab).register(entries -> entries.add(entry));
        } else {
            GuildQuestsMod.LOGGER.warn("Could not find a creative tab for " + Registries.ITEM.getId(entry) + ".");
        }
    }

    private static RegistryKey<ItemGroup> findTab() {
        RegistryKey<ItemGroup> misc = null;
        for (ItemGroup group : Registries.ITEM_GROUP) {
            Identifier id = Registries.ITEM_GROUP.getId(group);
            if (id == null) continue;
            Optional<RegistryKey<ItemGroup>> key = Registries.ITEM_GROUP.getKey(group);
            if (key.isEmpty()) continue;
            if ("ingredients".equals(id.getPath())) return key.get();
            if ("misc".equals(id.getPath())) misc = key.get();
        }
        return misc;
    }
}

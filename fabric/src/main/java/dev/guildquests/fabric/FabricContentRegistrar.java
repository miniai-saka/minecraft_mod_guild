package dev.guildquests.fabric;

import dev.guildquests.platform.ContentRegistrar;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Fabric registries are open during initialization, so plain registry writes are fine. */
final class FabricContentRegistrar implements ContentRegistrar {
    static final FabricContentRegistrar INSTANCE = new FabricContentRegistrar();

    private FabricContentRegistrar() {
    }

    @Override
    public void registerItem(ResourceLocation id, Item item) {
        Registry.register(BuiltInRegistries.ITEM, id, item);
    }

    @Override
    public void registerBlock(ResourceLocation id, Block block) {
        Registry.register(BuiltInRegistries.BLOCK, id, block);
    }

    @Override
    public void registerBlockEntityType(ResourceLocation id, BlockEntityType<?> type) {
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, type);
    }

    @Override
    public void registerCreativeModeTab(ResourceLocation id, CreativeModeTab tab) {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id, tab);
    }
}

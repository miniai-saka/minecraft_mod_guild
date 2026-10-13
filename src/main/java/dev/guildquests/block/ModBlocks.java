package dev.guildquests.block;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.item.ModItems;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.Material;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlocks {
    public static final Block GUILD_BANK = new GuildBankBlock(
            AbstractBlock.Settings.of(Material.METAL, MapColor.GOLD)
                    .mapColor(MapColor.GOLD)
                    .strength(3.5f, 6.0f)
                    .requiresTool()
                    .nonOpaque());

    public static final Block GUILD_DEPOSIT = new GuildDepositBlock(
            AbstractBlock.Settings.of(Material.METAL, MapColor.GOLD)
                    .mapColor(MapColor.GOLD)
                    .strength(2.5f, 6.0f)
                    .requiresTool());

    public static final BlockEntityType<GuildDepositBlockEntity> GUILD_DEPOSIT_BE =
            BlockEntityType.Builder.create(GuildDepositBlockEntity::new, GUILD_DEPOSIT).build(null);

    private ModBlocks() {
    }

    public static void register() {
        Identifier bankId = new Identifier(GuildQuestsMod.MOD_ID, "guild_bank");
        Registry.register(Registry.BLOCK, bankId, GUILD_BANK);
        Registry.register(Registry.ITEM, bankId, new BlockItem(GUILD_BANK, new Item.Settings().group(ModItems.GUILD_TAB)));

        Identifier depositId = new Identifier(GuildQuestsMod.MOD_ID, "guild_deposit");
        Registry.register(Registry.BLOCK, depositId, GUILD_DEPOSIT);
        Registry.register(Registry.BLOCK_ENTITY_TYPE, depositId, GUILD_DEPOSIT_BE);
        Registry.register(Registry.ITEM, depositId, new BlockItem(GUILD_DEPOSIT, new Item.Settings().group(ModItems.GUILD_TAB)));
    }
}

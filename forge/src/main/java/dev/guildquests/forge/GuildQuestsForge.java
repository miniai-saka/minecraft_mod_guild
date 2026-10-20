package dev.guildquests.forge;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.block.ModBlocks;
import dev.guildquests.effect.ModEffects;
import dev.guildquests.item.ModItems;
import dev.guildquests.platform.ContentRegistrar;
import dev.guildquests.platform.Platform;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegisterEvent;

/**
 * Forge entry point. Runs during mod construction: registry population, network channel,
 * platform backend and shared mod init all happen here, mirroring the Fabric entrypoints.
 */
@Mod(GuildQuestsForge.MOD_ID)
public final class GuildQuestsForge {
    public static final String MOD_ID = "guildquests";

    private final ForgeBackend backend = new ForgeBackend();

    public GuildQuestsForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ForgeNetwork.register();
        Platform.bind(backend);
        backend.registerForgeEvents();
        modBus.addListener(this::handleRegister);
        GuildQuestsMod.init();

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ForgeClientBootstrap.init(modBus));
    }

    /**
     * Forge freezes the vanilla registries before mod loading, so content must be
     * registered from {@link RegisterEvent}, one registry at a time.
     */
    private void handleRegister(RegisterEvent event) {
        ContentRegistrar registrar = new EventRegistrar(event);
        ResourceKey<? extends Registry<?>> key = event.getRegistryKey();
        if (Registries.BLOCK.equals(key)) {
            ModBlocks.registerBlocks(registrar);
        } else if (Registries.ITEM.equals(key)) {
            ModItems.registerItems(registrar);
            ModBlocks.registerBlockItems(registrar);
        } else if (Registries.BLOCK_ENTITY_TYPE.equals(key)) {
            ModBlocks.registerBlockEntityTypes(registrar);
        } else if (Registries.MOB_EFFECT.equals(key)) {
            ModEffects.registerEffects(registrar);
        } else if (Registries.CREATIVE_MODE_TAB.equals(key)) {
            ModItems.registerCreativeTab(registrar);
        }
    }

    /** Routes common registration calls into the in-flight {@link RegisterEvent}. */
    private record EventRegistrar(RegisterEvent event) implements ContentRegistrar {
        @Override
        public void registerItem(ResourceLocation id, Item item) {
            event.register(Registries.ITEM, id, () -> item);
        }

        @Override
        public void registerBlock(ResourceLocation id, Block block) {
            event.register(Registries.BLOCK, id, () -> block);
        }

        @Override
        public void registerBlockEntityType(ResourceLocation id, BlockEntityType<?> type) {
            event.register(Registries.BLOCK_ENTITY_TYPE, id, () -> type);
        }

        @Override
        public void registerMobEffect(ResourceLocation id, MobEffect effect) {
            event.register(Registries.MOB_EFFECT, id, () -> effect);
        }

        @Override
        public void registerCreativeModeTab(ResourceLocation id, CreativeModeTab tab) {
            event.register(Registries.CREATIVE_MODE_TAB, id, () -> tab);
        }
    }
}

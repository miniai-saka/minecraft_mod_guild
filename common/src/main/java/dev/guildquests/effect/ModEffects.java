package dev.guildquests.effect;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.platform.ContentRegistrar;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;

/** Status effects added by GuildQuests. */
public final class ModEffects {
    /** Held by players while emergency bosses must ignore them. */
    public static final MobEffect BOSS_PROTECTION = new BossProtectionEffect();

    public static final ResourceLocation BOSS_PROTECTION_ID =
            new ResourceLocation(GuildQuestsMod.MOD_ID, "boss_protection");

    private ModEffects() {
    }

    public static void registerEffects(ContentRegistrar registrar) {
        registrar.registerMobEffect(BOSS_PROTECTION_ID, BOSS_PROTECTION);
    }
}

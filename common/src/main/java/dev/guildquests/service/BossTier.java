package dev.guildquests.service;

import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.entity.EntityType;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Difficulty tiers for emergency bosses. A tier defines how much health the boss gets, which
 * mobs can appear, how strong its gear and buffs are, and how much extra reward it pays out.
 * Weights are configured through {@code bossTierWeights} (one entry per tier, in declaration order).
 */
public enum BossTier {
    WEAK("弱", 0.5, 0.5, BossBarColor.BLUE, -1, -1, -1,
            List.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
                    EntityType.SKELETON, EntityType.STRAY, EntityType.SPIDER, EntityType.CAVE_SPIDER)),
    NORMAL("並", 1.0, 1.0, BossBarColor.RED, -1, -1, -1,
            List.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
                    EntityType.SKELETON, EntityType.STRAY, EntityType.CREEPER,
                    EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.ENDERMAN,
                    EntityType.WITCH, EntityType.PILLAGER, EntityType.VINDICATOR,
                    EntityType.EVOKER, EntityType.RAVAGER)),
    STRONG("強", 2.0, 2.0, BossBarColor.YELLOW, 0, 0, 0,
            List.of(EntityType.ENDERMAN, EntityType.SKELETON, EntityType.STRAY,
                    EntityType.WITCH, EntityType.PILLAGER, EntityType.VINDICATOR,
                    EntityType.EVOKER, EntityType.RAVAGER)),
    ELITE("最強", 4.0, 4.0, BossBarColor.PURPLE, 1, 1, 0,
            List.of(EntityType.VINDICATOR, EntityType.EVOKER, EntityType.RAVAGER,
                    EntityType.WITCH));

    private final String label;
    private final double healthMultiplier;
    private final double rewardMultiplier;
    private final BossBarColor barColor;
    private final int strengthAmplifier;
    private final int speedAmplifier;
    private final int resistanceAmplifier;
    private final List<EntityType<?>> mobs;

    BossTier(String label, double healthMultiplier, double rewardMultiplier, BossBarColor barColor,
             int strengthAmplifier, int speedAmplifier, int resistanceAmplifier, List<EntityType<?>> mobs) {
        this.label = label;
        this.healthMultiplier = healthMultiplier;
        this.rewardMultiplier = rewardMultiplier;
        this.barColor = barColor;
        this.strengthAmplifier = strengthAmplifier;
        this.speedAmplifier = speedAmplifier;
        this.resistanceAmplifier = resistanceAmplifier;
        this.mobs = mobs;
    }

    public String label() {
        return label;
    }

    public double healthMultiplier() {
        return healthMultiplier;
    }

    public double rewardMultiplier() {
        return rewardMultiplier;
    }

    public BossBarColor barColor() {
        return barColor;
    }

    public int strengthAmplifier() {
        return strengthAmplifier;
    }

    public int speedAmplifier() {
        return speedAmplifier;
    }

    public int resistanceAmplifier() {
        return resistanceAmplifier;
    }

    public EntityType<?> randomMob(Random random) {
        return mobs.get(random.nextInt(mobs.size()));
    }

    /** Command id: weak / normal / strong / elite. */
    public static BossTier byId(String id) {
        if (id == null) return null;
        for (BossTier tier : values()) {
            if (tier.name().toLowerCase(Locale.ROOT).equals(id.toLowerCase(Locale.ROOT))) return tier;
        }
        return null;
    }

    /** Weighted roll. Missing or empty weights fall back to an equal distribution. */
    public static BossTier roll(Random random, int[] weights) {
        BossTier[] tiers = values();
        int total = 0;
        for (int i = 0; i < tiers.length; i++) {
            total += weight(weights, i);
        }
        if (total <= 0) {
            return tiers[random.nextInt(tiers.length)];
        }
        int pick = random.nextInt(total);
        for (int i = 0; i < tiers.length; i++) {
            pick -= weight(weights, i);
            if (pick < 0) return tiers[i];
        }
        return NORMAL;
    }

    private static int weight(int[] weights, int index) {
        if (weights == null || index >= weights.length) return 0;
        return Math.max(0, Math.min(1000, weights[index]));
    }

    /** Restores the tier of a boss that survived a restart, based on its display name. */
    public static BossTier fromDisplayName(String name) {
        if (name != null) {
            for (BossTier tier : values()) {
                if (name.startsWith("【緊急ボス・" + tier.label + "】")) return tier;
            }
        }
        return NORMAL;
    }
}

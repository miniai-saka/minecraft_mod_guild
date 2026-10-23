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
    ELITE("最強", 4.0, 5.0, BossBarColor.PURPLE, 1, 1, 0,
            List.of(EntityType.VINDICATOR, EntityType.EVOKER, EntityType.RAVAGER,
                    EntityType.WITCH)),
    WEAKEST("最弱", 0.25, 0.25, BossBarColor.WHITE, -1, -1, -1,
            List.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED,
                    EntityType.SKELETON, EntityType.STRAY, EntityType.SPIDER, EntityType.CAVE_SPIDER));

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

    /** Attack damage grows with the square root of health, so elite bosses hit about twice as hard. */
    public double attackMultiplier() {
        return Math.sqrt(healthMultiplier);
    }

    /** Extra melee reach in blocks; weaker tiers keep the vanilla range. */
    public double reachBonus() {
        return Math.max(0, Math.min(2, healthMultiplier - 1));
    }

    /** Attack speed grows with the tier, so elite bosses swing roughly twice as often. */
    public double attackSpeedMultiplier() {
        return Math.min(2.0, 1.0 + (healthMultiplier - 1) * 0.33);
    }

    /** Minimum attack damage; only the elite tier reaches far enough to one-shot the ender dragon (200 HP). */
    public double attackFloor() {
        return this == ELITE ? 250.0 : 0.0;
    }

    /** Cost position of this tier in the protection price table (weakest to elite). */
    public int priceIndex() {
        return switch (this) {
            case WEAKEST -> 0;
            case WEAK -> 1;
            case NORMAL -> 2;
            case STRONG -> 3;
            case ELITE -> 4;
        };
    }

    /** Coin price to buy protection against this tier, from the configured table. */
    public int protectionPrice(int[] prices) {
        int index = priceIndex();
        return prices != null && index < prices.length ? Math.max(0, prices[index]) : 0;
    }

    /** Arrow damage: lower than melee, but still meaningful for every tier. */
    public double arrowDamage() {
        return switch (this) {
            case WEAKEST -> 3;
            case WEAK -> 5;
            case NORMAL -> 7;
            case STRONG -> 9;
            case ELITE -> 60;
        };
    }

    /** The weakest boss revives as the elite with this chance when killed. */
    public double promoteChance(double configured) {
        return this == WEAKEST ? Math.max(0, Math.min(1, configured)) : 0;
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

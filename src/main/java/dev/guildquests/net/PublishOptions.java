package dev.guildquests.net;

import dev.guildquests.model.QuestType;

/** Fixed choice lists for the publish wizard (shared by client and server validation). */
public final class PublishOptions {
    public static final String[] KILL_TARGETS = {
            "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper", "minecraft:spider",
            "minecraft:enderman", "minecraft:blaze", "minecraft:pillager", "minecraft:phantom",
            "minecraft:slime", "minecraft:witch", "minecraft:drowned", "minecraft:husk",
            "minecraft:wither_skeleton", "minecraft:guardian", "minecraft:evoker", "minecraft:hoglin"
    };
    public static final String[] SUBMIT_TARGETS = {
            "minecraft:rotten_flesh", "minecraft:bone", "minecraft:string", "minecraft:gunpowder",
            "minecraft:coal", "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond",
            "minecraft:emerald", "minecraft:wheat", "minecraft:carrot", "minecraft:leather",
            "minecraft:feather", "minecraft:oak_log", "minecraft:cobblestone", "minecraft:sand"
    };
    public static final int[] AMOUNTS = {5, 10, 16, 32, 64};
    public static final long[] REWARDS = {10, 25, 50, 100, 250, 500};

    private PublishOptions() {
    }

    public static String[] targets(QuestType type) {
        return type == QuestType.KILL ? KILL_TARGETS : SUBMIT_TARGETS;
    }
}

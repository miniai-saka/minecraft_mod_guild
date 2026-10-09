package dev.guildquests.data;

import net.minecraft.nbt.NbtCompound;

public final class ActiveQuest {
    public String questId;
    public int progress;
    /** Concrete entity/item id rolled for today's board; null = use definition default. */
    public String target;
    /** Concrete count rolled for today's board; 0 = use definition amount. */
    public int amount;
    /** EXPLORE COORDINATE: x/z were drawn on today's board. */
    public boolean randomCoords;
    public double x;
    public double y;
    public double z;
    /** Urgent quest accepted from today's board (boosted reward). */
    public boolean urgent;
    /** Reward locked at accept time for an urgent quest; 0 = use the definition reward. */
    public long rewardOverride;

    public ActiveQuest(String questId, int progress) {
        this.questId = questId;
        this.progress = progress;
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("QuestId", questId);
        nbt.putInt("Progress", progress);
        nbt.putString("Target", target == null ? "" : target);
        nbt.putInt("Amount", amount);
        nbt.putBoolean("RandomCoords", randomCoords);
        nbt.putDouble("X", x);
        nbt.putDouble("Y", y);
        nbt.putDouble("Z", z);
        nbt.putBoolean("Urgent", urgent);
        nbt.putLong("RewardOverride", rewardOverride);
        return nbt;
    }

    public static ActiveQuest fromNbt(NbtCompound nbt) {
        ActiveQuest quest = new ActiveQuest(nbt.getString("QuestId"), Math.max(0, nbt.getInt("Progress")));
        String target = nbt.getString("Target");
        quest.target = target.isEmpty() ? null : target;
        quest.amount = Math.max(0, nbt.getInt("Amount"));
        quest.randomCoords = nbt.getBoolean("RandomCoords");
        quest.x = nbt.getDouble("X");
        quest.y = nbt.getDouble("Y");
        quest.z = nbt.getDouble("Z");
        quest.urgent = nbt.getBoolean("Urgent");
        quest.rewardOverride = Math.max(0, nbt.getLong("RewardOverride"));
        return quest;
    }
}

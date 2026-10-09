package dev.guildquests.data;

import net.minecraft.nbt.NbtCompound;

public final class DailyQuest {
    public String questId = "";
    /** Rolled concrete target (entity id / item id) for today's draw. */
    public String target = "";
    /** Rolled concrete amount for today's draw. */
    public int amount = 1;
    /** EXPLORE COORDINATE: true when x/y/z were drawn for today. */
    public boolean hasCoords = false;
    public double x;
    public double y;
    public double z;
    /** Urgent quest drawn for today (red board entry, boosted reward). */
    public boolean urgent = false;
    /** Boosted reward for an urgent quest; 0 = use the definition reward. */
    public long rewardOverride = 0;

    public DailyQuest() {
    }

    public DailyQuest(String questId) {
        this.questId = questId;
    }

    /** Effective reward for this board entry. */
    public long reward(long definitionReward) {
        return rewardOverride > 0 ? rewardOverride : definitionReward;
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("QuestId", questId);
        nbt.putString("Target", target == null ? "" : target);
        nbt.putInt("Amount", amount);
        nbt.putBoolean("HasCoords", hasCoords);
        nbt.putDouble("X", x);
        nbt.putDouble("Y", y);
        nbt.putDouble("Z", z);
        nbt.putBoolean("Urgent", urgent);
        nbt.putLong("RewardOverride", rewardOverride);
        return nbt;
    }

    public static DailyQuest fromNbt(NbtCompound nbt) {
        DailyQuest quest = new DailyQuest(nbt.getString("QuestId"));
        quest.target = nbt.getString("Target");
        quest.amount = Math.max(1, nbt.getInt("Amount"));
        quest.hasCoords = nbt.getBoolean("HasCoords");
        quest.x = nbt.getDouble("X");
        quest.y = nbt.getDouble("Y");
        quest.z = nbt.getDouble("Z");
        quest.urgent = nbt.getBoolean("Urgent");
        quest.rewardOverride = Math.max(0, nbt.getLong("RewardOverride"));
        return quest;
    }
}

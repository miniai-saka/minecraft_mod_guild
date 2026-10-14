package dev.guildquests.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PlayerGuildData {
    public long coins = 0;
    public int completedTotal = 0;
    /** Payment mode for Create shop purchases: RIBO / DEBIT / DISABLED. */
    public String paymentMode = "DEBIT";
    /** Outstanding revolving-credit (ribo) debt in coins, including accrued interest. */
    public long debt = 0;
    /** True when a scheduled ribo collection failed; locks guild features until debt == 0. */
    public boolean delinquent = false;
    /** Epoch millis of the next scheduled ribo collection. 0 when no debt. */
    public long nextRiboDueAt = 0;
    public final List<ActiveQuest> activeQuests = new ArrayList<>();
    public final Set<String> completedQuestIds = new HashSet<>();

    public ActiveQuest findActive(String questId) {
        for (ActiveQuest active : activeQuests) {
            if (active.questId.equals(questId)) return active;
        }
        return null;
    }

    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putLong("Coins", coins);
        nbt.putInt("CompletedTotal", completedTotal);
        nbt.putString("PaymentMode", paymentMode == null ? "DEBIT" : paymentMode);
        nbt.putLong("Debt", debt);
        nbt.putBoolean("Delinquent", delinquent);
        nbt.putLong("NextRiboDueAt", nextRiboDueAt);

        ListTag activeList = new ListTag();
        for (ActiveQuest active : activeQuests) activeList.add(active.toNbt());
        nbt.put("ActiveQuests", activeList);

        ListTag completedList = new ListTag();
        for (String id : completedQuestIds) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", id);
            completedList.add(entry);
        }
        nbt.put("CompletedQuestIds", completedList);
        return nbt;
    }

    public static PlayerGuildData fromNbt(CompoundTag nbt) {
        PlayerGuildData data = new PlayerGuildData();
        data.coins = Math.max(0L, nbt.getLong("Coins"));
        data.completedTotal = Math.max(0, nbt.getInt("CompletedTotal"));
        data.paymentMode = nbt.getString("PaymentMode");
        if (data.paymentMode.isBlank()) data.paymentMode = "DEBIT";
        data.debt = Math.max(0L, nbt.getLong("Debt"));
        data.delinquent = nbt.getBoolean("Delinquent");
        data.nextRiboDueAt = Math.max(0L, nbt.getLong("NextRiboDueAt"));
        if (data.debt <= 0) {
            data.delinquent = false;
            data.nextRiboDueAt = 0;
        }

        ListTag activeList = nbt.getList("ActiveQuests", Tag.TAG_COMPOUND);
        for (int i = 0; i < activeList.size(); i++) {
            ActiveQuest active = ActiveQuest.fromNbt(activeList.getCompound(i));
            if (!active.questId.isBlank()) data.activeQuests.add(active);
        }

        ListTag completedList = nbt.getList("CompletedQuestIds", Tag.TAG_COMPOUND);
        for (int i = 0; i < completedList.size(); i++) {
            String id = completedList.getCompound(i).getString("Id");
            if (!id.isBlank()) data.completedQuestIds.add(id);
        }
        return data;
    }
}

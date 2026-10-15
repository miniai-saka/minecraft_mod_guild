package dev.guildquests.data;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

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
    /** Auction-house mailbox: items owed to this player (winnings, refunds, expired listings). */
    public final List<ItemStack> mailbox = new ArrayList<>();

    public ActiveQuest findActive(String questId) {
        for (ActiveQuest active : activeQuests) {
            if (active.questId.equals(questId)) return active;
        }
        return null;
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putLong("Coins", coins);
        nbt.putInt("CompletedTotal", completedTotal);
        nbt.putString("PaymentMode", paymentMode == null ? "DEBIT" : paymentMode);
        nbt.putLong("Debt", debt);
        nbt.putBoolean("Delinquent", delinquent);
        nbt.putLong("NextRiboDueAt", nextRiboDueAt);

        NbtList activeList = new NbtList();
        for (ActiveQuest active : activeQuests) activeList.add(active.toNbt());
        nbt.put("ActiveQuests", activeList);

        NbtList completedList = new NbtList();
        for (String id : completedQuestIds) {
            NbtCompound entry = new NbtCompound();
            entry.putString("Id", id);
            completedList.add(entry);
        }
        nbt.put("CompletedQuestIds", completedList);

        NbtList mailboxList = new NbtList();
        for (ItemStack stack : mailbox) {
            if (stack != null && !stack.isEmpty()) mailboxList.add(stack.writeNbt(new NbtCompound()));
        }
        nbt.put("AuctionMailbox", mailboxList);
        return nbt;
    }

    public static PlayerGuildData fromNbt(NbtCompound nbt) {
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

        NbtList activeList = nbt.getList("ActiveQuests", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < activeList.size(); i++) {
            ActiveQuest active = ActiveQuest.fromNbt(activeList.getCompound(i));
            if (!active.questId.isBlank()) data.activeQuests.add(active);
        }

        NbtList completedList = nbt.getList("CompletedQuestIds", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < completedList.size(); i++) {
            String id = completedList.getCompound(i).getString("Id");
            if (!id.isBlank()) data.completedQuestIds.add(id);
        }

        NbtList mailboxList = nbt.getList("AuctionMailbox", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < mailboxList.size(); i++) {
            ItemStack stack = ItemStack.fromNbt(mailboxList.getCompound(i));
            if (!stack.isEmpty()) data.mailbox.add(stack);
        }
        return data;
    }
}

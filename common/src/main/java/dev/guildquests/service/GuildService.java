package dev.guildquests.service;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.config.ConfigManager;
import dev.guildquests.config.ModConfig;
import dev.guildquests.data.ActiveQuest;
import dev.guildquests.data.DailyQuest;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.data.PublishedQuest;
import dev.guildquests.model.ExploreMode;
import dev.guildquests.model.QuestDefinition;
import dev.guildquests.model.QuestType;
import dev.guildquests.item.ModItems;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class GuildService {
    private static final Random RANDOM = new Random();

    private GuildService() {
    }

    public static PlayerGuildData data(ServerPlayer player) {
        return GuildPersistentState.get(player.getServer()).get(player.getUUID());
    }

    public static boolean acceptQuest(ServerPlayer player, QuestDefinition quest) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUUID());
        ensureDailyBoard(player.getServer());

        if (data.findActive(quest.id) != null) {
            message(player, "そのクエストは既に受注中です。", ChatFormatting.YELLOW);
            return false;
        }
        if (!quest.repeatable && data.completedQuestIds.contains(quest.id)) {
            message(player, "そのクエストは既に達成済みです。", ChatFormatting.YELLOW);
            return false;
        }
        if (data.completedTotal < quest.requiredCompleted) {
            message(player, "このクエストはまだ解放されていません。", ChatFormatting.RED);
            return false;
        }
        if (data.activeQuests.size() >= ConfigManager.config().maxActiveQuests) {
            message(player, "同時受注数の上限に達しています。", ChatFormatting.RED);
            return false;
        }

        DailyQuest daily = state.findDaily(quest.id);
        if (daily == null) {
            message(player, "本日のクエストではありません（次回の抽選をお待ちください）。", ChatFormatting.YELLOW);
            return false;
        }

        ActiveQuest active = new ActiveQuest(quest.id, 0);
        active.target = daily.target.isBlank() ? null : daily.target;
        active.amount = Math.max(1, daily.amount);
        if (daily.hasCoords) {
            active.randomCoords = true;
            active.x = daily.x;
            active.y = daily.y;
            active.z = daily.z;
        }
        data.activeQuests.add(active);
        state.setDirty();

        StringBuilder detail = new StringBuilder();
        if (quest.type == QuestType.KILL) {
            detail.append("（対象: ").append(entityName(effectiveTarget(active, quest)))
                    .append(" × ").append(active.amount).append("）");
        } else if (quest.type == QuestType.SUBMIT) {
            detail.append("（納品: ").append(itemName(quest.target)).append(" × ").append(active.amount).append("）");
        } else if (active.randomCoords) {
            String dim = dimensionLabel(quest.dimension);
            detail.append("（目的地: ").append(dim.isEmpty() ? "" : dim + " ")
                    .append(formatCoords(active))
                    .append(" 半径 ").append((int) quest.radius).append("）");
        }
        message(player, "クエスト「" + quest.title + "」を受注しました" + detail + "。", ChatFormatting.GREEN);
        return true;
    }

    /** Resolves a quest by id from the config definitions. */
    public static QuestDefinition resolveQuest(MinecraftServer server, String questId) {
        return ConfigManager.quest(questId);
    }

    // ---------------------------------------------------------------------
    // Legacy cleanup: the player-published quest (bounty board) system was
    // removed. On startup any leftover board entries are deleted, active
    // "pub:" quests are dropped, and the escrowed rewards are refunded.
    // ---------------------------------------------------------------------

    /** Refunds and clears all leftover player-published quests (bounty board removal). */
    public static void purgePublishedQuests(MinecraftServer server) {
        GuildPersistentState state = GuildPersistentState.get(server);
        if (state.publishedQuests.isEmpty()) {
            // Still drop any stray active pub:* quests from older saves.
            boolean stray = false;
            for (PlayerGuildData data : state.players().values()) {
                for (int i = data.activeQuests.size() - 1; i >= 0; i--) {
                    String id = data.activeQuests.get(i).questId;
                    if (id != null && id.startsWith("pub:")) {
                        data.activeQuests.remove(i);
                        stray = true;
                    }
                }
            }
            if (stray) state.setDirty();
            return;
        }

        int count = state.publishedQuests.size();
        for (PublishedQuest quest : new ArrayList<>(state.publishedQuests)) {
            for (PlayerGuildData data : state.players().values()) {
                ActiveQuest active = data.findActive(quest.questId());
                if (active != null) data.activeQuests.remove(active);
            }
            if (quest.publisherId != null && quest.reward > 0) {
                PlayerGuildData publisher = state.get(quest.publisherId);
                publisher.coins = safeAdd(publisher.coins, quest.reward);
            }
        }
        state.publishedQuests.clear();
        state.setDirty();
        GuildQuestsMod.LOGGER.info("Removed {} player-published quests (escrowed rewards refunded).", count);
    }

    public static void broadcast(MinecraftServer server, String text, ChatFormatting formatting) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal("[Guild] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(text).withStyle(formatting)), false);
        }
    }

    public static boolean cancelQuest(ServerPlayer player, String questId) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUUID());
        ActiveQuest active = data.findActive(questId);
        if (active == null) return false;
        data.activeQuests.remove(active);
        state.setDirty();
        QuestDefinition quest = resolveQuest(player.getServer(), questId);
        message(player, "クエスト「" + (quest == null ? questId : quest.title) + "」を破棄しました。", ChatFormatting.GRAY);
        return true;
    }

    public static void onKill(ServerPlayer player, LivingEntity killed) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUUID());
        boolean changed = false;
        String killedId = BuiltInRegistries.ENTITY_TYPE.getKey(killed.getType()).toString();

        for (ActiveQuest active : data.activeQuests) {
            QuestDefinition quest = resolveQuest(player.getServer(), active.questId);
            if (quest == null || quest.type != QuestType.KILL) continue;
            int amount = effectiveAmount(active, quest);
            if (active.progress >= amount) continue;
            if (!effectiveTarget(active, quest).equals(killedId)) continue;

            active.progress = Math.min(amount, active.progress + 1);
            changed = true;
            if (active.progress >= amount) {
                message(player, "クエスト「" + quest.title + "」の条件を達成しました。ギルド画面で報酬を受け取れます。", ChatFormatting.GOLD);
            }
        }
        if (changed) state.setDirty();
    }

    public static void checkExplore(ServerPlayer player) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUUID());
        boolean changed = false;

        for (ActiveQuest active : data.activeQuests) {
            QuestDefinition quest = resolveQuest(player.getServer(), active.questId);
            if (quest == null || quest.type != QuestType.EXPLORE) continue;
            int amount = effectiveAmount(active, quest);
            if (active.progress >= amount) continue;
            if (!isExploreTargetReached(player, quest, active)) continue;

            active.progress = amount;
            changed = true;
            message(player, "探索クエスト「" + quest.title + "」の条件を達成しました。ギルド画面で報酬を受け取れます。", ChatFormatting.GOLD);
        }
        if (changed) state.setDirty();
    }

    public static boolean submitOrClaim(ServerPlayer player, String questId) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUUID());
        ActiveQuest active = data.findActive(questId);
        QuestDefinition quest = resolveQuest(player.getServer(), questId);
        if (active == null || quest == null) return false;

        int amount = effectiveAmount(active, quest);
        if (quest.type == QuestType.SUBMIT && active.progress < amount) {
            ResourceLocation itemId = safeIdentifier(quest.target);
            if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                message(player, "納品対象アイテムの設定が不正です: " + quest.target, ChatFormatting.RED);
                return false;
            }
            Item target = BuiltInRegistries.ITEM.get(itemId);
            int remaining = amount - active.progress;
            int available = countItem(player.getInventory(), target);
            int consume = Math.min(remaining, available);
            if (consume <= 0) {
                message(player, "納品対象を所持していません。残り " + remaining + " 個です。", ChatFormatting.YELLOW);
                return false;
            }
            removeItem(player.getInventory(), target, consume);
            active.progress += consume;
            state.setDirty();
            message(player, consume + " 個納品しました。進捗: " + active.progress + "/" + amount, ChatFormatting.AQUA);
        }

        if (active.progress >= amount) {
            return claimQuest(player, questId);
        }
        return true;
    }

    public static boolean claimQuest(ServerPlayer player, String questId) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUUID());
        ActiveQuest active = data.findActive(questId);
        QuestDefinition quest = resolveQuest(player.getServer(), questId);
        if (active == null || quest == null || active.progress < effectiveAmount(active, quest)) return false;

        long reward = quest.reward;
        long tribute = 0;
        if (data.delinquent && data.debt > 0 && reward > 0) {
            tribute = Math.min(reward, data.debt);
            data.debt -= tribute;
            reward -= tribute;
            if (data.debt <= 0) {
                data.debt = 0;
                data.delinquent = false;
                data.nextRiboDueAt = 0;
                message(player, "リボ債務を完済しました。ギルド機能のロックを解除しました。", ChatFormatting.GREEN);
            }
        }

        data.coins = safeAdd(data.coins, reward);
        data.completedTotal++;
        data.completedQuestIds.add(quest.id);
        data.activeQuests.remove(active);
        state.setDirty();

        if (tribute > 0) {
            message(player, "クエスト「" + quest.title + "」達成！報酬 " + tribute
                    + " コインはリボ返済に充当" + (reward > 0 ? "、残り " + reward + " コインは受け取り" : "")
                    + "（残債務 " + data.debt + "）", ChatFormatting.GOLD);
        } else {
            message(player, "クエスト「" + quest.title + "」達成！ +" + reward + " コイン", ChatFormatting.GOLD);
        }
        return true;
    }

    public static String rank(PlayerGuildData data) {
        return ConfigManager.config().rankThresholds.entrySet().stream()
                .filter(e -> data.completedTotal >= Math.max(0, e.getValue()))
                .max(Comparator.comparingInt(e -> e.getValue()))
                .map(Map.Entry::getKey)
                .orElse("E");
    }

    public static long coins(ServerPlayer player) {
        return data(player).coins;
    }

    public static boolean payCoins(ServerPlayer player, long amount) {
        if (amount <= 0) return false;
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUUID());
        if (data.coins < amount) return false;
        data.coins -= amount;
        state.setDirty();
        return true;
    }

    public static void creditCoins(MinecraftServer server, UUID uuid, long amount) {
        if (amount <= 0 || uuid == null) return;
        GuildPersistentState state = GuildPersistentState.get(server);
        PlayerGuildData data = state.get(uuid);
        data.coins = safeAdd(data.coins, amount);
        state.setDirty();
    }

    // ---------------------------------------------------------------------
    // Payment modes (per player) and ribo (revolving credit)
    // ---------------------------------------------------------------------

    public static final String MODE_RIBO = "RIBO";
    public static final String MODE_DEBIT = "DEBIT";
    public static final String MODE_DISABLED = "DISABLED";

    public static String paymentMode(PlayerGuildData data) {
        String mode = data.paymentMode;
        if (MODE_RIBO.equals(mode) || MODE_DISABLED.equals(mode)) return mode;
        return MODE_DEBIT;
    }

    public static String paymentModeLabel(String mode) {
        if (MODE_RIBO.equals(mode)) return "リボ";
        if (MODE_DISABLED.equals(mode)) return "無効";
        return "引き落とし";
    }

    public static void setPaymentMode(ServerPlayer player, String mode) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUUID());
        data.paymentMode = mode;
        state.setDirty();
        switch (mode) {
            case MODE_RIBO -> message(player, "支払い方法を「リボ」に変更しました。残高がなくても購入でき、価格は債務として定期的に自動徴収されます。", ChatFormatting.AQUA);
            case MODE_DISABLED -> message(player, "支払い方法を「無効」に変更しました。残高からは支払えなくなりました。", ChatFormatting.YELLOW);
            default -> message(player, "支払い方法を「引き落とし」に変更しました。購入時に残高から即時引き落としします。", ChatFormatting.GREEN);
        }
    }

    public static void addDebt(ServerPlayer player, long amount) {
        if (amount <= 0) return;
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUUID());
        data.debt = safeAdd(data.debt, amount);
        if (data.debt > 0 && data.nextRiboDueAt <= 0) {
            data.nextRiboDueAt = System.currentTimeMillis() + riboIntervalMillis();
        }
        state.setDirty();
    }

    /** Player-to-player transfer of balance coins. Free of charge (no bank fee). */
    public static boolean transferCoins(ServerPlayer sender, ServerPlayer receiver, long amount) {
        if (receiver == null || amount <= 0) return false;
        if (sender == receiver) {
            message(sender, "自分自身には送金できません。", ChatFormatting.YELLOW);
            return false;
        }
        GuildPersistentState state = GuildPersistentState.get(sender.getServer());
        PlayerGuildData from = state.get(sender.getUUID());
        PlayerGuildData to = state.get(receiver.getUUID());
        if (from.delinquent) {
            riboLockMessage(sender, from);
            return false;
        }
        if (from.coins < amount) {
            message(sender, "残高が不足しています。残高: " + from.coins + " / 必要: " + amount, ChatFormatting.RED);
            return false;
        }
        from.coins -= amount;
        to.coins = safeAdd(to.coins, amount);
        state.setDirty();

        String senderName = sender.getName().getString();
        String receiverName = receiver.getName().getString();
        message(sender, receiverName + " へ " + amount + " コインを送金しました（手数料無料）。残高: " + from.coins, ChatFormatting.GREEN);
        if (!receiver.isRemoved()) {
            message(receiver, senderName + " から " + amount + " コインを受け取りました。残高: " + to.coins, ChatFormatting.AQUA);
        }
        return true;
    }

    private static long riboIntervalMillis() {
        return ConfigManager.config().riboIntervalMinutes * 60_000L;
    }

    /**
     * Scheduled ribo collection. Runs periodically for every player with outstanding debt:
     * applies interest, then collects the fixed installment from the balance. A failed
     * collection marks the player delinquent, which locks their guild features until the
     * full debt is repaid (quest rewards are tributed automatically while locked).
     */
    public static void runRiboCycles(MinecraftServer server) {
        ModConfig config = ConfigManager.config();
        long now = System.currentTimeMillis();
        long interval = riboIntervalMillis();
        GuildPersistentState state = GuildPersistentState.get(server);

        for (Map.Entry<UUID, PlayerGuildData> entry : state.players().entrySet()) {
            PlayerGuildData data = entry.getValue();
            if (data.debt <= 0) {
                if (data.nextRiboDueAt != 0 || data.delinquent) {
                    data.debt = 0;
                    data.delinquent = false;
                    data.nextRiboDueAt = 0;
                    state.setDirty();
                }
                continue;
            }
            if (data.nextRiboDueAt <= 0) {
                data.nextRiboDueAt = now + interval;
                state.setDirty();
            }
            if (now < data.nextRiboDueAt) continue;

            data.nextRiboDueAt = now + interval;
            if (config.riboInterestPercent > 0) {
                long interest = Math.round(data.debt * config.riboInterestPercent / 100.0);
                if (interest > 0) data.debt = safeAdd(data.debt, interest);
            }

            long due = Math.min(config.riboInstallment, data.debt);
            boolean newlyDelinquent = false;
            if (data.coins >= due) {
                data.coins -= due;
                data.debt -= due;
            } else if (!data.delinquent) {
                data.delinquent = true;
                newlyDelinquent = true;
            }

            boolean unlocked = false;
            if (data.debt <= 0) {
                data.debt = 0;
                data.delinquent = false;
                data.nextRiboDueAt = 0;
                unlocked = true;
            }
            state.setDirty();

            ServerPlayer online = server.getPlayerList().getPlayer(entry.getKey());
            if (online != null && newlyDelinquent) {
                message(online, "リボの定期返済が残高不足で失敗しました（債務 " + data.debt + "）。"
                        + "完済するまでギルド機能がロックされています。クエスト報酬は自動で返済に充てられます。", ChatFormatting.RED);
            }
            if (online != null && unlocked) {
                message(online, "リボ債務を完済しました。ギルド機能のロックを解除しました。", ChatFormatting.GREEN);
            }
        }
    }

    public static void riboLockMessage(ServerPlayer player, PlayerGuildData data) {
        message(player, "リボ払いの延滞中です。債務 " + data.debt
                + " を完済するまでギルド機能がロックされています（クエスト報酬で返済できます）。", ChatFormatting.RED);
    }

    public static long countHeldCoins(ServerPlayer player) {
        return countItem(player.getInventory(), ModItems.COIN);
    }

    /** Transaction fee in coins for the given amount. Waived when opened through the bank block. */
    public static long bankFee(long amount, boolean feeWaived) {
        if (feeWaived || amount <= 0) return 0;
        double pct = ConfigManager.config().bankFeePercent;
        if (pct <= 0) return 0;
        return Math.round(amount * pct / 100.0);
    }

    public static boolean bankWithdraw(ServerPlayer player, long amount, boolean feeWaived) {
        if (amount <= 0 || amount > Integer.MAX_VALUE) return false;
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUUID());
        if (data.delinquent) {
            riboLockMessage(player, data);
            return false;
        }
        if (data.coins < amount) {
            message(player, "コイン残高が不足しています。残高: " + data.coins + " / 必要: " + amount, ChatFormatting.RED);
            return false;
        }
        long fee = bankFee(amount, feeWaived);
        long receive = amount - fee;
        if (receive <= 0) {
            message(player, "手数料が高すぎて引き出せません。", ChatFormatting.RED);
            return false;
        }
        ItemStack coinStack = new ItemStack(ModItems.COIN, (int) receive);
        if (!canFit(player.getInventory(), coinStack)) {
            message(player, "インベントリに空きがありません。", ChatFormatting.RED);
            return false;
        }
        data.coins -= amount;
        giveCoins(player.getInventory(), coinStack);
        state.setDirty();
        message(player, receive + " コインを引き出しました"
                + (fee > 0 ? "（手数料 " + fee + "）" : "") + "。残高: " + data.coins, ChatFormatting.GREEN);
        return true;
    }

    public static boolean bankDeposit(ServerPlayer player, long amount, boolean feeWaived) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUUID());
        if (data.delinquent) {
            riboLockMessage(player, data);
            return false;
        }
        long held = countItem(player.getInventory(), ModItems.COIN);
        long take = amount <= 0 ? held : Math.min(amount, held);
        if (take <= 0) {
            message(player, "預け入れできるギルドコインを持っていません。", ChatFormatting.YELLOW);
            return false;
        }
        long fee = bankFee(take, feeWaived);
        long credit = take - fee;
        if (credit <= 0) {
            message(player, "手数料が高すぎて預け入れできません。", ChatFormatting.RED);
            return false;
        }
        removeItem(player.getInventory(), ModItems.COIN, (int) take);
        data.coins = safeAdd(data.coins, credit);
        state.setDirty();
        message(player, take + " コインを預け入れました"
                + (fee > 0 ? "（手数料 " + fee + "）" : "") + "。残高: " + data.coins, ChatFormatting.GREEN);
        return true;
    }

    /**
     * Fee-free deposit of every held coin into the target account (deposit block).
     * Always credits the owner's balance, never the clicking player's.
     */
    public static boolean depositHeldCoinsTo(ServerPlayer player, UUID target, String targetName) {
        return depositHeldCoinsTo(player, target, targetName, Long.MAX_VALUE);
    }

    /**
     * Fee-free deposit of held coins into the target account, capped at maxAmount.
     * Always credits the owner's balance, never the clicking player's.
     */
    public static boolean depositHeldCoinsTo(ServerPlayer player, UUID target, String targetName, long maxAmount) {
        if (target == null) return false;
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(target);
        if (data.delinquent) {
            riboLockMessage(player, data);
            return false;
        }
        int held = (int) Math.min(maxAmount, countItem(player.getInventory(), ModItems.COIN));
        if (held <= 0) {
            message(player, "預け入れできるギルドコインを持っていません。", ChatFormatting.YELLOW);
            return false;
        }
        removeItem(player.getInventory(), ModItems.COIN, held);
        data.coins = safeAdd(data.coins, held);
        state.setDirty();
        if (target.equals(player.getUUID())) {
            message(player, held + " コインを預け入れました（手数料なし）。残高: " + data.coins, ChatFormatting.GREEN);
        } else {
            message(player, targetName + " の口座に " + held + " コインを預け入れました。", ChatFormatting.GREEN);
        }
        return true;
    }

    private static boolean isExploreTargetReached(ServerPlayer player, QuestDefinition quest, ActiveQuest active) {
        ServerLevel world = player.serverLevel();
        BlockPos pos = player.blockPosition();

        if (quest.exploreMode == ExploreMode.COORDINATE) {
            if (!world.dimension().location().toString().equals(quest.dimension)) return false;
            boolean rolled = active != null && active.randomCoords;
            double tx = rolled ? active.x : quest.x;
            double ty = rolled ? active.y : quest.y;
            double tz = rolled ? active.z : quest.z;
            double dx = player.getX() - tx;
            double dy = player.getY() - ty;
            double dz = player.getZ() - tz;
            return dx * dx + dy * dy + dz * dz <= quest.radius * quest.radius;
        }

        if (quest.exploreMode == ExploreMode.BIOME) {
            String wanted = effectiveTarget(active, quest);
            return world.getBiome(pos).unwrapKey()
                    .map(key -> key.location().toString().equals(wanted))
                    .orElse(false);
        }

        if (quest.exploreMode == ExploreMode.STRUCTURE) {
            ResourceLocation id = safeIdentifier(quest.target);
            if (id == null) return false;
            ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE, id);
            StructureStart start = world.structureManager().getStructureWithPieceAt(pos, key);
            return start != null && start.isValid();
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Daily board (server-wide draw, refreshed once per in-game day)
    // ---------------------------------------------------------------------

    /** Draws today's quest board when the in-game day changed. Safe to call every tick. */
    public static void ensureDailyBoard(MinecraftServer server) {
        GuildPersistentState state = GuildPersistentState.get(server);
        long day = Math.floorDiv(server.overworld().getDayTime(), 24000L);
        if (day == state.dailyBoardDay) return;

        state.dailyBoard.clear();
        state.dailyBoardDay = day;

        List<QuestDefinition> pool = new ArrayList<>(ConfigManager.quests());
        if (!pool.isEmpty()) {
            Collections.shuffle(pool, RANDOM);
            ModConfig config = ConfigManager.config();
            int min = Math.max(1, Math.min(9, config.dailyQuestCountMin));
            int max = Math.max(min, Math.min(9, config.dailyQuestCountMax));
            int count = Math.min(pool.size(), min + RANDOM.nextInt(max - min + 1));
            for (int i = 0; i < count; i++) {
                state.dailyBoard.add(drawDaily(pool.get(i), server));
            }
        }
        state.setDirty();
    }

    /** Rolls today's concrete values for one quest definition. */
    private static DailyQuest drawDaily(QuestDefinition quest, MinecraftServer server) {
        DailyQuest daily = new DailyQuest(quest.id);
        if (quest.type == QuestType.KILL) {
            if (quest.targets != null && quest.targets.length > 0) {
                daily.target = quest.targets[RANDOM.nextInt(quest.targets.length)];
            } else {
                daily.target = quest.target;
            }
            daily.amount = rollAmount(quest);
        } else if (quest.type == QuestType.SUBMIT) {
            daily.target = quest.target;
            daily.amount = rollAmount(quest);
        } else {
            daily.target = quest.target;
            daily.amount = 1;
            if (quest.exploreMode == ExploreMode.BIOME && quest.biomes != null && quest.biomes.length > 0) {
                daily.target = quest.biomes[RANDOM.nextInt(quest.biomes.length)];
            }
            if (quest.exploreMode == ExploreMode.COORDINATE && quest.randomCoordinates) {
                ModConfig config = ConfigManager.config();
                int range = Math.max(1, quest.coordRange > 0 ? quest.coordRange : config.exploreRandomRange);
                daily.hasCoords = true;
                daily.x = quest.x + RANDOM.nextInt(range * 2 + 1) - range;
                daily.y = quest.y;
                daily.z = quest.z + RANDOM.nextInt(range * 2 + 1) - range;
                ServerLevel targetWorld = null;
                try {
                    targetWorld = server.getLevel(
                            ResourceKey.create(Registries.DIMENSION, new ResourceLocation(quest.dimension)));
                } catch (RuntimeException ignored) {
                }
                if (targetWorld != null) {
                    int top = targetWorld.getHeight(Heightmap.Types.MOTION_BLOCKING,
                            (int) Math.floor(daily.x), (int) Math.floor(daily.z));
                    if (top > targetWorld.getMinBuildHeight()) daily.y = top;
                }
            }
        }
        return daily;
    }

    /** Ticks until the next daily draw. */
    public static long ticksUntilNextDraw(MinecraftServer server) {
        return 24000L - Math.floorMod(server.overworld().getDayTime(), 24000L);
    }

    // ---------------------------------------------------------------------
    // Effective values and display helpers
    // ---------------------------------------------------------------------

    /** Count for this acceptance: the rolled amount when set, else the definition amount. */
    public static int effectiveAmount(ActiveQuest active, QuestDefinition quest) {
        if (active != null && active.amount > 0) return active.amount;
        return Math.max(1, quest.amount);
    }

    /** Target for this acceptance: the rolled entity id when set, else the definition default. */
    public static String effectiveTarget(ActiveQuest active, QuestDefinition quest) {
        if (active != null && active.target != null && !active.target.isBlank()) return active.target;
        if (quest.target != null && !quest.target.isBlank()) return quest.target;
        if (quest.targets != null && quest.targets.length > 0) return quest.targets[0];
        return "";
    }

    /** Human readable item name for an item id, falling back to the raw id. */
    public static String itemName(String id) {
        ResourceLocation rid = safeIdentifier(id);
        if (rid != null && BuiltInRegistries.ITEM.containsKey(rid)) {
            Item item = BuiltInRegistries.ITEM.get(rid);
            return item.getName(new ItemStack(item)).getString();
        }
        return id == null || id.isBlank() ? "?" : id;
    }

    /** Human readable biome name for a biome id, falling back to the raw id. */
    public static String biomeName(String id) {
        ResourceLocation rid = safeIdentifier(id);
        if (rid != null) {
            return Component.translatable("biome." + rid.getNamespace() + "." + rid.getPath()).getString();
        }
        return id == null || id.isBlank() ? "?" : id;
    }

    /** Human readable structure name for a structure id (namespace stripped). */
    public static String structureName(String id) {
        ResourceLocation rid = safeIdentifier(id);
        if (rid != null && "minecraft".equals(rid.getNamespace())) {
            return rid.getPath().replace('_', ' ');
        }
        return id == null || id.isBlank() ? "?" : id;
    }

    /** Dimension display name: "" for the overworld, else e.g. "ネザー". */
    public static String dimensionLabel(String dimension) {
        if (dimension == null || dimension.isBlank() || "minecraft:overworld".equals(dimension)) return "";
        if ("minecraft:the_nether".equals(dimension)) return "ネザー";
        if ("minecraft:the_end".equals(dimension)) return "エンド";
        return dimension;
    }

    /** Human readable in-game time, e.g. "2日" or "1日23時間". */
    public static String formatGameTime(long ticks) {
        if (ticks <= 0) return "1分未満";
        long days = ticks / 24000;
        long hours = (ticks % 24000) / 1000;
        long minutes = (ticks % 1000) * 60 / 1000;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("日");
        if (hours > 0) sb.append(hours).append("時間");
        if (days == 0 && minutes > 0) sb.append(minutes).append("分");
        return sb.length() == 0 ? "1分未満" : sb.toString();
    }

    public static String formatCoords(ActiveQuest active) {
        return "(" + (int) active.x + ", " + (int) active.y + ", " + (int) active.z + ")";
    }

    /** Human readable entity name for an entity id, falling back to the raw id. */
    public static String entityName(String id) {
        ResourceLocation rid = safeIdentifier(id);
        if (rid != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rid)) {
            return Component.translatable("entity." + rid.getNamespace() + "." + rid.getPath()).getString();
        }
        return id == null || id.isBlank() ? "?" : id;
    }

    private static int rollAmount(QuestDefinition quest) {
        if (quest.amountMin > 0 && quest.amountMax > 0) {
            int min = Math.min(quest.amountMin, quest.amountMax);
            int max = Math.max(quest.amountMin, quest.amountMax);
            return min + RANDOM.nextInt(max - min + 1);
        }
        return Math.max(1, quest.amount);
    }

    private static int countItem(Container inventory, Item target) {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(target)) count += stack.getCount();
        }
        return count;
    }

    private static void removeItem(Container inventory, Item target, int amount) {
        int remaining = amount;
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.is(target)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        inventory.setChanged();
    }

    private static void giveCoins(Container inventory, ItemStack coinStack) {
        long left = coinStack.getCount();
        int max = coinStack.getMaxStackSize();
        for (int i = 0; i < 36 && left > 0; i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty()) {
                int give = (int) Math.min(max, left);
                ItemStack add = coinStack.copy();
                add.setCount(give);
                inventory.setItem(i, add);
                left -= give;
            } else if (ItemStack.isSameItemSameTags(existing, coinStack)) {
                int space = Math.max(0, existing.getMaxStackSize() - existing.getCount());
                int give = (int) Math.min(space, left);
                existing.grow(give);
                left -= give;
            }
        }
        inventory.setChanged();
    }

    private static boolean canFit(Container inventory, ItemStack incoming) {
        int remaining = incoming.getCount();
        for (int i = 0; i < 36 && remaining > 0; i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty()) {
                remaining -= incoming.getMaxStackSize();
            } else if (ItemStack.isSameItemSameTags(existing, incoming)) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
        }
        return remaining <= 0;
    }

    private static ResourceLocation safeIdentifier(String raw) {
        try {
            return raw == null ? null : new ResourceLocation(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long safeAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }

    public static void message(ServerPlayer player, String text, ChatFormatting formatting) {
        player.sendSystemMessage(Component.literal("[Guild] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(formatting)), false);
    }
}

package dev.guildquests.service;

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
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.structure.Structure;

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

    public static PlayerGuildData data(ServerPlayerEntity player) {
        return GuildPersistentState.get(player.getServer()).get(player.getUuid());
    }

    public static boolean acceptQuest(ServerPlayerEntity player, QuestDefinition quest) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());
        ensureDailyBoard(player.getServer());

        if (data.findActive(quest.id) != null) {
            message(player, "そのクエストは既に受注中です。", Formatting.YELLOW);
            return false;
        }
        if (!quest.repeatable && data.completedQuestIds.contains(quest.id)) {
            message(player, "そのクエストは既に達成済みです。", Formatting.YELLOW);
            return false;
        }
        if (data.completedTotal < quest.requiredCompleted) {
            message(player, "このクエストはまだ解放されていません。", Formatting.RED);
            return false;
        }
        if (data.activeQuests.size() >= ConfigManager.config().maxActiveQuests) {
            message(player, "同時受注数の上限に達しています。", Formatting.RED);
            return false;
        }

        DailyQuest daily = state.findDaily(quest.id);
        if (daily == null) {
            message(player, "本日のクエストではありません（次回の抽選をお待ちください）。", Formatting.YELLOW);
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
        state.markDirty();

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
        message(player, "クエスト「" + quest.title + "」を受注しました" + detail + "。", Formatting.GREEN);
        return true;
    }

    /** Resolves a quest by id: config definitions first, then player-published quests. */
    public static QuestDefinition resolveQuest(MinecraftServer server, String questId) {
        QuestDefinition def = ConfigManager.quest(questId);
        if (def != null) return def;
        PublishedQuest published = GuildPersistentState.get(server).findByQuestId(questId);
        return published == null ? null : published.toDefinition();
    }

    private static boolean isPublishedId(String questId) {
        return questId != null && questId.startsWith("pub:");
    }

    // ---------------------------------------------------------------------
    // Player-published quests (bounty board)
    // ---------------------------------------------------------------------

    /** Trims and length-caps a client-supplied free-text string (blank -> ""). */
    private static String clampLength(String value, int maxChars) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.length() <= maxChars) return trimmed;
        return trimmed.substring(0, maxChars);
    }

    /** Escrows the reward and publishes a quest on the shared board. */
    public static boolean publishQuest(ServerPlayerEntity player, QuestType type, String target,
                                       int amount, long reward, String title, String description) {
        if (!ConfigManager.config().playerQuestsEnabled) {
            message(player, "公開クエスト機能は無効になっています。", Formatting.RED);
            return false;
        }
        if (target == null || target.isBlank()) {
            message(player, "対象が指定されていません。", Formatting.YELLOW);
            return false;
        }
        ModConfig config = ConfigManager.config();
        reward = Math.max(config.publishedQuestRewardMin, Math.min(config.publishedQuestRewardMax, reward));
        amount = Math.max(1, amount);

        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());

        if (data.delinquent) {
            riboLockMessage(player, data);
            return false;
        }
        int own = 0;
        for (PublishedQuest q : state.publishedQuests) {
            if (player.getUuid().equals(q.publisherId)) own++;
        }
        if (own >= config.publishedQuestMaxPerPlayer) {
            message(player, "公開中のクエストが上限です（" + config.publishedQuestMaxPerPlayer + " 件）。", Formatting.RED);
            return false;
        }
        if (data.coins < reward) {
            message(player, "報酬のエスクローに必要な残高が不足しています（必要: " + reward + " / 残高: " + data.coins + "）。", Formatting.RED);
            return false;
        }

        PublishedQuest quest = new PublishedQuest(Long.toHexString(RANDOM.nextLong()) + Long.toHexString(System.nanoTime()));
        quest.publisherId = player.getUuid();
        quest.publisherName = player.getName().getString();
        quest.type = type;
        quest.target = target;
        quest.amount = amount;
        quest.reward = reward;
        quest.createdAtDay = Math.floorDiv(player.getServer().getOverworld().getTimeOfDay(), 24000L);
        quest.title = clampLength(title, 100);
        quest.description = clampLength(description, 300);

        data.coins -= reward;
        state.publishedQuests.add(quest);
        state.markDirty();

        message(player, "クエスト「" + quest.toDefinition().title + "」を公開しました（報酬 " + reward
                + " コインを預託、残高 " + data.coins + "）。", Formatting.GREEN);
        broadcast(player.getServer(), player.getName().getString() + " が新しい依頼クエストを公開しました: "
                + quest.toDefinition().title, Formatting.AQUA);
        return true;
    }

    /** Publisher cancels an untaken quest and gets the escrow back. */
    public static boolean cancelPublishedQuest(ServerPlayerEntity player, String id) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PublishedQuest quest = state.findPublished(id);
        if (quest == null) {
            message(player, "その公開クエストは存在しません。", Formatting.YELLOW);
            return false;
        }
        if (!player.getUuid().equals(quest.publisherId)) {
            message(player, "自分の公開クエストのみ取り消せます。", Formatting.RED);
            return false;
        }
        if (quest.isTaken()) {
            message(player, "すでに受注されているため取り消せません（受注者が破棄すれば戻ります）。", Formatting.YELLOW);
            return false;
        }
        refundEscrow(player.getServer(), quest);
        state.publishedQuests.remove(quest);
        state.markDirty();
        message(player, "公開クエストを取り消し、報酬 " + quest.reward + " コインを返金しました。", Formatting.GRAY);
        return true;
    }

    /** First taker accepts a published quest; progress is tracked like a normal quest. */
    public static boolean acceptPublishedQuest(ServerPlayerEntity player, String id) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());
        PublishedQuest quest = state.findPublished(id);
        if (quest == null) {
            message(player, "その公開クエストは存在しません。", Formatting.YELLOW);
            return false;
        }
        if (quest.isTaken()) {
            message(player, "すでに他のプレイヤーが受注しています。", Formatting.YELLOW);
            return false;
        }
        if (player.getUuid().equals(quest.publisherId)) {
            message(player, "自分の公開クエストは受注できません。", Formatting.YELLOW);
            return false;
        }
        if (data.findActive(quest.questId()) != null) {
            message(player, "そのクエストは既に受注中です。", Formatting.YELLOW);
            return false;
        }
        if (data.activeQuests.size() >= ConfigManager.config().maxActiveQuests) {
            message(player, "同時受注数の上限に達しています。", Formatting.RED);
            return false;
        }

        quest.takerId = player.getUuid();
        quest.takerName = player.getName().getString();
        ActiveQuest active = new ActiveQuest(quest.questId(), 0);
        active.target = quest.target;
        active.amount = quest.amount;
        data.activeQuests.add(active);
        state.markDirty();

        message(player, "依頼クエスト「" + quest.toDefinition().title + "」を受注しました（依頼者: " + quest.publisherName + "）。", Formatting.GREEN);
        ServerPlayerEntity publisher = player.getServer().getPlayerManager().getPlayer(quest.publisherId);
        if (publisher != null) {
            message(publisher, "あなたの公開クエスト「" + quest.toDefinition().title + "」を "
                    + player.getName().getString() + " が受注しました。", Formatting.AQUA);
        }
        return true;
    }

    /** Taker abandons a published quest: it returns to the open board with progress reset. */
    private static boolean releasePublishedQuest(ServerPlayerEntity player, String questId) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PublishedQuest quest = state.findByQuestId(questId);
        if (quest == null) return false;
        quest.takerId = null;
        quest.takerName = "";
        state.markDirty();
        message(player, "依頼を破棄しました。クエストは掲示板に戻ります。", Formatting.GRAY);
        ServerPlayerEntity publisher = player.getServer().getPlayerManager().getPlayer(quest.publisherId);
        if (publisher != null) {
            message(publisher, "公開クエスト「" + quest.toDefinition().title + "」の受注者が破棄しました。再び受注可能になります。", Formatting.YELLOW);
        }
        return true;
    }

    private static void refundEscrow(MinecraftServer server, PublishedQuest quest) {
        if (quest.publisherId == null) return;
        creditCoins(server, quest.publisherId, quest.reward);
        ServerPlayerEntity publisher = server.getPlayerManager().getPlayer(quest.publisherId);
        if (publisher != null) {
            message(publisher, "公開クエストの預託分 " + quest.reward + " コインが返金されました。", Formatting.AQUA);
        }
    }

    /** Expires untaken published quests on day rollover and refunds the escrow. */
    public static void expirePublishedQuests(MinecraftServer server) {
        int expireDays = ConfigManager.config().publishedQuestExpireDays;
        if (expireDays <= 0) return;
        GuildPersistentState state = GuildPersistentState.get(server);
        long day = Math.floorDiv(server.getOverworld().getTimeOfDay(), 24000L);
        boolean changed = false;
        for (int i = state.publishedQuests.size() - 1; i >= 0; i--) {
            PublishedQuest quest = state.publishedQuests.get(i);
            if (quest.isTaken()) continue;
            if (day - quest.createdAtDay < expireDays) continue;
            refundEscrow(server, quest);
            state.publishedQuests.remove(i);
            changed = true;
        }
        if (changed) state.markDirty();
    }

    public static void broadcast(MinecraftServer server, String text, Formatting formatting) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.sendMessage(Text.literal("[Guild] ").formatted(Formatting.GOLD)
                    .append(Text.literal(text).formatted(formatting)), false);
        }
    }

    public static boolean cancelQuest(ServerPlayerEntity player, String questId) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());
        ActiveQuest active = data.findActive(questId);
        if (active == null) return false;
        if (isPublishedId(questId) && releasePublishedQuest(player, questId)) {
            data.activeQuests.remove(active);
            state.markDirty();
            return true;
        }
        data.activeQuests.remove(active);
        state.markDirty();
        QuestDefinition quest = resolveQuest(player.getServer(), questId);
        message(player, "クエスト「" + (quest == null ? questId : quest.title) + "」を破棄しました。", Formatting.GRAY);
        return true;
    }

    public static void onKill(ServerPlayerEntity player, LivingEntity killed) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());
        boolean changed = false;
        String killedId = Registries.ENTITY_TYPE.getId(killed.getType()).toString();

        for (ActiveQuest active : data.activeQuests) {
            QuestDefinition quest = resolveQuest(player.getServer(), active.questId);
            if (quest == null || quest.type != QuestType.KILL) continue;
            int amount = effectiveAmount(active, quest);
            if (active.progress >= amount) continue;
            if (!effectiveTarget(active, quest).equals(killedId)) continue;

            active.progress = Math.min(amount, active.progress + 1);
            changed = true;
            if (active.progress >= amount) {
                message(player, "クエスト「" + quest.title + "」の条件を達成しました。ギルド画面で報酬を受け取れます。", Formatting.GOLD);
            }
        }
        if (changed) state.markDirty();
    }

    public static void checkExplore(ServerPlayerEntity player) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());
        boolean changed = false;

        for (ActiveQuest active : data.activeQuests) {
            QuestDefinition quest = resolveQuest(player.getServer(), active.questId);
            if (quest == null || quest.type != QuestType.EXPLORE) continue;
            int amount = effectiveAmount(active, quest);
            if (active.progress >= amount) continue;
            if (!isExploreTargetReached(player, quest, active)) continue;

            active.progress = amount;
            changed = true;
            message(player, "探索クエスト「" + quest.title + "」の条件を達成しました。ギルド画面で報酬を受け取れます。", Formatting.GOLD);
        }
        if (changed) state.markDirty();
    }

    public static boolean submitOrClaim(ServerPlayerEntity player, String questId) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());
        ActiveQuest active = data.findActive(questId);
        QuestDefinition quest = resolveQuest(player.getServer(), questId);
        if (active == null || quest == null) return false;

        int amount = effectiveAmount(active, quest);
        if (quest.type == QuestType.SUBMIT && active.progress < amount) {
            Identifier itemId = safeIdentifier(quest.target);
            if (itemId == null || !Registries.ITEM.containsId(itemId)) {
                message(player, "納品対象アイテムの設定が不正です: " + quest.target, Formatting.RED);
                return false;
            }
            Item target = Registries.ITEM.get(itemId);
            int remaining = amount - active.progress;
            int available = countItem(player.getInventory(), target);
            int consume = Math.min(remaining, available);
            if (consume <= 0) {
                message(player, "納品対象を所持していません。残り " + remaining + " 個です。", Formatting.YELLOW);
                return false;
            }
            removeItem(player.getInventory(), target, consume);
            active.progress += consume;
            state.markDirty();
            message(player, consume + " 個納品しました。進捗: " + active.progress + "/" + amount, Formatting.AQUA);
        }

        if (active.progress >= amount) {
            return claimQuest(player, questId);
        }
        return true;
    }

    public static boolean claimQuest(ServerPlayerEntity player, String questId) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());
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
                message(player, "リボ債務を完済しました。ギルド機能のロックを解除しました。", Formatting.GREEN);
            }
        }

        data.coins = safeAdd(data.coins, reward);
        data.completedTotal++;
        data.completedQuestIds.add(quest.id);
        data.activeQuests.remove(active);

        // Published quests: the escrowed reward is paid out and the quest leaves the board.
        if (isPublishedId(questId)) {
            PublishedQuest published = state.findByQuestId(questId);
            if (published != null) {
                state.publishedQuests.remove(published);
                ServerPlayerEntity publisher = player.getServer().getPlayerManager().getPlayer(published.publisherId);
                if (publisher != null) {
                    message(publisher, "あなたの公開クエスト「" + published.toDefinition().title + "」が "
                            + player.getName().getString() + " に達成されました。", Formatting.AQUA);
                }
            }
        }
        state.markDirty();

        if (tribute > 0) {
            message(player, "クエスト「" + quest.title + "」達成！報酬 " + tribute
                    + " コインはリボ返済に充当" + (reward > 0 ? "、残り " + reward + " コインは受け取り" : "")
                    + "（残債務 " + data.debt + "）", Formatting.GOLD);
        } else {
            message(player, "クエスト「" + quest.title + "」達成！ +" + reward + " コイン", Formatting.GOLD);
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

    public static long coins(ServerPlayerEntity player) {
        return data(player).coins;
    }

    public static boolean payCoins(ServerPlayerEntity player, long amount) {
        if (amount <= 0) return false;
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());
        if (data.coins < amount) return false;
        data.coins -= amount;
        state.markDirty();
        return true;
    }

    public static void creditCoins(MinecraftServer server, UUID uuid, long amount) {
        if (amount <= 0 || uuid == null) return;
        GuildPersistentState state = GuildPersistentState.get(server);
        PlayerGuildData data = state.get(uuid);
        data.coins = safeAdd(data.coins, amount);
        state.markDirty();
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

    public static void setPaymentMode(ServerPlayerEntity player, String mode) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());
        data.paymentMode = mode;
        state.markDirty();
        switch (mode) {
            case MODE_RIBO -> message(player, "支払い方法を「リボ」に変更しました。残高がなくても購入でき、価格は債務として定期的に自動徴収されます。", Formatting.AQUA);
            case MODE_DISABLED -> message(player, "支払い方法を「無効」に変更しました。残高からは支払えなくなりました。", Formatting.YELLOW);
            default -> message(player, "支払い方法を「引き落とし」に変更しました。購入時に残高から即時引き落としします。", Formatting.GREEN);
        }
    }

    public static void addDebt(ServerPlayerEntity player, long amount) {
        if (amount <= 0) return;
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());
        data.debt = safeAdd(data.debt, amount);
        if (data.debt > 0 && data.nextRiboDueAt <= 0) {
            data.nextRiboDueAt = System.currentTimeMillis() + riboIntervalMillis();
        }
        state.markDirty();
    }

    /** Player-to-player transfer of balance coins. Free of charge (no bank fee). */
    public static boolean transferCoins(ServerPlayerEntity sender, ServerPlayerEntity receiver, long amount) {
        if (receiver == null || amount <= 0) return false;
        if (sender == receiver) {
            message(sender, "自分自身には送金できません。", Formatting.YELLOW);
            return false;
        }
        GuildPersistentState state = GuildPersistentState.get(sender.getServer());
        PlayerGuildData from = state.get(sender.getUuid());
        PlayerGuildData to = state.get(receiver.getUuid());
        if (from.delinquent) {
            riboLockMessage(sender, from);
            return false;
        }
        if (from.coins < amount) {
            message(sender, "残高が不足しています。残高: " + from.coins + " / 必要: " + amount, Formatting.RED);
            return false;
        }
        from.coins -= amount;
        to.coins = safeAdd(to.coins, amount);
        state.markDirty();

        String senderName = sender.getName().getString();
        String receiverName = receiver.getName().getString();
        message(sender, receiverName + " へ " + amount + " コインを送金しました（手数料無料）。残高: " + from.coins, Formatting.GREEN);
        if (!receiver.isRemoved()) {
            message(receiver, senderName + " から " + amount + " コインを受け取りました。残高: " + to.coins, Formatting.AQUA);
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
                    state.markDirty();
                }
                continue;
            }
            if (data.nextRiboDueAt <= 0) {
                data.nextRiboDueAt = now + interval;
                state.markDirty();
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
            state.markDirty();

            ServerPlayerEntity online = server.getPlayerManager().getPlayer(entry.getKey());
            if (online != null && newlyDelinquent) {
                message(online, "リボの定期返済が残高不足で失敗しました（債務 " + data.debt + "）。"
                        + "完済するまでギルド機能がロックされています。クエスト報酬は自動で返済に充てられます。", Formatting.RED);
            }
            if (online != null && unlocked) {
                message(online, "リボ債務を完済しました。ギルド機能のロックを解除しました。", Formatting.GREEN);
            }
        }
    }

    public static void riboLockMessage(ServerPlayerEntity player, PlayerGuildData data) {
        message(player, "リボ払いの延滞中です。債務 " + data.debt
                + " を完済するまでギルド機能がロックされています（クエスト報酬で返済できます）。", Formatting.RED);
    }

    public static long countHeldCoins(ServerPlayerEntity player) {
        return countItem(player.getInventory(), ModItems.COIN);
    }

    /** Transaction fee in coins for the given amount. Waived when opened through the bank block. */
    public static long bankFee(long amount, boolean feeWaived) {
        if (feeWaived || amount <= 0) return 0;
        double pct = ConfigManager.config().bankFeePercent;
        if (pct <= 0) return 0;
        return Math.round(amount * pct / 100.0);
    }

    public static boolean bankWithdraw(ServerPlayerEntity player, long amount, boolean feeWaived) {
        if (amount <= 0 || amount > Integer.MAX_VALUE) return false;
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());
        if (data.delinquent) {
            riboLockMessage(player, data);
            return false;
        }
        if (data.coins < amount) {
            message(player, "コイン残高が不足しています。残高: " + data.coins + " / 必要: " + amount, Formatting.RED);
            return false;
        }
        long fee = bankFee(amount, feeWaived);
        long receive = amount - fee;
        if (receive <= 0) {
            message(player, "手数料が高すぎて引き出せません。", Formatting.RED);
            return false;
        }
        ItemStack coinStack = new ItemStack(ModItems.COIN, (int) receive);
        if (!canFit(player.getInventory(), coinStack)) {
            message(player, "インベントリに空きがありません。", Formatting.RED);
            return false;
        }
        data.coins -= amount;
        giveCoins(player.getInventory(), coinStack);
        state.markDirty();
        message(player, receive + " コインを引き出しました"
                + (fee > 0 ? "（手数料 " + fee + "）" : "") + "。残高: " + data.coins, Formatting.GREEN);
        return true;
    }

    public static boolean bankDeposit(ServerPlayerEntity player, long amount, boolean feeWaived) {
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(player.getUuid());
        if (data.delinquent) {
            riboLockMessage(player, data);
            return false;
        }
        long held = countItem(player.getInventory(), ModItems.COIN);
        long take = amount <= 0 ? held : Math.min(amount, held);
        if (take <= 0) {
            message(player, "預け入れできるギルドコインを持っていません。", Formatting.YELLOW);
            return false;
        }
        long fee = bankFee(take, feeWaived);
        long credit = take - fee;
        if (credit <= 0) {
            message(player, "手数料が高すぎて預け入れできません。", Formatting.RED);
            return false;
        }
        removeItem(player.getInventory(), ModItems.COIN, (int) take);
        data.coins = safeAdd(data.coins, credit);
        state.markDirty();
        message(player, take + " コインを預け入れました"
                + (fee > 0 ? "（手数料 " + fee + "）" : "") + "。残高: " + data.coins, Formatting.GREEN);
        return true;
    }

    /**
     * Fee-free deposit of every held coin into the target account (deposit block right-click).
     * Always credits the owner's balance, never the clicking player's.
     */
    public static boolean depositHeldCoinsTo(ServerPlayerEntity player, UUID target, String targetName) {
        if (target == null) return false;
        GuildPersistentState state = GuildPersistentState.get(player.getServer());
        PlayerGuildData data = state.get(target);
        if (data.delinquent) {
            riboLockMessage(player, data);
            return false;
        }
        int held = countItem(player.getInventory(), ModItems.COIN);
        if (held <= 0) {
            message(player, "預け入れできるギルドコインを持っていません。", Formatting.YELLOW);
            return false;
        }
        removeItem(player.getInventory(), ModItems.COIN, held);
        data.coins = safeAdd(data.coins, held);
        state.markDirty();
        if (target.equals(player.getUuid())) {
            message(player, held + " コインを預け入れました（手数料なし）。残高: " + data.coins, Formatting.GREEN);
        } else {
            message(player, targetName + " の口座に " + held + " コインを預け入れました。", Formatting.GREEN);
        }
        return true;
    }

    private static boolean isExploreTargetReached(ServerPlayerEntity player, QuestDefinition quest, ActiveQuest active) {
        ServerWorld world = player.getServerWorld();
        BlockPos pos = player.getBlockPos();

        if (quest.exploreMode == ExploreMode.COORDINATE) {
            if (!world.getRegistryKey().getValue().toString().equals(quest.dimension)) return false;
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
            return world.getBiome(pos).getKey()
                    .map(key -> key.getValue().toString().equals(wanted))
                    .orElse(false);
        }

        if (quest.exploreMode == ExploreMode.STRUCTURE) {
            Identifier id = safeIdentifier(quest.target);
            if (id == null) return false;
            RegistryKey<Structure> key = RegistryKey.of(RegistryKeys.STRUCTURE, id);
            StructureStart start = world.getStructureAccessor().getStructureContaining(pos, key);
            return start != null && start.hasChildren();
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Daily board (server-wide draw, refreshed once per in-game day)
    // ---------------------------------------------------------------------

    /** Draws today's quest board when the in-game day changed. Safe to call every tick. */
    public static void ensureDailyBoard(MinecraftServer server) {
        GuildPersistentState state = GuildPersistentState.get(server);
        long day = Math.floorDiv(server.getOverworld().getTimeOfDay(), 24000L);
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
        expirePublishedQuests(server);
        state.markDirty();
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
                ServerWorld targetWorld = null;
                try {
                    targetWorld = server.getWorld(
                            RegistryKey.of(RegistryKeys.WORLD, new Identifier(quest.dimension)));
                } catch (RuntimeException ignored) {
                }
                if (targetWorld != null) {
                    int top = targetWorld.getTopY(Heightmap.Type.MOTION_BLOCKING,
                            (int) Math.floor(daily.x), (int) Math.floor(daily.z));
                    if (top > targetWorld.getBottomY()) daily.y = top;
                }
            }
        }
        return daily;
    }

    /** Ticks until the next daily draw. */
    public static long ticksUntilNextDraw(MinecraftServer server) {
        return 24000L - Math.floorMod(server.getOverworld().getTimeOfDay(), 24000L);
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
        Identifier rid = safeIdentifier(id);
        if (rid != null && Registries.ITEM.containsId(rid)) {
            Item item = Registries.ITEM.get(rid);
            return item.getName(new ItemStack(item)).getString();
        }
        return id == null || id.isBlank() ? "?" : id;
    }

    /** Human readable biome name for a biome id, falling back to the raw id. */
    public static String biomeName(String id) {
        Identifier rid = safeIdentifier(id);
        if (rid != null) {
            return Text.translatable("biome." + rid.getNamespace() + "." + rid.getPath()).getString();
        }
        return id == null || id.isBlank() ? "?" : id;
    }

    /** Human readable structure name for a structure id (namespace stripped). */
    public static String structureName(String id) {
        Identifier rid = safeIdentifier(id);
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
        Identifier rid = safeIdentifier(id);
        if (rid != null && Registries.ENTITY_TYPE.containsId(rid)) {
            return Text.translatable("entity." + rid.getNamespace() + "." + rid.getPath()).getString();
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

    private static int countItem(PlayerInventory inventory, Item target) {
        int count = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isOf(target)) count += stack.getCount();
        }
        return count;
    }

    private static void removeItem(PlayerInventory inventory, Item target, int amount) {
        int remaining = amount;
        for (int i = 0; i < inventory.size() && remaining > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isOf(target)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.decrement(take);
            remaining -= take;
        }
        inventory.markDirty();
    }

    private static void giveCoins(PlayerInventory inventory, ItemStack coinStack) {
        long left = coinStack.getCount();
        int max = coinStack.getMaxCount();
        for (int i = 0; i < 36 && left > 0; i++) {
            ItemStack existing = inventory.getStack(i);
            if (existing.isEmpty()) {
                int give = (int) Math.min(max, left);
                ItemStack add = coinStack.copy();
                add.setCount(give);
                inventory.setStack(i, add);
                left -= give;
            } else if (ItemStack.canCombine(existing, coinStack)) {
                int space = Math.max(0, existing.getMaxCount() - existing.getCount());
                int give = (int) Math.min(space, left);
                existing.increment(give);
                left -= give;
            }
        }
        inventory.markDirty();
    }

    private static boolean canFit(PlayerInventory inventory, ItemStack incoming) {
        int remaining = incoming.getCount();
        for (int i = 0; i < 36 && remaining > 0; i++) {
            ItemStack existing = inventory.getStack(i);
            if (existing.isEmpty()) {
                remaining -= incoming.getMaxCount();
            } else if (ItemStack.canCombine(existing, incoming)) {
                remaining -= Math.max(0, existing.getMaxCount() - existing.getCount());
            }
        }
        return remaining <= 0;
    }

    private static Identifier safeIdentifier(String raw) {
        try {
            return raw == null ? null : new Identifier(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long safeAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }

    public static void message(ServerPlayerEntity player, String text, Formatting formatting) {
        player.sendMessage(Text.literal("[Guild] ").formatted(Formatting.GOLD)
                .append(Text.literal(text).formatted(formatting)), false);
    }
}

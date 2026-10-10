package dev.guildquests.net;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.ActiveQuest;
import dev.guildquests.data.DailyQuest;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.model.QuestDefinition;
import dev.guildquests.model.QuestType;
import dev.guildquests.net.GuiState.Candidate;
import dev.guildquests.net.GuiState.QuestRow;
import dev.guildquests.service.GuildService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds {@link GuiState} payloads for each view on the server thread. */
public final class GuiStateFactory {
    private GuiStateFactory() {
    }

    public static GuiState guild(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        GuildService.ensureDailyBoard(server);
        PlayerGuildData data = GuildService.data(player);
        GuildPersistentState state = GuildPersistentState.get(server);

        GuiState s = new GuiState();
        s.view = "guild";
        s.balance = data.coins;
        s.rank = GuildService.rank(data);
        s.completedTotal = data.completedTotal;
        s.maxActive = ConfigManager.config().maxActiveQuests;
        s.dailyCount = state.dailyBoard.size();
        s.nextDraw = GuildService.formatGameTime(GuildService.ticksUntilNextDraw(server));

        for (DailyQuest daily : state.dailyBoard) {
            QuestDefinition quest = ConfigManager.quest(daily.questId);
            if (quest == null) continue;
            QuestRow row = new QuestRow();
            row.questId = quest.id;
            row.title = quest.title;
            row.description = quest.description == null ? "" : quest.description;
            row.reward = quest.reward;

            ActiveQuest active = data.findActive(quest.id);
            boolean locked = data.completedTotal < quest.requiredCompleted;
            boolean doneOnce = !quest.repeatable && data.completedQuestIds.contains(quest.id);
            if (locked) {
                row.status = "LOCKED";
                row.lockReason = "解放条件: 累計 " + quest.requiredCompleted + " 件達成";
                row.objective = dailyObjectiveLine(daily, quest);
            } else if (doneOnce) {
                row.status = "DONE_ONCE";
            } else if (active != null) {
                int amount = GuildService.effectiveAmount(active, quest);
                row.status = "ACTIVE";
                row.objective = activeObjectiveLine(quest, active);
                row.progress = active.progress;
                row.amount = amount;
                row.ready = active.progress >= amount;
                row.canSubmit = quest.type == QuestType.SUBMIT && !row.ready;
            } else {
                row.status = "AVAILABLE";
                row.objective = dailyObjectiveLine(daily, quest);
            }
            s.daily.add(row);
        }

        for (ActiveQuest active : data.activeQuests) {
            QuestDefinition quest = GuildService.resolveQuest(server, active.questId);
            QuestRow row = new QuestRow();
            row.questId = active.questId;
            row.progress = active.progress;
            if (quest == null) {
                row.status = "BROKEN";
                row.title = "不明なクエスト: " + active.questId;
                row.description = "設定から削除されたクエストです。";
                s.active.add(row);
                continue;
            }
            int amount = GuildService.effectiveAmount(active, quest);
            row.title = quest.title;
            row.description = quest.description == null ? "" : quest.description;
            row.reward = quest.reward;
            row.amount = amount;
            row.ready = active.progress >= amount;
            row.status = "ACTIVE";
            row.objective = activeObjectiveLine(quest, active);
            row.canSubmit = quest.type == QuestType.SUBMIT && !row.ready;
            s.active.add(row);
        }
        return s;
    }

    public static GuiState bank(ServerPlayerEntity player, boolean feeWaived) {
        PlayerGuildData data = GuildService.data(player);

        GuiState s = new GuiState();
        s.view = "bank";
        s.balance = data.coins;
        s.held = GuildService.countHeldCoins(player);
        s.feeWaived = feeWaived;
        s.feeRate = ConfigManager.config().bankFeePercent;
        s.paymentMode = GuildService.paymentMode(data);
        s.debt = data.debt;
        s.delinquent = data.delinquent;
        s.nextRiboMinutes = data.debt > 0
                ? Math.max(0, (data.nextRiboDueAt - System.currentTimeMillis()) / 60_000L) : 0;
        s.riboInstallment = ConfigManager.config().riboInstallment;
        s.riboInterestPercent = ConfigManager.config().riboInterestPercent;
        s.riboIntervalMinutes = ConfigManager.config().riboIntervalMinutes;
        return s;
    }

    public static GuiState transfer(ServerPlayerEntity player) {
        PlayerGuildData data = GuildService.data(player);

        GuiState s = new GuiState();
        s.view = "transfer";
        s.balance = data.coins;
        s.held = GuildService.countHeldCoins(player);

        List<ServerPlayerEntity> online = new ArrayList<>();
        for (ServerPlayerEntity other : player.getServer().getPlayerManager().getPlayerList()) {
            if (other != player && !other.isRemoved()) online.add(other);
        }
        online.sort(Comparator.comparing(p -> p.getName().getString()));
        for (ServerPlayerEntity other : online) {
            Candidate candidate = new Candidate();
            candidate.uuid = other.getUuid().toString();
            candidate.name = other.getName().getString();
            candidate.balance = GuildService.data(other).coins;
            s.candidates.add(candidate);
        }
        return s;
    }

    /** Concrete objective line for today's rolled values (no ranges, no "random"). */
    static String dailyObjectiveLine(DailyQuest daily, QuestDefinition quest) {
        String dim = GuildService.dimensionLabel(quest.dimension);
        String prefix = dim.isEmpty() ? "" : dim + " ";
        String suffix = dim.isEmpty() ? "" : " (" + dim + ")";
        String biomeTarget = daily.target == null || daily.target.isBlank() ? quest.target : daily.target;
        return switch (quest.type) {
            case KILL -> "討伐: " + GuildService.entityName(daily.target) + " × " + daily.amount;
            case SUBMIT -> "納品: " + GuildService.itemName(daily.target) + " × " + daily.amount;
            case EXPLORE -> switch (quest.exploreMode) {
                case COORDINATE -> daily.hasCoords
                        ? "目的地: " + prefix + "(" + (int) daily.x + ", " + (int) daily.y + ", " + (int) daily.z
                                + ") 半径" + (int) quest.radius
                        : "探索: " + prefix + "(" + (int) quest.x + ", " + (int) quest.y + ", " + (int) quest.z
                                + ") 半径" + (int) quest.radius;
                case BIOME -> "バイオーム探索: " + GuildService.biomeName(biomeTarget) + suffix;
                case STRUCTURE -> "構造物探索: " + GuildService.structureName(quest.target) + suffix;
            };
        };
    }

    static String activeObjectiveLine(QuestDefinition quest, ActiveQuest active) {
        String dim = GuildService.dimensionLabel(quest.dimension);
        String prefix = dim.isEmpty() ? "" : dim + " ";
        String suffix = dim.isEmpty() ? "" : " (" + dim + ")";
        return switch (quest.type) {
            case KILL -> "対象: " + GuildService.entityName(GuildService.effectiveTarget(active, quest))
                    + " × " + GuildService.effectiveAmount(active, quest);
            case SUBMIT -> "納品: " + GuildService.itemName(quest.target)
                    + " × " + GuildService.effectiveAmount(active, quest);
            case EXPLORE -> switch (quest.exploreMode) {
                case COORDINATE -> active.randomCoords
                        ? "目的地: " + prefix + GuildService.formatCoords(active) + " 半径" + (int) quest.radius
                        : "探索: " + prefix + "(" + (int) quest.x + ", " + (int) quest.y + ", " + (int) quest.z
                                + ") 半径" + (int) quest.radius;
                case BIOME -> "バイオーム探索: "
                        + GuildService.biomeName(GuildService.effectiveTarget(active, quest)) + suffix;
                case STRUCTURE -> "構造物探索: " + GuildService.structureName(quest.target) + suffix;
            };
        };
    }
}

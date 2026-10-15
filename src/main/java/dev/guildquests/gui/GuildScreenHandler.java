package dev.guildquests.gui;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.ActiveQuest;
import dev.guildquests.data.DailyQuest;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.model.QuestDefinition;
import dev.guildquests.model.QuestType;
import dev.guildquests.service.GuildService;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class GuildScreenHandler extends ScreenHandler {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayerEntity player, int button, SlotActionType actionType);
    }

    private static final int DISPLAY_SLOTS = 54;
    /** Hard cap for the daily board row (row 2 = slots 9..17); dailyQuestCountMax clamps to 9. */
    private static final int MAX_DAILY_SLOTS = 9;
    private final SimpleInventory display = new SimpleInventory(DISPLAY_SLOTS);
    private final ServerPlayerEntity owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    public GuildScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity owner) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.owner = owner;

        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                addSlot(new Slot(display, index, 8 + col * 18, 18 + row * 18) {
                    @Override
                    public boolean canInsert(ItemStack stack) {
                        return false;
                    }

                    @Override
                    public boolean canTakeItems(PlayerEntity playerEntity) {
                        return false;
                    }
                });
            }
        }

        // Match the slot count/order of the vanilla Generic 9x6 container.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 198));
        }

        populate();
    }

    public static void openQuests(ServerPlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, ignored) -> new GuildScreenHandler(syncId, playerInventory, player),
                Text.literal("冒険者ギルド - クエスト")
        ));
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return player == owner && !owner.isRemoved();
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex >= 0 && slotIndex < DISPLAY_SLOTS && player instanceof ServerPlayerEntity serverPlayer) {
            MenuAction action = actions.get(slotIndex);
            if (action != null) {
                action.run(serverPlayer, button, actionType);
                if (serverPlayer.currentScreenHandler == this) {
                    populate();
                    sendContentUpdates();
                }
            }
            return;
        }
        super.onSlotClick(slotIndex, button, actionType, player);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    private void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, ItemStack.EMPTY);
        fillBackground();
        PlayerGuildData data = GuildService.data(owner);

        set(0, item(Items.GOLD_NUGGET, "所持コイン: " + data.coins,
                "電子通貨として保存されています。"));
        set(1, item(Items.NETHER_STAR, "ギルドランク: " + GuildService.rank(data),
                "累計達成: " + data.completedTotal + " 件"));
        set(2, item(Items.CLOCK, "同時受注上限: " + ConfigManager.config().maxActiveQuests + " 件",
                "クエスト報酬に日次上限はありません。"));

        populateQuests(data);
    }

    private void populateQuests(PlayerGuildData data) {
        GuildService.ensureDailyBoard(owner.getServer());
        List<DailyQuest> board = GuildPersistentState.get(owner.getServer()).dailyBoard;

        set(4, item(Items.WRITABLE_BOOK, "本日のクエスト（" + board.size() + "問）",
                "毎日の抽選で決まります。下の一覧を左クリックして受注します。",
                "次の抽選まで: " + GuildService.formatGameTime(GuildService.ticksUntilNextDraw(owner.getServer()))));
        set(6, item(Items.BOOK, "受注中",
                "4段目に現在の受注クエストを表示します。",
                "左クリック: 納品/報酬受取",
                "右クリック: クエスト破棄"));
        setAction(8, item(Items.GOLD_BLOCK, "ギルド銀行",
                "クリックで銀行を開きます",
                "預け入れ・引き出し（手数料あり）",
                "銀行ブロック経由なら手数料無料"),
                (p, b, t) -> BankScreenHandler.open(p));
        setAction(7, item(Items.PLAYER_HEAD, "送金",
                "クリックで送金メニューを開きます",
                "送金先の選択・金額指定",
                "手数料: 無料"),
                (p, b, t) -> TransferScreenHandler.open(p));
        setAction(3, item(Items.HAY_BLOCK, "ギャンブル",
                "クリックでギャンブルメニューを開きます",
                "ブラックジャック・競馬",
                "所持コインで賭けられます"),
                (p, b, t) -> GambleScreenHandler.open(p));
        setAction(5, item(Items.EMERALD, "オークション",
                "クリックでオークション市場を開きます",
                "出品・購入・入札ができます",
                "固定価格 / 入札式を選択可能"),
                (p, b, t) -> AuctionBrowseScreenHandler.open(p));

        for (int i = 0; i < Math.min(MAX_DAILY_SLOTS, board.size()); i++) {
            DailyQuest daily = board.get(i);
            QuestDefinition quest = ConfigManager.quest(daily.questId);
            if (quest == null) continue;
            int slot = 9 + i;
            ActiveQuest active = data.findActive(quest.id);
            boolean locked = data.completedTotal < quest.requiredCompleted;
            boolean completedOneTime = !quest.repeatable && data.completedQuestIds.contains(quest.id);
            long reward = quest.reward;
            String title = quest.title;

            ItemStack icon;
            if (locked) {
                icon = item(Items.BARRIER, "[LOCK] " + title,
                        quest.description,
                        "解放条件: 累計 " + quest.requiredCompleted + " 件達成",
                        dailyObjectiveLine(daily, quest),
                        rewardLine(reward));
            } else if (completedOneTime) {
                icon = item(Items.GRAY_DYE, "[達成済] " + title,
                        quest.description,
                        "このクエストは再受注できません。");
            } else if (active != null) {
                int amount = GuildService.effectiveAmount(active, quest);
                icon = questIcon(quest, "[受注中] " + title,
                        quest.description,
                        activeObjectiveLine(quest, active),
                        "進捗: " + active.progress + "/" + amount,
                        rewardLine(reward));
            } else {
                setAction(slot, questIcon(quest, title,
                        quest.description,
                        dailyObjectiveLine(daily, quest),
                        rewardLine(reward),
                        "左クリックで受注"),
                        (p, b, t) -> {
                            if (b == 0) GuildService.acceptQuest(p, quest);
                        });
                continue;
            }
            set(slot, icon);
        }

        // Active quests occupy row 5 (slots 36-44).
        List<ActiveQuest> activeList = new ArrayList<>(data.activeQuests);
        for (int i = 0; i < Math.min(9, activeList.size()); i++) {
            ActiveQuest active = activeList.get(i);
            QuestDefinition quest = GuildService.resolveQuest(owner.getServer(), active.questId);
            int slot = 36 + i;
            if (quest == null) {
                setAction(slot, item(Items.BARRIER, "不明なクエスト: " + active.questId,
                                "設定から削除されたクエストです。",
                                "右クリックで破棄できます。"),
                        (p, b, t) -> { if (b == 1) GuildService.cancelQuest(p, active.questId); });
                continue;
            }
            boolean ready = active.progress >= GuildService.effectiveAmount(active, quest);
            int amount = GuildService.effectiveAmount(active, quest);
            long reward = quest.reward;
            String title = quest.title;
            ItemStack icon = item(ready ? Items.LIME_DYE : activeIcon(quest),
                    (ready ? "[達成可能] " : "[進行中] ") + title,
                    activeObjectiveLine(quest, active),
                    "進捗: " + active.progress + "/" + amount,
                    rewardLine(reward),
                    quest.type == QuestType.SUBMIT && !ready ? "左クリック: 所持アイテムを納品" : "左クリック: 報酬を受け取る",
                    "右クリック: 破棄");
            setAction(slot, icon, (p, b, t) -> {
                if (b == 1) GuildService.cancelQuest(p, quest.id);
                else if (b == 0) GuildService.submitOrClaim(p, quest.id);
            });
        }

        setAction(49, item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeHandledScreen());
    }

    private void fillBackground() {
        ItemStack pane = item(Items.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, pane.copy());
    }

    private void set(int slot, ItemStack stack) {
        display.setStack(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, stack);
        actions.put(slot, action);
    }

    private static ItemStack questIcon(QuestDefinition quest, String name, String... lore) {
        return item(activeIcon(quest), name, lore);
    }

    /** Reward line for a board/active entry. */
    private static String rewardLine(long reward) {
        return "報酬: " + reward + " コイン";
    }

    private static Item activeIcon(QuestDefinition quest) {
        return switch (quest.type) {
            case KILL -> Items.IRON_SWORD;
            case SUBMIT -> Items.HOPPER;
            case EXPLORE -> Items.COMPASS;
        };
    }

    /** Concrete objective line for today's rolled values (no ranges, no "random"). */
    private static String dailyObjectiveLine(DailyQuest daily, QuestDefinition quest) {
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

    private static String activeObjectiveLine(QuestDefinition quest, ActiveQuest active) {
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

    private static ItemStack item(Item item, String name, String... lore) {
        return item(item, Formatting.WHITE, name, lore);
    }

    private static ItemStack item(Item item, Formatting nameFormat, String name, String... lore) {
        return GuildItems.item(item, nameFormat, name, lore);
    }
}

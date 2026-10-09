package dev.guildquests.gui;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.data.PublishedQuest;
import dev.guildquests.model.QuestType;
import dev.guildquests.service.GuildService;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
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

/** Shared bounty board listing player-published quests (accept / cancel / abandon). */
public final class PublishedQuestBoardScreenHandler extends ScreenHandler {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayerEntity player, int button, SlotActionType actionType);
    }

    private static final int DISPLAY_SLOTS = 54;
    private static final int MAX_BOARD_SLOTS = 27;
    private final SimpleInventory display = new SimpleInventory(DISPLAY_SLOTS);
    private final ServerPlayerEntity owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    public PublishedQuestBoardScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity owner) {
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

    public static void open(ServerPlayerEntity player) {
        if (!ConfigManager.config().playerQuestsEnabled) {
            GuildService.message(player, "公開クエスト機能は無効になっています。", Formatting.RED);
            return;
        }
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, ignored) -> new PublishedQuestBoardScreenHandler(syncId, playerInventory, player),
                Text.literal("依頼クエスト掲示板")
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
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        PlayerGuildData data = GuildService.data(owner);
        List<PublishedQuest> quests = new ArrayList<>(
                GuildPersistentState.get(owner.getServer()).publishedQuests);

        set(0, item(Items.LECTERN, "依頼クエスト掲示板",
                "公開中の依頼: " + quests.size() + " 件（最大 " + MAX_BOARD_SLOTS + " 件表示）",
                "左クリックで受注",
                "自分の依頼は右クリックで取り消し",
                "受注中の依頼は右クリックで破棄"));
        set(1, item(Items.GOLD_NUGGET, "所持コイン: " + data.coins,
                "受注して達成すると報酬が入ります"));
        set(4, item(Items.WRITABLE_BOOK, "掲示板の使い方",
                "① 依頼を選んで左クリックで受注",
                "② クエスト画面で進行・納品・報酬受取",
                "③ 自分の依頼は右クリックで取り消し（返金）",
                "④ 依頼者は報酬を預けたまま掲載されます"));

        for (int i = 0; i < Math.min(MAX_BOARD_SLOTS, quests.size()); i++) {
            PublishedQuest quest = quests.get(i);
            int slot = 9 + i;
            setAction(slot, boardEntry(quest, data), (p, b, t) -> {
                if (b == 1) {
                    if (p.getUuid().equals(quest.publisherId)) {
                        GuildService.cancelPublishedQuest(p, quest.id);
                    } else if (p.getUuid().equals(quest.takerId)) {
                        GuildService.cancelQuest(p, quest.questId());
                    }
                } else if (b == 0) {
                    GuildService.acceptPublishedQuest(p, quest.id);
                }
            });
        }

        if (quests.size() > MAX_BOARD_SLOTS) {
            set(48, item(Items.BOOK, "ほかに " + (quests.size() - MAX_BOARD_SLOTS) + " 件あります",
                    "掲載上限のため表示されない依頼があります"));
        }

        setAction(45, item(Items.ARROW, "戻る",
                "ギルドメニューに戻ります"),
                (p, b, t) -> GuildScreenHandler.openQuests(p));
        setAction(49, item(Items.EMERALD, "クエストを公開",
                "自分の依頼を掲示板に出します",
                "クリックで公開ウィザードを開きます"),
                (p, b, t) -> PublishQuestScreenHandler.open(p));
        setAction(53, item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeHandledScreen());
    }

    private ItemStack boardEntry(PublishedQuest quest, PlayerGuildData data) {
        QuestType type = quest.type;
        String objective = type == QuestType.KILL
                ? "討伐: " + GuildService.entityName(quest.target) + " × " + quest.amount
                : "納品: " + GuildService.itemName(quest.target) + " × " + quest.amount;

        boolean own = owner.getUuid().equals(quest.publisherId);
        boolean mine = owner.getUuid().equals(quest.takerId);
        String prefix;
        Formatting nameFormat;
        if (own) {
            prefix = "[自分の依頼] ";
            nameFormat = Formatting.GOLD;
        } else if (quest.isTaken()) {
            prefix = "[受注中] ";
            nameFormat = Formatting.GRAY;
        } else {
            prefix = "[募集中] ";
            nameFormat = Formatting.WHITE;
        }

        List<String> lore = new ArrayList<>();
        lore.add(quest.toDefinition().title);
        lore.add("依頼者: " + quest.publisherName);
        lore.add(objective);
        lore.add("報酬: " + quest.reward + " コイン");
        if (quest.isTaken()) {
            lore.add("受注者: " + quest.takerName);
        }
        lore.add("公開日: " + quest.createdAtDay + " 日目");
        if (data.findActive(quest.questId()) != null) {
            lore.add("このクエストは既に受注中です");
        }
        if (own && !quest.isTaken()) {
            lore.add("右クリックで取り消し（報酬 " + quest.reward + " コインを返金）");
        } else if (mine) {
            lore.add("右クリックで破棄（掲示板に戻ります）");
        } else if (!quest.isTaken()) {
            lore.add("左クリックで受注");
        }
        return item(icon(type), nameFormat, prefix + quest.toDefinition().title,
                lore.toArray(new String[0]));
    }

    private static Item icon(QuestType type) {
        return type == QuestType.KILL ? Items.IRON_SWORD : Items.HOPPER;
    }

    private void set(int slot, ItemStack stack) {
        display.setStack(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, stack);
        actions.put(slot, action);
    }

    private static ItemStack item(Item item, String name, String... lore) {
        return item(item, Formatting.WHITE, name, lore);
    }

    private static ItemStack item(Item item, Formatting nameFormat, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.setCustomName(Text.literal(name).formatted(nameFormat));
        Text[] lines = new Text[lore.length];
        for (int i = 0; i < lore.length; i++) lines[i] = Text.literal(lore[i]).formatted(Formatting.GRAY);
        setLore(stack, lines);
        return stack;
    }

    private static void setLore(ItemStack stack, Text... lines) {
        if (lines.length == 0) return;
        NbtCompound display = stack.getOrCreateSubNbt("display");
        NbtList lore = new NbtList();
        for (Text line : lines) {
            lore.add(NbtString.of(Text.Serializer.toJson(line)));
        }
        display.put("Lore", lore);
    }
}

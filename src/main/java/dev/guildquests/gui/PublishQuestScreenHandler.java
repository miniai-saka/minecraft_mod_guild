package dev.guildquests.gui;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.config.ModConfig;
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

import java.util.HashMap;
import java.util.Map;

/** Wizard for publishing a player quest: pick type, target, amount and reward, then escrow. */
public final class PublishQuestScreenHandler extends ScreenHandler {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayerEntity player, int button, SlotActionType actionType);
    }

    private static final String[] KILL_TARGETS = {
            "minecraft:zombie", "minecraft:skeleton", "minecraft:creeper", "minecraft:spider",
            "minecraft:enderman", "minecraft:blaze", "minecraft:pillager", "minecraft:phantom",
            "minecraft:slime", "minecraft:witch", "minecraft:drowned", "minecraft:husk",
            "minecraft:wither_skeleton", "minecraft:guardian", "minecraft:evoker", "minecraft:hoglin"
    };
    private static final String[] SUBMIT_TARGETS = {
            "minecraft:rotten_flesh", "minecraft:bone", "minecraft:string", "minecraft:gunpowder",
            "minecraft:coal", "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond",
            "minecraft:emerald", "minecraft:wheat", "minecraft:carrot", "minecraft:leather",
            "minecraft:feather", "minecraft:oak_log", "minecraft:cobblestone", "minecraft:sand"
    };
    private static final int[] AMOUNTS = {5, 10, 16, 32, 64};
    private static final long[] REWARDS = {10, 25, 50, 100, 250, 500};

    private static final int DISPLAY_SLOTS = 27;
    private final SimpleInventory display = new SimpleInventory(DISPLAY_SLOTS);
    private final ServerPlayerEntity owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    private QuestType type = QuestType.KILL;
    private int targetIndex = 0;
    private int amountIndex = 1;
    private int rewardIndex = 2;

    public PublishQuestScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity owner) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.owner = owner;

        for (int row = 0; row < 3; row++) {
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
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }

        populate();
    }

    public static void open(ServerPlayerEntity player) {
        if (!ConfigManager.config().playerQuestsEnabled) {
            GuildService.message(player, "公開クエスト機能は無効になっています。", Formatting.RED);
            return;
        }
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, ignored) -> new PublishQuestScreenHandler(syncId, playerInventory, player),
                Text.literal("クエストを公開")
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

    private String[] targets() {
        return type == QuestType.KILL ? KILL_TARGETS : SUBMIT_TARGETS;
    }

    private long currentReward() {
        ModConfig config = ConfigManager.config();
        long base = REWARDS[rewardIndex];
        return Math.max(config.publishedQuestRewardMin, Math.min(config.publishedQuestRewardMax, base));
    }

    private void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, ItemStack.EMPTY);
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        String[] targets = targets();
        if (targetIndex >= targets.length) targetIndex = 0;
        String target = targets[targetIndex];
        int amount = AMOUNTS[amountIndex];
        long reward = currentReward();

        setAction(0, item(type == QuestType.KILL ? Items.IRON_SWORD : Items.HOPPER,
                        "種類: " + (type == QuestType.KILL ? "討伐" : "納品"),
                        "クリックで切り替え（討伐 ↔ 納品）"),
                (p, b, t) -> {
                    type = type == QuestType.KILL ? QuestType.SUBMIT : QuestType.KILL;
                    targetIndex = 0;
                });

        setAction(1, item(type == QuestType.KILL ? Items.BONE : Items.CHEST,
                        "対象: " + displayName(target),
                        "クリックで対象を切り替え",
                        target),
                (p, b, t) -> {
                    if (b == 0) targetIndex = (targetIndex + 1) % targets().length;
                    else if (b == 1) targetIndex = (targetIndex + targets().length - 1) % targets().length;
                });

        setAction(2, item(Items.ARROW, "数量: " + amount,
                        "左クリックで増加 / 右クリックで減少",
                        "5 → 10 → 16 → 32 → 64"),
                (p, b, t) -> {
                    if (b == 1) amountIndex = (amountIndex + AMOUNTS.length - 1) % AMOUNTS.length;
                    else amountIndex = (amountIndex + 1) % AMOUNTS.length;
                });

        setAction(3, item(Items.EMERALD, "報酬: " + reward + " コイン",
                        "左クリックで増加 / 右クリックで減少",
                        "公開時に残高から預託されます",
                        "設定範囲: " + ConfigManager.config().publishedQuestRewardMin
                                + " 〜 " + ConfigManager.config().publishedQuestRewardMax),
                (p, b, t) -> {
                    if (b == 1) rewardIndex = (rewardIndex + REWARDS.length - 1) % REWARDS.length;
                    else rewardIndex = (rewardIndex + 1) % REWARDS.length;
                });

        set(4, item(Items.WRITABLE_BOOK, "プレビュー",
                "タイトル: " + previewTitle(type, target, amount),
                "依頼者: " + owner.getName().getString(),
                "報酬: " + reward + " コイン（達成者へ支払い）",
                "達成すると報酬は受注者に入ります"));

        setAction(8, item(Items.BARRIER, "キャンセル"), (p, b, t) -> p.closeHandledScreen());

        setAction(13, item(Items.LIME_CONCRETE, "この内容で公開する",
                        "クリックで依頼を掲示板に公開",
                        "報酬 " + reward + " コインを残高から預託",
                        "誰かが達成すると報酬が支払われます"),
                (p, b, t) -> {
                    if (b == 0 && GuildService.publishQuest(p, type, target, amount, reward)) {
                        p.closeHandledScreen();
                    }
                });

        set(17, item(Items.GOLD_NUGGET, "自分の残高: " + GuildService.data(owner).coins,
                "預託できるだけの残高が必要です"));

        set(22, item(Items.WRITABLE_BOOK, "公開クエストのやり方",
                "① 種類・対象・数量・報酬を選択",
                "② 「この内容で公開する」で掲示板へ",
                "③ 報酬は公開時に残高から預託",
                "④ 誰かが達成すると報酬が支払われる",
                "⑤ 受注されるまでなら右クリック等で取り消し可"));
    }

    private static String previewTitle(QuestType type, String target, int amount) {
        if (type == QuestType.KILL) {
            return "依頼：討伐 " + GuildService.entityName(target) + " × " + amount;
        }
        return "依頼：納品 " + GuildService.itemName(target) + " × " + amount;
    }

    private static String displayName(String target) {
        // Called during populate where registries are available.
        return GuildService.entityName(target).equals(target)
                ? GuildService.itemName(target)
                : GuildService.entityName(target);
    }

    private void set(int slot, ItemStack stack) {
        display.setStack(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, stack);
        actions.put(slot, action);
    }

    private static ItemStack item(Item item, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.setCustomName(Text.literal(name).formatted(Formatting.WHITE));
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

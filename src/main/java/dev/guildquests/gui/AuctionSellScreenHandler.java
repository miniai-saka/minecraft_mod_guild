package dev.guildquests.gui;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.AuctionListing;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.item.ModItems;
import dev.guildquests.service.AuctionService;
import dev.guildquests.service.GuildService;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Listing screen: escrows the stack the player is currently holding and lists it
 * as a fixed-price or auction sale. The price is chosen with the digit heads and
 * paper buttons. Only display slots are interactive (inventory stays vanilla).
 */
public final class AuctionSellScreenHandler extends ScreenHandler {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayerEntity player, int button, SlotActionType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;
    private static final int MAX_PRICE = 9_999_999;

    private final SimpleInventory display = new SimpleInventory(DISPLAY_SLOTS);
    private final ServerPlayerEntity owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();
    private long price;
    private boolean auctionMode;

    public AuctionSellScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity owner) {
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
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, ignored) -> new AuctionSellScreenHandler(syncId, playerInventory, player),
                Text.literal("出品する")
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
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, GuildItems.item(Items.GRAY_STAINED_GLASS_PANE, " "));

        PlayerGuildData data = GuildService.data(owner);
        ItemStack held = owner.getMainHandStack();

        set(0, GuildItems.item(Items.PAPER, "手に持っているアイテムを出品",
                held.isEmpty() ? "現在: 何も持っていません" : "現在: " + held.getName().getString() + " ×" + held.getCount(),
                "別のアイテムは持ち替えてから開き直してください"));

        // Slot 4: mode toggle.
        setAction(4, GuildItems.item(auctionMode ? Items.LIME_CONCRETE : Items.GRAY_CONCRETE,
                        auctionMode ? "形式: オークション（入札式）" : "形式: 固定価格",
                        "クリックで切替",
                        auctionMode ? "開始価格から入札、期限で最高額が落札" : "設定価格で即売り"),
                (p, b, t) -> {
                    if (b == 0) auctionMode = !auctionMode;
                });

        // Row 0, slots 5-8: digit heads.
        int[] places = {1000, 100, 10, 1};
        String[] labels = {"千の位", "百の位", "十の位", "一の位"};
        for (int i = 0; i < 4; i++) {
            int place = places[i];
            int digit = (int) ((price / place) % 10);
            setAction(5 + i, GuildItems.digitHead(digit, labels[i]), (p, b, t) -> {
                if (b != 0 && b != 1) return;
                long cur = (price / place) % 10;
                long next = Math.floorMod(cur + (b == 0 ? 1 : -1), 10);
                price = Math.min(MAX_PRICE, Math.max(0, price - cur * place + next * place));
            });
        }

        // Row 1: preview, price, duration, usage, paper buttons.
        if (held.isEmpty()) {
            set(9, GuildItems.item(Items.ITEM_FRAME, "出品するアイテム: なし",
                    "売りたいアイテムを手に持って",
                    "この画面を開き直してください"));
        } else {
            set(9, GuildItems.decorateCopy(held, Formatting.WHITE,
                    held.getName().getString() + " ×" + held.getCount(),
                    "出品予定のアイテム"));
        }
        set(10, GuildItems.item(ModItems.COIN, "価格: " + price + " コイン",
                auctionMode ? "オークションの開始価格" : "固定販売価格",
                "数字頭・紙で調整",
                "売却時に手数料 " + feeText() + "%"));
        set(11, GuildItems.item(Items.CLOCK, "出品期間: " + ConfigManager.config().auctionDurationMinutes + " 分",
                "期限到来で自動的に精算されます"));
        set(12, GuildItems.item(Items.BOOK, "出品中: " + AuctionService.listingsBy(owner).size()
                        + " / " + ConfigManager.config().auctionMaxListingsPerPlayer,
                "上限を超えては出品できません"));
        addPaper(13, 1);
        addPaper(14, 10);
        addPaper(15, 100);
        addPaper(16, 1000);
        set(17, GuildItems.item(Items.KNOWLEDGE_BOOK, "使い方",
                "1. 売りたいアイテムを手に持つ",
                "2. 価格を決める",
                "3. 下段のボタンで出品"));

        // Row 2: confirm / clear / close.
        String modeName = auctionMode ? "オークション" : "固定価格";
        if (held.isEmpty()) {
            set(18, GuildItems.item(Items.RED_CONCRETE, "出品できません",
                    "アイテムを手に持っていません"));
        } else if (auctionMode && price <= 0) {
            set(18, GuildItems.item(Items.RED_CONCRETE, "出品できません",
                    "オークションの開始価格は 1 コイン以上"));
        } else {
            setAction(18, GuildItems.item(Items.LIME_CONCRETE, "「" + modeName + "」で出品",
                            held.getName().getString() + " ×" + held.getCount(),
                            "価格: " + price + " コイン",
                            "クリックで出品"),
                    (p, b, t) -> {
                        if (b == 0) list(p, auctionMode ? AuctionListing.MODE_AUCTION : AuctionListing.MODE_BUYOUT);
                    });
        }

        setAction(22, GuildItems.item(Items.BARRIER, "価格クリア"), (p, b, t) -> {
            if (b == 0) price = 0;
        });
        setAction(26, GuildItems.item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeHandledScreen());

        set(19, GuildItems.item(Items.GOLD_NUGGET, "残高: " + data.coins,
                "出品にはコインはかかりません"));
    }

    private void addPaper(int slot, int amount) {
        long preview = Math.min(MAX_PRICE, price + amount);
        setAction(slot, GuildItems.item(Items.PAPER, "＋" + amount,
                        "クリックで " + amount + " 加算",
                        "現在: " + price + " → " + preview),
                (p, b, t) -> {
                    if (b == 0) price = Math.min(MAX_PRICE, price + amount);
                });
    }

    private void list(ServerPlayerEntity player, String mode) {
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty()) {
            GuildService.message(player, "出品するアイテムを手に持ってください。", Formatting.YELLOW);
            return;
        }
        int slot = player.getInventory().selectedSlot;
        long id = AuctionService.createListing(player, held, mode, price);
        if (id > 0) {
            player.getInventory().removeStack(slot);
            price = 0;
        }
    }

    private String feeText() {
        double pct = ConfigManager.config().auctionFeePercent;
        if (pct == Math.floor(pct)) return String.valueOf((long) pct);
        return String.valueOf(pct);
    }

    private void set(int slot, ItemStack stack) {
        display.setStack(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, stack);
        actions.put(slot, action);
    }
}

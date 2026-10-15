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
import java.util.List;
import java.util.Map;

/**
 * Global auction market. Buyout listings are purchased with a left click;
 * auction listings are bid on using the digit-head amount in the bottom row.
 */
public final class AuctionBrowseScreenHandler extends ScreenHandler {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayerEntity player, int button, SlotActionType actionType);
    }

    private static final int DISPLAY_SLOTS = 54;
    private static final int PAGE_SIZE = 36;
    private static final int FIRST_ITEM_SLOT = 9;
    private static final int MAX_BID = 9_999_999;

    private final SimpleInventory display = new SimpleInventory(DISPLAY_SLOTS);
    private final ServerPlayerEntity owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();
    private int page;
    private long bidAmount;

    public AuctionBrowseScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity owner) {
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
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, ignored) -> new AuctionBrowseScreenHandler(syncId, playerInventory, player),
                Text.literal("ギルドオークション")
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
        List<AuctionListing> all = AuctionService.listings(owner.getServer());

        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;

        set(0, GuildItems.item(Items.GOLD_INGOT, "残高: " + data.coins + " コイン",
                "所持コイン: " + GuildService.countHeldCoins(owner),
                "購入・入札は銀行残高から"));
        setAction(1, GuildItems.item(Items.EMERALD, "出品する",
                        "手持ちのアイテムを売りに出す",
                        "固定価格 / オークションを選択"),
                (p, b, t) -> AuctionSellScreenHandler.open(p));
        setAction(2, GuildItems.item(Items.CHEST, "自分の出品・受取ボックス",
                        "出品の取消や、落札品の受け取り"),
                (p, b, t) -> AuctionMineScreenHandler.open(p));
        setAction(3, GuildItems.item(Items.CLOCK, "更新",
                        "最新の出品状況を表示"),
                (p, b, t) -> { });

        set(4, GuildItems.item(Items.PAPER, "ページ " + (page + 1) + " / " + pages,
                "出品数: " + all.size() + " 件",
                "手数料: " + feeText() + "%"));

        if (page > 0) {
            setAction(5, GuildItems.item(Items.ARROW, "前のページ"), (p, b, t) -> {
                if (b == 0) page--;
            });
        } else {
            set(5, GuildItems.item(Items.GRAY_DYE, "前のページ", "最初のページです"));
        }
        if (page < pages - 1) {
            setAction(6, GuildItems.item(Items.ARROW, "次のページ"), (p, b, t) -> {
                if (b == 0) page++;
            });
        } else {
            set(6, GuildItems.item(Items.GRAY_DYE, "次のページ", "最後のページです"));
        }

        set(7, GuildItems.item(Items.KNOWLEDGE_BOOK, "使い方",
                "固定価格: 左クリックで即購入",
                "オークション: 下段で入札額を決めて左クリック",
                "落札・期限切れの品は受取ボックスへ"));
        setAction(8, GuildItems.item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeHandledScreen());

        // Listings (rows 1-4).
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = page * PAGE_SIZE + i;
            if (idx >= all.size()) break;
            renderListing(FIRST_ITEM_SLOT + i, all.get(idx));
        }

        // Row 5: bid amount controls.
        int[] places = {1000, 100, 10, 1};
        String[] labels = {"千の位", "百の位", "十の位", "一の位"};
        for (int i = 0; i < 4; i++) {
            int slot = 45 + i;
            int place = places[i];
            int digit = (int) ((bidAmount / place) % 10);
            setAction(slot, GuildItems.digitHead(digit, labels[i]), (p, b, t) -> {
                if (b != 0 && b != 1) return;
                long cur = (bidAmount / place) % 10;
                long next = Math.floorMod(cur + (b == 0 ? 1 : -1), 10);
                bidAmount = Math.min(MAX_BID, Math.max(0, bidAmount - cur * place + next * place));
            });
        }
        set(49, GuildItems.item(ModItems.COIN, "入札額: " + bidAmount,
                "オークション出品に入札する金額",
                "現在の最高額より高い額を指定"));
        addPaper(50, 1);
        addPaper(51, 10);
        addPaper(52, 100);
        setAction(53, GuildItems.item(Items.BARRIER, "入札額クリア"), (p, b, t) -> {
            if (b == 0) bidAmount = 0;
        });
    }

    private void renderListing(int slot, AuctionListing listing) {
        boolean mine = owner.getUuid().equals(listing.sellerId);
        String label = listing.item.getName().getString() + " ×" + listing.item.getCount();
        String remaining = AuctionService.formatRemaining(listing.expireAt);

        String[] lore;
        if (listing.isAuction()) {
            long min = listing.minNextBid(ConfigManager.config().auctionBidStep);
            if (listing.hasBid()) {
                lore = new String[]{
                        "出品者: " + listing.sellerName,
                        "形式: オークション",
                        "現在の最高入札: " + listing.highestBid + "（" + listing.highestBidderName + "）",
                        "最低入札額: " + min,
                        "残り時間: " + remaining,
                        mine ? "自分の出品です" : "左クリック: " + bidAmount + " コインで入札"
                };
            } else {
                lore = new String[]{
                        "出品者: " + listing.sellerName,
                        "形式: オークション",
                        "開始価格: " + listing.price,
                        "最低入札額: " + min,
                        "残り時間: " + remaining,
                        mine ? "自分の出品です" : "左クリック: " + bidAmount + " コインで入札"
                };
            }
        } else {
            lore = new String[]{
                    "出品者: " + listing.sellerName,
                    "形式: 固定価格",
                    "価格: " + listing.price + " コイン",
                    "残り時間: " + remaining,
                    mine ? "自分の出品です" : "左クリック: 購入"
            };
        }

        long id = listing.id;
        ItemStack icon = GuildItems.decorateCopy(listing.item, Formatting.WHITE, label, lore);
        if (mine) {
            set(slot, icon);
            return;
        }
        setAction(slot, icon, (p, b, t) -> {
            if (b == 0) AuctionService.interact(p, id, bidAmount);
        });
    }

    private void addPaper(int slot, int amount) {
        long preview = Math.min(MAX_BID, bidAmount + amount);
        setAction(slot, GuildItems.item(Items.PAPER, "＋" + amount,
                        "クリックで " + amount + " 加算",
                        "現在: " + bidAmount + " → " + preview),
                (p, b, t) -> {
                    if (b == 0) bidAmount = Math.min(MAX_BID, bidAmount + amount);
                });
    }

    private String feeText() {
        double pct = ConfigManager.config().auctionFeePercent;
        if (pct == Math.floor(pct)) return String.valueOf((long) pct);
        return String.valueOf(pct);
    }

    private void fillBackground() {
        ItemStack pane = GuildItems.item(Items.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, pane.copy());
    }

    private void set(int slot, ItemStack stack) {
        display.setStack(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, stack);
        actions.put(slot, action);
    }
}

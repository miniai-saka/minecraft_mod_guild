package dev.guildquests.gui;

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
 * The player's own listings (cancellable) plus the auction mailbox where won,
 * refunded or expired items wait to be collected into the inventory.
 */
public final class AuctionMineScreenHandler extends ScreenHandler {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayerEntity player, int button, SlotActionType actionType);
    }

    private static final int DISPLAY_SLOTS = 54;
    private static final int MY_LISTING_SLOTS = 18;
    private static final int MAILBOX_SLOTS = 27;
    private static final int MY_LISTING_START = 9;
    private static final int MAILBOX_START = 27;

    private final SimpleInventory display = new SimpleInventory(DISPLAY_SLOTS);
    private final ServerPlayerEntity owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    public AuctionMineScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity owner) {
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
                (syncId, playerInventory, ignored) -> new AuctionMineScreenHandler(syncId, playerInventory, player),
                Text.literal("自分の出品・受取ボックス")
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
        ItemStack pane = GuildItems.item(Items.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, pane.copy());

        PlayerGuildData data = GuildService.data(owner);
        List<AuctionListing> mine = AuctionService.listingsBy(owner);
        List<ItemStack> box = data.mailbox;

        set(0, GuildItems.item(Items.PAPER, "自分の出品・受取ボックス",
                "出品の取消と、落札品の受け取り"));
        set(1, GuildItems.item(ModItems.COIN, "残高: " + data.coins + " コイン",
                "所持コイン: " + GuildService.countHeldCoins(owner)));
        set(4, GuildItems.item(Items.CHEST, "出品中: " + mine.size() + " 件",
                "左クリックで出品を取り消します",
                "入札がある場合は返金されます"));
        set(5, GuildItems.item(Items.HOPPER, "受取ボックス: " + box.size() + " 件",
                "左クリックで1件受け取ります"));
        setAction(6, GuildItems.item(Items.LIME_CONCRETE, "受取ボックスを全て受け取る",
                        "入る分だけまとめて受け取ります"),
                (p, b, t) -> {
                    if (b == 0) AuctionService.claimAllMailbox(p);
                });
        setAction(8, GuildItems.item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeHandledScreen());

        for (int i = 0; i < MY_LISTING_SLOTS; i++) {
            if (i >= mine.size()) break;
            AuctionListing listing = mine.get(i);
            long id = listing.id;
            String label = listing.item.getName().getString() + " ×" + listing.item.getCount();
            String[] lore;
            if (listing.isAuction()) {
                lore = new String[]{
                        "形式: オークション",
                        listing.hasBid()
                                ? "最高入札: " + listing.highestBid + "（" + listing.highestBidderName + "）"
                                : "開始価格: " + listing.price,
                        "残り時間: " + AuctionService.formatRemaining(listing.expireAt),
                        "左クリック: 出品取消（アイテム返却）"
                };
            } else {
                lore = new String[]{
                        "形式: 固定価格",
                        "価格: " + listing.price + " コイン",
                        "残り時間: " + AuctionService.formatRemaining(listing.expireAt),
                        "左クリック: 出品取消（アイテム返却）"
                };
            }
            setAction(MY_LISTING_START + i, GuildItems.decorateCopy(listing.item, Formatting.WHITE, label, lore),
                    (p, b, t) -> {
                        if (b == 0) AuctionService.cancel(p, id);
                    });
        }

        for (int i = 0; i < MAILBOX_SLOTS; i++) {
            if (i >= box.size()) break;
            ItemStack stack = box.get(i);
            int index = i;
            String label = stack.getName().getString() + " ×" + stack.getCount();
            ItemStack icon = GuildItems.decorateCopy(stack, Formatting.WHITE, label,
                    "受取待ちのアイテム",
                    "左クリック: 受け取る");
            setAction(MAILBOX_START + i, icon, (p, b, t) -> {
                if (b == 0) AuctionService.claimMailbox(p, index);
            });
        }
    }

    private void set(int slot, ItemStack stack) {
        display.setStack(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, stack);
        actions.put(slot, action);
    }
}

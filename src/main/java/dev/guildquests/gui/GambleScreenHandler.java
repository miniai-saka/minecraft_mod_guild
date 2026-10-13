package dev.guildquests.gui;

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

import java.util.HashMap;
import java.util.Map;

public final class GambleScreenHandler extends ScreenHandler {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayerEntity player, int button, SlotActionType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;
    private final SimpleInventory display = new SimpleInventory(DISPLAY_SLOTS);
    private final ServerPlayerEntity owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    public GambleScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity owner) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.owner = owner;

        for (int i = 0; i < DISPLAY_SLOTS; i++) {
            addSlot(new Slot(display, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18) {
                @Override public boolean canInsert(ItemStack stack) { return false; }
                @Override public boolean canTakeItems(PlayerEntity player) { return false; }
            });
        }
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));

        populate();
    }

    public static void open(ServerPlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, ignored) -> new GambleScreenHandler(syncId, playerInventory, player),
                Text.literal("ギャンブル")
        ));
    }

    private void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, ItemStack.EMPTY);
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        long balance = GuildService.data(owner).coins;

        // Row 0: balance info
        set(4, item(Items.GOLD_INGOT, "所持コイン: " + balance));

        // Row 1: game selection
        setAction(11, item(Items.PLAYER_HEAD, "ブラックジャック", "クリックでプレイ", "21に近い方が勝ち"),
                (p, b, t) -> { if (b == 0) BlackjackScreenHandler.open(p); });
        setAction(13, item(Items.HAY_BLOCK, "競馬", "クリックでプレイ", "馬に賭けて大勝負！"),
                (p, b, t) -> { if (b == 0) HorseRaceScreenHandler.open(p); });

        // Row 2: close
        setAction(22, item(Items.BARRIER, "閉じる"),
                (p, b, t) -> p.closeHandledScreen());
    }

    private static ItemStack item(Item item, String name, String... lore) {
        return GuildItems.item(item, name, lore);
    }



    private void set(int slot, ItemStack stack) {
        display.setStack(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, stack);
        actions.put(slot, action);
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
    public ItemStack transferSlot(PlayerEntity player, int slot) { return ItemStack.EMPTY; }

    @Override
    public boolean canUse(PlayerEntity player) { return player == owner && !owner.isRemoved(); }
}

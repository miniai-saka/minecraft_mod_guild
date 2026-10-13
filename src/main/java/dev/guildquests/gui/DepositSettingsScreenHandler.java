package dev.guildquests.gui;

import dev.guildquests.block.GuildDepositBlockEntity;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.item.ModItems;
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

public final class DepositSettingsScreenHandler extends ScreenHandler {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayerEntity player, int button, SlotActionType actionType);
    }

    private static final int DISPLAY_SLOTS = 9;
    private final SimpleInventory display = new SimpleInventory(DISPLAY_SLOTS);
    private final ServerPlayerEntity owner;
    private final GuildDepositBlockEntity deposit;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    public DepositSettingsScreenHandler(int syncId, PlayerInventory playerInventory,
                                        ServerPlayerEntity owner, GuildDepositBlockEntity deposit) {
        super(ScreenHandlerType.GENERIC_9X1, syncId);
        this.owner = owner;
        this.deposit = deposit;

        for (int col = 0; col < DISPLAY_SLOTS; col++) {
            addSlot(new Slot(display, col, 8 + col * 18, 35) {
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

    public static void open(ServerPlayerEntity player, GuildDepositBlockEntity deposit) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, ignored) ->
                        new DepositSettingsScreenHandler(syncId, playerInventory, player, deposit),
                Text.literal("入金ブロック設定")
        ));
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        if (player != owner || owner.isRemoved()) return false;
        return deposit.getWorld() != null
                && deposit.getWorld().getBlockEntity(deposit.getPos()) == deposit;
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
    public ItemStack transferSlot(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    private void populate() {
        actions.clear();
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        PlayerGuildData data = GuildService.data(owner);
        long held = GuildService.countHeldCoins(owner);

        set(0, item(Items.GOLD_BLOCK, "入金ブロック設定",
                "設置者: " + deposit.getOwnerName(),
                "残高: " + data.coins,
                "所持コイン: " + held));

        // Notification toggle.
        boolean notify = deposit.isNotifyOnDeposit();
        setAction(1, item(notify ? Items.LIME_CONCRETE : Items.GRAY_CONCRETE,
                        (notify ? "● " : "") + "入金通知: " + (notify ? "ON" : "OFF"),
                        "クリックで切り替え",
                        notify ? "入金されるとチャットで通知します" : "入金通知は無効です"),
                (p, b, t) -> {
                    if (b == 0) deposit.setNotifyOnDeposit(!deposit.isNotifyOnDeposit());
                });

        depositButton(2, 1, held);
        depositButton(3, 10, held);
        depositButton(4, 64, held);
        depositAllButton(5, held);

        setAction(8, item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeHandledScreen());
    }

    private void depositButton(int slot, int amount, long held) {
        String label = "預け入れ: " + amount;
        if (held <= 0) {
            setAction(slot, item(ModItems.COIN, label, "所持コインがありません"),
                    (p, b, t) -> GuildService.depositHeldCoinsTo(p, deposit.getOwnerId(), deposit.getOwnerName(), amount));
            return;
        }
        int take = (int) Math.min(amount, held);
        setAction(slot, item(ModItems.COIN, label,
                        "クリックで預け入れ",
                        "手持ち -" + take + " → 残高 +" + take,
                        "手数料: 無料"),
                (p, b, t) -> {
                    if (b == 0) GuildService.depositHeldCoinsTo(p, deposit.getOwnerId(), deposit.getOwnerName(), amount);
                });
    }

    private void depositAllButton(int slot, long held) {
        String label = "預け入れ: 全部";
        if (held <= 0) {
            setAction(slot, item(ModItems.COIN, label, "所持コインがありません"),
                    (p, b, t) -> GuildService.depositHeldCoinsTo(p, deposit.getOwnerId(), deposit.getOwnerName(), Long.MAX_VALUE));
            return;
        }
        setAction(slot, item(ModItems.COIN, label,
                        "クリックで全部預け入れ",
                        "手持ち -" + held + " → 残高 +" + held,
                        "手数料: 無料"),
                (p, b, t) -> {
                    if (b == 0) GuildService.depositHeldCoinsTo(p, deposit.getOwnerId(), deposit.getOwnerName(), Long.MAX_VALUE);
                });
    }

    private void set(int slot, ItemStack stack) {
        display.setStack(slot, stack);
    }

    private void setAction(int slot, ItemStack stack, MenuAction action) {
        set(slot, stack);
        actions.put(slot, action);
    }

    private static ItemStack item(Item item, String name, String... lore) {
        return GuildItems.item(item, name, lore);
    }


}

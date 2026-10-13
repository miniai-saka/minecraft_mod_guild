package dev.guildquests.gui;

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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TransferScreenHandler extends ScreenHandler {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayerEntity player, int button, SlotActionType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;
    private final SimpleInventory display = new SimpleInventory(DISPLAY_SLOTS);
    private final ServerPlayerEntity owner;
    private final Map<Integer, MenuAction> actions = new HashMap<>();
    /** Currently selected recipient; null = unselected. */
    private UUID transferTargetId;
    /** Amount to send, 0..9999, composed via digit heads and paper buttons. */
    private long transferAmount;

    public TransferScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity owner) {
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
                (syncId, playerInventory, ignored) -> new TransferScreenHandler(syncId, playerInventory, player),
                Text.literal("ギルド送金")
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
        for (int i = 0; i < DISPLAY_SLOTS; i++) display.setStack(i, item(Items.GRAY_STAINED_GLASS_PANE, " "));

        PlayerGuildData data = GuildService.data(owner);
        long balance = data.coins;
        if (transferAmount > 9999) transferAmount = 9999;
        ServerPlayerEntity target = currentTransferTarget();
        List<ServerPlayerEntity> candidates = transferCandidates();

        // Columns 0-3 (slots 0-3, 9-12, 18-21): player heads for recipient selection.
        for (int i = 0; i < 12; i++) {
            int slot = headSlot(i);
            if (i < candidates.size()) {
                ServerPlayerEntity candidate = candidates.get(i);
                boolean selected = target != null && target.getUuid().equals(candidate.getUuid());
                String name = candidate.getName().getString();
                UUID id = candidate.getUuid();
                setAction(slot, GuildItems.headItem(name, id, (selected ? "● " : "") + name,
                                selected ? "現在の送金先" : "クリックで送金先に選択",
                                "残高: " + GuildService.data(candidate).coins),
                        (p, b, t) -> {
                            if (b == 0) transferTargetId = id;
                        });
            } else {
                set(slot, item(Items.PLAYER_HEAD, " "));
            }
        }
        if (candidates.isEmpty()) {
            set(0, item(Items.PLAYER_HEAD, "オンラインの他プレイヤーがいません",
                    "送金先は同じサーバーの",
                    "オンラインプレイヤーのみ"));
        }

        // Column 4 (slots 4, 13, 22): center info / close.
        set(4, item(ModItems.COIN, "所持コイン: " + GuildService.countHeldCoins(owner),
                "銀行残高: " + balance,
                "送金は銀行残高から引き落とし",
                "手数料は無料"));
        set(13, item(Items.PAPER, "送金予定額: " + transferAmount,
                target == null ? "送金先: 未選択（左の頭を選択）" : "送金先: " + target.getName().getString(),
                "金額は右の紙・数字頭で調整",
                "最大 9999 まで"));

        // Confirm button at bottom-right (slot 26).
        if (target == null) {
            setAction(26, item(Items.LIME_CONCRETE, "送金決定",
                            "送金先が選ばれていません",
                            "左のプレイヤー頭から選択してください"),
                    (p, b, t) -> { });
        } else if (transferAmount <= 0) {
            setAction(26, item(Items.LIME_CONCRETE, "送金決定",
                            "金額が 0 です",
                            "紙ボタンまたは数字頭で金額を設定"),
                    (p, b, t) -> { });
        } else if (balance < transferAmount) {
            setAction(26, item(Items.LIME_CONCRETE, "送金決定",
                            "銀行残高が不足しています（残高: " + balance + "）",
                            "送金先: " + target.getName().getString()),
                    (p, b, t) -> { });
        } else {
            String name = target.getName().getString();
            UUID id = target.getUuid();
            long amount = transferAmount;
            setAction(26, item(Items.LIME_CONCRETE, "送金決定: " + amount,
                            "クリックで " + name + " へ送金",
                            "残高 -" + amount + " → " + name + " +" + amount,
                            "手数料: 無料"),
                    (p, b, t) -> {
                        if (b == 0) send(p, id, amount);
                    });
        }

        // Row 0, columns 5-8 (slots 5-8): digit heads (thousands, hundreds, tens, ones).
        int[] places = {1000, 100, 10, 1};
        String[] labels = {"千の位", "百の位", "十の位", "一の位"};
        for (int i = 0; i < 4; i++) {
            int place = places[i];
            int digit = (int) ((transferAmount / place) % 10);
            setAction(5 + i, digitHead(digit, labels[i]),
                    (p, b, t) -> {
                        if (b == 0) {
                            transferAmount = Math.floorMod(transferAmount + place, 10000);
                        } else if (b == 1) {
                            transferAmount = Math.floorMod(transferAmount - place, 10000);
                        }
                    });
        }

        // Row 1, columns 5-8 (slots 14-17): paper buttons to add amounts.
        addPaper(14, 1);
        addPaper(15, 10);
        addPaper(16, 100);
        addPaper(17, 64);

        // Row 2, column 7: clear amount.
        setAction(25, item(Items.BARRIER, "金額クリア"),
                (p, b, t) -> {
                    if (b == 0) transferAmount = 0;
                });

        // Row 2, column 4: close.
        setAction(22, item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeHandledScreen());
    }

    /** Map a 0..11 head index to its display slot (columns 0-3, all three rows). */
    private static int headSlot(int index) {
        int row = index / 4;
        int col = index % 4;
        return col + row * 9;
    }

    private void addPaper(int slot, int amount) {
        long preview = Math.min(9999, transferAmount + amount);
        setAction(slot, item(Items.PAPER, "＋" + amount,
                        "クリックで " + amount + " 加算",
                        "現在: " + transferAmount + " → " + preview),
                (p, b, t) -> {
                    if (b == 0) transferAmount = Math.min(9999, transferAmount + amount);
                });
    }

    static ItemStack digitHead(int digit, String placeLabel) {
        return GuildItems.digitHead(digit, placeLabel);
    }

    /** Online players other than the owner, sorted by name. */
    private List<ServerPlayerEntity> transferCandidates() {
        List<ServerPlayerEntity> list = new ArrayList<>();
        for (ServerPlayerEntity p : owner.getServer().getPlayerManager().getPlayerList()) {
            if (p != owner && !p.isRemoved()) list.add(p);
        }
        list.sort(Comparator.comparing(p -> p.getName().getString()));
        return list;
    }

    private ServerPlayerEntity findPlayer(UUID id) {
        if (id == null) return null;
        for (ServerPlayerEntity p : transferCandidates()) {
            if (p.getUuid().equals(id)) return p;
        }
        return null;
    }

    private ServerPlayerEntity currentTransferTarget() {
        return findPlayer(transferTargetId);
    }

    /** Resolve the recipient at click time so a logged-off player is never used. */
    private void send(ServerPlayerEntity sender, UUID targetId, long amount) {
        ServerPlayerEntity target = findPlayer(targetId);
        if (target == null) {
            sender.sendMessage(Text.literal("送金先がオンラインではありません").formatted(Formatting.RED), false);
            return;
        }
        GuildService.transferCoins(sender, target, amount);
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

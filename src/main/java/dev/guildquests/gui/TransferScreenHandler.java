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
        long held = GuildService.countHeldCoins(owner);
        ServerPlayerEntity target = currentTransferTarget();

        set(0, item(ModItems.COIN, "所持コイン: " + held,
                "銀行残高: " + data.coins,
                "送金は銀行残高から引き落とします"));

        recipientButton(1, target);

        set(8, item(Items.LIME_CONCRETE, "手数料: 無料",
                "プレイヤー間の送金は",
                "手数料がかかりません"));

        sendButton(4, 1, data.coins, target);
        sendButton(5, 10, data.coins, target);
        sendButton(6, 64, data.coins, target);
        sendAllButton(7, data.coins, target);

        setAction(13, item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeHandledScreen());

        // Row 1: direct-select heads for online players (first 9 by name).
        List<ServerPlayerEntity> candidates = transferCandidates();
        for (int i = 0; i < Math.min(9, candidates.size()); i++) {
            ServerPlayerEntity candidate = candidates.get(i);
            boolean selected = target != null && target.getUuid().equals(candidate.getUuid());
            String name = candidate.getName().getString();
            UUID id = candidate.getUuid();
            setAction(9 + i, headItem(name, id, (selected ? "● " : "") + name,
                            selected ? "現在の送金先" : "クリックで送金先に選択",
                            "残高: " + GuildService.data(candidate).coins),
                    (p, b, t) -> {
                        if (b == 0) transferTargetId = id;
                    });
        }
        if (candidates.isEmpty()) {
            set(9, item(Items.PLAYER_HEAD, "オンラインの他プレイヤーがいません",
                    "送金先は同じサーバーの",
                    "オンラインプレイヤーのみ"));
        } else if (candidates.size() > 9) {
            set(17, item(Items.COMPARATOR, "送金先: " + candidates.size() + "人",
                    "上段の送金先ピッカーで",
                    "全員から選択できます"));
        }

        set(22, item(Items.WRITABLE_BOOK, "ギルド送金",
                "① プレイヤー頭か送金先ピッカーで宛先を選択",
                "② 金額ボタン（1 / 10 / 64 / 全部）で送金",
                "③ 残高から即時引き落とし、相手に加算",
                "手数料は無料、延滞中は送金できません"));
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

    private void recipientButton(int slot, ServerPlayerEntity target) {
        List<ServerPlayerEntity> candidates = transferCandidates();
        if (candidates.isEmpty()) {
            set(slot, item(Items.PLAYER_HEAD, "送金先: なし",
                    "オンラインの他のプレイヤーがいません"));
            return;
        }
        String name = target == null ? "未選択" : target.getName().getString();
        ItemStack head = target == null
                ? item(Items.PLAYER_HEAD, "送金先: 未選択",
                        "左クリックで送金先を切り替え",
                        "オンライン: " + candidates.size() + "人",
                        "下段の頭アイコンから直接選択も可能")
                : headItem(target.getName().getString(), target.getUuid(), "送金先: " + name,
                        "左クリックで送金先を切り替え",
                        "オンライン: " + candidates.size() + "人",
                        "下段の頭アイコンから直接選択も可能");
        setAction(slot, head,
                (p, b, t) -> {
                    if (b != 0) return;
                    List<ServerPlayerEntity> list = transferCandidates();
                    if (list.isEmpty()) return;
                    int idx = -1;
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).getUuid().equals(transferTargetId)) {
                            idx = i;
                            break;
                        }
                    }
                    transferTargetId = list.get((idx + 1) % list.size()).getUuid();
                });
    }

    private void sendButton(int slot, long amount, long balance, ServerPlayerEntity target) {
        String label = "送金: " + amount;
        if (target == null) {
            setAction(slot, item(Items.PAPER, label,
                            "送金先が選ばれていません",
                            "プレイヤー頭アイコンで送金先を選んでください"),
                    (p, b, t) -> { });
            return;
        }
        String name = target.getName().getString();
        UUID id = target.getUuid();
        if (balance < amount) {
            setAction(slot, item(Items.PAPER, label,
                            "銀行残高が不足しています（残高: " + balance + "）",
                            "送金先: " + name),
                    (p, b, t) -> send(p, id, amount));
            return;
        }
        setAction(slot, item(Items.PAPER, label,
                        "クリックで " + name + " へ送金",
                        "残高 -" + amount + " → " + name + " +" + amount,
                        "手数料: 無料"),
                (p, b, t) -> {
                    if (b == 0) send(p, id, amount);
                });
    }

    private void sendAllButton(int slot, long balance, ServerPlayerEntity target) {
        String label = "送金: 全部";
        if (target == null) {
            setAction(slot, item(Items.PAPER, label,
                            "送金先が選ばれていません",
                            "プレイヤー頭アイコンで送金先を選んでください"),
                    (p, b, t) -> { });
            return;
        }
        String name = target.getName().getString();
        UUID id = target.getUuid();
        if (balance <= 0) {
            setAction(slot, item(Items.PAPER, label, "銀行残高がありません", "送金先: " + name),
                    (p, b, t) -> send(p, id, 0));
            return;
        }
        setAction(slot, item(Items.PAPER, label,
                        "クリックで全部送金（残高 " + balance + "）",
                        name + " に +" + balance + " 追加",
                        "手数料: 無料"),
                (p, b, t) -> {
                    if (b == 0) send(p, id, balance);
                });
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
        ItemStack stack = new ItemStack(item);
        stack.setCustomName(Text.literal(name).formatted(Formatting.WHITE));
        Text[] lines = new Text[lore.length];
        for (int i = 0; i < lore.length; i++) lines[i] = Text.literal(lore[i]).formatted(Formatting.GRAY);
        setLore(stack, lines);
        return stack;
    }

    /** Player head showing the owner's skin (resolved client-side from the profile id). */
    private static ItemStack headItem(String ownerName, UUID ownerId, String name, String... lore) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        NbtCompound skull = stack.getOrCreateSubNbt("SkullOwner");
        skull.putString("Id", ownerId.toString());
        skull.putString("Name", ownerName);
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

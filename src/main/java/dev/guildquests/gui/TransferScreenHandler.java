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

        // Columns 1-3 (slots 1-3, 10-12, 19-21): player heads for recipient selection.
        // Column 0 is a blank gutter so the row starts with the heads after it.
        for (int i = 0; i < 9; i++) {
            int slot = headSlot(i);
            if (i < candidates.size()) {
                ServerPlayerEntity candidate = candidates.get(i);
                boolean selected = target != null && target.getUuid().equals(candidate.getUuid());
                String name = candidate.getName().getString();
                UUID id = candidate.getUuid();
                setAction(slot, headItem(name, id, (selected ? "● " : "") + name,
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
            set(1, item(Items.PLAYER_HEAD, "オンラインの他プレイヤーがいません",
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

    /** Map a 0..8 head index to its display slot (columns 1-3, all three rows). */
    private static int headSlot(int index) {
        int row = index / 3;
        int col = 1 + index % 3;
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

    /** mcheads.ru "Quartz 0".."Quartz 9" texture payloads (index = digit). */
    private static final String[] DIGIT_TEXTURES = {
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWY4ODZkOWM0MGVmN2Y1MGMyMzg4MjQ3OTJjNDFmYmZiNTRmNjY1ZjE1OWJmMWJjYjBiMjdiM2VhZDM3M2IifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTBhMTllMjNkMjFmMmRiMDYzY2M1NWU5OWFlODc0ZGM4YjIzYmU3NzliZTM0ZTUyZTdjN2I5YTI1In19fQ==",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2M1OTZhNDFkYWVhNTFiZTJlOWZlYzdkZTJkODkwNjhlMmZhNjFjOWQ1N2E4YmRkZTQ0YjU1OTM3YjYwMzcifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjg1ZDRmZGE1NmJmZWI4NTEyNDQ2MGZmNzJiMjUxZGNhOGQxZGViNjU3ODA3MGQ2MTJiMmQzYWRiZjVhOCJ9fX0=",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzg1MmEyNWZlNjljYTg2ZmI5ODJmYjNjYzdhYzk3OTNmNzM1NmI1MGI5MmNiMGUxOTNkNmI0NjMyYTliZDYyOSJ9fX0=",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzRlZTdkOTU0ZWIxNGE1Y2NkMzQ2MjY2MjMxYmY5YTY3MTY1MjdiNTliYmNkNzk1NmNlZjA0YTlkOWIifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjY4MmEzYWU5NDgzNzRlMDM3ZTNkN2RkNjg3ZDU5ZDE4NWRkMmNjOGZjMDlkZmViNDJmOThmOGQyNTllNWMzIn19fQ==",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGVhMzBjMjRjNjBiM2JjMWFmNjU4ZWY2NjFiNzcxYzQ4ZDViOWM5ZTI4MTg4Y2Y5ZGU5ZjgzMjQyMmU1MTAifX19",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjZhYmFmZDAyM2YyMzBlNDQ4NWFhZjI2ZTE5MzY4ZjU5ODBkNGYxNGE1OWZjYzZkMTFhNDQ2Njk5NDg5MiJ9fX0=",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGQ3OTEwZTEwMzM0Zjg5MGE2MjU0ODNhYzBjODI0YjVlNGExYTRiMTVhOTU2MzI3YTNlM2FlNDU4ZDllYTQifX19"
    };

    static ItemStack digitHead(int digit, String placeLabel) {
        return headItemWithTexture("digit", new UUID(0, digit), DIGIT_TEXTURES[digit],
                "送金額 " + placeLabel + ": " + digit,
                "左クリックで +1（0-9 で循環）",
                "右クリックで -1");
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
        ItemStack stack = new ItemStack(item);
        stack.setCustomName(Text.literal(name).formatted(Formatting.WHITE));
        Text[] lines = new Text[lore.length];
        for (int i = 0; i < lore.length; i++) lines[i] = Text.literal(lore[i]).formatted(Formatting.GRAY);
        setLore(stack, lines);
        return stack;
    }

    /** Player head showing the owner's skin (resolved client-side from the profile id). */
    private static ItemStack headItem(String ownerName, UUID ownerId, String name, String... lore) {
        return headItemWithTexture(ownerName, ownerId, null, name, lore);
    }

    /**
     * Player head with an optional custom texture. When {@code textureValue} is non-null it is written as
     * SkullOwner.Properties.Textures so the client renders that exact texture instead of resolving the id.
     */
    private static ItemStack headItemWithTexture(String ownerName, UUID ownerId, String textureValue, String name, String... lore) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        NbtCompound skull = stack.getOrCreateSubNbt("SkullOwner");
        skull.putString("Id", ownerId.toString());
        skull.putString("Name", ownerName);
        if (textureValue != null) {
            NbtCompound property = new NbtCompound();
            property.putString("Value", textureValue);
            NbtList textures = new NbtList();
            textures.add(property);
            NbtCompound properties = new NbtCompound();
            properties.put("textures", textures);
            skull.put("Properties", properties);
        }
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

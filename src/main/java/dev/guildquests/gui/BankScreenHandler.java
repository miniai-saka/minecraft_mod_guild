package dev.guildquests.gui;

import dev.guildquests.config.ConfigManager;
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

import java.util.HashMap;
import java.util.Map;

public final class BankScreenHandler extends ScreenHandler {
    @FunctionalInterface
    private interface MenuAction {
        void run(ServerPlayerEntity player, int button, SlotActionType actionType);
    }

    private static final int DISPLAY_SLOTS = 27;
    private final SimpleInventory display = new SimpleInventory(DISPLAY_SLOTS);
    private final ServerPlayerEntity owner;
    private final boolean feeWaived;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    public BankScreenHandler(int syncId, PlayerInventory playerInventory, ServerPlayerEntity owner, boolean feeWaived) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.owner = owner;
        this.feeWaived = feeWaived;

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
        open(player, false);
    }

    public static void openFromBlock(ServerPlayerEntity player) {
        open(player, true);
    }

    private static void open(ServerPlayerEntity player, boolean feeWaived) {
        String title = feeWaived ? "ギルド銀行（手数料無料）" : "ギルド銀行";
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, ignored) -> new BankScreenHandler(syncId, playerInventory, player, feeWaived),
                Text.literal(title)
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

        set(0, item(ModItems.COIN, "所持コイン: " + held, "インベントリ内のギルドコイン"));
        set(4, item(Items.GOLD_INGOT, "銀行残高: " + data.coins, "電子通貨として保存されています"));
        set(8, feeWaived
                ? item(Items.EMERALD, "手数料: 無料", "ギルド銀行ブロック経由のため", "すべての取引が無料です")
                : item(Items.PAPER, "手数料: " + feePercentText() + "%",
                        "預け入れ・引き出し時に課金されます",
                        "ギルド銀行ブロック経由なら無料です"));

        depositButton(9, 1, held);
        depositButton(10, 10, held);
        depositButton(11, 64, held);
        depositAllButton(12, held);

        setAction(13, item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeHandledScreen());

        withdrawButton(14, 1, data.coins);
        withdrawButton(15, 10, data.coins);
        withdrawButton(16, 64, data.coins);
        withdrawAllButton(17, data.coins);

        // Player-to-player transfer lives in its own menu.
        setAction(5, item(Items.PLAYER_HEAD, "送金（新メニュー）",
                "クリックで送金メニューを開きます",
                "送金先の選択・金額指定はこちらで",
                "手数料: 無料"),
                (p, b, t) -> TransferScreenHandler.open(p));

        String mode = GuildService.paymentMode(data);
        paymentModeButton(18, GuildService.MODE_RIBO, "リボ", mode);
        paymentModeButton(19, GuildService.MODE_DEBIT, "引き落とし", mode);
        paymentModeButton(20, GuildService.MODE_DISABLED, "無効", mode);
        debtDisplay(24, data);

        set(22, item(Items.WRITABLE_BOOK, "ギルド銀行",
                "左上: 預け入れ / 右上: 引き出し",
                "金額: 1 / 10 / 64 / 全部",
                "送金: 「送金（新メニュー）」から開きます",
                "下段: 支払い方法（リボ/引き落とし/無効）",
                "リボは定期自動徴収・延滞でロック"));
    }

    private void paymentModeButton(int slot, String id, String label, String current) {
        boolean active = id.equals(current);
        Item icon = active ? Items.LIME_CONCRETE : Items.GRAY_CONCRETE;
        String name = (active ? "● " : "") + "支払い: " + label;
        String[] lore = switch (id) {
            case GuildService.MODE_RIBO -> new String[]{
                    "クリックでリボ払いに切替",
                    "残高がなくても購入でき、価格は債務化",
                    "利息 " + ConfigManager.config().riboInterestPercent + "% / "
                            + ConfigManager.config().riboIntervalMinutes + "分ごとに "
                            + ConfigManager.config().riboInstallment + " コインを自動徴収",
                    "返済不能で延滞ロック → クエストで返済"
            };
            case GuildService.MODE_DISABLED -> new String[]{
                    "クリックで無効に切替",
                    "残高からの支払いを禁止",
                    "手持ちのコインのみで支払う"
            };
            default -> new String[]{
                    "クリックで引き落としに切替",
                    "購入時に残高から即時引き落とし",
                    "コインはストックティッカーに入金され",
                    "店主が引き出せる"
            };
        };
        setAction(slot, item(icon, name, lore), (p, b, t) -> {
            if (b == 0) GuildService.setPaymentMode(p, id);
        });
    }

    private void debtDisplay(int slot, PlayerGuildData data) {
        if (data.debt > 0) {
            long minutesLeft = Math.max(0, (data.nextRiboDueAt - System.currentTimeMillis()) / 60_000L);
            if (data.delinquent) {
                set(slot, item(Items.REDSTONE_BLOCK, "リボ債務: " + data.debt,
                        "⚠ 延滞中：ギルド機能ロック中",
                        "クエスト報酬は全額自動で返済に充当",
                        "完済するまで銀行・残高支払い不可"));
            } else {
                set(slot, item(Items.REDSTONE, "リボ債務: " + data.debt,
                        "次回の自動徴収まで約 " + minutesLeft + " 分",
                        "残高から " + ConfigManager.config().riboInstallment + " コインを定期徴収",
                        "利息 " + ConfigManager.config().riboInterestPercent + "% が周期ごとに加算"));
            }
        } else {
            set(slot, item(Items.GOLD_NUGGET, "リボ債務: なし",
                    "支払い方法をリボにすると",
                    "後払い購入（債務）が使える"));
        }
    }

    private void depositButton(int slot, long amount, long held) {
        String label = "預け入れ: " + amount;
        if (held <= 0) {
            setAction(slot, item(ModItems.COIN, label, "所持コインがありません"),
                    (p, b, t) -> GuildService.bankDeposit(p, amount, feeWaived));
            return;
        }
        long take = Math.min(amount, held);
        long fee = GuildService.bankFee(take, feeWaived);
        setAction(slot, item(ModItems.COIN, label,
                        "クリックで預け入れ",
                        "手持ち -" + take + " → 残高 +" + (take - fee),
                        feeLine(fee)),
                (p, b, t) -> {
                    if (b == 0) GuildService.bankDeposit(p, amount, feeWaived);
                });
    }

    private void depositAllButton(int slot, long held) {
        String label = "預け入れ: 全部";
        if (held <= 0) {
            setAction(slot, item(ModItems.COIN, label, "所持コインがありません"),
                    (p, b, t) -> GuildService.bankDeposit(p, 0, feeWaived));
            return;
        }
        long fee = GuildService.bankFee(held, feeWaived);
        setAction(slot, item(ModItems.COIN, label,
                        "クリックで全部預け入れ",
                        "手持ち -" + held + " → 残高 +" + (held - fee),
                        feeLine(fee)),
                (p, b, t) -> {
                    if (b == 0) GuildService.bankDeposit(p, 0, feeWaived);
                });
    }

    private void withdrawButton(int slot, long amount, long balance) {
        String label = "引き出し: " + amount;
        if (balance < amount) {
            setAction(slot, item(Items.GOLD_NUGGET, label, "残高が不足しています"),
                    (p, b, t) -> GuildService.bankWithdraw(p, amount, feeWaived));
            return;
        }
        long fee = GuildService.bankFee(amount, feeWaived);
        setAction(slot, item(Items.GOLD_NUGGET, label,
                        "クリックで引き出し",
                        "残高 -" + amount + " → 手持ち +" + (amount - fee),
                        feeLine(fee)),
                (p, b, t) -> {
                    if (b == 0) GuildService.bankWithdraw(p, amount, feeWaived);
                });
    }

    private void withdrawAllButton(int slot, long balance) {
        String label = "引き出し: 全部";
        if (balance <= 0) {
            setAction(slot, item(Items.GOLD_NUGGET, label, "残高がありません"),
                    (p, b, t) -> GuildService.bankWithdraw(p, 0, feeWaived));
            return;
        }
        long amount = Math.min(balance, Integer.MAX_VALUE);
        long fee = GuildService.bankFee(amount, feeWaived);
        setAction(slot, item(Items.GOLD_NUGGET, label,
                        "クリックで全部引き出し",
                        "残高 -" + amount + " → 手持ち +" + (amount - fee),
                        feeLine(fee)),
                (p, b, t) -> {
                    if (b == 0) GuildService.bankWithdraw(p, amount, feeWaived);
                });
    }

    private String feeLine(long fee) {
        if (feeWaived) return "手数料: 無料（ブロック経由）";
        return fee > 0 ? "手数料: -" + fee + " コイン" : "手数料: 0 コイン";
    }

    private String feePercentText() {
        double pct = ConfigManager.config().bankFeePercent;
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

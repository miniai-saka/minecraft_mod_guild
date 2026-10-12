package dev.guildquests.gui;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.GuildPersistentState;
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
    /** Amount selected via digit heads / paper buttons, 0..Integer.MAX_VALUE. */
    private long bankAmount;

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
        long balance = data.coins;
        if (bankAmount < 0) bankAmount = 0;

        // Columns 0-2, row 0: bank balance / fee / transfer menu.
        set(0, item(Items.GOLD_INGOT, "銀行残高: " + balance, "電子通貨として保存されています"));
        set(1, feeWaived
                ? item(Items.EMERALD, "手数料: 無料", "ギルド銀行ブロック経由のため", "すべての取引が無料です")
                : item(Items.PAPER, "手数料: " + feePercentText() + "%",
                        "預け入れ・引き出し時に課金されます",
                        "ギルド銀行ブロック経由なら無料です"));
        setAction(2, item(Items.PLAYER_HEAD, "送金（新メニュー）",
                "クリックで送金メニューを開きます",
                "送金先の選択・金額指定はこちらで",
                "手数料: 無料"),
                (p, b, t) -> TransferScreenHandler.open(p));

        // Column 3, row 0: held coins.
        set(3, item(ModItems.COIN, "所持コイン: " + held, "インベントリ内のギルドコイン"));

        // Row 0, columns 4-7: digit heads (thousands, hundreds, tens, ones).
        int[] places = {1000, 100, 10, 1};
        String[] labels = {"千の位", "百の位", "十の位", "一の位"};
        for (int i = 0; i < 4; i++) {
            long place = places[i];
            long digit = (bankAmount / place) % 10;
            setAction(4 + i, TransferScreenHandler.digitHead((int) digit, labels[i]),
                    (p, b, t) -> {
                        if (b != 0 && b != 1) return;
                        long cur = (bankAmount / place) % 10;
                        long next = Math.floorMod(cur + (b == 0 ? 1 : -1), 10);
                        bankAmount = bankAmount - cur * place + next * place;
                    });
        }

        // Row 0, column 8: guild rank summary.
        set(8, item(Items.NETHER_STAR, "ギルドランク: " + GuildService.rank(data),
                "累計達成: " + data.completedTotal + " 件",
                "同時受注上限: " + ConfigManager.config().maxActiveQuests + " 件"));

        // Columns 0-2, row 1: payment modes.
        String mode = GuildService.paymentMode(data);
        paymentModeButton(9, GuildService.MODE_RIBO, "リボ", mode);
        paymentModeButton(10, GuildService.MODE_DEBIT, "引き落とし", mode);
        paymentModeButton(11, GuildService.MODE_DISABLED, "無効", mode);

        // Column 3, row 1: selected amount summary.
        long fee = GuildService.bankFee(bankAmount, feeWaived);
        set(12, item(Items.PAPER, "入出金額: " + bankAmount,
                "入金: 手持ち -" + bankAmount + " → 残高 +" + (bankAmount - fee),
                "出金: 残高 -" + bankAmount + " → 手持ち +" + (bankAmount - fee),
                feeLine(fee),
                "数字頭・紙で金額を調整"));

        // Row 1, columns 4-7: paper buttons to add amounts.
        addPaper(13, 1);
        addPaper(14, 10);
        addPaper(15, 100);
        addPaper(16, 64);

        // Row 1, column 8: active quest count.
        set(17, item(Items.BOOK, "受注中クエスト: " + data.activeQuests.size() + " 件",
                "同時受注上限: " + ConfigManager.config().maxActiveQuests + " 件",
                "内容はギルド画面（/guild）で確認できます"));

        // Columns 0-2, row 2: debt / help.
        debtDisplay(18, data);
        set(19, item(Items.WRITABLE_BOOK, "ギルド銀行",
                "数字頭・紙で金額を選択してから",
                "下段の入金・出金ボタンで実行",
                "所持金全部ボタンで全額をまとめて選択",
                "下段左: 支払い方法（リボ/引き落とし/無効）",
                "リボは定期自動徴収・延滞でロック"));

        // Row 2, column 2: today's quests.
        GuildService.ensureDailyBoard(owner.getServer());
        var board = GuildPersistentState.get(owner.getServer()).dailyBoard;
        set(20, item(Items.LECTERN, "本日のクエスト: " + board.size() + " 問",
                "次の抽選まで: " + GuildService.formatGameTime(GuildService.ticksUntilNextDraw(owner.getServer())),
                "受注はギルド画面（/guild）から"));

        // Column 3, row 2: close.
        setAction(21, item(Items.BARRIER, "閉じる"), (p, b, t) -> p.closeHandledScreen());

        // Row 2, column 4: select the full bank balance (for withdrawal).
        setAction(22, item(Items.GOLD_BLOCK, "銀行残高全部選択",
                        "クリックで銀行残高全額を選択",
                        "現在: " + bankAmount + " → " + balance),
                (p, b, t) -> {
                    if (b == 0) bankAmount = Math.min(balance, Integer.MAX_VALUE);
                });

        // Row 2, columns 5-8: clear / select all held / deposit / withdraw.
        setAction(23, item(Items.BARRIER, "金額クリア"),
                (p, b, t) -> {
                    if (b == 0) bankAmount = 0;
                });
        setAction(24, item(ModItems.COIN, "所持金全部選択",
                        "クリックで所持コイン全額を選択",
                        "現在: " + bankAmount + " → " + held),
                (p, b, t) -> {
                    if (b == 0) bankAmount = Math.min(held, Integer.MAX_VALUE);
                });
        depositAction(25, held);
        withdrawAction(26, balance);
    }

    private void depositAction(int slot, long held) {
        if (bankAmount <= 0) {
            setAction(slot, item(ModItems.COIN, "入金",
                            "金額が 0 です",
                            "数字頭または紙で金額を選択"),
                    (p, b, t) -> { });
            return;
        }
        if (bankAmount > held) {
            setAction(slot, item(ModItems.COIN, "入金",
                            "所持コインが不足しています",
                            "所持: " + held + " / 選択: " + bankAmount),
                    (p, b, t) -> { });
            return;
        }
        long fee = GuildService.bankFee(bankAmount, feeWaived);
        setAction(slot, item(ModItems.COIN, "入金: " + bankAmount,
                        "クリックで預け入れ",
                        "手持ち -" + bankAmount + " → 残高 +" + (bankAmount - fee),
                        feeLine(fee)),
                (p, b, t) -> {
                    if (b == 0) GuildService.bankDeposit(p, bankAmount, feeWaived);
                });
    }

    private void withdrawAction(int slot, long balance) {
        if (bankAmount <= 0) {
            setAction(slot, item(Items.GOLD_NUGGET, "出金",
                            "金額が 0 です",
                            "数字頭または紙で金額を選択"),
                    (p, b, t) -> { });
            return;
        }
        if (bankAmount > balance) {
            setAction(slot, item(Items.GOLD_NUGGET, "出金",
                            "銀行残高が不足しています",
                            "残高: " + balance + " / 選択: " + bankAmount),
                    (p, b, t) -> { });
            return;
        }
        long fee = GuildService.bankFee(bankAmount, feeWaived);
        setAction(slot, item(Items.GOLD_NUGGET, "出金: " + bankAmount,
                        "クリックで引き出し",
                        "残高 -" + bankAmount + " → 手持ち +" + (bankAmount - fee),
                        feeLine(fee)),
                (p, b, t) -> {
                    if (b == 0) GuildService.bankWithdraw(p, bankAmount, feeWaived);
                });
    }

    private void addPaper(int slot, int amount) {
        long preview = Math.min(Integer.MAX_VALUE, bankAmount + amount);
        setAction(slot, item(Items.PAPER, "＋" + amount,
                        "クリックで " + amount + " 加算",
                        "現在: " + bankAmount + " → " + preview),
                (p, b, t) -> {
                    if (b == 0) bankAmount = Math.min(Integer.MAX_VALUE, bankAmount + amount);
                });
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

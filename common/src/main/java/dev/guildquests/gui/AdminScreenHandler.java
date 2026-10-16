package dev.guildquests.gui;

import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.service.AdminLoanPolicy;
import dev.guildquests.service.GuildService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Vanilla chest menu; every selection, permission check and mutation lives on the server. */
public final class AdminScreenHandler extends AbstractContainerMenu {
    private final ServerPlayer owner;
    private final SimpleContainer display = new SimpleContainer(54);
    private final Map<Integer, Runnable> actions = new HashMap<>();
    private String view = "PLAYERS", operation = "", notice = "";
    private UUID target;
    private int page;
    private long amount, reviewedLoan;
    private boolean reviewedLock;

    public AdminScreenHandler(int id, net.minecraft.world.Container inventory, ServerPlayer owner) {
        super(MenuType.GENERIC_9x6, id); this.owner = owner;
        for (int i = 0; i < 54; i++) addSlot(new Slot(display, i, 8 + i % 9 * 18, 18 + i / 9 * 18) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 198));
        populate();
    }
    private static boolean allowed(ServerPlayer player) { return player.createCommandSourceStack().hasPermission(2); }
    public static void open(ServerPlayer player) {
        if (!allowed(player)) return;
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new AdminScreenHandler(id, inventory, player), Component.literal("ギルド管理 - 借入・滞納")));
    }
    private GuildPersistentState state() { return GuildPersistentState.get(owner.getServer()); }
    private PlayerGuildData data() { return state().get(target); }
    private String name(UUID id) {
        var online = owner.getServer().getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        String known = state().get(id).playerName;
        return known == null || known.isBlank() ? id.toString().substring(0, 8) : known;
    }
    private void set(int slot, Item item, String title, String... lore) { display.setItem(slot, GuildItems.item(item, title, lore)); }
    private void action(int slot, Item item, String title, Runnable action, String... lore) { set(slot, item, title, lore); actions.put(slot, action); }
    private void populate() {
        actions.clear();
        for (int i = 0; i < 54; i++) set(i, Items.GRAY_STAINED_GLASS_PANE, " ");
        if (view.equals("PLAYERS")) players();
        else {
            set(4, Items.PLAYER_HEAD, "対象: " + name(target), "UUID: " + target, "借入: " + data().loan + " GC", "FX・リボ債務: " + data().debt + " GC", "滞納: " + (data().delinquent ? "あり" : "なし"));
            switch (view) { case "DETAIL" -> detail(); case "AMOUNT" -> input(); case "CONFIRM" -> confirmation(); }
        }
        if (!notice.isEmpty()) set(0, Items.PAPER, notice);
    }
    private void players() {
        for (var online : owner.getServer().getPlayerList().getPlayers()) GuildService.data(online);
        List<UUID> ids = new ArrayList<>(state().players().keySet());
        ids.sort(Comparator.<UUID>comparingInt(id -> owner.getServer().getPlayerList().getPlayer(id) == null ? 1 : 0).thenComparing(this::name, String.CASE_INSENSITIVE_ORDER).thenComparing(UUID::toString));
        int pages = Math.max(1, (ids.size() + 26) / 27); page = Math.max(0, Math.min(page, pages - 1));
        set(4, Items.BOOK, "管理するプレイヤーを選択", "オンライン・保存済みプレイヤー", "ページ " + (page + 1) + " / " + pages, "権限: OPレベル2以上");
        for (int i = page * 27; i < Math.min(ids.size(), (page + 1) * 27); i++) {
            UUID id = ids.get(i); var wallet = state().get(id); int slot = 9 + i % 27;
            display.setItem(slot, GuildItems.playerHead(owner.getServer(), id, name(id), name(id),
                    owner.getServer().getPlayerList().getPlayer(id) == null ? "オフライン" : "オンライン", "借入: " + wallet.loan + " GC", "FX・リボ債務: " + wallet.debt + " GC", "滞納: " + (wallet.delinquent ? "あり" : "なし"), "クリックで管理"));
            actions.put(slot, () -> { target = id; view = "DETAIL"; notice = ""; });
        }
        if (page > 0) action(45, Items.ARROW, "前のページ", () -> page--);
        if (page + 1 < pages) action(53, Items.ARROW, "次のページ", () -> page++);
        action(49, Items.BARRIER, "閉じる", owner::closeContainer);
    }
    private void detail() {
        action(10, Items.EMERALD, "借入を追加", () -> beginAmount("ADD"), "loan add と同じ操作", "所持金は増やさず、借入だけ加算");
        action(12, Items.WRITABLE_BOOK, "借入を指定額に設定", () -> beginAmount("SET"), "loan set と同じ操作", "0以上の整数GCを入力");
        action(14, Items.TNT, "借入を帳消し", () -> review("CLEAR"), "loan clear と同じ操作", "FX・リボ債務は変更しません", "次の画面で確認して確定");
        set(22, Items.PAPER, "現在の管理状態", "借入: " + data().loan + " GC", "FX・リボ債務: " + data().debt + " GC",
                "滞納: " + (data().delinquent ? "あり" : "なし"), "管理者による固定ロック: " + (data().adminDelinquent ? "あり" : "なし"));
        action(29, Items.REDSTONE_TORCH, "滞納状態にする", () -> review("ON"), "delinquent on と同じ操作", "管理者が解除するまでロックを維持");
        action(33, Items.LIME_DYE, "滞納状態を解除", () -> review("OFF"), "delinquent off と同じ操作", "借入・FX債務の残高は変更しません", "後の返済失敗で再び滞納する場合があります");
        action(49, Items.ARROW, "プレイヤー選択へ戻る", () -> view = "PLAYERS");
    }
    private void beginAmount(String op) { operation = op; amount = 0; notice = ""; view = "AMOUNT"; }
    private void input() {
        set(13, Items.GOLD_INGOT, "入力額: " + amount + " GC", label(), "数字を順にクリックして入力", "最大 " + Long.MAX_VALUE + " GC");
        for (int digit = 1; digit <= 9; digit++) {
            int value = digit, slot = 20 + (digit - 1) / 3 * 9 + (digit - 1) % 3;
            action(slot, Items.WHITE_CONCRETE, Integer.toString(digit), () -> append(value));
            display.getItem(slot).setCount(digit);
        }
        action(48, Items.WHITE_CONCRETE, "0", () -> append(0));
        action(45, Items.RED_DYE, "入力を消去", () -> amount = 0);
        action(47, Items.ARROW, "1桁消す", () -> amount /= 10);
        action(16, Items.CHEST, "現在の借入額を入力", () -> amount = data().loan);
        action(50, Items.LIME_CONCRETE, "内容を確認", () -> review(operation));
        action(53, Items.BARRIER, "戻る", () -> view = "DETAIL");
    }
    private void append(int digit) {
        if (amount > (Long.MAX_VALUE - digit) / 10) { notice = "金額の上限を超えています。"; return; }
        amount = amount * 10 + digit; notice = "";
    }
    private String label() { return switch (operation) { case "ADD" -> "借入に加算"; case "SET" -> "借入を指定額へ変更"; case "CLEAR" -> "借入を帳消し"; case "ON" -> "滞納状態にする"; default -> "滞納状態を解除"; }; }
    private void review(String op) { operation = op; reviewedLoan = data().loan; reviewedLock = data().delinquent; view = "CONFIRM"; }
    private void confirmation() {
        long after = switch (operation) { case "ADD" -> reviewedLoan > Long.MAX_VALUE - amount ? Long.MAX_VALUE : reviewedLoan + amount; case "SET" -> amount; case "CLEAR" -> 0; default -> reviewedLoan; };
        set(22, Items.PAPER, "確認: " + label(), "対象: " + name(target), "借入 " + reviewedLoan + " → " + after + " GC",
                "滞納 " + (reviewedLock ? "あり" : "なし") + " → " + (operation.equals("ON") || !operation.equals("OFF") && reviewedLock ? "あり" : "なし"), "FX・リボ債務と所持金は変更しません");
        action(29, Items.ARROW, "戻る", () -> view = operation.equals("ADD") || operation.equals("SET") ? "AMOUNT" : "DETAIL");
        action(33, Items.LIME_CONCRETE, "確定する", this::apply, "対象と変更内容を確認してください");
    }
    private void apply() {
        if (data().loan != reviewedLoan || data().delinquent != reviewedLock) { notice = "状態が変わりました。再度確認してください。"; view = "DETAIL"; return; }
        long due = System.currentTimeMillis() + ConfigManager.config().consumerLoanIntervalMinutes * 60_000L;
        switch (operation) {
            case "ADD" -> AdminLoanPolicy.add(data(), amount, due);
            case "SET" -> AdminLoanPolicy.set(data(), amount, due);
            case "CLEAR" -> AdminLoanPolicy.clear(data());
            case "ON" -> AdminLoanPolicy.delinquent(data(), true);
            case "OFF" -> AdminLoanPolicy.delinquent(data(), false);
            default -> throw new IllegalStateException("Unknown admin operation");
        }
        state().setDirty();
        String result = name(target) + ": " + label() + " / 借入 " + data().loan + " GC / 滞納 " + (data().delinquent ? "あり" : "なし");
        owner.createCommandSourceStack().sendSuccess(() -> Component.literal("[Guild Admin] " + result), true);
        notice = "設定を反映しました。"; view = "DETAIL";
    }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (player != owner || owner.containerMenu != this || !allowed(owner)) { if (player == owner) owner.closeContainer(); return; }
        if (type != ClickType.PICKUP || button != 0 || slot < 0 || slot >= 54) return;
        Runnable action = actions.get(slot); if (action == null) return;
        action.run(); if (owner.containerMenu == this) { populate(); broadcastChanges(); }
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return player == owner && allowed(owner) && !owner.isRemoved(); }
}

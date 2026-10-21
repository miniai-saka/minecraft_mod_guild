package dev.guildquests.client;

import dev.guildquests.fx.FxModels.*;
import dev.guildquests.item.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.Locale;

/** Native Minecraft ticket, with three entry methods and full-position exits. */
public final class FxMinecraftScreen extends Screen {
    private Snapshot data;
    private long receivedAt = System.currentTimeMillis(), messageUntil;
    private String message = "";
    private boolean error;
    private Side side = Side.BUY;
    private OrderType type = OrderType.MARKET;
    private int tab, page, x, y, w, h;
    private EditBox units, leverage, price, amount;
    private Button placeOrder;
    private String modal = "";
    private Request pending;

    public FxMinecraftScreen(Snapshot snapshot) { super(Component.literal("Guild FX — 仮想通貨取引")); data = snapshot; }
    public void accept(Snapshot snapshot) {
        boolean changed = !data.selected.equals(snapshot.selected);
        boolean rowsChanged = data.account.positions.size() != snapshot.account.positions.size() || data.account.orders.size() != snapshot.account.orders.size();
        data = snapshot; receivedAt = System.currentTimeMillis();
        if (snapshot.message != null && !snapshot.message.isBlank()) {
            message = snapshot.message; error = snapshot.error; messageUntil = System.currentTimeMillis() + 6000;
        }
        if (price != null && (changed || type == OrderType.MARKET)) price.setValue(defaultPrice());
        if (changed || rowsChanged && tab != 0 && modal.isEmpty()) init();
        updateActions();
    }
    private Quote quote() { return data.quotes.stream().filter(q -> q.symbol.equals(data.selected)).findFirst().orElse(data.quotes.get(0)); }
    private String defaultPrice() {
        double value = side == Side.BUY ? quote().ask : quote().bid;
        if (type == OrderType.MARKET) return number(value);
        return number(value * (1 + (type == OrderType.STOP ? 1 : -1) * side.sign() * .005));
    }
    @Override protected void init() {
        String quantity = units == null ? "1" : units.getValue(), multiple = leverage == null ? "10" : leverage.getValue();
        String entryPrice = price == null ? defaultPrice() : price.getValue(), transfer = amount == null ? "100" : amount.getValue();
        clearWidgets();
        w = Math.min(432, width - 12); h = Math.min(314, Math.max(236, height - 12)); x = (width - w) / 2; y = Math.max(0, (height - h) / 2);
        if (!modal.isEmpty()) { initModal(transfer); return; }
        String[] tabs = {"取引", "建玉", "注文", "履歴"};
        int tabWidth = (w - 100) / 4;
        for (int i = 0; i < tabs.length; i++) {
            int selected = i;
            Button button = button(x + 8 + i * tabWidth, y + 43, tabWidth - 3, tabs[i], () -> { tab = selected; page = 0; init(); });
            if (tab == i) button.active = false;
        }
        button(x + w - 90, y + 43, 82, "UI設定", FxClient::settings);
        if (tab == 0) {
            int col = (w - 24) / 3;
            button(x + 8, y + 74, col, data.selected + "/GC ▼", () -> {
                int index = 0; for (int i = 0; i < data.quotes.size(); i++) if (data.quotes.get(i).symbol.equals(data.selected)) index = i;
                Request r = request("SELECT"); r.symbol = data.quotes.get((index + 1) % data.quotes.size()).symbol; send(r);
            });
            button(x + 12 + col, y + 74, col, side == Side.BUY ? "買い / BUY" : "売り / SELL", () -> {
                side = side == Side.BUY ? Side.SELL : Side.BUY; price.setValue(defaultPrice()); init();
            });
            button(x + 16 + 2 * col, y + 74, col, label(type) + " ▼", () -> {
                type = switch (type) { case MARKET -> OrderType.LIMIT; case LIMIT -> OrderType.STOP; default -> OrderType.MARKET; };
                price.setValue(defaultPrice()); init();
            });
            units = field(x + 64, y + 125, 100, "数量", quantity);
            leverage = field(x + w - 102, y + 125, 86, "倍率", multiple);
            price = field(x + 64, y + 154, 138, "注文価格", entryPrice); price.setEditable(type != OrderType.MARKET);
            placeOrder = button(x + 8, y + 191, w - 144, "注文内容を確認", this::review);
            updateActions();
            button(x + w - 130, y + 191, 122, "入金 / 出金", () -> { modal = "TRANSFER"; init(); });
        } else {
            int count = count(), rows = rows(); page = Math.max(0, Math.min(page, Math.max(0, (count - 1) / rows)));
            for (int row = 0; row < rows && page * rows + row < count; row++) {
                int index = page * rows + row, rowY = y + 76 + row * 31;
                if (tab == 1) {
                    button(x + w - 63, rowY + 3, 55, "決済", () -> {
                        if (index >= data.account.positions.size()) return;
                        Position p = data.account.positions.get(index);
                        pending = request("CLOSE"); pending.id = p.id; pending.units = p.units; modal = "REVIEW"; init();
                    });
                } else if (tab == 2) {
                    button(x + w - 63, rowY + 3, 55, "取消", () -> {
                        if (index >= data.account.orders.size()) return;
                        Request r = request("CANCEL"); r.id = data.account.orders.get(index).id; send(r);
                    });
                }
            }
            button(x + 8, y + h - 57, 60, "前へ", () -> { page = Math.max(0, page - 1); init(); });
            button(x + 74, y + h - 57, 60, "次へ", () -> { if ((page + 1) * rows < count) page++; init(); });
            button(x + w - 130, y + h - 57, 122, "入金 / 出金", () -> { modal = "TRANSFER"; init(); });
        }
        if (h >= 250) button(x + w - 88, y + h - 26, 80, "閉じる", this::onClose);
        else button(x + w - 28, y + 7, 20, "×", this::onClose);
    }
    private void updateActions() {
        if (placeOrder != null) placeOrder.active = data.enabled && !data.delinquent && !data.account.tradingLocked && data.account.pendingMarginCall <= 0;
    }
    private int rows() { return Math.max(1, Math.min(4, (h - 142) / 31)); }
    private int count() { return tab == 1 ? data.account.positions.size() : tab == 2 ? data.account.orders.size() : data.account.history.size(); }
    private Button button(int bx, int by, int width, String text, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(bx, by, width, 20).build());
    }
    private EditBox field(int bx, int by, int width, String label, String value) {
        EditBox box = new EditBox(font, bx, by, width, 20, Component.literal(label));
        box.setMaxLength(18); box.setFilter(s -> s.matches("[0-9]*\\.?[0-9]*")); box.setValue(value);
        return addRenderableWidget(box);
    }
    private void initModal(String initial) {
        int bx = x + 16;
        if (modal.equals("TRANSFER")) {
            amount = field(bx + 80, y + 113, w - 116, "金額", initial);
            button(bx, y + 153, (w - 38) / 2, "FX口座へ入金", () -> transfer("DEPOSIT"));
            button(x + w / 2 + 3, y + 153, (w - 38) / 2, "所持金へ出金", () -> transfer("WITHDRAW"));
            if (data.guildDebt > 0 && !data.miningDebtRepaymentOnly) button(bx, y + 181, w - 32, "所持金から債務を返済", () -> transfer("REPAY_DEBT"));
        } else {
            int actionY = y + Math.max(178, h - 66);
            button(bx, actionY, (w - 38) / 2, "戻る", () -> { modal = ""; init(); });
            button(x + w / 2 + 3, actionY, (w - 38) / 2, "確定して送信", () -> { send(pending); modal = ""; init(); });
        }
        if (modal.equals("TRANSFER")) button(x + w - 96, y + h - 27, 80, "戻る", () -> { modal = ""; init(); });
    }
    private void review() {
        try {
            Request r = request("ORDER"); r.side = side; r.type = type; r.units = parse(units);
            double lev = parse(leverage);
            int maxLeverage = quote().maxLeverage > 0 ? quote().maxLeverage : data.config.maxLeverage;
            if (lev != Math.floor(lev) || lev < 1 || lev > maxLeverage) throw new IllegalArgumentException("倍率は1〜" + maxLeverage + "の整数です。");
            if (r.units <= 0) throw new IllegalArgumentException("数量を入力してください。");
            r.leverage = (int) lev; r.price = type == OrderType.MARKET ? 0 : parse(price);
            pending = r; modal = "REVIEW"; init();
        } catch (IllegalArgumentException ex) { localError(ex.getMessage()); }
    }
    private void transfer(String op) {
        try {
            double value = parse(amount);
            if (value < 1 || value != Math.floor(value) || value > 1e12) throw new IllegalArgumentException("金額は1以上の整数です。");
            Request r = request(op); r.amount = (long) value; send(r); modal = ""; init();
        } catch (IllegalArgumentException ex) { localError(ex.getMessage()); }
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF101010); g.fill(x, y, x + w, y + h, 0xFFC6C6C6);
        g.fill(x, y, x + w, y + 2, 0xFFFFFFFF); g.fill(x, y, x + 2, y + h, 0xFFFFFFFF);
        g.fill(x, y + h - 2, x + w, y + h, 0xFF555555); g.fill(x + w - 2, y, x + w, y + h, 0xFF555555);
        g.renderItem(new ItemStack(ModItems.COIN), x + 8, y + 7);
        text(g, title.getString(), x + 30, y + 10, 0x333333);
        boolean compactError = h <= 285 && error && System.currentTimeMillis() < messageUntil;
        text(g, compactError ? message : "FX " + number(data.account.balance) + " GC   所持 " + data.wallet + " GC", x + 8, y + 29, compactError ? 0xAA2222 : 0x404040);
        if (!modal.isEmpty()) drawModal(g);
        else if (tab == 0) {
            text(g, "ASK " + number(quote().ask) + "  /  BID " + number(quote().bid), x + 10, y + 103, 0x333333);
            text(g, "数量", x + 10, y + 131, 0x333333); text(g, "倍率", x + w - 142, y + 131, 0x333333);
            text(g, "価格", x + 10, y + 160, 0x333333);
            text(g, type == OrderType.MARKET ? "成行で約定" : type == OrderType.LIMIT ? "有利な価格を指定" : "到達で成行発注", x + 212, y + 160, 0x555555);
            text(g, data.delinquent ? "滞納中 / 新規取引停止 / 債務 " + data.guildDebt + " GC" : data.config.marginCallEnabled ? "追証あり / 不足は所持金で精算・未払いは滞納" : "追証なし / ゼロカット", x + 10, y + 178, data.delinquent || data.config.marginCallEnabled ? 0x9B2828 : 0x3B5636);
            if (h > 285) {
                text(g, "有効証拠金 " + number(data.equity) + "  余剰 " + number(data.freeMargin) + " GC", x + 10, y + 224, 0x404040);
                text(g, data.delinquent ? "債務滞納中: " + data.guildDebt + " GC / 新規取引停止" : riskLabel(), x + 10, y + 240, data.delinquent || data.config.marginCallEnabled ? 0x9B2828 : 0x3B5636);
                text(g, quote().event + " / " + (quote().style == null ? "バランス" : quote().style) + " / 最大 " + (quote().maxLeverage > 0 ? quote().maxLeverage : data.config.maxLeverage) + "倍", x + 10, y + 256, 0x555555);
            }
        } else drawRows(g);
        super.render(g, mx, my, delta);
        if (h > 285 && System.currentTimeMillis() < messageUntil) text(g, font.plainSubstrByWidth(message, w - 112), x + 10, y + h - 37, error ? 0xAA2222 : 0x24552E);
    }
    private String riskLabel() { return data.config.marginCallEnabled ? "追証あり: 不足分は所持金から精算・未払いは滞納" : "追証なし: ゼロカット / FX口座の資金内で取引"; }
    private void drawRows(GuiGraphics g) {
        int count = count();
        if (count == 0) text(g, "まだデータがありません。", x + 12, y + 100, 0x555555);
        int textRight = x + w - (tab == 1 || tab == 2 ? 67 : 8);
        for (int row = 0; row < rows() && page * rows() + row < count; row++) {
            int index = page * rows() + row, rowY = y + 77 + row * 31;
            g.fill(x + 7, rowY - 2, x + w - 7, rowY + 27, 0xFFB5B5B5);
            if (tab == 1) {
                Position p = data.account.positions.get(index);
                text(g, p.symbol + " " + (p.side == Side.BUY ? "買い" : "売り") + "  " + number(p.units) + " / " + p.leverage + "倍", x + 11, rowY + 1, textRight, 0x333333);
                Quote q = data.quotes.stream().filter(item -> item.symbol.equals(p.symbol)).findFirst().orElse(quote());
                double pnl = ((p.side == Side.BUY ? q.bid : q.ask) - p.entryPrice) * p.units * p.side.sign();
                text(g, "約定 " + number(p.entryPrice) + " / 損益 " + number(pnl) + " GC", x + 11, rowY + 14, textRight, pnl < 0 ? 0x9B2828 : 0x24552E);
            } else if (tab == 2) {
                Order o = data.account.orders.get(index);
                text(g, "#" + o.id + " " + o.symbol + " " + label(o.type) + " " + o.side, x + 11, rowY + 1, textRight, 0x333333);
                text(g, number(o.units) + " / " + o.leverage + "倍 @ " + number(o.price), x + 11, rowY + 14, textRight, 0x444444);
            } else {
                History event = data.account.history.get(count - 1 - index);
                text(g, event.symbol + "  " + event.action + "  #" + event.id, x + 11, rowY + 1, textRight, 0x333333);
                text(g, event.detail + "  " + number(event.pnl) + " GC", x + 11, rowY + 14, textRight, 0x444444);
            }
        }
    }
    private void drawModal(GuiGraphics g) {
        text(g, (modal.equals("TRANSFER") ? "入出金" + (data.miningDebtRepaymentOnly ? "" : " / 債務の返済") : "送信内容の確認") + (data.config.marginCallEnabled ? " / 追証あり" : " / 追証なし"), x + 16, y + 59, 0x333333);
        if (modal.equals("TRANSFER")) {
            text(g, "出金可能 " + number(Math.floor(data.withdrawable)) + " GC", x + 16, y + 83, 0x404040);
            text(g, "債務残高 " + data.guildDebt + " GC", x + 16, y + 97, data.delinquent ? 0x9B2828 : 0x444444);
            text(g, "金額 (GC)", x + 16, y + 119, 0x333333);
            if (h > 285) text(g, riskLabel(), x + 16, y + 236, data.config.marginCallEnabled ? 0x9B2828 : 0x3B5636);
        } else {
            text(g, pending.op.equals("CLOSE") ? "建玉 #" + pending.id + " を全量決済" : pending.symbol + " " + label(pending.type) + " / " + (pending.side == Side.BUY ? "買い" : "売り"), x + 16, y + 87, 0x333333);
            text(g, "数量 " + number(pending.units) + (pending.op.equals("ORDER") ? "  倍率 " + pending.leverage : ""), x + 16, y + 108, 0x404040);
            if (pending.op.equals("ORDER")) text(g, "価格 " + (pending.type == OrderType.MARKET ? "成行（最新価格）" : number(pending.price)), x + 16, y + 129, 0x404040);
            text(g, "スプレッド・手数料・スリッページが適用されます。", x + 16, y + 145, 0x555555);
            text(g, riskLabel(), x + 16, y + 161, data.config.marginCallEnabled ? 0x9B2828 : 0x3B5636);
        }
    }
    private void text(GuiGraphics g, String text, int px, int py, int color) { text(g, text, px, py, x + w - 8, color); }
    /** Draws one line inside the panel, cut at {@code right} with a visible ellipsis when it does not fit. */
    private void text(GuiGraphics g, String text, int px, int py, int right, int color) {
        int width = Math.max(0, right - px);
        String shown = font.plainSubstrByWidth(text, width);
        if (!shown.equals(text)) shown = font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
        g.drawString(font, shown, px, py, color, false);
    }
    private Request request(String op) { Request r = new Request(); r.op = op; r.symbol = data.selected; return r; }
    private void send(Request r) {
        if (System.currentTimeMillis() - receivedAt > Math.max(5000, data.config.snapshotIntervalTicks * 150L)) { localError("相場更新を待ってください。"); return; }
        FxClient.send(r);
    }
    private void localError(String text) { message = text; error = true; messageUntil = System.currentTimeMillis() + 6000; }
    private static double parse(EditBox field) {
        try { double value = Double.parseDouble(field.getValue()); if (!Double.isFinite(value)) throw new NumberFormatException(); return value; }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("数値を確認してください。"); }
    }
    private static String number(double value) { return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", ""); }
    private static String label(OrderType type) { return switch (type) { case MARKET -> "成行"; case LIMIT -> "指値"; case STOP -> "逆指値"; default -> type.toString(); }; }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 256 && !modal.isEmpty()) { modal = ""; init(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void tick() { if (units != null) units.tick(); if (leverage != null) leverage.tick(); if (price != null) price.tick(); if (amount != null) amount.tick(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() { if (!FxClient.switchingUi()) FxClient.send(request("UNSUBSCRIBE")); }
}

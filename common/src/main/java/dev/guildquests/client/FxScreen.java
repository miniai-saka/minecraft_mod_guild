package dev.guildquests.client;

import dev.guildquests.fx.FxModels.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A resolution-independent, texture-free terminal. All prices and balances come from the server. */
public final class FxScreen extends Screen {
    private static final int W = 1100, H = 680;
    private static final int BG = 0xFFF3F5FA, WHITE = 0xFFFFFFFF, INK = 0xFF202A43;
    private static final int MUTED = 0xFF69758B, LINE = 0xFFE7EAF2, BLUE = 0xFF5966EE;
    private static final int GREEN = 0xFF15997D, RED = 0xFFE06276, PALE = 0xFFF0F2FF;
    private static final Style FONT = Style.EMPTY.withFont(new ResourceLocation("guildquests", "fx"));
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String[] MODES = {"成行", "指値", "逆指値", "OCO", "IFD", "IFO"};
    private static final String[] MODE_HELP = {
            "現在のASK/BIDで即時約定。スプレッドとスリッページがあります。",
            "買いは現在値より安く、売りは高くなったときに約定します。",
            "買いは現在値より高く、売りは安くなったときに成行で約定します。",
            "同じ売買方向の指値と逆指値を組み合わせ、片方の約定で他方を取消。",
            "新規注文が約定すると、指定した利確指値が有効になります。",
            "新規注文が約定すると、利確指値と損切逆指値のOCOが有効になります。"};
    private Snapshot data;
    private long receivedAt = System.currentTimeMillis(), messageUntil;
    private String message = "";
    private boolean messageError;
    private Side side = Side.BUY;
    private OrderType mode = OrderType.MARKET;
    private Entry entry = Entry.LIMIT;
    private int tab, scroll, timeframe, candleCount = 60, chartOffset, watchScroll;
    private float scale = 1;
    private double originX, originY, mx, my;
    private final List<Hit> hits = new ArrayList<>();
    private final List<EditBox> fields = new ArrayList<>();
    private EditBox units, leverage, price, second, tp, sl, trailing, expiry, modalInput;
    private EditBox focused;
    private String modal = "";
    private Request pending;
    private long editingId;
    private Position closing;
    private record Hit(int x, int y, int w, int h, Runnable action, String tip) { }

    public FxScreen(Snapshot snapshot) {
        super(Component.literal("Guild FX")); data = snapshot;
    }

    public void accept(Snapshot snapshot) {
        boolean changed = !data.selected.equals(snapshot.selected);
        data = snapshot; receivedAt = System.currentTimeMillis();
        if (changed) { defaults(); chartOffset = 0; }
        if (snapshot.message != null && !snapshot.message.isBlank()) {
            message = snapshot.message; messageError = snapshot.error; messageUntil = System.currentTimeMillis() + 6500;
        }
        if (editingId != 0 && data.account.positions.stream().noneMatch(p -> p.id == editingId)) editingId = 0;
    }

    @Override
    protected void init() {
        String[] old = fields.isEmpty() ? null : fields.stream().map(EditBox::getValue).toArray(String[]::new);
        fields.clear(); focused = null;
        units = field(820, 321, 116, "数量", "1");
        leverage = field(948, 321, 116, "レバレッジ", "10");
        price = field(820, 365, 116, "注文価格", "");
        second = field(948, 365, 116, "OCO逆指値", "");
        tp = field(820, 409, 116, "利確価格", "");
        sl = field(948, 409, 116, "損切価格", "");
        trailing = field(820, 453, 116, "トレール幅", "");
        expiry = field(948, 453, 116, "期限（分）", "0");
        if (old != null && old.length == fields.size()) for (int i = 0; i < old.length; i++) fields.get(i).setValue(old[i]);
        else defaults();
        modalInput = new NumberField(348, 300, 404, "金額・数量");
        modalInput.setMaxLength(18); modalInput.setBordered(false); modalInput.setTextColor(INK);
        modalInput.setFilter(s -> s.matches("[0-9]*\\.?[0-9]*"));
        updateTransform();
    }

    private EditBox field(int x, int y, int width, String label, String initial) {
        EditBox f = new NumberField(x, y, width, label);
        f.setBordered(false); f.setTextColor(INK); f.setTextColorUneditable(MUTED); f.setMaxLength(18);
        f.setFilter(s -> s.matches("[0-9]*\\.?[0-9]*")); f.setValue(initial); fields.add(f); return f;
    }

    private void defaults() {
        if (price == null) return;
        Quote q = quote();
        double current = side == Side.BUY ? q.ask : q.bid;
        double sign = entry == Entry.STOP || mode == OrderType.STOP ? 1 : -1;
        price.setValue(number(current * (1 + sign * side.sign() * .005)));
        second.setValue(number(current * (1 + side.sign() * .005)));
    }

    private Quote quote() { return data.quotes.stream().filter(q -> q.symbol.equals(data.selected)).findFirst().orElse(data.quotes.get(0)); }
    private void updateTransform() {
        scale = Math.min(width / (float) W, height / (float) H);
        originX = (width - W * scale) / 2; originY = (height - H * scale) / 2;
    }
    private boolean stale() { return System.currentTimeMillis() - receivedAt > Math.max(5000, data.config.snapshotIntervalTicks * 150L); }

    @Override
    public void render(GuiGraphics d, int mouseX, int mouseY, float delta) {
        updateTransform(); mx = (mouseX - originX) / scale; my = (mouseY - originY) / scale;
        hits.clear();
        d.fill(0, 0, width, height, BG);
        d.pose().pushPose(); d.pose().translate(originX, originY, 0); d.pose().scale(scale, scale, 1);
        header(d); metrics(d); watchlist(d); chart(d); orderPanel(d, delta); table(d); footer(d);
        if (!modal.isEmpty()) drawModal(d, delta);
        else for (Hit h : hits) if (!h.tip.isBlank() && hover(h.x, h.y, h.w, h.h)) { tooltip(d, h.tip); break; }
        if (System.currentTimeMillis() < messageUntil && modal.isEmpty()) {
            box(d, 206, 625, 690, 32, messageError ? 0xFFFFE9ED : 0xFFE4F6F0);
            text(d, fit(message, 662), 220, 637, messageError ? RED : GREEN);
        }
        d.pose().popPose();
    }

    private void header(GuiGraphics d) {
        box(d, 18, 16, 34, 34, BLUE);
        text(d, "G", 30, 28, WHITE);
        large(d, "GUILD", 62, 18, 1.6f, INK); large(d, "FX", 130, 18, 1.6f, BLUE);
        text(d, "ギルドコインで、架空通貨の市場へ。", 62, 41, MUTED);
        box(d, 316, 23, 114, 21, 0xFFE4F6F0); text(d, "VIRTUAL MARKET", 324, 30, GREEN);
        button(d, 450, 20, 92, 30, "UI設定", false, BLUE, FxClient::settings, "Minecraft簡易UIと白背景の詳細UIを切り替えます。");
        text(d, data.delinquent ? "債務滞納中 / 新規取引停止" : data.config.marginCallEnabled ? "追証あり" : "追証なし", 560, 30, data.delinquent || data.config.marginCallEnabled ? RED : GREEN);
        dot(d, 770, 31, stale() ? RED : GREEN);
        text(d, stale() ? "接続を確認中" : "LIVE  " + clock(data.time), 782, 27, stale() ? RED : MUTED);
        button(d, 906, 20, 112, 30, "入金 / 出金", false, BLUE, () -> showTransfer(), "ギルドコインの移動・債務の返済。追証の有無はサーバー設定に従います。");
        button(d, 1030, 20, 48, 30, "閉じる", false, MUTED, this::onClose, "Escでも閉じられます。注文と建玉はサーバーで継続します。");
    }

    private void metrics(GuiGraphics d) {
        panel(d, 18, 68, 1060, 69);
        metric(d, 34, "口座残高", money(data.account.balance), "GC", INK);
        metric(d, 238, "有効証拠金", money(data.equity), "GC", INK);
        metric(d, 446, "余剰証拠金", money(Math.max(0, data.freeMargin)), "GC", INK);
        metric(d, 655, "評価損益", signed(data.unrealized), "GC", tone(data.unrealized));
        text(d, "証拠金維持率", 864, 82, MUTED);
        large(d, data.usedMargin == 0 ? "—" : money(data.equity / data.usedMargin * 100) + "%", 864, 98, 1.5f,
                data.usedMargin > 0 && data.equity / data.usedMargin < 1 ? RED : INK);
        for (int x : new int[]{219, 427, 635, 844}) d.fill(x, 81, x + 1, 124, LINE);
    }
    private void metric(GuiGraphics d, int x, String label, String value, String unit, int color) {
        text(d, label, x, 82, MUTED); large(d, fit(value, 116), x, 99, 1.55f, color);
        text(d, unit, x + 158, 111, MUTED);
    }

    private void watchlist(GuiGraphics d) {
        panel(d, 18, 150, 158, 285);
        text(d, "マーケット", 32, 165, INK); text(d, "GC建て", 130, 165, MUTED);
        int index = 0;
        watchScroll = Math.max(0, Math.min(watchScroll, Math.max(0, data.quotes.size() - 3)));
        for (Quote q : data.quotes.subList(watchScroll, data.quotes.size())) {
            int y = 190 + index++ * 65;
            if (y + 55 > 401) break;
            boolean active = q.symbol.equals(data.selected);
            if (active) box(d, 26, y - 4, 142, 59, PALE);
            dot(d, 37, y + 7, 0xFF000000 | q.color);
            text(d, q.symbol + "/GC", 48, y + 2, active ? BLUE : INK);
            text(d, fit(q.name, 112), 35, y + 19, MUTED);
            text(d, number(q.price), 35, y + 35, INK);
            right(d, String.format(Locale.ROOT, "%+.2f%%", q.change), 159, y + 35, tone(q.change));
            hits.add(new Hit(26, y - 4, 142, 59, () -> select(q.symbol), "騰落率は保存チャートの先頭価格との比較です。"));
        }
        text(d, "独自生成相場", 29, 416, MUTED);
    }

    private List<Candle> candles() {
        long interval = switch (timeframe) { case 1 -> 30000; case 2 -> 60000; case 3 -> 300000; default -> data.config.candleSeconds * 1000L; };
        Map<Long, Candle> groups = new LinkedHashMap<>();
        for (Candle c : data.candles) {
            long key = c.time / interval * interval;
            Candle merged = groups.get(key);
            if (merged == null) { merged = new Candle(key, c.open); groups.put(key, merged); }
            merged.high = Math.max(merged.high, c.high); merged.low = Math.min(merged.low, c.low);
            merged.close = c.close; merged.volume += c.volume;
        }
        return new ArrayList<>(groups.values());
    }

    private void chart(GuiGraphics d) {
        panel(d, 188, 150, 600, 285);
        Quote q = quote();
        text(d, q.symbol + " / GC", 204, 165, INK);
        large(d, number(q.price), 204, 183, 1.65f, INK);
        text(d, "高値 " + number(q.high) + "   安値 " + number(q.low), 335, 188, MUTED);
        String[] frames = {data.config.candleSeconds + "秒", "30秒", "1分", "5分"};
        for (int i = 0; i < 4; i++) {
            final int selected = i;
            button(d, 560 + i * 48, 160, 42, 22, frames[i], timeframe == i, BLUE, () -> { timeframe = selected; chartOffset = 0; }, "ローソク足の集約間隔。実際に生成されたデータを表示します。");
        }
        final int x = 205, y = 218, cw = 509, ch = 146;
        List<Candle> all = candles();
        chartOffset = Math.max(0, Math.min(chartOffset, Math.max(0, all.size() - 5)));
        int end = Math.max(1, all.size() - chartOffset), start = Math.max(0, end - candleCount);
        List<Candle> visible = all.subList(start, Math.min(end, all.size()));
        double lo = q.price, hi = q.price, maxVolume = 1;
        for (Candle c : visible) { lo = Math.min(lo, c.low); hi = Math.max(hi, c.high); maxVolume = Math.max(maxVolume, c.volume); }
        double pad = Math.max((hi - lo) * .14, q.price * .0005); lo -= pad; hi += pad;
        double range = hi - lo;
        for (int i = 0; i <= 4; i++) {
            int gy = y + i * ch / 4; d.fill(x, gy, x + cw, gy + 1, LINE);
            text(d, number(hi - range * i / 4), x + cw + 8, gy - 3, MUTED);
        }
        int slots = Math.max(visible.size(), Math.min(candleCount, 40));
        double step = cw / (double) slots;
        int offset = slots - visible.size();
        for (int i = 0; i < visible.size(); i++) {
            Candle c = visible.get(i); int cx = x + (int) ((i + offset + .5) * step);
            int high = py(c.high, hi, range, y, ch), low = py(c.low, hi, range, y, ch);
            int open = py(c.open, hi, range, y, ch), close = py(c.close, hi, range, y, ch);
            int color = c.close >= c.open ? GREEN : RED;
            d.fill(cx, high, cx + 1, low + 1, color);
            int half = Math.max(1, (int) (step * .3));
            d.fill(cx - half, Math.min(open, close), cx + half + 1, Math.max(open, close) + 1, color);
            int volume = (int) (c.volume / maxVolume * 23);
            d.fill(cx - half, 398 - volume, cx + half + 1, 398, (color & 0x00FFFFFF) | 0x55000000);
        }
        int currentY = py(q.price, hi, range, y, ch);
        dashed(d, x, currentY, cw, BLUE);
        box(d, 717, currentY - 6, 63, 13, BLUE); text(d, number(q.price), 720, currentY - 3, WHITE);
        for (Position p : data.account.positions) if (p.symbol.equals(data.selected)) {
            if (p.entryPrice >= lo && p.entryPrice <= hi) {
                int gy = py(p.entryPrice, hi, range, y, ch); dashed(d, x, gy, cw, tone(p.side.sign()));
                text(d, "#" + p.id + " " + (p.side == Side.BUY ? "BUY" : "SELL"), x + 4, gy - 10, tone(p.side.sign()));
            }
            chartLevel(d, p.takeProfit, "TP", GREEN, lo, hi, range, x, y, cw, ch);
            chartLevel(d, p.stopLoss, "SL", RED, lo, hi, range, x, y, cw, ch);
        }
        if (hover(x, y, cw, 181) && !visible.isEmpty() && modal.isEmpty()) {
            int index = Math.max(0, Math.min(visible.size() - 1, (int) ((mx - x) / step) - offset));
            Candle c = visible.get(index); int cx = x + (int) ((index + offset + .5) * step);
            dashedVertical(d, cx, y, 180, 0xFFBCC2D1);
            int hy = Math.max(y, Math.min(y + ch, (int) my)); dashed(d, x, hy, cw, 0xFFBCC2D1);
            box(d, x + 2, y + 2, 368, 16, 0xF2FFFFFF);
            text(d, clock(c.time) + "  O " + number(c.open) + "  H " + number(c.high) + "  L " + number(c.low) + "  C " + number(c.close), x + 7, y + 6, INK);
        }
        if (visible.isEmpty()) text(d, "最初の価格を生成しています…", 348, 288, MUTED);
        else {
            text(d, clock(visible.get(0).time), x, 405, MUTED);
            right(d, clock(visible.get(visible.size() - 1).time), x + cw, 405, MUTED);
        }
        button(d, 547, 413, 30, 16, "<", false, MUTED, () -> chartOffset += 15, "過去の足へ移動");
        button(d, 580, 413, 42, 16, "最新", false, BLUE, () -> chartOffset = 0, "最新のローソク足に戻る");
        button(d, 625, 413, 30, 16, ">", false, MUTED, () -> chartOffset = Math.max(0, chartOffset - 15), "新しい足へ移動");
        button(d, 680, 413, 30, 16, "−", false, MUTED, () -> candleCount = Math.min(120, candleCount + 10), "縮小。チャート上のホイールでも操作できます。");
        button(d, 714, 413, 30, 16, "+", false, MUTED, () -> candleCount = Math.max(15, candleCount - 10), "拡大");
    }

    private void chartLevel(GuiGraphics d, double value, String label, int color, double lo, double hi, double range, int x, int y, int w, int h) {
        if (value <= 0 || value < lo || value > hi) return;
        int gy = py(value, hi, range, y, h); dashed(d, x, gy, w, color); text(d, label, x + w - 17, gy - 10, color);
    }
    private static int py(double price, double hi, double range, int y, int height) { return y + (int) ((hi - price) / range * height); }

    private void orderPanel(GuiGraphics d, float delta) {
        panel(d, 800, 150, 278, 496);
        text(d, editingId == 0 ? "新規注文" : "決済OCOの編集  #" + editingId, 818, 165, INK);
        right(d, "最大 " + data.config.maxLeverage + "x", 1060, 165, BLUE);
        button(d, 818, 190, 120, 30, "買い / BUY", side == Side.BUY, GREEN, () -> changeSide(Side.BUY), "ASKで買い、BIDで決済します。");
        button(d, 946, 190, 116, 30, "売り / SELL", side == Side.SELL, RED, () -> changeSide(Side.SELL), "BIDで売り、ASKで決済します。");
        for (int i = 0; i < MODES.length; i++) {
            final int which = i;
            button(d, 818 + (i % 3) * 83, 231 + (i / 3) * 26, 77, 22, MODES[i], mode.ordinal() == i, BLUE, () -> changeMode(which), MODE_HELP[i]);
        }
        if (mode == OrderType.IFD || mode == OrderType.IFO) {
            text(d, "新規", 820, 291, MUTED);
            for (int i = 0; i < 3; i++) {
                final int which = i;
                button(d, 859 + i * 67, 285, 61, 21, new String[]{"成行", "指値", "逆指値"}[i], entry.ordinal() == i, BLUE,
                        () -> { entry = Entry.values()[which]; defaults(); }, "新規注文の約定方法");
            }
        } else text(d, mode == OrderType.OCO ? "指値 + 逆指値 / 片方の約定で他方取消" : "価格は 1 " + data.selected + " あたりの GC", 820, 291, MUTED);
        input(d, units, "数量 (" + data.selected + ")", editingId == 0, delta);
        input(d, leverage, "レバレッジ (1〜" + data.config.maxLeverage + ")", editingId == 0, delta);
        boolean pendingEntry = editingId == 0 && mode != OrderType.MARKET && !((mode == OrderType.IFD || mode == OrderType.IFO) && entry == Entry.MARKET);
        input(d, price, mode == OrderType.OCO ? "指値価格" : "新規価格 (GC)", pendingEntry, delta);
        input(d, second, "OCO 逆指値価格", editingId == 0 && mode == OrderType.OCO, delta);
        input(d, tp, "利確 / TP (GC)", true, delta);
        input(d, sl, "損切 / SL (GC)", mode != OrderType.IFD, delta);
        input(d, trailing, "トレール幅 (GC)", true, delta);
        input(d, expiry, "期限 (分・0=無期限)", pendingEntry, delta);
        double size = numberOrZero(units), lev = numberOrZero(leverage);
        double ref = pendingEntry ? numberOrZero(price) : (side == Side.BUY ? quote().ask : quote().bid);
        if (mode == OrderType.OCO) ref = Math.max(ref, numberOrZero(second));
        double notional = size * ref;
        d.fill(818, 489, 1062, 490, LINE);
        text(d, "必要証拠金（概算）", 820, 501, MUTED); right(d, money(lev > 0 ? notional / lev : 0) + " GC", 1062, 501, INK);
        text(d, "新規手数料（概算）", 820, 518, MUTED); right(d, number(notional * data.config.commissionBps / 10000) + " GC", 1062, 518, INK);
        text(d, "ASK " + number(quote().ask), 820, 536, GREEN); right(d, "BID " + number(quote().bid), 1062, 536, RED);
        button(d, 818, 558, 244, 31, editingId == 0 ? "注文内容を確認する" : "決済OCOを更新する", true,
                stale() ? MUTED : editingId == 0 ? BLUE : GREEN, this::reviewOrder, "送信前に注文内容を表示します。サーバーが最終的に価格・証拠金を検証します。");
        button(d, 818, 598, 118, 23, "TP/SL ±1%", false, BLUE, this::protectionPreset, "基準価格の±1%を設定。1000倍ではロスカットが先に起きる場合があります。");
        button(d, 944, 598, 118, 23, editingId == 0 ? "決済条件を消す" : "新規注文に戻る", false, MUTED, () -> {
            if (editingId != 0) editingId = 0; else { tp.setValue(""); sl.setValue(""); trailing.setValue(""); }
        }, "利確・損切が未指定なら、手動決済またはロスカットまで建玉を維持します。");
        text(d, data.config.marginCallEnabled ? "追証あり / 未払いは既存の滞納処理へ" : "ゼロカット / 追証なし / 共通証拠金", 818, 632, data.config.marginCallEnabled ? RED : MUTED);
    }

    private void input(GuiGraphics d, EditBox f, String label, boolean enabled, float delta) {
        f.setEditable(enabled); f.setVisible(enabled);
        int x = f.getX(), y = f.getY();
        text(d, label, x, y - 12, MUTED);
        box(d, x - 2, y - 2, f.getWidth() + 4, 25, enabled ? BG : 0xFFF9FAFC);
        if (enabled) {
            d.fill(x - 2, y + 22, x + f.getWidth() + 2, y + 23, f.isFocused() ? BLUE : LINE);
            f.render(d, (int) mx, (int) my, delta);
            if (f.getValue().isEmpty() && !f.isFocused()) text(d, "任意 / 0", x + 1, y + 7, MUTED);
        } else text(d, "—", x + 3, y + 7, MUTED);
    }

    private void table(GuiGraphics d) {
        panel(d, 18, 447, 770, 199);
        String[] tabs = {"建玉  " + data.account.positions.size(), "注文  " + data.account.orders.size(), "取引履歴", "ガイド"};
        for (int i = 0; i < tabs.length; i++) {
            final int which = i;
            button(d, 30 + i * 113, 457, 106, 25, tabs[i], tab == i, BLUE, () -> { tab = which; scroll = 0; }, "");
        }
        if (tab == 0) button(d, 688, 458, 83, 23, "全建玉決済", false, RED, () -> confirmAction("CLOSE_ALL"), "保有する全通貨の建玉を成行で決済します。");
        if (tab == 1) button(d, 688, 458, 83, 23, "全注文取消", false, RED, () -> confirmAction("CANCEL_ALL"), "全ての未約定注文を取り消し、拘束証拠金を解放します。");
        d.fill(30, 488, 776, 489, LINE);
        if (tab == 3) { guide(d); return; }
        text(d, tab == 2 ? "市場時刻 / 操作" : "通貨 / 売買", 34, 500, MUTED);
        text(d, tab == 2 ? "注文ID / 詳細" : "数量 / 倍率", 184, 500, MUTED);
        text(d, tab == 1 ? "指値 / 逆指値" : "約定価格", 314, 500, MUTED);
        text(d, tab == 0 ? "利確 / 損切" : tab == 1 ? "拘束証拠金 / 期限" : "数量 / 手数料", 440, 500, MUTED);
        text(d, tab == 0 ? "評価損益 (GC)" : tab == 1 ? "種別" : "損益・入出金 (GC)", 588, 500, MUTED);
        int count = tab == 0 ? data.account.positions.size() : tab == 1 ? data.account.orders.size() : data.account.history.size();
        int page = 4;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, count - page)));
        if (count == 0) {
            text(d, tab == 0 ? "まだ建玉がありません。右のパネルから注文できます。" : tab == 1 ? "未約定の注文はありません。" : "入出金・注文・約定の履歴がここに表示されます。", 210, 560, MUTED);
        }
        for (int i = 0; i < page && i + scroll < count; i++) {
            int index = i + scroll, y = 519 + i * 27;
            if (i % 2 == 0) box(d, 30, y - 3, 745, 26, 0xFFF8F9FC);
            if (tab == 0) {
                Position p = data.account.positions.get(index);
                text(d, p.symbol + "/GC  " + p.side, 35, y, p.side == Side.BUY ? GREEN : RED);
                text(d, "#" + p.id, 35, y + 11, MUTED);
                text(d, number(p.units) + " / " + p.leverage + "x", 184, y + 3, INK);
                text(d, number(p.entryPrice), 314, y + 3, INK);
                text(d, "TP " + optional(p.takeProfit), 440, y, GREEN); text(d, "SL " + optional(p.stopLoss), 440, y + 11, RED);
                double pnl = positionPnl(p); text(d, signed(pnl), 588, y + 3, tone(pnl));
                button(d, 682, y - 1, 42, 21, "OCO", false, BLUE, () -> edit(p), "建玉の利確・損切・トレーリング条件を変更します。");
                button(d, 730, y - 1, 42, 21, "決済", false, RED, () -> showClose(p), "全数量または一部数量を成行決済します。");
            } else if (tab == 1) {
                Order o = data.account.orders.get(index);
                text(d, o.symbol + "/GC  " + o.side, 35, y, o.side == Side.BUY ? GREEN : RED); text(d, "#" + o.id, 35, y + 11, MUTED);
                text(d, number(o.units) + " / " + o.leverage + "x", 184, y + 3, INK);
                text(d, number(o.price), 314, y, INK); if (o.type == OrderType.OCO) text(d, number(o.secondPrice), 314, y + 11, MUTED);
                text(d, money(o.reserved) + " GC", 440, y, INK); text(d, o.expiresAt == 0 ? "無期限" : clock(o.expiresAt), 440, y + 11, MUTED);
                text(d, o.type.toString(), 588, y + 3, BLUE);
                button(d, 724, y - 1, 48, 21, "取消", false, RED, () -> { Request r = request("CANCEL"); r.id = o.id; send(r); }, "OCOでは、両方の新規条件をまとめて取り消します。");
            } else {
                History h = data.account.history.get(count - 1 - index);
                text(d, clock(h.time) + "  " + h.action, 35, y, INK); text(d, h.symbol, 35, y + 11, MUTED);
                text(d, fit("#" + h.id + " " + h.detail, 119), 184, y + 3, MUTED);
                text(d, h.price == 0 ? "—" : number(h.price), 314, y + 3, INK);
                text(d, number(h.units), 440, y, INK); text(d, "手数料 " + number(h.fee), 440, y + 11, MUTED);
                text(d, signed(h.pnl), 588, y + 3, tone(h.pnl));
                hits.add(new Hit(30, y - 3, 745, 26, () -> { }, h.action + " / " + h.detail));
            }
        }
        text(d, "使用証拠金 " + money(data.usedMargin) + "   注文拘束 " + money(data.reservedMargin) + " GC", 34, 633, MUTED);
        if (count > page) {
            button(d, 651, 629, 29, 14, "↑", false, MUTED, () -> scroll = Math.max(0, scroll - page), "前の行。表の上のホイールでも移動できます。");
            text(d, (scroll + 1) + "–" + Math.min(count, scroll + page) + "/" + count, 686, 633, MUTED);
            button(d, 746, 629, 29, 14, "↓", false, MUTED, () -> scroll += page, "次の行");
        }
    }

    private void guide(GuiGraphics d) {
        String[] lines = {
                "01  右上からギルドコインをFX口座へ入金。全銘柄をGC建てで売買します。",
                "02  買いはASKで新規・BIDで決済。売りはBIDで新規・ASKで決済します。",
                "03  OCOは指値＋逆指値の二択。IFDは新規＋利確、IFOは新規＋利確＋損切です。",
                "04  建玉のOCOボタンで利確・損切を編集。片方の決済で建玉全体を閉じます。",
                "05  維持率 " + money(data.config.stopOutRatio * 100) + "%以下でロスカット。" + (data.config.marginCallEnabled ? "不足は所持金で精算し、未払いは滞納扱いです。" : "残高が負なら0に補填し、追証は発生しません。"),
                "06  高倍率では小さな逆行でもロスカット。損切は価格飛びで不利に約定します。",
                "07  ログアウト中もサーバー稼働中は相場・注文が進行。停止中の価格変動はありません。",
                "08  初期の足は生成履歴。手数料 " + number(data.config.commissionBps) + " bps / 片道。出来高は相場活動指数です。"};
        for (int i = 0; i < lines.length; i++) text(d, lines[i], 35, 500 + i * 16, i == 5 ? RED : MUTED);
    }

    private void footer(GuiGraphics d) {
        Quote q = quote();
        dot(d, 25, 662, q.event.equals("通常相場") ? GREEN : RED);
        text(d, q.event.equals("通常相場") ? "市場は稼働中" : q.event, 36, 658, q.event.equals("通常相場") ? MUTED : RED);
        text(d, "所持金 " + data.wallet + " GC    債務 " + data.guildDebt + " GC    損益 " + signed(data.account.realized), 280, 658, data.delinquent ? RED : MUTED);
        right(d, "GUILD FX  /  ギャンブルメニューから取引", 1078, 658, MUTED);
    }

    private void changeSide(Side value) { side = value; editingId = 0; tp.setValue(""); sl.setValue(""); defaults(); }
    private void changeMode(int value) {
        mode = OrderType.values()[value]; editingId = 0;
        if (mode == OrderType.IFD) sl.setValue(""); defaults();
    }
    private void protectionPreset() {
        double reference = mode == OrderType.MARKET || (mode == OrderType.IFD || mode == OrderType.IFO) && entry == Entry.MARKET
                ? quote().price : numberOrZero(price);
        if (editingId != 0) reference = quote().price;
        if (reference <= 0) return;
        tp.setValue(number(reference * (1 + side.sign() * .01)));
        if (mode != OrderType.IFD) sl.setValue(number(reference * (1 - side.sign() * .01)));
    }
    private void select(String symbol) {
        if (symbol.equals(data.selected)) return;
        editingId = 0; Request r = request("SELECT"); r.symbol = symbol; send(r);
    }
    private void edit(Position p) {
        if (!p.symbol.equals(data.selected)) select(p.symbol);
        editingId = p.id; side = p.side; tp.setValue(p.takeProfit == 0 ? "" : number(p.takeProfit));
        sl.setValue(p.stopLoss == 0 ? "" : number(p.stopLoss)); trailing.setValue(p.trailingDistance == 0 ? "" : number(p.trailingDistance));
        mode = OrderType.IFO;
        units.setValue(number(p.units)); leverage.setValue(Integer.toString(p.leverage));
    }
    private void reviewOrder() {
        try {
            if (stale()) throw new IllegalArgumentException("相場更新を待ってください。接続を確認中です。");
            Request r = request(editingId == 0 ? "ORDER" : "AMEND");
            r.id = editingId; r.symbol = data.selected; r.side = side; r.type = mode; r.entry = entry;
            r.units = parse(units); r.leverage = integer(leverage); r.price = parse(price); r.secondPrice = parse(second);
            r.takeProfit = parse(tp); r.stopLoss = mode == OrderType.IFD ? 0 : parse(sl); r.trailingDistance = parse(trailing);
            r.expiryMinutes = integer(expiry);
            if (editingId == 0 && (r.units <= 0 || r.leverage < 1 || r.leverage > data.config.maxLeverage))
                throw new IllegalArgumentException("数量とレバレッジを確認してください。");
            pending = r; modal = "REVIEW"; clearFocus();
        } catch (IllegalArgumentException ex) { localError(ex.getMessage()); }
    }
    private void confirmAction(String op) { pending = request(op); modal = "REVIEW"; clearFocus(); }
    private void showTransfer() { modal = "TRANSFER"; modalInput.setValue("100"); clearFocus(); focus(modalInput); }
    private void showClose(Position p) { closing = p; modal = "CLOSE"; modalInput.setValue(number(p.units)); clearFocus(); focus(modalInput); }

    private void drawModal(GuiGraphics d, float delta) {
        hits.clear(); d.fill(0, 0, W, H, 0x66303A54);
        panel(d, 320, 164, 460, 352);
        text(d, modal.equals("TRANSFER") ? "FX口座への入金・出金" : modal.equals("CLOSE") ? "建玉を決済する  #" + closing.id : "注文内容の確認", 344, 186, INK);
        button(d, 726, 177, 34, 24, "×", false, MUTED, () -> modal = "", "閉じる");
        d.fill(344, 210, 756, 211, LINE);
        if (modal.equals("TRANSFER")) {
            text(d, "所持ギルドコイン", 345, 229, MUTED); right(d, data.wallet + " GC", 753, 229, INK);
            text(d, "出金可能額（整数）", 345, 251, MUTED);
            right(d, money(Math.floor(Math.max(0, Math.min(data.account.balance, data.freeMargin)))) + " GC", 753, 251, INK);
            text(d, "金額 (GC) / 1以上の整数", 345, 282, MUTED);
            modalField(d, delta);
            text(d, data.config.marginCallEnabled ? "追証あり: ロスカット後の不足を所持金から自動精算。" : "追証なし: 損失はFX口座の資金内でゼロカット。", 345, 345, data.config.marginCallEnabled ? RED : MUTED);
            text(d, data.config.marginCallEnabled ? "未払いは債務・滞納へ。既存の返済・利息設定に従います。" : "モード変更前に発生した債務・未精算の追証は残ります。", 345, 363, MUTED);
            button(d, 344, 401, 196, 32, "FX口座へ入金", true, BLUE, () -> transfer("DEPOSIT"), "所持ギルドコインから入金します。");
            button(d, 552, 401, 202, 32, "所持金へ出金", true, GREEN, () -> transfer("WITHDRAW"), "整数GCを出金します。端数はFX口座に残ります。");
            if (data.guildDebt > 0) button(d, 344, 440, 410, 23, "所持金から債務を返済（残り " + data.guildDebt + " GC）", false, RED, () -> transfer("REPAY_DEBT"), "入力額を上限として、既存のリボ債務を含む債務に返済します。完済で滞納を解除します。");
            text(d, "手数料なし。出金は建玉・注文の証拠金を確保した範囲で行えます。", 344, 467, MUTED);
        } else if (modal.equals("CLOSE")) {
            text(d, closing.symbol + "/GC   " + closing.side + "   " + closing.leverage + "x", 345, 230, INK);
            text(d, "評価損益 " + signed(positionPnl(closing)) + " GC", 345, 251, tone(positionPnl(closing)));
            text(d, "決済する数量（保有 " + number(closing.units) + "）", 345, 282, MUTED); modalField(d, delta);
            button(d, 344, 341, 92, 24, "全数量", false, BLUE, () -> modalInput.setValue(number(closing.units)), "");
            button(d, 444, 341, 92, 24, "半分", false, BLUE, () -> modalInput.setValue(number(Math.floor(closing.units / 2 / data.config.unitStep) * data.config.unitStep)), "取引単位に合わせて切り捨てます。");
            text(d, "決済時のBID/ASKで約定し、手数料が差し引かれます。", 345, 389, MUTED);
            button(d, 344, 424, 410, 33, "この数量を成行で決済", true, RED, () -> {
                try { Request r = request("CLOSE"); r.id = closing.id; r.units = parse(modalInput);
                    if (r.units <= 0 || r.units > closing.units) throw new IllegalArgumentException("決済数量を確認してください。");
                    send(r); modal = "";
                } catch (IllegalArgumentException ex) { localError(ex.getMessage()); }
            }, "価格変動で表示損益と実際の決済額が変わる場合があります。");
        } else {
            boolean isOrder = pending.op.equals("ORDER"), amend = pending.op.equals("AMEND");
            if (isOrder || amend) {
                text(d, (amend ? "決済OCO  #" + pending.id : pending.type + "  " + pending.side) + "   " + pending.symbol + "/GC", 345, 229, BLUE);
                text(d, "数量 " + number(pending.units) + "    レバレッジ " + pending.leverage + "x", 345, 255, INK);
                boolean market = pending.type == OrderType.MARKET || (pending.type == OrderType.IFD || pending.type == OrderType.IFO) && pending.entry == Entry.MARKET;
                text(d, "新規価格  " + (market ? "成行（送信後の最新価格）" : number(pending.price)), 345, 279, INK);
                if (pending.type == OrderType.OCO) text(d, "OCO逆指値  " + number(pending.secondPrice), 345, 302, INK);
                text(d, "利確 " + optional(pending.takeProfit) + "    損切 " + optional(pending.stopLoss), 345, 326, INK);
                text(d, "トレール " + optional(pending.trailingDistance) + "    期限 " + (pending.expiryMinutes == 0 ? "無期限" : pending.expiryMinutes + "分"), 345, 350, MUTED);
                text(d, "スプレッド・手数料・スリッページが適用されます。", 345, 389, MUTED);
                text(d, data.config.marginCallEnabled ? "追証あり: 不足は所持金から精算、未払いは滞納になります。" : "追証なし: ゼロカット。既存の債務は免除されません。", 345, 407, data.config.marginCallEnabled ? RED : MUTED);
            } else {
                large(d, pending.op.equals("CLOSE_ALL") ? "全ての建玉を成行決済します。" : "全ての未約定注文を取り消します。", 345, 252, 1.1f, INK);
                text(d, "対象はこのFX口座の全銘柄です。", 345, 289, MUTED);
            }
            button(d, 344, 424, 197, 33, "戻って修正", false, BLUE, () -> modal = "", "");
            button(d, 552, 424, 202, 33, amend ? "条件を更新する" : "確定して送信", true, BLUE, () -> { send(pending); modal = ""; editingId = 0; }, "サーバーが注文条件と証拠金を再検証します。");
        }
        if (System.currentTimeMillis() < messageUntil && messageError) text(d, fit(message, 406), 345, 491, RED);
    }

    private void modalField(GuiGraphics d, float delta) {
        box(d, 344, 295, 410, 31, BG); d.fill(344, 325, 754, 326, BLUE);
        modalInput.render(d, (int) mx, (int) my, delta);
    }
    private void transfer(String op) {
        try { Request r = request(op); double amount = parse(modalInput);
            if (amount <= 0 || amount != Math.floor(amount) || amount > 1e12) throw new IllegalArgumentException("金額は1以上の整数で指定してください。");
            r.amount = (long) amount; send(r); modal = "";
        } catch (IllegalArgumentException ex) { localError(ex.getMessage()); }
    }

    private double positionPnl(Position p) {
        Quote q = data.quotes.stream().filter(item -> item.symbol.equals(p.symbol)).findFirst().orElse(quote());
        return ((p.side == Side.BUY ? q.bid : q.ask) - p.entryPrice) * p.units * p.side.sign();
    }
    private Request request(String op) { Request r = new Request(); r.op = op; r.symbol = data.selected; return r; }
    private void send(Request r) { if (!stale() || r.op.equals("UNSUBSCRIBE")) FxClient.send(r); else localError("サーバーの相場更新を待ってください。"); }
    private void localError(String value) { message = value; messageError = true; messageUntil = System.currentTimeMillis() + 6500; }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        double px = (x - originX) / scale, py = (y - originY) / scale;
        if (button == 0) {
            for (Hit hit : new ArrayList<>(hits)) if (px >= hit.x && px < hit.x + hit.w && py >= hit.y && py < hit.y + hit.h) {
                clearFocus(); hit.action.run(); return true;
            }
        }
        if (modal.equals("TRANSFER") || modal.equals("CLOSE")) {
            if (modalInput.mouseClicked(px, py, button)) { focus(modalInput); return true; }
        } else if (modal.isEmpty()) {
            for (EditBox f : fields) if (f.isVisible() && f.mouseClicked(px, py, button)) { focus(f); return true; }
        }
        clearFocus(); return true;
    }
    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return focused != null && focused.mouseDragged((x - originX) / scale, (y - originY) / scale, button, dx / scale, dy / scale);
    }
    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (!modal.isEmpty()) return true;
        double px = (x - originX) / scale, py = (y - originY) / scale;
        if (px < 176 && py > 150 && py < 435) watchScroll = Math.max(0, watchScroll - (int) amount);
        else if (px < 790 && py > 447) scroll = Math.max(0, scroll - (int) amount);
        else if (px > 188 && px < 788 && py > 150 && py < 435) {
            if (hasShiftDown()) chartOffset = Math.max(0, chartOffset + (int) amount * 5);
            else candleCount = Math.max(15, Math.min(120, candleCount - (int) amount * 5));
        }
        return true;
    }
    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && !modal.isEmpty()) { modal = ""; clearFocus(); return true; }
        if (key == GLFW.GLFW_KEY_TAB && modal.isEmpty()) {
            List<EditBox> visible = fields.stream().filter(EditBox::isVisible).toList();
            int next = (visible.indexOf(focused) + (hasShiftDown() ? visible.size() - 1 : 1)) % visible.size(); focus(visible.get(next)); return true;
        }
        if (focused != null && focused.keyPressed(key, scanCode, modifiers)) return true;
        return super.keyPressed(key, scanCode, modifiers);
    }
    @Override
    public boolean charTyped(char chr, int modifiers) { return focused != null && focused.charTyped(chr, modifiers); }
    @Override
    public void tick() { for (EditBox f : fields) f.tick(); if (modalInput != null) modalInput.tick(); }
    private void clearFocus() { if (focused != null) focused.setFocused(false); focused = null; }
    private void focus(EditBox f) { clearFocus(); focused = f; f.setFocused(true); }
    @Override
    public boolean isPauseScreen() { return false; }
    @Override
    public void removed() { if (!FxClient.switchingUi()) FxClient.send(request("UNSUBSCRIBE")); }

    private void button(GuiGraphics d, int x, int y, int w, int h, String label, boolean active, int color, Runnable action, String tip) {
        boolean over = hover(x, y, w, h) && modal.isEmpty();
        box(d, x, y, w, h, active ? color : over ? 0xFFE9EDF9 : 0xFFF3F5FA);
        int tx = x + (w - measure(label)) / 2;
        text(d, label, tx, y + (h - 8) / 2, active ? WHITE : color);
        hits.add(new Hit(x, y, w, h, action, tip));
    }
    private boolean hover(int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private void tooltip(GuiGraphics d, String tip) {
        int tw = Math.min(500, measure(tip) + 20), x = Math.min(W - tw - 10, Math.max(10, (int) mx - tw / 2));
        int y = Math.max(5, Math.min(H - 35, (int) my + 18));
        box(d, x, y, tw, 26, INK); text(d, fit(tip, tw - 18), x + 9, y + 9, WHITE);
    }
    private void text(GuiGraphics d, String value, int x, int y, int color) { d.drawString(font, Component.literal(value).setStyle(FONT), x, y, color, false); }
    private int measure(String value) { return font.width(Component.literal(value).setStyle(FONT)); }
    private void right(GuiGraphics d, String value, int x, int y, int color) { text(d, value, x - measure(value), y, color); }
    private void large(GuiGraphics d, String value, int x, int y, float size, int color) {
        d.pose().pushPose(); d.pose().translate(x, y, 0); d.pose().scale(size, size, 1); text(d, value, 0, 0, color); d.pose().popPose();
    }
    private String fit(String value, int width) {
        if (measure(value) <= width) return value;
        int end = value.length();
        while (end > 0 && measure(value.substring(0, end) + "…") > width) end--;
        return value.substring(0, end) + "…";
    }
    private static void panel(GuiGraphics d, int x, int y, int w, int h) { box(d, x, y + 2, w, h, 0x080D2145); box(d, x, y, w, h, WHITE); }
    private static void box(GuiGraphics d, int x, int y, int w, int h, int color) {
        d.fill(x + 3, y, x + w - 3, y + h, color); d.fill(x, y + 3, x + w, y + h - 3, color);
        d.fill(x + 1, y + 1, x + w - 1, y + h - 1, color);
    }
    private static void dot(GuiGraphics d, int x, int y, int color) { box(d, x, y, 6, 6, color); }
    private static void dashed(GuiGraphics d, int x, int y, int w, int color) { for (int dx = 0; dx < w; dx += 7) d.fill(x + dx, y, x + Math.min(w, dx + 3), y + 1, color); }
    private static void dashedVertical(GuiGraphics d, int x, int y, int h, int color) { for (int dy = 0; dy < h; dy += 6) d.fill(x, y + dy, x + 1, y + Math.min(h, dy + 3), color); }
    private static String clock(long time) { return CLOCK.format(Instant.ofEpochMilli(time)); }
    private static String number(double value) { return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", ""); }
    private static String money(double value) { return String.format(Locale.ROOT, "%,.2f", value); }
    private static String signed(double value) { return (value >= 0 ? "+" : "") + money(value); }
    private static String optional(double value) { return value > 0 ? number(value) : "未設定"; }
    private static int tone(double value) { return value > 0 ? GREEN : value < 0 ? RED : MUTED; }
    private static double parse(EditBox f) {
        if (f.getValue().isBlank()) return 0;
        try { double value = Double.parseDouble(f.getValue()); if (!Double.isFinite(value)) throw new NumberFormatException(); return value; }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("数値を確認してください。"); }
    }
    private static int integer(EditBox f) {
        double value = parse(f); if (value != Math.floor(value) || value > Integer.MAX_VALUE) throw new IllegalArgumentException("倍率と期限は整数で指定してください。"); return (int) value;
    }
    private static double numberOrZero(EditBox f) { try { return parse(f); } catch (IllegalArgumentException ignored) { return 0; } }

    /** Keeps Minecraft's editing/clipboard behavior and draws crisp text without its dark shadow. */
    private final class NumberField extends EditBox {
        private int anchor, dragAnchor, first, ticks;
        NumberField(int x, int y, int width, String label) { super(FxScreen.this.font, x, y, width, 22, Component.literal(label)); }
        @Override public void tick() { super.tick(); ticks++; }
        @Override public void setHighlightPos(int position) { super.setHighlightPos(position); anchor = position; }
        @Override public void renderWidget(GuiGraphics d, int mouseX, int mouseY, float delta) {
            if (!isVisible()) return;
            String value = getValue(); int cursor = Math.max(0, Math.min(value.length(), getCursorPosition()));
            first = Math.max(0, Math.min(first, cursor));
            while (first < cursor && measure(value.substring(first, cursor)) > getWidth() - 5) first++;
            int last = value.length();
            while (last > first && measure(value.substring(first, last)) > getWidth() - 3) last--;
            int selectedStart = Math.max(first, Math.min(cursor, Math.min(value.length(), anchor)));
            int selectedEnd = Math.min(last, Math.max(cursor, Math.min(value.length(), anchor)));
            if (selectedEnd > selectedStart && isFocused()) {
                int left = getX() + measure(value.substring(first, selectedStart));
                int right = getX() + measure(value.substring(first, selectedEnd));
                d.fill(left, getY() + 2, right, getY() + 19, 0x335966EE);
            }
            text(d, value.substring(first, last), getX() + 1, getY() + 7, INK);
            if (isFocused() && ticks / 10 % 2 == 0) {
                int cx = getX() + measure(value.substring(first, cursor)) + 1;
                d.fill(cx, getY() + 3, cx + 1, getY() + 18, BLUE);
            }
        }
        private int indexAt(double x) {
            String value = getValue(); double target = x - getX();
            for (int i = first; i < value.length(); i++)
                if (target < (measure(value.substring(first, i)) + measure(value.substring(first, i + 1))) / 2.0) return i;
            return value.length();
        }
        @Override public void onClick(double x, double y) {
            int position = indexAt(x); moveCursorTo(position); setHighlightPos(position); dragAnchor = position;
        }
        @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
            if (button != 0 || !isFocused()) return false;
            setCursorPosition(indexAt(x)); setHighlightPos(dragAnchor); return true;
        }
    }
}

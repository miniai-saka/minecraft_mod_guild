package dev.guildquests.fx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FxModels {
    private FxModels() { }
    public enum Side { BUY, SELL; public int sign() { return this == BUY ? 1 : -1; } }
    public enum OrderType { MARKET, LIMIT, STOP, OCO, IFD, IFO }
    public enum Entry { MARKET, LIMIT, STOP }

    public static final class Request {
        public String op = "";
        public String symbol = "AUR";
        public OrderType type = OrderType.MARKET;
        public Entry entry = Entry.LIMIT;
        public Side side = Side.BUY;
        public double units;
        public int leverage = 10;
        public double price;
        public double secondPrice;
        public double takeProfit;
        public double stopLoss;
        public double trailingDistance;
        public int expiryMinutes;
        public long id;
        public long amount;
    }

    public static final class Candle {
        public long time;
        public double open, high, low, close, volume;
        public Candle(long time, double price) {
            this.time = time; open = high = low = close = price;
        }
        public void add(double price, double activity) {
            high = Math.max(high, price); low = Math.min(low, price); close = price; volume += activity;
        }
    }

    public static final class Market {
        public String symbol;
        public double price;
        public double previous;
        public double trend;
        public int shockLeft;
        public String event = "通常相場";
        public long eventTime;
        public List<Candle> candles = new ArrayList<>();
        /** In-game day this anchor belongs to, and the once-per-day open/target drawn from it. */
        public long day;
        public double dayOpen;
        public double dayTarget;
        public long dayStart;
        /** GARCH(1,1) variance and the slow trend component. */
        public double variance;
        public double slowTrend;
    }

    public static final class Position {
        public long id;
        public String symbol;
        public Side side;
        public double units, entryPrice, margin, takeProfit, stopLoss, trailingDistance, bestPrice;
        public int leverage;
        public long openedAt;
    }

    /** OCO entry alternatives share one reservation. TP/SL on a position form exit OCO. */
    public static final class Order {
        public long id;
        public String symbol;
        public Side side;
        public OrderType type;
        public Entry entry;
        public double units, price, secondPrice, takeProfit, stopLoss, trailingDistance, reserved;
        public int leverage;
        public long createdAt, expiresAt;
    }

    public static final class History {
        public long time, id;
        public String symbol, action, detail;
        public double price, units, pnl, fee;
        public History(long time, long id, String symbol, String action, String detail,
                       double price, double units, double pnl, double fee) {
            this.time = time; this.id = id; this.symbol = symbol; this.action = action;
            this.detail = detail; this.price = price; this.units = units; this.pnl = pnl; this.fee = fee;
        }
    }

    public static final class Account {
        public double balance;
        public double realized;
        public double fees;
        public int zeroCuts;
        public int marginCalls;
        public double pendingMarginCall;
        public boolean tradingLocked;
        public List<Position> positions = new ArrayList<>();
        public List<Order> orders = new ArrayList<>();
        public List<History> history = new ArrayList<>();
    }

    public static final class State {
        public int schema = 1;
        public long nextId = 1;
        public long time = System.currentTimeMillis();
        public long randomState = 0x1234abcd5678ef90L ^ System.nanoTime();
        /** In-game day number that owns dailySeed. Missing in older saves. */
        public long seedDay = Long.MIN_VALUE;
        /** PRNG seed derived from seedSalt and seedDay. Not exposed to clients. */
        public long dailySeed;
        /** Secret per-save salt used to derive dailySeed from the in-game day. */
        public long seedSalt;
        public Map<String, Market> markets = new LinkedHashMap<>();
        public Map<String, Account> accounts = new LinkedHashMap<>();
    }

    public static final class Quote {
        public String symbol, name, event;
        /** Japanese balance profile label; null for snapshots from older servers. */
        public String style;
        public int color;
        public int maxLeverage;
        public double commissionBps;
        public double price, bid, ask, change, high, low;
        public long eventTime;
    }

    public static final class Snapshot {
        public boolean open;
        public boolean enabled;
        /** True when debt can only be repaid by mining, so the FX repayment action is hidden. */
        public boolean miningDebtRepaymentOnly;
        public String selected;
        public String message = "";
        public boolean error;
        public long time, wallet;
        public FxConfig config;
        public Account account;
        public double equity, usedMargin, reservedMargin, freeMargin, unrealized;
        /** Realised cash above margin; the only amount that may be withdrawn. */
        public double withdrawable;
        public long guildDebt;
        public boolean delinquent;
        public List<Quote> quotes = new ArrayList<>();
        public List<Candle> candles = new ArrayList<>();
    }
}

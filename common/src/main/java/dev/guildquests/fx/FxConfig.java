package dev.guildquests.fx;

import java.util.ArrayList;
import java.util.List;

/** All rates are fractions unless the field ends in Bps (1 bps = 0.01%). */
public final class FxConfig {
    public boolean enabled = true;
    public boolean marginCallEnabled = false;
    public String defaultUi = "MINECRAFT";
    public int marketIntervalTicks = 20;
    public int snapshotIntervalTicks = 20;
    public int maxLeverage = 1000;
    public double minUnits = 0.01;
    public double maxUnits = 1_000_000;
    public double unitStep = 0.01;
    public double maxNotional = 100_000_000;
    public double maxAccountBalance = 1_000_000_000_000.0;
    public int maxPositions = 32;
    public int maxOrders = 64;
    public int historyLimit = 200;
    public int candleSeconds = 5;
    public int candleHistory = 720;
    public int initialHistoryCandles = 120;
    public int maxExpiryMinutes = 1440;
    public double spreadBps = 2.0;
    public double commissionBps = 0.5;
    public double marketSlippageBps = 0.4;
    public double stopOutRatio = 0.5;
    public double volatility = 0.0009;
    public double trendPersistence = 0.90;
    public double meanReversion = 0.00003;
    public double dailyBias = 0.00003;
    public double dailyVolatilityMin = 0.7;
    public double dailyVolatilityMax = 1.6;
    public double dailyShockMin = 0.6;
    public double dailyShockMax = 1.6;
    /** Realistic price model: daily normal anchor, GARCH, jumps and a slow trend. Off = classic walk. */
    public boolean realisticPricesEnabled = false;

    /** Standard deviation of the once-per-day log return draw (0.03 is about 3%). */
    public double dailyReturnSigma = 0.03;
    /** Hard cap on a single day's log return, in either direction. */
    public double dailyReturnMax = 0.15;
    /** GARCH(1,1) volatility clustering; alpha + beta stays below 1. */
    public double garchAlpha = 0.10;
    public double garchBeta = 0.85;
    /** Merton jump diffusion: per-step jump probability and log-jump size. */
    public double jumpProbability = 0.0008;
    public double jumpSigma = 0.035;
    /** Long-memory trend persistence; near 1 keeps a direction for minutes, not seconds. */
    public double slowTrendPersistence = 0.995;
    /** Optional real-market symbol fed from a public HTTP API instead of the simulated walk. */
    public boolean realMarketEnabled = false;
    public String realMarketSymbol = "BTC";
    public String realMarketName = "ビットコイン";
    public String realMarketApiUrl = "https://api.coinbase.com/v2/prices/BTC-USD/spot";
    public int realMarketRefreshSeconds = 60;
    public double realMarketInitialPrice = 60000;
    public double crashProbability = 0.0005;
    public double rallyProbability = 0.00035;
    public double shockMin = 0.04;
    public double shockMax = 0.18;
    public int shockDurationSteps = 45;
    public double shockVolatilityMultiplier = 3;
    public double minimumPrice = 0.01;
    public double maximumPrice = 1_000_000;
    public List<Symbol> symbols = new ArrayList<>(List.of(
            new Symbol("AUR", "オーロラ", 100, 1.0, 0x5966EE, Style.BALANCED),
            new Symbol("EMB", "エンバー", 50, 1.65, 0xEB9059, Style.STABLE),
            new Symbol("TID", "タイド", 25, 0.75, 0x24A99B, Style.VOLATILE)));

    /** Per-symbol balance profile. Factors are applied to the global market settings. */
    public enum Style {
        STABLE("安定", 0.40, 0.80, 30.0, 0.30, 0.60, 0.80, 0.80, 0.25),
        BALANCED("バランス", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0),
        VOLATILE("変動", 1.80, 1.05, 0.10, 1.80, 1.40, 1.40, 1.50, 1.0);

        private final String label;
        private final double volatility;
        private final double trend;
        private final double meanReversion;
        private final double shockChance;
        private final double shockAmplitude;
        private final double spread;
        private final double commission;
        private final double leverage;

        Style(String label, double volatility, double trend, double meanReversion, double shockChance,
              double shockAmplitude, double spread, double commission, double leverage) {
            this.label = label;
            this.volatility = volatility;
            this.trend = trend;
            this.meanReversion = meanReversion;
            this.shockChance = shockChance;
            this.shockAmplitude = shockAmplitude;
            this.spread = spread;
            this.commission = commission;
            this.leverage = leverage;
        }

        public String label() { return label; }
        public double volatilityFactor() { return volatility; }
        public double trendFactor() { return trend; }
        public double meanReversionFactor() { return meanReversion; }
        public double shockChanceFactor() { return shockChance; }
        public double shockAmplitudeFactor() { return shockAmplitude; }
        public double spreadFactor() { return spread; }
        public double commissionFactor() { return commission; }
        public double leverageFactor() { return leverage; }
    }

    public static final class Symbol {
        public String id;
        public String name;
        public double initialPrice;
        public double volatilityMultiplier;
        public int color;
        /** Balance profile. Missing in old configs; sanitize fills it from the symbol id. */
        public Style style;

        public Symbol(String id, String name, double price, double volatility, int color) {
            this(id, name, price, volatility, color, Style.BALANCED);
        }

        public Symbol(String id, String name, double price, double volatility, int color, Style style) {
            this.id = id; this.name = name; this.initialPrice = price;
            this.volatilityMultiplier = volatility; this.color = color; this.style = style;
        }
    }

    public FxConfig sanitize() {
        defaultUi = "WHITE".equalsIgnoreCase(defaultUi) ? "WHITE" : "MINECRAFT";
        marketIntervalTicks = Math.max(1, Math.min(1200, marketIntervalTicks));
        snapshotIntervalTicks = Math.max(5, Math.min(200, snapshotIntervalTicks));
        maxLeverage = Math.max(1, Math.min(1000, maxLeverage));
        minUnits = bounded(minUnits, 0.0001, 1e6, .01);
        maxUnits = bounded(maxUnits, minUnits, 1e9, 1e6);
        unitStep = bounded(unitStep, .0001, maxUnits, .01);
        maxNotional = bounded(maxNotional, 1, 1e12, 1e8);
        maxAccountBalance = bounded(maxAccountBalance, 1, 1e12, 1e12);
        maxPositions = Math.max(1, Math.min(64, maxPositions));
        maxOrders = Math.max(1, Math.min(128, maxOrders));
        historyLimit = Math.max(10, Math.min(500, historyLimit));
        candleSeconds = Math.max(1, Math.min(300, candleSeconds));
        candleHistory = Math.max(60, Math.min(1440, candleHistory));
        initialHistoryCandles = Math.max(0, Math.min(candleHistory, initialHistoryCandles));
        maxExpiryMinutes = Math.max(1, Math.min(10080, maxExpiryMinutes));
        spreadBps = bounded(spreadBps, 0, 100, 2.0);
        commissionBps = bounded(commissionBps, 0, 100, .5);
        marketSlippageBps = bounded(marketSlippageBps, 0, 100, .4);
        stopOutRatio = bounded(stopOutRatio, .01, 1, .5);
        volatility = bounded(volatility, 0, .1, .0009);
        trendPersistence = bounded(trendPersistence, 0, .9999, .90);
        meanReversion = bounded(meanReversion, 0, .1, .00003);
        dailyBias = bounded(dailyBias, 0, .002, .00003);
        dailyVolatilityMin = bounded(dailyVolatilityMin, .1, 3, .7);
        dailyVolatilityMax = bounded(dailyVolatilityMax, dailyVolatilityMin, 5, 1.6);
        dailyShockMin = bounded(dailyShockMin, .1, 3, .6);
        dailyShockMax = bounded(dailyShockMax, dailyShockMin, 5, 1.6);
        dailyReturnSigma = bounded(dailyReturnSigma, .0001, .5, .03);
        dailyReturnMax = bounded(dailyReturnMax, .005, .9, .15);
        garchAlpha = bounded(garchAlpha, 0, .5, .10);
        garchBeta = bounded(garchBeta, 0, .98, .85);
        if (garchAlpha + garchBeta >= .9999) garchBeta = Math.max(0, .9999 - garchAlpha);
        jumpProbability = bounded(jumpProbability, 0, .5, .0008);
        jumpSigma = bounded(jumpSigma, 0, .5, .035);
        slowTrendPersistence = bounded(slowTrendPersistence, 0, .9999, .995);
        crashProbability = bounded(crashProbability, 0, 1, .0005);
        rallyProbability = bounded(rallyProbability, 0, 1 - crashProbability, .00035);
        shockMin = bounded(shockMin, .001, .9, .04);
        shockMax = bounded(shockMax, shockMin, .95, .18);
        shockDurationSteps = Math.max(1, Math.min(3600, shockDurationSteps));
        shockVolatilityMultiplier = bounded(shockVolatilityMultiplier, 1, 20, 3);
        minimumPrice = bounded(minimumPrice, .0001, 1e5, .01);
        maximumPrice = bounded(maximumPrice, minimumPrice * 2, 1e9, 1e6);
        if (symbols == null || symbols.isEmpty()) symbols = new FxConfig().symbols;
        if (realMarketEnabled) {
            realMarketSymbol = realMarketSymbol == null || !realMarketSymbol.matches("[A-Z][A-Z0-9]{1,7}")
                    ? "BTC" : realMarketSymbol;
            realMarketName = realMarketName == null || realMarketName.isBlank() ? realMarketSymbol : realMarketName;
            realMarketRefreshSeconds = Math.max(10, Math.min(3600, realMarketRefreshSeconds));
            realMarketInitialPrice = bounded(realMarketInitialPrice, minimumPrice, maximumPrice, 60000);
            if (symbols.stream().noneMatch(s -> s != null && realMarketSymbol.equals(s.id))) {
                List<Symbol> grown = new ArrayList<>(symbols);
                grown.add(new Symbol(realMarketSymbol, realMarketName, realMarketInitialPrice, 1.0, 0xF7931A, Style.VOLATILE));
                symbols = grown;
            }
        }
        List<Symbol> clean = new ArrayList<>();
        for (Symbol s : symbols) {
            if (s == null || s.id == null || !s.id.matches("[A-Z][A-Z0-9]{1,7}")
                    || clean.stream().anyMatch(other -> other.id.equals(s.id))) continue;
            s.name = s.name == null || s.name.isBlank() ? s.id : s.name.substring(0, Math.min(24, s.name.length()));
            s.initialPrice = bounded(s.initialPrice, minimumPrice, maximumPrice, 100);
            s.volatilityMultiplier = bounded(s.volatilityMultiplier, 0, 10, 1);
            if (s.style == null) s.style = defaultStyle(s.id);
            clean.add(s);
            if (clean.size() >= 8) break;
        }
        symbols = clean.isEmpty() ? new FxConfig().symbols : clean;
        return this;
    }

    static double bounded(double n, double low, double high, double fallback) {
        return Double.isFinite(n) ? Math.max(low, Math.min(high, n)) : fallback;
    }

    /** Profiles for the bundled symbols; any other symbol defaults to BALANCED. */
    private static Style defaultStyle(String id) {
        if ("EMB".equals(id)) return Style.STABLE;
        if ("TID".equals(id)) return Style.VOLATILE;
        return Style.BALANCED;
    }
}

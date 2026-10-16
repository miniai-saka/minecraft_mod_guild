package dev.guildquests.fx;

import java.util.ArrayList;
import java.util.List;

/** All rates are fractions unless the field ends in Bps (1 bps = 0.01%). */
public final class FxConfig {
    public boolean enabled = true;
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
    public double spreadBps = 1.5;
    public double commissionBps = 0.1;
    public double marketSlippageBps = 0.15;
    public double stopOutRatio = 0.5;
    public double volatility = 0.0009;
    public double trendPersistence = 0.97;
    public double meanReversion = 0.00001;
    public double crashProbability = 0.0005;
    public double rallyProbability = 0.00035;
    public double shockMin = 0.04;
    public double shockMax = 0.18;
    public int shockDurationSteps = 45;
    public double shockVolatilityMultiplier = 3;
    public double minimumPrice = 0.01;
    public double maximumPrice = 1_000_000;
    public List<Symbol> symbols = new ArrayList<>(List.of(
            new Symbol("AUR", "オーロラ", 100, 1.0, 0x5966EE),
            new Symbol("EMB", "エンバー", 50, 1.65, 0xEB9059),
            new Symbol("TID", "タイド", 25, 0.75, 0x24A99B)));

    public static final class Symbol {
        public String id;
        public String name;
        public double initialPrice;
        public double volatilityMultiplier;
        public int color;

        public Symbol(String id, String name, double price, double volatility, int color) {
            this.id = id; this.name = name; this.initialPrice = price;
            this.volatilityMultiplier = volatility; this.color = color;
        }
    }

    public FxConfig sanitize() {
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
        spreadBps = bounded(spreadBps, 0, 100, 1.5);
        commissionBps = bounded(commissionBps, 0, 100, .1);
        marketSlippageBps = bounded(marketSlippageBps, 0, 100, .15);
        stopOutRatio = bounded(stopOutRatio, .01, 1, .5);
        volatility = bounded(volatility, 0, .1, .0009);
        trendPersistence = bounded(trendPersistence, 0, .9999, .97);
        meanReversion = bounded(meanReversion, 0, .1, .00001);
        crashProbability = bounded(crashProbability, 0, 1, .0005);
        rallyProbability = bounded(rallyProbability, 0, 1 - crashProbability, .00035);
        shockMin = bounded(shockMin, .001, .9, .04);
        shockMax = bounded(shockMax, shockMin, .95, .18);
        shockDurationSteps = Math.max(1, Math.min(3600, shockDurationSteps));
        shockVolatilityMultiplier = bounded(shockVolatilityMultiplier, 1, 20, 3);
        minimumPrice = bounded(minimumPrice, .0001, 1e5, .01);
        maximumPrice = bounded(maximumPrice, minimumPrice * 2, 1e9, 1e6);
        if (symbols == null || symbols.isEmpty()) symbols = new FxConfig().symbols;
        List<Symbol> clean = new ArrayList<>();
        for (Symbol s : symbols) {
            if (s == null || s.id == null || !s.id.matches("[A-Z][A-Z0-9]{1,7}")
                    || clean.stream().anyMatch(other -> other.id.equals(s.id))) continue;
            s.name = s.name == null || s.name.isBlank() ? s.id : s.name.substring(0, Math.min(24, s.name.length()));
            s.initialPrice = bounded(s.initialPrice, minimumPrice, maximumPrice, 100);
            s.volatilityMultiplier = bounded(s.volatilityMultiplier, 0, 10, 1);
            clean.add(s);
            if (clean.size() >= 8) break;
        }
        symbols = clean.isEmpty() ? new FxConfig().symbols : clean;
        return this;
    }

    static double bounded(double n, double low, double high, double fallback) {
        return Double.isFinite(n) ? Math.max(low, Math.min(high, n)) : fallback;
    }
}

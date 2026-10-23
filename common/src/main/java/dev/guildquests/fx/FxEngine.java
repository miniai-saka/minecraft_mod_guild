package dev.guildquests.fx;

import dev.guildquests.fx.FxModels.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Server-owned exchange. Contains no Minecraft classes, wall clock prices, or external services. */
public final class FxEngine {
    private static final SecureRandom SALT_RANDOM = new SecureRandom();
    public final State state;
    private FxConfig config;

    public FxEngine(FxConfig config, State state) {
        this.state = state;
        configure(config);
    }

    public void configure(FxConfig value) {
        config = value.sanitize();
        state.nextId = Math.max(1, state.nextId);
        for (FxConfig.Symbol s : config.symbols) {
            state.markets.computeIfAbsent(s.id, id -> {
                Market m = new Market(); m.symbol = id; m.price = m.previous = s.initialPrice;
                seedMarket(m, s);
                return m;
            });
        }
        for (Market m : state.markets.values()) {
            m.price = FxConfig.bounded(m.price, config.minimumPrice, config.maximumPrice, 100);
            if (m.candles == null) m.candles = new ArrayList<>();
            trim(m.candles, config.candleHistory);
        }
    }

    public FxConfig config() { return config; }

    /**
     * Selects the in-game day's seed. The seed is a pure function of the persisted market salt and
     * the day number, so a restart on the same day resumes the same day seed instead of rerolling it.
     * The first step for a new day reseeds the PRNG; restarts during the same day keep the persisted
     * PRNG position.
     */
    public void beginDay(long day) {
        if (state.seedSalt == 0) {
            long salt;
            do { salt = SALT_RANDOM.nextLong(); } while (salt == 0);
            state.seedSalt = salt;
        }
        long seed = dailySeed(day);
        if (state.seedDay == day && state.dailySeed == seed) {
            for (Market m : state.markets.values()) {
                if (m.day != day || m.dayOpen <= 0 || m.dayTarget <= 0) anchorDay(m, day);
            }
            return;
        }
        state.seedDay = day;
        state.dailySeed = seed;
        state.randomState = seed;
        for (Market m : state.markets.values()) anchorDay(m, day);
    }

    /** Draws today's open and close from a normal daily return; the intraday path follows this line. */
    private void anchorDay(Market m, long day) {
        FxConfig.Symbol setting = settings(m.symbol);
        FxConfig.Style style = style(setting);
        m.day = day;
        m.dayOpen = m.price > 0 ? m.price : setting.initialPrice;
        m.dayStart = state.time;
        double z = Math.max(-3, Math.min(3, normal(symbolSeed(m.symbol))));
        double sigma = config.dailyReturnSigma * style.volatilityFactor()
                * Math.max(.1, setting.volatilityMultiplier);
        double move = Math.max(-config.dailyReturnMax, Math.min(config.dailyReturnMax, z * sigma));
        m.dayTarget = m.dayOpen * Math.exp(move);
    }

    /** Deterministic standard normal from two mixed uniforms (Box-Muller). */
    private static double normal(long seed) {
        double u1 = Math.max(1e-9, unit(mix(seed + 0x9E3779B97F4A7C15L)));
        double u2 = unit(mix(seed + 0xBF58476D1CE4E5B9L));
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    long dailySeed(long day) { return mix(state.seedSalt ^ mix(day)); }

    static long mix(long value) {
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private void seedMarket(Market m, FxConfig.Symbol s) {
        long now = state.time;
        for (int i = config.initialHistoryCandles; i > 0; i--) {
            state.time = now - i * config.candleSeconds * 1000L;
            m.previous = m.price;
            double movement = (random() + random() + random() - 1.5) * config.volatility
                    * s.volatilityMultiplier * style(s).volatilityFactor() * Math.sqrt(config.candleSeconds);
            updatePrice(m, m.price * Math.exp(movement));
        }
        state.time = now;
        m.candles.add(new Candle(bucket(now), m.price));
        trim(m.candles, config.candleHistory);
    }
    public Account account(String owner) { return state.accounts.computeIfAbsent(owner, key -> new Account()); }
    public double bid(Market m) { return m.price * (1 - spreadBps(m.symbol) / 20000); }
    public double ask(Market m) { return m.price * (1 + spreadBps(m.symbol) / 20000); }
    public double pnl(Position p) {
        Market m = market(p.symbol);
        return (exitQuote(m, p.side) - p.entryPrice) * p.units * p.side.sign();
    }
    public double unrealized(Account a) { return a.positions.stream().mapToDouble(this::pnl).sum(); }
    public double equity(Account a) { return a.balance + unrealized(a); }
    public double margin(Account a) { return a.positions.stream().mapToDouble(p -> p.margin).sum(); }
    public double reserved(Account a) { return a.orders.stream().mapToDouble(o -> o.reserved).sum(); }
    public double free(Account a) { return equity(a) - margin(a) - reserved(a); }

    /**
     * Cash that may leave the FX account: realised balance above the used and reserved margin.
     * Unrealised profit counts towards margin headroom but must not be withdrawable, otherwise a
     * player could recover the initial margin on a winning position and keep the upside with no
     * downside (especially with zero-cut enabled).
     */
    public double withdrawable(Account a) {
        return Math.max(0, Math.min(free(a), a.balance - margin(a) - reserved(a)));
    }

    public void deposit(Account a, long amount) {
        require(amount > 0 && amount <= config.maxAccountBalance - a.balance, "入金額が口座上限を超えています。");
        a.balance += amount;
        record(a, 0, "GC", "入金", amount + " GC", 0, 0, amount, 0);
    }

    public void withdraw(Account a, long amount) {
        protect(a);
        require(amount > 0 && amount <= Math.floor(withdrawable(a) + 1e-8), "出金可能な余剰証拠金が不足しています。");
        a.balance -= amount;
        record(a, 0, "GC", "出金", amount + " GC", 0, 0, -amount, 0);
    }

    public String submit(Account a, Request r) {
        protect(a);
        require(!a.tradingLocked && a.pendingMarginCall <= 0, "債務の滞納・追証の精算中は新規注文できません。");
        require(config.enabled, "FXの新規取引は停止中です。決済・取消・出金は利用できます。");
        require(r.side != null && r.type != null && r.entry != null, "注文種別が不正です。");
        require(config.symbols.stream().anyMatch(s -> s.id.equals(r.symbol)), "取扱通貨を選択してください。");
        Market m = market(r.symbol);
        require(finite(r.units) && r.units >= config.minUnits && r.units <= config.maxUnits, "数量が取引範囲外です。");
        double steps = r.units / config.unitStep;
        require(Math.abs(steps - Math.rint(steps)) < 1e-6, "数量は " + config.unitStep + " 単位で指定してください。");
        int maxLeverage = maxLeverage(r.symbol);
        require(r.leverage >= 1 && r.leverage <= maxLeverage, "レバレッジは1〜" + maxLeverage + "倍です。");
        require(a.positions.size() + a.orders.size() < config.maxPositions + config.maxOrders, "注文・建玉数の上限です。");
        Entry entry = switch (r.type) {
            case MARKET -> Entry.MARKET;
            case LIMIT, OCO -> Entry.LIMIT;
            case STOP -> Entry.STOP;
            case IFD, IFO -> r.entry;
        };
        double ref = entry == Entry.MARKET ? entryQuote(m, r.side) : validPrice(r.price);
        if (entry != Entry.MARKET) validateEntry(entry, r.side, ref, entryQuote(m, r.side));
        if (r.type == OrderType.OCO) {
            validPrice(r.secondPrice);
            validateEntry(Entry.STOP, r.side, r.secondPrice, entryQuote(m, r.side));
        }
        if (r.type == OrderType.IFD) require(r.takeProfit > 0 && r.stopLoss == 0, "IFDは新規注文＋利確指値を指定してください。");
        if (r.type == OrderType.IFO) require(r.takeProfit > 0 && r.stopLoss > 0, "IFOは新規注文＋利確・損切を指定してください。");
        validateProtection(r.side, ref, r.takeProfit, r.stopLoss, r.trailingDistance);
        if (r.type == OrderType.OCO) validateProtection(r.side, r.secondPrice, r.takeProfit, r.stopLoss, r.trailingDistance);
        double reservePrice = r.type == OrderType.OCO ? Math.max(ref, r.secondPrice) : ref;
        double notional = r.units * reservePrice;
        require(finite(notional) && notional <= config.maxNotional, "取引金額が上限を超えています。");
        require(r.expiryMinutes >= 0 && r.expiryMinutes <= config.maxExpiryMinutes, "有効期限が範囲外です。");
        double reservation = notional / r.leverage + fee(notional, r.symbol);
        require(reservation <= free(a), "余剰証拠金が不足しています（必要 " + format(reservation) + " GC）。");
        Order o = new Order();
        o.id = state.nextId++; o.symbol = r.symbol; o.side = r.side; o.type = r.type; o.entry = entry;
        o.units = r.units; o.leverage = r.leverage; o.price = ref; o.secondPrice = r.secondPrice;
        o.takeProfit = r.takeProfit; o.stopLoss = r.stopLoss; o.trailingDistance = r.trailingDistance;
        o.reserved = reservation; o.createdAt = state.time;
        o.expiresAt = r.expiryMinutes == 0 ? 0 : state.time + r.expiryMinutes * 60_000L;
        if (entry == Entry.MARKET) {
            fill(a, o, executionEntry(m, o.side), false);
            protect(a);
            return "成行注文 #" + o.id + " が約定しました。";
        }
        require(a.orders.size() < config.maxOrders, "未約定注文数の上限です。");
        a.orders.add(o);
        record(a, o.id, o.symbol, "注文受付", o.type + " / " + o.side, o.price, o.units, 0, 0);
        return o.type + " 注文 #" + o.id + " を受け付けました。";
    }

    public void cancel(Account a, long id) {
        Order o = a.orders.stream().filter(item -> item.id == id).findFirst().orElseThrow(() -> error("注文が見つかりません。"));
        a.orders.remove(o);
        record(a, o.id, o.symbol, "取消", o.type.toString(), o.price, o.units, 0, 0);
    }

    public void cancelAll(Account a) {
        for (Order o : new ArrayList<>(a.orders)) cancel(a, o.id);
    }

    public void close(Account a, long id, double units) {
        protect(a);
        Position p = findPosition(a, id);
        require(finite(units) && (units == 0 || units > 0 && units <= p.units), "決済数量が不正です。");
        double size = units == 0 ? p.units : units;
        require(size == p.units || Math.abs(size / config.unitStep - Math.rint(size / config.unitStep)) < 1e-6,
                "決済数量が取引単位と一致しません。");
        closeAt(a, p, size, executionExit(market(p.symbol), p.side), "手動決済");
        protect(a);
    }

    public void closeAll(Account a) {
        for (Position p : new ArrayList<>(a.positions)) closeAt(a, p, p.units, executionExit(market(p.symbol), p.side), "全決済");
        zeroFloor(a);
    }

    public void amend(Account a, long id, double tp, double sl, double trailing) {
        protect(a);
        Position p = findPosition(a, id);
        validateProtection(p.side, exitQuote(market(p.symbol), p.side), tp, sl, trailing);
        p.takeProfit = tp; p.stopLoss = sl; p.trailingDistance = trailing;
        p.bestPrice = exitQuote(market(p.symbol), p.side);
        record(a, p.id, p.symbol, "決済OCO変更", "TP " + format(tp) + " / SL " + format(sl), 0, p.units, 0, 0);
    }

    /** One server-time step. Disconnected players remain in the same risk loop. */
    public void step(long elapsedMillis) {
        state.time += Math.max(1, elapsedMillis);
        for (Market m : state.markets.values()) {
            if (config.realMarketEnabled && m.symbol.equals(config.realMarketSymbol) && RealMarket.price() > 0) {
                // Real-market symbol: follow the API price instead of the simulated walk.
                m.previous = m.price;
                updatePrice(m, RealMarket.price());
                continue;
            }
            FxConfig.Symbol s = settings(m.symbol);
            FxConfig.Style style = style(s);
            double roll = random();
            double shock = 0;
            double dayShock = dailyShock(m.symbol);
            double shockChance = Math.min(1, (config.crashProbability + config.rallyProbability)
                    * style.shockChanceFactor() * dayShock);
            if (roll < shockChance) {
                boolean crash = roll < Math.min(1, config.crashProbability * style.shockChanceFactor()
                        * dayShock);
                shock = (config.shockMin + random() * (config.shockMax - config.shockMin))
                        * style.shockAmplitudeFactor() * (crash ? -1 : 1);
                m.shockLeft = config.shockDurationSteps;
                m.event = crash ? "暴落・流動性ショック" : "急騰・買い集中";
                m.eventTime = state.time;
            }
            double noise = (random() + random() + random() + random() + random() + random() - 3) * Math.sqrt(2);
            double targetVol = config.volatility * s.volatilityMultiplier * style.volatilityFactor()
                    * dailyVolatility(m.symbol);
            double targetVar = Math.max(1e-12, targetVol * targetVol);
            m.trend = m.trend * Math.min(.9999, config.trendPersistence * style.trendFactor())
                    + (random() - .5) * targetVol * .08;
            double move;
            if (config.realisticPricesEnabled) {
                // GARCH(1,1): the last return feeds the next variance, so calm and stormy phases cluster.
                if (!(m.variance > 0)) m.variance = targetVar;
                double sigma = Math.sqrt(m.variance) * (m.shockLeft > 0 ? config.shockVolatilityMultiplier : 1);
                double stepReturn = noise * sigma;
                // Merton jump diffusion: rare fat-tailed moves on top of the diffusion.
                if (random() < config.jumpProbability) {
                    stepReturn += config.jumpSigma * Math.max(-4, Math.min(4, normal(state.randomState)));
                }
                m.variance = targetVar * Math.max(.01, 1 - config.garchAlpha - config.garchBeta)
                        + config.garchAlpha * stepReturn * stepReturn + config.garchBeta * m.variance;
                m.slowTrend = m.slowTrend * config.slowTrendPersistence + (random() - .5) * targetVol * .05;
                double dayLengthMs = 24000.0 * 50.0;
                double dayFraction = m.dayTarget > 0
                        ? Math.max(0, Math.min(1, (state.time - m.dayStart) / dayLengthMs)) : 1;
                double dayPath = m.dayTarget > 0
                        ? m.dayOpen * Math.exp(Math.log(m.dayTarget / m.dayOpen) * dayFraction) : m.price;
                double dayPull = m.dayTarget > 0 ? Math.log(dayPath / m.price) * 0.02 : 0;
                move = stepReturn + m.trend + m.slowTrend + dailyBias(m.symbol) + dayPull
                        + Math.log(s.initialPrice / m.price) * config.meanReversion * style.meanReversionFactor();
            } else {
                // Classic model: plain random walk with trend and weak mean reversion.
                move = noise * targetVol * (m.shockLeft > 0 ? config.shockVolatilityMultiplier : 1)
                        + m.trend + dailyBias(m.symbol)
                        + Math.log(s.initialPrice / m.price) * config.meanReversion * style.meanReversionFactor();
            }
            m.previous = m.price;
            updatePrice(m, m.price * Math.exp(Math.max(-1, Math.min(1, move))) * (1 + shock));
            if (m.shockLeft > 0) m.shockLeft--;
            else m.event = "通常相場";
        }
        for (Account a : state.accounts.values()) process(a);
    }

    /** Deterministic quote injection used by the regression suite and administrator shock command. */
    public void setPrice(String symbol, double price) {
        validPrice(price);
        Market m = market(symbol); m.previous = m.price; updatePrice(m, price);
        for (Account a : state.accounts.values()) process(a);
    }

    public void shock(String symbol, boolean crash) {
        Market m = market(symbol);
        double move = (config.shockMin + random() * (config.shockMax - config.shockMin))
                * style(settings(symbol)).shockAmplitudeFactor();
        m.event = crash ? "暴落・流動性ショック" : "急騰・買い集中";
        m.eventTime = state.time; m.shockLeft = config.shockDurationSteps;
        setPrice(symbol, Math.max(config.minimumPrice, Math.min(config.maximumPrice, m.price * (1 + (crash ? -move : move)))));
    }

    private void process(Account a) {
        if (!config.enabled || a.tradingLocked || a.pendingMarginCall > 0) cancelAll(a);
        for (Position p : new ArrayList<>(a.positions)) {
            double quote = exitQuote(market(p.symbol), p.side);
            if (p.trailingDistance > 0) {
                p.bestPrice = p.side == Side.BUY ? Math.max(p.bestPrice, quote) : Math.min(p.bestPrice, quote);
                double trail = p.bestPrice - p.side.sign() * p.trailingDistance;
                if (trail > 0) p.stopLoss = p.stopLoss == 0 ? trail : p.side == Side.BUY ? Math.max(p.stopLoss, trail) : Math.min(p.stopLoss, trail);
            }
            boolean stop = p.stopLoss > 0 && (quote - p.stopLoss) * p.side.sign() <= 0;
            boolean take = p.takeProfit > 0 && (quote - p.takeProfit) * p.side.sign() >= 0;
            if (stop || take) closeAt(a, p, p.units, stop ? executionExit(market(p.symbol), p.side) : quote,
                    stop ? "損切 / OCO" : "利確 / OCO");
        }
        protect(a);
        for (Order o : new ArrayList<>(a.orders)) {
            if (!a.orders.contains(o)) continue;
            if (o.expiresAt > 0 && state.time >= o.expiresAt) {
                a.orders.remove(o); record(a, o.id, o.symbol, "期限切れ", o.type.toString(), o.price, o.units, 0, 0); continue;
            }
            Market m = market(o.symbol);
            double q = entryQuote(m, o.side);
            boolean trigger = o.entry == Entry.LIMIT ? (q - o.price) * o.side.sign() <= 0 : (q - o.price) * o.side.sign() >= 0;
            boolean alternative = o.type == OrderType.OCO && (q - o.secondPrice) * o.side.sign() >= 0;
            if (!trigger && !alternative) continue;
            a.orders.remove(o); // Release the single OCO reservation before checking the actual fill.
            double execution = o.entry == Entry.LIMIT && !alternative
                    ? (o.side == Side.BUY ? Math.min(q, o.price) : Math.max(q, o.price)) : executionEntry(m, o.side);
            try { fill(a, o, execution, true); }
            catch (IllegalArgumentException e) { record(a, o.id, o.symbol, "失効", e.getMessage(), execution, o.units, 0, 0); }
            protect(a);
        }
        // Newly activated IFD / IFO exits can execute in the very same quote after a gap.
        for (Position p : new ArrayList<>(a.positions)) {
            double q = exitQuote(market(p.symbol), p.side);
            if (p.stopLoss > 0 && (q - p.stopLoss) * p.side.sign() <= 0)
                closeAt(a, p, p.units, executionExit(market(p.symbol), p.side), "損切 / OCO");
            else if (p.takeProfit > 0 && (q - p.takeProfit) * p.side.sign() >= 0)
                closeAt(a, p, p.units, q, "利確 / OCO");
        }
        protect(a);
    }

    private void fill(Account a, Order o, double price, boolean pending) {
        double notional = price * o.units;
        double cost = fee(notional, o.symbol);
        double margin = notional / o.leverage;
        // Include the spread loss so opening a position never consumes reserved funds of another order.
        double spreadLoss = (price - exitQuote(market(o.symbol), o.side)) * o.side.sign() * o.units;
        require(a.positions.size() < config.maxPositions, "建玉数の上限です。");
        require(finite(notional) && notional <= config.maxNotional, "約定金額が上限を超えています。");
        require(margin + cost + Math.max(0, spreadLoss) <= free(a), "約定時の証拠金が不足しています。");
        Position p = new Position();
        p.id = o.id; p.symbol = o.symbol; p.side = o.side; p.units = o.units; p.entryPrice = price;
        p.margin = margin; p.leverage = o.leverage; p.takeProfit = o.takeProfit; p.stopLoss = o.stopLoss;
        p.trailingDistance = o.trailingDistance; p.bestPrice = exitQuote(market(o.symbol), o.side); p.openedAt = state.time;
        a.balance -= cost; a.fees += cost; a.realized -= cost; a.positions.add(p);
        record(a, p.id, p.symbol, "新規約定", o.side + " / " + o.leverage + "x" + (pending && o.type == OrderType.OCO ? " / 他方取消" : ""), price, p.units, -cost, cost);
    }

    private void closeAt(Account a, Position p, double units, double price, String reason) {
        double gross = (price - p.entryPrice) * units * p.side.sign();
        double cost = fee(price * units, p.symbol);
        double previousBalance = a.balance;
        a.balance = Math.min(Math.max(config.maxAccountBalance, previousBalance), previousBalance + gross - cost);
        double settled = a.balance - previousBalance;
        a.realized += settled; a.fees += cost;
        double fraction = units / p.units;
        record(a, p.id, p.symbol, reason, p.side.toString(), price, units, settled, cost);
        if (fraction >= 1 - 1e-10) a.positions.remove(p);
        else { p.units -= units; p.margin *= 1 - fraction; }
    }

    /** Cross margin is confined to this FX account; no wallet/bank/debt access exists in the engine. */
    public void protect(Account a) {
        if (!a.positions.isEmpty() && (equity(a) <= 0 || equity(a) <= margin(a) * config.stopOutRatio)) {
            cancelAll(a);
            for (Position p : new ArrayList<>(a.positions))
                closeAt(a, p, p.units, executionExit(market(p.symbol), p.side), "強制ロスカット");
        }
        zeroFloor(a);
        // Falling equity can invalidate reservations even before stop-out.
        while (!a.orders.isEmpty() && free(a) < -1e-7) {
            Order o = a.orders.remove(a.orders.size() - 1);
            record(a, o.id, o.symbol, "証拠金取消", "余剰証拠金不足", o.price, o.units, 0, 0);
        }
    }

    private void zeroFloor(Account a) {
        if (a.positions.isEmpty() && a.balance < 0) {
            double shortage = -a.balance; a.balance = 0; cancelAll(a);
            if (config.marginCallEnabled) {
                a.pendingMarginCall += shortage; a.marginCalls++;
                record(a, 0, "GC", "追証発生", "不足 " + shortage + " GCを精算。未払いは既存の滞納処理へ", 0, 0, 0, 0);
            } else {
                a.zeroCuts++;
                record(a, 0, "GC", "ゼロカット", "不足分を補填・追証なし", 0, 0, shortage, 0);
            }
        }
    }

    public void recordMarginSettlement(Account a, long paid, long unpaid) {
        record(a, 0, "GC", "追証精算", "所持金から " + paid + " GC / 滞納債務へ " + unpaid + " GC", 0, 0, 0, 0);
    }

    public Snapshot snapshot(String owner, String selected, long wallet) {
        Account a = account(owner);
        Snapshot s = new Snapshot(); s.enabled = config.enabled; s.time = state.time; s.wallet = wallet;
        s.selected = state.markets.containsKey(selected) ? selected : config.symbols.get(0).id;
        s.config = config; s.account = a;
        s.unrealized = unrealized(a); s.equity = equity(a); s.usedMargin = margin(a);
        s.reservedMargin = reserved(a); s.freeMargin = free(a); s.withdrawable = withdrawable(a);
        for (Market m : state.markets.values()) {
            FxConfig.Symbol setting = settings(m.symbol);
            Quote q = new Quote(); q.symbol = m.symbol; q.name = setting.name; q.color = setting.color;
            q.style = style(setting).label(); q.maxLeverage = maxLeverage(m.symbol);
            q.commissionBps = config.commissionBps * style(setting).commissionFactor();
            q.price = m.price; q.bid = bid(m); q.ask = ask(m); q.event = m.event; q.eventTime = m.eventTime;
            double first = m.candles.isEmpty() ? m.price : m.candles.get(0).open;
            q.change = (m.price / first - 1) * 100;
            q.high = m.candles.stream().mapToDouble(c -> c.high).max().orElse(m.price);
            q.low = m.candles.stream().mapToDouble(c -> c.low).min().orElse(m.price);
            s.quotes.add(q);
        }
        List<Candle> candles = market(s.selected).candles;
        s.candles = new ArrayList<>(candles.subList(Math.max(0, candles.size() - 360), candles.size()));
        return s;
    }

    private void updatePrice(Market m, double price) {
        m.price = Math.max(config.minimumPrice, Math.min(config.maximumPrice, price));
        long time = bucket(state.time);
        if (m.candles.isEmpty() || m.candles.get(m.candles.size() - 1).time != time)
            m.candles.add(new Candle(time, m.previous > 0 ? m.previous : m.price));
        m.candles.get(m.candles.size() - 1).add(m.price, Math.abs(m.price - m.previous) / m.price * 10000 + 1);
        trim(m.candles, config.candleHistory);
    }

    private long bucket(long time) { long size = config.candleSeconds * 1000L; return time / size * size; }
    private Market market(String id) {
        Market m = state.markets.get(id); require(m != null, "不明な通貨です。"); return m;
    }
    private FxConfig.Symbol settings(String id) {
        return config.symbols.stream().filter(s -> s.id.equals(id)).findFirst()
                .orElseGet(() -> new FxConfig.Symbol(id, id + " (新規停止)", state.markets.get(id).price, 1, 0x8892A5));
    }
    private Position findPosition(Account a, long id) {
        return a.positions.stream().filter(p -> p.id == id).findFirst().orElseThrow(() -> error("建玉が見つかりません。既に決済された可能性があります。"));
    }
    private void validateEntry(Entry entry, Side side, double price, double current) {
        require(entry == Entry.LIMIT ? (price - current) * side.sign() < 0 : (price - current) * side.sign() > 0,
                entry == Entry.LIMIT ? "指値は買いなら現在ASKより下、売りなら現在BIDより上です。" : "逆指値は買いなら現在ASKより上、売りなら現在BIDより下です。");
    }
    private void validateProtection(Side side, double reference, double tp, double sl, double trailing) {
        require(finite(tp) && finite(sl) && finite(trailing) && tp >= 0 && sl >= 0 && trailing >= 0, "決済価格は有限の正数、未指定は0にしてください。");
        if (tp > 0) require(validPrice(tp) > 0 && (tp - reference) * side.sign() > 0, "利確は買いなら基準価格より上、売りなら下です。");
        if (sl > 0) require(validPrice(sl) > 0 && (sl - reference) * side.sign() < 0, "損切は買いなら基準価格より下、売りなら上です。");
        require(trailing == 0 || trailing >= config.minimumPrice && trailing < reference, "トレーリング幅が範囲外です。");
    }
    private double validPrice(double p) { require(finite(p) && p >= config.minimumPrice && p <= config.maximumPrice, "価格が範囲外です。"); return p; }
    private double entryQuote(Market m, Side side) { return side == Side.BUY ? ask(m) : bid(m); }
    private double exitQuote(Market m, Side side) { return side == Side.BUY ? bid(m) : ask(m); }
    private double executionEntry(Market m, Side side) { return entryQuote(m, side) * (1 + side.sign() * config.marketSlippageBps / 10000); }
    private double executionExit(Market m, Side side) { return exitQuote(m, side) * (1 - side.sign() * config.marketSlippageBps / 10000); }
    private double fee(double notional, String symbol) {
        return notional * config.commissionBps * style(settings(symbol)).commissionFactor() / 10000;
    }
    private double spreadBps(String symbol) {
        return config.spreadBps * style(settings(symbol)).spreadFactor();
    }
    private int maxLeverage(String symbol) {
        return Math.max(1, (int) Math.floor(config.maxLeverage * style(settings(symbol)).leverageFactor()));
    }
    private static FxConfig.Style style(FxConfig.Symbol symbol) {
        return symbol.style == null ? FxConfig.Style.BALANCED : symbol.style;
    }
    private long symbolSeed(String symbol) {
        return mix(state.dailySeed ^ mix(symbol.hashCode()));
    }
    double dailyBias(String symbol) {
        double unit = unit(symbolSeed(symbol));
        return (unit * 2 - 1) * config.dailyBias * style(settings(symbol)).volatilityFactor();
    }
    private double dailyVolatility(String symbol) {
        double unit = unit(mix(symbolSeed(symbol) + 0x9E3779B97F4A7C15L));
        return config.dailyVolatilityMin + unit * (config.dailyVolatilityMax - config.dailyVolatilityMin);
    }
    private double dailyShock(String symbol) {
        double unit = unit(mix(symbolSeed(symbol) + 0xBF58476D1CE4E5B9L));
        return config.dailyShockMin + unit * (config.dailyShockMax - config.dailyShockMin);
    }
    private static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }
    private double random() {
        long x = state.randomState == 0 ? 0x1234abcd5678ef90L : state.randomState;
        x ^= x << 13; x ^= x >>> 7; x ^= x << 17; state.randomState = x;
        return (x >>> 11) * 0x1.0p-53;
    }
    private void record(Account a, long id, String symbol, String action, String detail, double price, double units, double pnl, double fee) {
        a.history.add(new History(state.time, id, symbol, action, detail, price, units, pnl, fee));
        trim(a.history, config.historyLimit);
    }
    private static <T> void trim(List<T> list, int size) { if (list.size() > size) list.subList(0, list.size() - size).clear(); }
    private static boolean finite(double value) { return Double.isFinite(value); }
    private static String format(double n) { return String.format(java.util.Locale.ROOT, "%.4f", n); }
    private static void require(boolean condition, String message) { if (!condition) throw error(message); }
    private static IllegalArgumentException error(String message) { return new IllegalArgumentException(message); }
}

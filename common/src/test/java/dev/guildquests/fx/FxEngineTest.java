package dev.guildquests.fx;

import com.google.gson.Gson;
import dev.guildquests.fx.FxModels.*;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class FxEngineTest {
    FxConfig c;
    FxEngine e;
    Account a;
    @Before public void setup() {
        c = new FxConfig(); c.volatility = 0; c.crashProbability = 0; c.rallyProbability = 0;
        c.spreadBps = c.commissionBps = c.marketSlippageBps = 0; c.initialHistoryCandles = 0;
        State state = new State(); state.time = 0; state.randomState = 12345;
        e = new FxEngine(c, state); a = e.account("alice"); e.deposit(a, 10000);
    }
    Request order(OrderType type, Side side, double units, int leverage, double price) {
        Request r = new Request(); r.type = type; r.symbol = "AUR"; r.side = side;
        r.units = units; r.leverage = leverage; r.price = price; return r;
    }
    @Test public void longAndShortSettleUsingActualUnits() {
        e.submit(a, order(OrderType.MARKET, Side.BUY, 10, 10, 0));
        e.setPrice("AUR", 110); assertEquals(100, e.unrealized(a), 1e-8);
        e.closeAll(a); assertEquals(10100, a.balance, 1e-8);
        e.submit(a, order(OrderType.MARKET, Side.SELL, 10, 10, 0));
        e.setPrice("AUR", 100); e.closeAll(a); assertEquals(10200, a.balance, 1e-8);
    }
    @Test public void limitGetsPriceImprovementAndReleasesReservation() {
        e.submit(a, order(OrderType.LIMIT, Side.BUY, 10, 10, 95));
        assertEquals(95, e.reserved(a), 1e-8);
        e.setPrice("AUR", 90); assertTrue(a.orders.isEmpty());
        assertEquals(90, a.positions.get(0).entryPrice, 1e-8); assertEquals(0, e.reserved(a), 0);
    }
    @Test public void stopGapsAtQuoteAndAdverseSlippage() {
        c.marketSlippageBps = 10; e.configure(c);
        e.submit(a, order(OrderType.STOP, Side.BUY, 1, 1, 105)); e.setPrice("AUR", 120);
        assertEquals(120.12, a.positions.get(0).entryPrice, 1e-8);
    }
    @Test public void entryOcoUsesOneReservationAndOnlyFillsOnce() {
        Request r = order(OrderType.OCO, Side.BUY, 10, 10, 95); r.secondPrice = 105;
        e.submit(a, r); assertEquals(105, e.reserved(a), 1e-8);
        e.setPrice("AUR", 110); assertEquals(1, a.positions.size()); assertEquals(0, a.orders.size());
        e.setPrice("AUR", 90); assertEquals(1, a.positions.size());
    }
    @Test public void ifoArmsExitsOnlyAfterEntryAndTakeProfitCancelsStop() {
        Request r = order(OrderType.IFO, Side.BUY, 10, 10, 95); r.takeProfit = 110; r.stopLoss = 85;
        e.submit(a, r); e.setPrice("AUR", 110); assertTrue(a.positions.isEmpty()); assertEquals(1, a.orders.size());
        e.setPrice("AUR", 94); assertEquals(1, a.positions.size());
        e.setPrice("AUR", 111); assertTrue(a.positions.isEmpty()); assertTrue(a.orders.isEmpty());
        assertEquals(10170, a.balance, 1e-8);
        e.setPrice("AUR", 80); assertEquals(10170, a.balance, 1e-8);
    }
    @Test public void ifdArmsSingleTakeProfitAndSupportsMarketEntry() {
        Request r = order(OrderType.IFD, Side.SELL, 10, 10, 0); r.entry = Entry.MARKET; r.takeProfit = 95;
        e.submit(a, r); e.setPrice("AUR", 94); assertTrue(a.positions.isEmpty()); assertEquals(10060, a.balance, 1e-8);
    }
    @Test public void ifoStopExecutesOnSameQuoteAsEntryAfterGap() {
        Request r = order(OrderType.IFO, Side.BUY, 10, 10, 95); r.takeProfit = 105; r.stopLoss = 90;
        e.submit(a, r); e.setPrice("AUR", 80); assertTrue(a.positions.isEmpty()); assertTrue(a.orders.isEmpty());
        assertEquals(10000, a.balance, 1e-8);
    }
    @Test public void partialClosePreservesRemainingProtectionAndProportionalMargin() {
        Request r = order(OrderType.MARKET, Side.BUY, 10, 10, 0); r.takeProfit = 120; e.submit(a, r);
        Position p = a.positions.get(0); e.setPrice("AUR", 110); e.close(a, p.id, 4);
        assertEquals(6, p.units, 0); assertEquals(60, p.margin, 1e-8); assertEquals(120, p.takeProfit, 0);
        assertEquals(10040, a.balance, 1e-8);
    }
    @Test public void thousandTimesCrashZeroCutsAndClearsEveryOrder() {
        e.withdraw(a, 9900); e.submit(a, order(OrderType.MARKET, Side.BUY, 900, 1000, 0));
        e.submit(a, order(OrderType.LIMIT, Side.BUY, .01, 1, 1));
        e.setPrice("AUR", 20); assertEquals(0, a.balance, 0); assertEquals(1, a.zeroCuts);
        assertTrue(a.positions.isEmpty()); assertTrue(a.orders.isEmpty());
        assertEquals("ゼロカット", a.history.get(a.history.size() - 1).action);
    }
    @Test public void marginThresholdLiquidatesBeforeNegativeBalance() {
        e.withdraw(a, 9900); e.submit(a, order(OrderType.MARKET, Side.BUY, 900, 1000, 0));
        e.setPrice("AUR", 99.93); assertTrue(a.positions.isEmpty()); assertEquals(37, a.balance, 1e-6); assertEquals(0, a.zeroCuts);
    }
    @Test public void withdrawalCannotRemoveMarginOrUnrealizedProfit() {
        e.submit(a, order(OrderType.MARKET, Side.BUY, 900, 10, 0));
        reject(() -> e.withdraw(a, 1001)); assertEquals(10000, a.balance, 0);
        e.setPrice("AUR", 101);
        // The 900 unrealised gain raises free margin, but it is not withdrawable cash.
        reject(() -> e.withdraw(a, 1001));
        assertEquals(1000, e.withdrawable(a), 1e-8);
        e.withdraw(a, 1000); assertEquals(9000, a.balance, 1e-8); assertEquals(900, e.free(a), 1e-6);
    }
    @Test public void unrealizedLossRestrictsWithdrawal() {
        e.submit(a, order(OrderType.MARKET, Side.BUY, 100, 10, 0)); e.setPrice("AUR", 95);
        reject(() -> e.withdraw(a, 8501)); e.withdraw(a, 8500); assertEquals(0, e.free(a), 1e-8);
    }
    @Test public void feesAndBidAskAreChargedOnBothSides() {
        c.spreadBps = 20; c.commissionBps = 10; e.configure(c);
        e.submit(a, order(OrderType.MARKET, Side.BUY, 10, 10, 0));
        assertEquals(100.1, a.positions.get(0).entryPrice, 1e-8);
        e.closeAll(a); assertEquals(9996, a.balance, 1e-7); assertEquals(2, a.fees, 1e-7);
    }
    @Test public void orderExpiresBeforeTrigger() {
        Request r = order(OrderType.LIMIT, Side.BUY, 1, 1, 99); r.expiryMinutes = 1;
        e.submit(a, r); e.step(60000); assertTrue(a.orders.isEmpty()); assertEquals(0, e.reserved(a), 0);
    }
    @Test public void cancelReleasesOnlyItsOwnReservation() {
        e.submit(a, order(OrderType.LIMIT, Side.BUY, 1, 1, 90));
        e.submit(a, order(OrderType.STOP, Side.BUY, 1, 1, 110));
        e.cancel(a, a.orders.get(0).id); assertEquals(110, e.reserved(a), 0); assertEquals(9890, e.free(a), 0);
    }
    @Test public void insufficientMarginAtGapRejectsPendingOrderWithoutDebt() {
        e.withdraw(a, 9800); e.submit(a, order(OrderType.STOP, Side.BUY, 1, 1, 110));
        e.setPrice("AUR", 300); assertTrue(a.orders.isEmpty()); assertTrue(a.positions.isEmpty()); assertEquals(200, a.balance, 0);
    }
    @Test public void trailingStopRatchetsAndNeverRetreats() {
        Request r = order(OrderType.MARKET, Side.BUY, 10, 10, 0); r.trailingDistance = 2; e.submit(a, r);
        e.setPrice("AUR", 110); assertEquals(108, a.positions.get(0).stopLoss, 0);
        e.setPrice("AUR", 109); assertEquals(108, a.positions.get(0).stopLoss, 0);
        e.setPrice("AUR", 107); assertTrue(a.positions.isEmpty()); assertEquals(10070, a.balance, 0);
    }
    @Test public void amendProtectionIsOwnedByPositionAndCanBeCleared() {
        e.submit(a, order(OrderType.MARKET, Side.SELL, 1, 1, 0)); long id = a.positions.get(0).id;
        e.amend(a, id, 90, 110, 0); reject(() -> e.amend(a, id, 120, 110, 0));
        e.amend(a, id, 0, 0, 0); assertEquals(0, a.positions.get(0).stopLoss, 0);
    }
    @Test public void rejectsMalformedNumbersWrongDirectionAndOverLeverage() {
        reject(() -> e.submit(a, order(OrderType.MARKET, Side.BUY, Double.NaN, 1, 0)));
        reject(() -> e.submit(a, order(OrderType.MARKET, Side.BUY, 1, 1001, 0)));
        reject(() -> e.submit(a, order(OrderType.MARKET, Side.BUY, .015, 1, 0)));
        reject(() -> e.submit(a, order(OrderType.LIMIT, Side.BUY, 1, 1, 101)));
        reject(() -> e.submit(a, order(OrderType.STOP, Side.SELL, 1, 1, 101)));
        Request r = order(OrderType.MARKET, Side.BUY, 1, 1, 0); r.stopLoss = Double.POSITIVE_INFINITY;
        reject(() -> e.submit(a, r)); assertTrue(a.positions.isEmpty());
    }
    @Test public void jsonRoundTripPreservesPositionsOrdersRngAndBalances() {
        e.submit(a, order(OrderType.MARKET, Side.BUY, 1, 10, 0));
        Request r = order(OrderType.OCO, Side.SELL, 1, 10, 110); r.secondPrice = 90; e.submit(a, r);
        Gson gson = new Gson(); State restored = gson.fromJson(gson.toJson(e.state), State.class);
        FxEngine other = new FxEngine(c, restored); Account b = other.account("alice");
        assertEquals(a.balance, b.balance, 0); assertEquals(a.orders.get(0).secondPrice, b.orders.get(0).secondPrice, 0);
        assertEquals(a.positions.get(0).id, b.positions.get(0).id); assertEquals(e.state.nextId, other.state.nextId);
        e.step(1000); other.step(1000); assertEquals(e.state.randomState, other.state.randomState);
    }
    @Test public void disabledExchangeStillHonorsExitsAndWithdrawals() {
        e.submit(a, order(OrderType.MARKET, Side.BUY, 1, 1, 0)); c.enabled = false; e.configure(c);
        reject(() -> e.submit(a, order(OrderType.MARKET, Side.BUY, 1, 1, 0)));
        e.closeAll(a); e.withdraw(a, 10000); assertEquals(0, a.balance, 0);
    }
    @Test public void forcedShockHonorsBoundsAndDisconnectedAccounts() {
        c.shockMin = c.shockMax = .18; e.configure(c); e.shock("AUR", true);
        assertEquals(82, e.state.markets.get("AUR").price, 1e-8);
        assertTrue(e.state.markets.get("AUR").event.contains("暴落"));
    }
    @Test public void longRandomRunKeepsAccountsFiniteAndCandlesBounded() {
        c.volatility = .005; c.crashProbability = .02; c.rallyProbability = .02; e.configure(c);
        e.submit(a, order(OrderType.MARKET, Side.BUY, 10, 1000, 0));
        for (int i = 0; i < 20000; i++) {
            e.step(1000); assertTrue(Double.isFinite(e.equity(a))); assertTrue(a.positions.isEmpty() || e.equity(a) > 0);
            if (a.positions.isEmpty()) assertTrue(a.balance >= 0);
        }
        for (Market m : e.state.markets.values()) assertTrue(m.candles.size() <= c.candleHistory);
    }
    @Test public void invalidSettingsAreClampedAndDuplicateSymbolsRemoved() {
        FxConfig cfg = new FxConfig(); cfg.maxLeverage = 100000; cfg.volatility = Double.NaN; cfg.crashProbability = 2;
        cfg.symbols.add(cfg.symbols.get(0)); cfg.sanitize();
        assertEquals(1000, cfg.maxLeverage); assertTrue(Double.isFinite(cfg.volatility)); assertEquals(3, cfg.symbols.size());
        assertEquals(0, cfg.rallyProbability, 0);
    }
    @Test public void anotherAccountCannotCloseOrCancelOwnedIds() {
        e.submit(a, order(OrderType.MARKET, Side.BUY, 1, 1, 0));
        e.submit(a, order(OrderType.LIMIT, Side.BUY, 1, 1, 95));
        Account other = e.account("bob"); e.deposit(other, 500);
        reject(() -> e.close(other, a.positions.get(0).id, 0));
        reject(() -> e.cancel(other, a.orders.get(0).id));
        assertEquals(500, other.balance, 0); assertEquals(1, a.positions.size()); assertEquals(1, a.orders.size());
    }
    @Test public void loweringBalanceCapDoesNotEraseExistingMoneyOnSettlement() {
        e.submit(a, order(OrderType.MARKET, Side.BUY, 1, 1, 0));
        c.maxAccountBalance = 5000; e.configure(c); e.setPrice("AUR", 101); e.closeAll(a);
        assertEquals(10000, a.balance, 0); assertEquals(0, a.realized, 0);
    }
    @Test public void retiredSymbolsStillSettleExistingPositionsButRejectNewOrders() {
        e.submit(a, order(OrderType.MARKET, Side.BUY, 1, 1, 0)); c.symbols.remove(0); e.configure(c);
        reject(() -> e.submit(a, order(OrderType.MARKET, Side.BUY, 1, 1, 0)));
        e.setPrice("AUR", 110); e.closeAll(a); assertEquals(10010, a.balance, 0);
    }
    @Test public void disabledExchangeCancelsPendingOrdersInsteadOfFilling() {
        e.submit(a, order(OrderType.LIMIT, Side.BUY, 1, 1, 95));
        c.enabled = false; e.configure(c);
        e.setPrice("AUR", 90);
        assertTrue(a.positions.isEmpty());
        assertTrue(a.orders.isEmpty());
    }
    private static void reject(Runnable r) { try { r.run(); fail("Expected rejection"); } catch (IllegalArgumentException expected) { } }
}

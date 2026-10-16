package dev.guildquests.fx;

import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.fx.FxModels.*;
import dev.guildquests.service.GuildDebtPolicy;
import net.minecraft.nbt.CompoundTag;
import org.junit.Before;
import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class FxMarginCallTest {
    private FxConfig config;
    private FxEngine engine;
    private Account account;
    private PlayerGuildData wallet;

    @Before public void setup() {
        config = new FxConfig(); config.initialHistoryCandles = 0;
        config.volatility = config.crashProbability = config.rallyProbability = 0;
        config.spreadBps = config.commissionBps = config.marketSlippageBps = 0;
        engine = new FxEngine(config, new State()); account = engine.account(UUID.randomUUID().toString());
        wallet = new PlayerGuildData(); wallet.coins = 50000;
    }
    private Request buy() {
        Request r = new Request(); r.type = OrderType.MARKET; r.symbol = "AUR";
        r.side = Side.BUY; r.units = 900; r.leverage = 1000; return r;
    }
    private void crash(boolean marginCall, boolean shortPosition) {
        config.marginCallEnabled = marginCall; engine.configure(config); engine.deposit(account, 100);
        Request r = buy(); if (shortPosition) r.side = Side.SELL;
        engine.submit(account, r); engine.setPrice("AUR", shortPosition ? 180 : 20);
    }
    @Test public void defaultIsZeroCutAndNativeUi() {
        assertFalse(new FxConfig().sanitize().marginCallEnabled);
        assertEquals("MINECRAFT", new FxConfig().sanitize().defaultUi);
        crash(false, false); FxMarginSettlement.settle(engine, account, wallet, 123);
        assertEquals(0, account.balance, 0); assertEquals(1, account.zeroCuts);
        assertEquals(0, account.pendingMarginCall, 0); assertEquals(50000, wallet.coins);
        assertEquals(0, wallet.debt); assertFalse(wallet.delinquent);
    }
    @Test public void longGapCollectsWalletAndUsesExistingOverdueDebt() {
        crash(true, false); assertEquals(71900, account.pendingMarginCall, 0);
        assertTrue(account.positions.isEmpty()); assertEquals(0, account.zeroCuts);
        var result = FxMarginSettlement.settle(engine, account, wallet, 123);
        assertEquals(50000, result.paid()); assertEquals(21900, result.debtAdded());
        assertEquals(0, wallet.coins); assertEquals(21900, wallet.debt);
        assertEquals(123, wallet.nextRiboDueAt); assertTrue(wallet.delinquent);
        assertTrue(account.tradingLocked); assertEquals(0, account.pendingMarginCall, 0);
        assertThrows(IllegalArgumentException.class, () -> engine.submit(account, buy()));
    }
    @Test public void shortGapHasTheSameSettlementRules() {
        crash(true, true); FxMarginSettlement.settle(engine, account, wallet, 123);
        assertEquals(21900, wallet.debt); assertTrue(wallet.delinquent);
    }
    @Test public void sufficientCashPaysWithoutDebtOrLock() {
        wallet.coins = 100000; crash(true, false);
        FxMarginSettlement.settle(engine, account, wallet, 123);
        assertEquals(28100, wallet.coins); assertEquals(0, wallet.debt);
        assertFalse(wallet.delinquent); assertFalse(account.tradingLocked);
        assertEquals(1, account.marginCalls);
    }
    @Test public void repeatedSettlementDoesNotCollectTwiceOrDuplicateDebt() {
        crash(true, false); FxMarginSettlement.settle(engine, account, wallet, 123);
        wallet.coins = 100; int history = account.history.size();
        var again = FxMarginSettlement.settle(engine, account, wallet, 999);
        assertEquals(0, again.paid()); assertEquals(0, again.debtAdded());
        assertEquals(100, wallet.coins); assertEquals(21900, wallet.debt);
        assertEquals(history, account.history.size()); assertEquals(123, wallet.nextRiboDueAt);
    }
    @Test public void existingRiboDebtAndDueDateAreRetained() {
        wallet.debt = 1000; wallet.nextRiboDueAt = 42;
        crash(true, false); FxMarginSettlement.settle(engine, account, wallet, 123);
        assertEquals(22900, wallet.debt); assertEquals(42, wallet.nextRiboDueAt);
        assertTrue(wallet.delinquent);
    }
    @Test public void partialRepaymentKeepsLockAndFullRepaymentReleasesIt() {
        crash(true, false); FxMarginSettlement.settle(engine, account, wallet, 123);
        wallet.coins = 30000;
        assertEquals(10000, GuildDebtPolicy.repay(wallet, 10000));
        FxMarginSettlement.settle(engine, account, wallet, 123); assertTrue(account.tradingLocked);
        assertEquals(11900, GuildDebtPolicy.repay(wallet, Long.MAX_VALUE));
        FxMarginSettlement.settle(engine, account, wallet, 123);
        assertFalse(wallet.delinquent); assertFalse(account.tradingLocked);
        assertEquals(0, wallet.nextRiboDueAt); assertEquals(8100, wallet.coins);
        engine.deposit(account, 100); engine.submit(account, buy()); assertEquals(1, account.positions.size());
    }
    @Test public void disablingMarginCallDoesNotForgiveAnExistingClaimOrDebt() {
        crash(true, false); config.marginCallEnabled = false; engine.configure(config);
        FxMarginSettlement.settle(engine, account, wallet, 123);
        assertEquals(21900, wallet.debt); assertTrue(account.tradingLocked);
        engine.protect(account); assertEquals(0, account.zeroCuts);
    }
    @Test public void fractionalShortageRoundsOnceToIntegerGuildCoin() {
        account.pendingMarginCall = 1.00000000001;
        FxMarginSettlement.settle(engine, account, wallet, 123); assertEquals(49999, wallet.coins);
        account.pendingMarginCall = 1.2345;
        FxMarginSettlement.settle(engine, account, wallet, 123); assertEquals(49997, wallet.coins);
    }
    @Test public void debtOverflowRetainsOutstandingClaimInsteadOfErasingIt() {
        wallet.coins = 0; wallet.debt = Long.MAX_VALUE - 10; account.pendingMarginCall = 100;
        FxMarginSettlement.settle(engine, account, wallet, 123);
        assertEquals(Long.MAX_VALUE, wallet.debt); assertEquals(90, account.pendingMarginCall, 0);
        assertTrue(account.tradingLocked);
        wallet.coins = 100; FxMarginSettlement.settle(engine, account, wallet, 123);
        assertEquals(10, wallet.coins); assertEquals(0, account.pendingMarginCall, 0);
        assertEquals(Long.MAX_VALUE, wallet.debt);
    }
    @Test public void settlementDoesNotCountTheTradingLossAgainInRealizedPnl() {
        crash(true, false); double loss = account.realized;
        FxMarginSettlement.settle(engine, account, wallet, 123);
        assertEquals(-72000, loss, 0); assertEquals(loss, account.realized, 0);
        assertEquals(0, account.history.stream().filter(h -> h.action.startsWith("追証")).mapToDouble(h -> h.pnl).sum(), 0);
    }
    @Test public void pendingClaimAndDebtSurviveTheSameGuildSave() {
        UUID owner = UUID.randomUUID(); GuildPersistentState guild = new GuildPersistentState();
        Account stored = guild.fx.engine().account(owner.toString());
        stored.pendingMarginCall = 345.67; stored.tradingLocked = true; stored.marginCalls = 2;
        var debtor = guild.get(owner); debtor.coins = 12; debtor.debt = 800;
        debtor.delinquent = true; debtor.nextRiboDueAt = 123456;
        GuildPersistentState loaded = GuildPersistentState.fromNbt(guild.save(new CompoundTag()));
        assertEquals(345.67, loaded.fx.engine().account(owner.toString()).pendingMarginCall, 0);
        assertEquals(2, loaded.fx.engine().account(owner.toString()).marginCalls);
        assertTrue(loaded.fx.engine().account(owner.toString()).tradingLocked);
        assertEquals(800, loaded.get(owner).debt); assertEquals(12, loaded.get(owner).coins);
        assertEquals(123456, loaded.get(owner).nextRiboDueAt); assertTrue(loaded.get(owner).delinquent);
    }
}

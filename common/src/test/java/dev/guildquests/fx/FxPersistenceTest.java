package dev.guildquests.fx;

import dev.guildquests.fx.FxModels.*;
import net.minecraft.nbt.CompoundTag;
import dev.guildquests.data.GuildPersistentState;
import java.util.UUID;
import org.junit.Test;

import static org.junit.Assert.*;

public class FxPersistenceTest {
    @Test public void nbtRoundTripHandlesPayloadLargerThanNbtStringLimit() {
        FxPersistentState store = new FxPersistentState();
        FxEngine engine = store.engine(); Account account = engine.account("persistent-player");
        engine.deposit(account, 123456);
        Request r = new Request(); r.symbol = "AUR"; r.type = OrderType.IFO;
        r.units = 2; r.leverage = 1000; r.price = engine.state.markets.get("AUR").price * .9;
        r.takeProfit = r.price * 1.1; r.stopLoss = r.price * .9; engine.submit(account, r);
        for (int i = 0; i < 500; i++) engine.state.markets.get("AUR").candles.add(new Candle(i * 5000L, 100.123456789));
        CompoundTag nbt = store.writeNbt(new CompoundTag());
        assertTrue(nbt.getByteArray("Exchange").length > 65535);
        FxEngine restored = FxPersistentState.fromNbt(nbt).engine(); Account loaded = restored.account("persistent-player");
        assertEquals(123456, loaded.balance, 0); assertEquals(1, loaded.orders.size());
        assertEquals(1000, loaded.orders.get(0).leverage); assertEquals(engine.state.randomState, restored.state.randomState);
        assertEquals(engine.state.time, restored.state.time);
    }
    @Test(expected = IllegalStateException.class) public void corruptSaveFailsClosedInsteadOfResettingMoney() {
        CompoundTag broken = new CompoundTag(); broken.putByteArray("Exchange", new byte[]{1, 2, 3});
        FxPersistentState locked = FxPersistentState.fromNbt(broken);
        assertFalse(locked.healthy());
        assertArrayEquals(broken.getByteArray("Exchange"), locked.writeNbt(new CompoundTag()).getByteArray("Exchange"));
        locked.engine();
    }
    @Test public void legacyGuildSaveRetainsWalletAndCreatesEmptyFxAccount() {
        UUID id = UUID.randomUUID();
        CompoundTag root = walletSave(id, 12345);
        GuildPersistentState guild = GuildPersistentState.fromNbt(root);
        assertEquals(12345, guild.get(id).coins);
        assertTrue(guild.fx.healthy());
        assertEquals(0, guild.fx.engine().account(id.toString()).balance, 0);
    }
    @Test public void corruptFxPayloadCannotTriggerVanillaFallbackAndResetGuildWallets() {
        UUID id = UUID.randomUUID(); CompoundTag root = walletSave(id, 98765);
        CompoundTag broken = new CompoundTag(); broken.putByteArray("Exchange", new byte[]{1, 2, 3});
        root.put("FxExchange", broken);
        GuildPersistentState guild = GuildPersistentState.fromNbt(root);
        assertEquals(98765, guild.get(id).coins); assertFalse(guild.fx.healthy());
        CompoundTag saved = guild.save(new CompoundTag());
        assertArrayEquals(new byte[]{1, 2, 3}, saved.getCompound("FxExchange").getByteArray("Exchange"));
        assertEquals(98765, saved.getCompound("Players").getCompound(id.toString()).getLong("Coins"));
    }
    private static CompoundTag walletSave(UUID id, long coins) {
        CompoundTag player = new CompoundTag(); player.putLong("Coins", coins);
        CompoundTag players = new CompoundTag(); players.put(id.toString(), player);
        CompoundTag root = new CompoundTag(); root.put("Players", players); return root;
    }
}

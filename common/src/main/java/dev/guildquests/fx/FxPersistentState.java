package dev.guildquests.fx;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import dev.guildquests.data.GuildPersistentState;

import java.nio.charset.StandardCharsets;

public final class FxPersistentState {
    private FxModels.State data = new FxModels.State();
    private FxEngine engine;
    private final Runnable dirty;
    private CompoundTag damaged;

    public FxPersistentState() { this(() -> { }); }
    public FxPersistentState(Runnable dirty) { this.dirty = dirty; }
    public void setDirty() { dirty.run(); }
    public boolean healthy() { return damaged == null; }

    public FxEngine engine() {
        if (!healthy()) throw new IllegalStateException("FX data is locked after a save error. Restore guildquests_players.dat from a backup.");
        if (engine == null) engine = new FxEngine(FxSettings.get(), data);
        else if (engine.config() != FxSettings.get()) engine.configure(FxSettings.get());
        return engine;
    }

    public CompoundTag writeNbt(CompoundTag nbt) {
        if (!healthy()) return damaged.copy(); // Never overwrite damaged data with an empty exchange.
        // A byte array avoids NBT's 65,535-byte limit on individual strings.
        nbt.putByteArray("Exchange", FxSettings.JSON.toJson(data).getBytes(StandardCharsets.UTF_8));
        return nbt;
    }

    public static FxPersistentState fromNbt(CompoundTag nbt) {
        return fromNbt(nbt, () -> { });
    }

    public static FxPersistentState fromNbt(CompoundTag nbt, Runnable dirty) {
        FxPersistentState result = new FxPersistentState(dirty);
        try {
            var json = com.google.gson.JsonParser.parseString(new String(nbt.getByteArray("Exchange"), StandardCharsets.UTF_8)).getAsJsonObject();
            for (String key : new String[]{"schema", "accounts", "markets", "nextId", "time", "randomState"})
                if (!json.has(key)) throw new IllegalArgumentException("Missing FX save field: " + key);
            result.data = FxSettings.JSON.fromJson(json, FxModels.State.class);
            if (result.data == null || result.data.schema != 1 || result.data.accounts == null || result.data.markets == null)
                throw new IllegalArgumentException("Unsupported or incomplete FX state");
        } catch (RuntimeException ex) {
            // PersistentStateManager catches parse exceptions and creates a new state. Retaining the
            // damaged payload here prevents that fallback from resetting wallets and FX balances.
            result.damaged = nbt.copy();
            dev.guildquests.GuildQuestsMod.LOGGER.error("FX data is locked; original data retained. Restore guildquests_players.dat from a backup.", ex);
        }
        return result;
    }

    public static FxPersistentState get(MinecraftServer server) {
        return GuildPersistentState.get(server).fx;
    }
}

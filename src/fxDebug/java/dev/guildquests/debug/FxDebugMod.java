package dev.guildquests.debug;

import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.fx.FxSettings;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Runs exclusively in the throwaway development world when -PfxDebug is selected. */
public final class FxDebugMod implements ModInitializer {
    @Override public void onInitialize() {
        FxSettings.get().crashProbability = 0;
        FxSettings.get().rallyProbability = 0;
        FxSettings.get().volatility = 0.0002;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Path expected = Path.of("expected-after-restart.json");
            if (!Files.exists(expected)) return;
            try {
                var json = com.google.gson.JsonParser.parseString(Files.readString(expected)).getAsJsonObject();
                UUID id = UUID.fromString(json.get("uuid").getAsString());
                GuildPersistentState guild = GuildPersistentState.get(server);
                var account = guild.fx.engine().account(id.toString());
                if (guild.get(id).coins != json.get("wallet").getAsLong()
                        || Math.abs(account.balance - json.get("balance").getAsDouble()) > 1e-8
                        || !account.positions.isEmpty() || !account.orders.isEmpty())
                    throw new IllegalStateException("Wallet / FX persistence mismatch after restart");
                Files.writeString(Path.of("restart-results.txt"), "PASS Wallet and FX balance restored atomically after graceful server restart\n");
                Files.delete(expected);
                System.out.println("FX_RESTART_PASS wallet=" + guild.get(id).coins + " balance=" + account.balance);
            } catch (Exception ex) { throw new IllegalStateException("FX restart check failed", ex); }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!handler.player.getName().getString().equals("FxTester")) return;
            GuildPersistentState guild = GuildPersistentState.get(server);
            guild.get(handler.player.getUuid()).coins = 50000;
            guild.fx.engine().state.accounts.remove(handler.player.getUuidAsString());
            guild.markDirty();
            server.getPlayerManager().addToOperators(handler.player.getGameProfile());
        });
    }
}

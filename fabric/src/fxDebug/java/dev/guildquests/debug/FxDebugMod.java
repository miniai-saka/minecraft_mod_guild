package dev.guildquests.debug;

import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.fx.FxSettings;
import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.ActiveQuest;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.service.GuildService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.LongArgumentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Runs exclusively in the throwaway development world when -PfxDebug is selected. */
public final class FxDebugMod implements ModInitializer {
    @Override public void onInitialize() {
        var config = FxSettings.get(); config.crashProbability = config.rallyProbability = config.volatility = 0;
        config.trendPersistence = config.meanReversion = 0;
        config.spreadBps = config.commissionBps = config.marketSlippageBps = 0;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Path adminExpected = Path.of("expected-admin-restart.json");
            if (Files.exists(adminExpected)) {
                try {
                    var json = com.google.gson.JsonParser.parseString(Files.readString(adminExpected)).getAsJsonObject();
                    var wallet = GuildPersistentState.get(server).get(UUID.fromString(json.get("uuid").getAsString()));
                    if (wallet.loan != 3210 || !wallet.delinquent || !wallet.adminDelinquent) throw new IllegalStateException("Admin loan/lock lost on restart");
                    Files.writeString(Path.of("admin-restart-results.txt"), "PASS Consumer loan and explicit admin delinquency survive restart\n");
                    Files.delete(adminExpected);
                } catch (Exception ex) { throw new IllegalStateException(ex); }
            }
            Path expected = Path.of("expected-after-restart.json");
            if (!Files.exists(expected)) return;
            try {
                var json = com.google.gson.JsonParser.parseString(Files.readString(expected)).getAsJsonObject();
                UUID id = UUID.fromString(json.get("uuid").getAsString());
                GuildPersistentState guild = GuildPersistentState.get(server);
                var account = guild.fx.engine().account(id.toString());
                if (guild.get(id).coins != json.get("wallet").getAsLong()
                        || guild.get(id).debt != json.get("debt").getAsLong()
                        || guild.get(id).delinquent != json.get("delinquent").getAsBoolean()
                        || account.tradingLocked != json.get("locked").getAsBoolean()
                        || FxSettings.get().marginCallEnabled != json.get("marginCall").getAsBoolean()
                        || Math.abs(account.balance - json.get("balance").getAsDouble()) > 1e-8)
                    throw new IllegalStateException("Wallet / FX / debt / config persistence mismatch after restart");
                Files.writeString(Path.of("restart-results.txt"), "PASS Wallet, FX, overdue debt, trading lock and command setting restored after server restart\n");
                Files.delete(expected);
                System.out.println("FX_RESTART_PASS debt=" + guild.get(id).debt + " marginCall=" + FxSettings.get().marginCallEnabled);
            } catch (Exception ex) { throw new IllegalStateException("FX restart check failed", ex); }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("fxdebug").requires(source -> source.hasPermission(2))
                    .then(Commands.literal("prepareadmin").executes(context -> {
                        var player = context.getSource().getPlayerOrException(); var guild = GuildPersistentState.get(player.getServer());
                        var own = guild.get(player.getUUID()); own.loan = 0; own.loanCreditMillis = 0; own.nextLoanInterestAt = 0; own.adminDelinquent = own.delinquent = false;
                        UUID offline = UUID.fromString("dff00f00-0000-4000-8000-000000000001");
                        var saved = guild.get(offline); saved.playerName = "OfflineBorrower"; saved.loan = 2000; saved.loanCreditMillis = 0; saved.nextLoanInterestAt = 0;
                        saved.debt = 0; saved.adminDelinquent = saved.delinquent = false; guild.setDirty(); return 1;
                    }))
                    .then(Commands.literal("adminsecurity").executes(context -> {
                        var player = context.getSource().getPlayerOrException(); var menu = player.containerMenu;
                        if (!(menu instanceof dev.guildquests.gui.AdminScreenHandler)) throw new IllegalStateException("No admin confirmation menu");
                        long previous = GuildPersistentState.get(player.getServer()).get(player.getUUID()).loan;
                        player.getServer().getPlayerList().deop(player.getGameProfile());
                        try {
                            menu.clicked(33, 0, net.minecraft.world.inventory.ClickType.PICKUP, player);
                            if (player.containerMenu == menu || GuildPersistentState.get(player.getServer()).get(player.getUUID()).loan != previous)
                                throw new IllegalStateException("Revoked operator could mutate debt");
                            Files.writeString(Path.of("admin-security-results.txt"), "PASS Permission revoked after opening menu blocks confirmation and closes menu\n");
                        } catch (Exception ex) { throw new IllegalStateException(ex); }
                        finally { player.getServer().getPlayerList().op(player.getGameProfile()); }
                        return 1;
                    }))
                    .then(Commands.literal("seed").executes(context -> {
                        var player = context.getSource().getPlayerOrException(); var guild = GuildPersistentState.get(player.getServer());
                        var wallet = guild.get(player.getUUID()); wallet.coins = 50000; wallet.debt = 0; wallet.delinquent = false; wallet.nextRiboDueAt = 0;
                        var engine = guild.fx.engine(); engine.state.accounts.remove(player.getStringUUID());
                        engine.setPrice("AUR", 100); engine.deposit(engine.account(player.getStringUUID()), 100); guild.setDirty(); return 1;
                    }))
                    .then(Commands.literal("gap").executes(context -> {
                        var guild = GuildPersistentState.get(context.getSource().getServer()); guild.fx.engine().setPrice("AUR", 20); guild.setDirty(); return 1;
                    }))
                    .then(Commands.literal("legacycheck").executes(context -> {
                        var player = context.getSource().getPlayerOrException(); var guild = GuildPersistentState.get(player.getServer());
                        var data = guild.get(player.getUUID()); var before = data.toNbt(); var cfg = ConfigManager.config();
                        double interest = cfg.riboInterestPercent; long installment = cfg.riboInstallment;
                        try {
                            if (!data.delinquent || data.debt != 21900) throw new IllegalStateException("Expected FX delinquency");
                            data.coins = 10000;
                            if (GuildService.payCoins(player, 1) || GuildService.bankWithdraw(player, 1, true))
                                throw new IllegalStateException("Legacy spending / bank did not enforce delinquency");
                            cfg.riboInterestPercent = 0; cfg.riboInstallment = 500; data.coins = 500; data.nextRiboDueAt = 1;
                            GuildService.runRiboCycles(player.getServer());
                            if (data.coins != 0 || data.debt != 21400 || !data.delinquent)
                                throw new IllegalStateException("Legacy installment did not repay FX debt");
                            var quest = ConfigManager.quests().stream().filter(q -> q.reward > 0 && q.reward < 21400).findFirst().orElseThrow();
                            data.activeQuests.clear(); data.activeQuests.add(new ActiveQuest(quest.id, Integer.MAX_VALUE));
                            if (!GuildService.claimQuest(player, quest.id) || data.debt != 21400 - quest.reward || data.coins != 0)
                                throw new IllegalStateException("Legacy quest reward did not pay FX debt");
                            data.debt = quest.reward; data.delinquent = true;
                            data.activeQuests.add(new ActiveQuest(quest.id, Integer.MAX_VALUE));
                            if (!GuildService.claimQuest(player, quest.id) || data.debt != 0 || data.delinquent)
                                throw new IllegalStateException("Legacy quest repayment did not unlock debtor");
                            Files.writeString(Path.of("legacy-debt-results.txt"), "PASS Existing bank and payment locks apply to FX debt\nPASS Existing installment cycle repays FX debt\nPASS Existing quest rewards repay FX debt and unlock after full repayment\n");
                        } catch (Exception ex) { throw new IllegalStateException("Legacy debt integration failed", ex); }
                        finally { guild.players().put(player.getUUID(), PlayerGuildData.fromNbt(before)); cfg.riboInterestPercent = interest; cfg.riboInstallment = installment; guild.setDirty(); }
                        return 1;
                    }))
                    .then(Commands.literal("cash").then(Commands.argument("coins", LongArgumentType.longArg(0))
                            .executes(context -> {
                                var player = context.getSource().getPlayerOrException(); var guild = GuildPersistentState.get(player.getServer());
                                guild.get(player.getUUID()).coins = LongArgumentType.getLong(context, "coins"); guild.setDirty(); return 1;
                            }))));
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!handler.player.getName().getString().equals("FxTester")) return;
            try { FxSettings.setMarginCall(false); } catch (Exception ex) { throw new IllegalStateException(ex); }
            var guild = GuildPersistentState.get(server); var wallet = guild.get(handler.player.getUUID());
            wallet.coins = 50000; wallet.debt = 0; wallet.delinquent = false; wallet.nextRiboDueAt = 0;
            wallet.adminDelinquent = false;
            guild.fx.engine().state.accounts.remove(handler.player.getStringUUID());
            guild.fx.engine().setPrice("AUR", 100); guild.setDirty();
            server.getPlayerList().op(handler.player.getGameProfile());
        });
    }
}

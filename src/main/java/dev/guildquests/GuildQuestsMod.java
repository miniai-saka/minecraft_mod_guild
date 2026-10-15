package dev.guildquests;

import com.mojang.brigadier.arguments.LongArgumentType;
import dev.guildquests.block.ModBlocks;
import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.gui.BankScreenHandler;
import dev.guildquests.gui.GuildScreenHandler;
import dev.guildquests.item.ModItems;
import dev.guildquests.platform.Platform;
import dev.guildquests.service.AuctionService;
import dev.guildquests.service.BossEvent;
import dev.guildquests.service.GuildService;
import net.fabricmc.api.ModInitializer;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GuildQuestsMod implements ModInitializer {
    public static final String MOD_ID = "guildquests";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static int exploreTicker = 0;
    private static int riboTicker = 0;
    private static int auctionTicker = 0;

    @Override
    public void onInitialize() {
        ConfigManager.loadAll();
        ModItems.register();
        ModBlocks.register();
        BossEvent.init();

        Platform.onOpenGuildRequest(GuildScreenHandler::openQuests);

        // The player-published quest (bounty board) system was removed; clean up old saves.
        Platform.onServerStarted(GuildService::purgePublishedQuests);

        Platform.onPlayerKill(GuildService::onKill);

        Platform.onServerTick(server -> {
            GuildService.ensureDailyBoard(server);
            dev.guildquests.gui.HorseRaceScreenHandler.tickAll(server);

            int interval = ConfigManager.config().exploreCheckIntervalTicks;
            exploreTicker++;
            if (exploreTicker >= interval) {
                exploreTicker = 0;
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    GuildService.checkExplore(player);
                }
            }

            riboTicker++;
            if (riboTicker >= 100) {
                riboTicker = 0;
                GuildService.runRiboCycles(server);
            }

            auctionTicker++;
            if (auctionTicker >= 100) {
                auctionTicker = 0;
                AuctionService.tick(server);
            }
        });

        Platform.onCommands((dispatcher, registryAccess) -> {
            dispatcher.register(CommandManager.literal("guild")
                    .executes(context -> {
                        GuildScreenHandler.openQuests(context.getSource().getPlayerOrThrow());
                        return 1;
                    })
                    .then(CommandManager.literal("status")
                            .executes(context -> {
                                ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                                PlayerGuildData data = GuildService.data(player);
                                player.sendMessage(Text.literal("[Guild] ").formatted(Formatting.GOLD)
                                        .append(Text.literal("Rank " + GuildService.rank(data)
                                                + " | Coins " + data.coins
                                                + " | Total " + data.completedTotal
                                                + " | Pay " + GuildService.paymentModeLabel(GuildService.paymentMode(data))
                                                + (data.debt > 0 ? " | Debt " + data.debt + (data.delinquent ? "（延滞中・ロック）" : "") : "")
                                        ).formatted(Formatting.YELLOW)), false);
                                return 1;
                            }))
                    .then(CommandManager.literal("bank")
                            .executes(context -> {
                                BankScreenHandler.open(context.getSource().getPlayerOrThrow());
                                return 1;
                            }))
                    .then(CommandManager.literal("auction")
                            .executes(context -> {
                                dev.guildquests.gui.AuctionBrowseScreenHandler.open(context.getSource().getPlayerOrThrow());
                                return 1;
                            })));

            var guildAdmin = CommandManager.literal("guildadmin")
                    .requires(source -> source.hasPermissionLevel(2));

            guildAdmin.then(CommandManager.literal("reload")
                    .executes(context -> {
                        ConfigManager.loadAll();
                        context.getSource().sendFeedback(() -> Text.literal("GuildQuests configuration reloaded."), true);
                        return 1;
                    }));

            guildAdmin.then(CommandManager.literal("boss")
                    .executes(context -> {
                        boolean spawned = BossEvent.spawnNow(context.getSource().getServer());
                        boolean result = spawned;
                        context.getSource().sendFeedback(() -> Text.literal(result
                                ? "Emergency boss spawned (chance roll skipped)."
                                : "Boss spawn failed: no players online or a boss is already alive."), false);
                        return spawned ? 1 : 0;
                    }));

            var coins = CommandManager.literal("coins");
            coins.then(CommandManager.literal("set")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                            .then(CommandManager.argument("amount", LongArgumentType.longArg(0))
                                    .executes(context -> {
                                        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                                        long amount = LongArgumentType.getLong(context, "amount");
                                        GuildPersistentState state = GuildPersistentState.get(context.getSource().getServer());
                                        PlayerGuildData data = state.get(target.getUuid());
                                        data.coins = amount;
                                        state.markDirty();
                                        context.getSource().sendFeedback(() -> Text.literal("Set " + target.getName().getString() + " coins to " + amount), true);
                                        return 1;
                                    }))));
            coins.then(CommandManager.literal("add")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                            .then(CommandManager.argument("amount", LongArgumentType.longArg(0))
                                    .executes(context -> {
                                        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
                                        long amount = LongArgumentType.getLong(context, "amount");
                                        GuildPersistentState state = GuildPersistentState.get(context.getSource().getServer());
                                        PlayerGuildData data = state.get(target.getUuid());
                                        data.coins = data.coins > Long.MAX_VALUE - amount ? Long.MAX_VALUE : data.coins + amount;
                                        state.markDirty();
                                        context.getSource().sendFeedback(() -> Text.literal("Added " + amount + " coins to " + target.getName().getString()), true);
                                        return 1;
                                    }))));
            guildAdmin.then(coins);

            dispatcher.register(guildAdmin);
        });

        LOGGER.info("Guild Quests & Economy initialized for Fabric 1.20.1");
    }
}

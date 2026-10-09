package dev.guildquests;

import com.mojang.brigadier.arguments.LongArgumentType;
import dev.guildquests.block.ModBlocks;
import dev.guildquests.config.ConfigManager;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.gui.BankScreenHandler;
import dev.guildquests.gui.GuildScreenHandler;
import dev.guildquests.item.ModItems;
import dev.guildquests.service.GuildService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GuildQuestsMod implements ModInitializer {
    public static final String MOD_ID = "guildquests";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** C2S: ask the server to open the guild quest screen (keybinding). */
    private static final Identifier OPEN_GUILD = new Identifier(MOD_ID, "open_guild");
    private static int exploreTicker = 0;
    private static int riboTicker = 0;

    @Override
    public void onInitialize() {
        ConfigManager.loadAll();
        ModItems.register();
        ModBlocks.register();

        ServerPlayNetworking.registerGlobalReceiver(OPEN_GUILD, (server, player, handler, buf, responseSender) ->
                server.execute(() -> GuildScreenHandler.openQuests(player)));

        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity) -> {
            if (entity instanceof ServerPlayerEntity player) {
                GuildService.onKill(player, killedEntity);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GuildService.ensureDailyBoard(server);

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
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
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
                            })));

            var guildAdmin = CommandManager.literal("guildadmin")
                    .requires(source -> source.hasPermissionLevel(2));

            guildAdmin.then(CommandManager.literal("reload")
                    .executes(context -> {
                        ConfigManager.loadAll();
                        context.getSource().sendFeedback(() -> Text.literal("GuildQuests configuration reloaded."), true);
                        return 1;
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

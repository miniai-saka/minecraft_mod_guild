package dev.guildquests.platform;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Loader-specific entry points (events, networking, config dir).
 * Keep Fabric imports confined here so a future loader port only rewrites this class.
 */
public final class Platform {
    /** C2S: ask the server to open the guild quest screen (keybinding). */
    public static final Identifier OPEN_GUILD = new Identifier("guildquests", "open_guild");

    private Platform() {
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("guildquests");
    }

    /** Loader name used for update asset lookup. */
    public static String getLoaderName() {
        return "fabric";
    }

    /** Path to the mods folder. */
    public static Path getModsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    public static void onOpenGuildRequest(Consumer<ServerPlayerEntity> handler) {
        ServerPlayNetworking.registerGlobalReceiver(OPEN_GUILD, (server, player, networkHandler, buf, responseSender) ->
                server.execute(() -> handler.accept(player)));
    }

    public static void onServerStarted(Consumer<MinecraftServer> handler) {
        ServerLifecycleEvents.SERVER_STARTED.register(handler::accept);
    }

    public static void onPlayerKill(BiConsumer<ServerPlayerEntity, LivingEntity> handler) {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity) -> {
            if (entity instanceof ServerPlayerEntity player) {
                handler.accept(player, killedEntity);
            }
        });
    }

    public static void onServerTick(Consumer<MinecraftServer> handler) {
        ServerTickEvents.END_SERVER_TICK.register(handler::accept);
    }

    public static void onCommands(CommandHandler handler) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                handler.register(dispatcher, registryAccess));
    }

    public static boolean canSend(ServerPlayerEntity player, Identifier channel) {
        return ServerPlayNetworking.canSend(player, channel);
    }

    public static void send(ServerPlayerEntity player, Identifier channel, PacketByteBuf buf) {
        ServerPlayNetworking.send(player, channel, buf);
    }

    @FunctionalInterface
    public interface CommandHandler {
        void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess);
    }
}

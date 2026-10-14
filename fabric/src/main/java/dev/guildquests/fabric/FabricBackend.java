package dev.guildquests.fabric;

import dev.guildquests.platform.Platform;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Fabric implementation of the loader-neutral {@link Platform.Backend}. */
public final class FabricBackend implements Platform.Backend {
    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("guildquests");
    }

    @Override
    public void onOpenGuildRequest(Consumer<ServerPlayer> handler) {
        ServerPlayNetworking.registerGlobalReceiver(Platform.OPEN_GUILD,
                (server, player, listener, buf, sender) -> server.execute(() -> handler.accept(player)));
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> handler) {
        ServerLifecycleEvents.SERVER_STARTED.register(handler::accept);
    }

    @Override
    public void onServerStopped(Consumer<MinecraftServer> handler) {
        ServerLifecycleEvents.SERVER_STOPPED.register(handler::accept);
    }

    @Override
    public void onPlayerKill(BiConsumer<ServerPlayer, LivingEntity> handler) {
        ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((world, entity, killedEntity) -> {
            if (entity instanceof ServerPlayer player) {
                handler.accept(player, killedEntity);
            }
        });
    }

    @Override
    public void onServerTick(Consumer<MinecraftServer> handler) {
        ServerTickEvents.END_SERVER_TICK.register(handler::accept);
    }

    @Override
    public void onCommands(Platform.CommandHandler handler) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                handler.register(dispatcher, registryAccess));
    }

    @Override
    public void onAllowDamage(Platform.AllowDamageHandler handler) {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(handler::allow);
    }

    @Override
    public void onEntityDeath(BiConsumer<LivingEntity, DamageSource> handler) {
        ServerLivingEntityEvents.AFTER_DEATH.register(handler::accept);
    }

    @Override
    public boolean canSend(ServerPlayer player, ResourceLocation channel) {
        return ServerPlayNetworking.canSend(player, channel);
    }

    @Override
    public void send(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
        ServerPlayNetworking.send(player, channel, buf);
    }
}

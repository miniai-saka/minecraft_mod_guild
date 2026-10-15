package dev.guildquests.forge;

import dev.guildquests.platform.Platform;
import dev.guildquests.service.BossEvent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Forge implementation of the loader-neutral {@link Platform.Backend}. */
public final class ForgeBackend implements Platform.Backend {
    private Consumer<MinecraftServer> serverStarted;
    private Consumer<MinecraftServer> serverStopped;
    private Consumer<MinecraftServer> serverTick;
    private Platform.CommandHandler commands;
    private Platform.AllowDamageHandler allowDamage;
    private BiConsumer<ServerPlayer, LivingEntity> playerKill;
    private BiConsumer<LivingEntity, DamageSource> entityDeath;

    /** Wire the stored handlers up to the Forge event bus. Safe to call once at construction. */
    public void registerForgeEvents() {
        MinecraftForge.EVENT_BUS.addListener(this::handleServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::handleServerStopped);
        MinecraftForge.EVENT_BUS.addListener(this::handleServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::handleCommands);
        MinecraftForge.EVENT_BUS.addListener(this::handleLivingAttack);
        MinecraftForge.EVENT_BUS.addListener(this::handleLivingDeath);
    }

    private void handleServerStarted(ServerStartedEvent event) {
        if (serverStarted != null) {
            serverStarted.accept(event.getServer());
        }
    }

    private void handleServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        if (serverStopped != null) {
            serverStopped.accept(event.getServer());
        }
    }

    private void handleServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && serverTick != null) {
            serverTick.accept(event.getServer());
        }
    }

    private void handleCommands(RegisterCommandsEvent event) {
        if (commands != null) {
            commands.register(event.getDispatcher(), event.getBuildContext());
        }
    }

    private void handleLivingAttack(LivingAttackEvent event) {
        if (allowDamage == null) {
            return;
        }
        if (!allowDamage.allow(event.getEntity(), event.getSource(), event.getAmount())) {
            event.setCanceled(true);
        }
    }

    private void handleLivingDeath(LivingDeathEvent event) {
        if (entityDeath != null) {
            entityDeath.accept(event.getEntity(), event.getSource());
        }
        if (playerKill != null) {
            Entity killer = event.getSource().getEntity();
            if (killer instanceof ServerPlayer player) {
                playerKill.accept(player, event.getEntity());
            }
        }
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve("guildquests");
    }

    @Override
    public String getLoaderName() {
        return "forge";
    }

    @Override
    public Path getModsDir() {
        return FMLPaths.MODSDIR.get();
    }

    @Override
    public void onOpenGuildRequest(Consumer<ServerPlayer> handler) {
        ForgeNetwork.setOpenGuildHandler(handler);
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> handler) {
        this.serverStarted = handler;
    }

    @Override
    public void onServerStopped(Consumer<MinecraftServer> handler) {
        this.serverStopped = handler;
    }

    @Override
    public void onPlayerKill(BiConsumer<ServerPlayer, LivingEntity> handler) {
        this.playerKill = handler;
    }

    @Override
    public void onServerTick(Consumer<MinecraftServer> handler) {
        this.serverTick = handler;
    }

    @Override
    public void onCommands(Platform.CommandHandler handler) {
        this.commands = handler;
    }

    @Override
    public void onAllowDamage(Platform.AllowDamageHandler handler) {
        this.allowDamage = handler;
    }

    @Override
    public void onEntityDeath(BiConsumer<LivingEntity, DamageSource> handler) {
        this.entityDeath = handler;
    }

    @Override
    public boolean canSend(ServerPlayer player, ResourceLocation channel) {
        if (!BossEvent.BOSS_PIN.equals(channel)) {
            return false;
        }
        return ForgeNetwork.channel().isRemotePresent(player.connection.connection);
    }

    @Override
    public void send(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf buf) {
        if (!BossEvent.BOSS_PIN.equals(channel)) {
            throw new IllegalArgumentException("Unknown guildquests channel: " + channel);
        }
        int x = buf.readVarInt();
        int y = buf.readVarInt();
        int z = buf.readVarInt();
        int lifetime = buf.readVarInt();
        ForgeNetwork.sendBossPin(player, x, y, z, lifetime);
    }
}

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
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Forge implementation of the loader-neutral {@link Platform.Backend}.
 * <p>
 * Each hook keeps a list of handlers (like Fabric's event callbacks), so registering more than
 * one handler per hook accumulates them instead of overwriting — e.g. the boss event, the FX
 * service and the guild core all register a server tick.
 */
public final class ForgeBackend implements Platform.Backend {
    private final List<Consumer<MinecraftServer>> serverStarted = new ArrayList<>();
    private final List<Consumer<MinecraftServer>> serverStopped = new ArrayList<>();
    private final List<Consumer<MinecraftServer>> serverStopping = new ArrayList<>();
    private final List<Consumer<MinecraftServer>> serverTick = new ArrayList<>();
    private final List<Platform.CommandHandler> commands = new ArrayList<>();
    private final List<Platform.AllowDamageHandler> allowDamage = new ArrayList<>();
    private final List<BiConsumer<ServerPlayer, LivingEntity>> playerKill = new ArrayList<>();
    private final List<BiConsumer<LivingEntity, DamageSource>> entityDeath = new ArrayList<>();
    private final List<Platform.BlockBreakHandler> blockBreak = new ArrayList<>();

    /** Wire the stored handlers up to the Forge event bus. Safe to call once at construction. */
    public void registerForgeEvents() {
        MinecraftForge.EVENT_BUS.addListener(this::handleServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::handleServerStopped);
        MinecraftForge.EVENT_BUS.addListener(this::handleServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::handleServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::handleCommands);
        MinecraftForge.EVENT_BUS.addListener(this::handleLivingAttack);
        MinecraftForge.EVENT_BUS.addListener(this::handleLivingDeath);
        MinecraftForge.EVENT_BUS.addListener(this::handleBlockBreak);
    }

    private void handleBlockBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            for (Platform.BlockBreakHandler handler : blockBreak) {
                handler.accept(player, event.getState());
            }
        }
    }

    private void handleServerStarted(ServerStartedEvent event) {
        for (Consumer<MinecraftServer> handler : serverStarted) {
            handler.accept(event.getServer());
        }
    }

    private void handleServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        for (Consumer<MinecraftServer> handler : serverStopped) {
            handler.accept(event.getServer());
        }
    }

    private void handleServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        for (Consumer<MinecraftServer> handler : serverStopping) handler.accept(event.getServer());
    }

    private void handleServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        for (Consumer<MinecraftServer> handler : serverTick) {
            handler.accept(event.getServer());
        }
    }

    private void handleCommands(RegisterCommandsEvent event) {
        for (Platform.CommandHandler handler : commands) {
            handler.register(event.getDispatcher(), event.getBuildContext());
        }
    }

    private void handleLivingAttack(LivingAttackEvent event) {
        for (Platform.AllowDamageHandler handler : allowDamage) {
            if (!handler.allow(event.getEntity(), event.getSource(), event.getAmount())) {
                event.setCanceled(true);
                return;
            }
        }
    }

    private void handleLivingDeath(LivingDeathEvent event) {
        for (BiConsumer<LivingEntity, DamageSource> handler : entityDeath) {
            handler.accept(event.getEntity(), event.getSource());
        }
        Entity killer = event.getSource().getEntity();
        if (killer instanceof ServerPlayer player) {
            for (BiConsumer<ServerPlayer, LivingEntity> handler : playerKill) {
                handler.accept(player, event.getEntity());
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
    public String getModVersion() {
        return net.minecraftforge.fml.ModList.get().getModContainerById("guildquests").orElseThrow()
                .getModInfo().getVersion().toString();
    }

    @Override
    public Path getModJar() {
        Path path = net.minecraftforge.fml.ModList.get().getModFileById("guildquests").getFile().getFilePath();
        return java.nio.file.Files.isRegularFile(path) ? path : null;
    }

    @Override
    public void validateUpdateMetadata(java.util.zip.ZipFile jar, String version, boolean dedicatedServer) throws java.io.IOException {
        java.util.Map<String, String> installed = new java.util.HashMap<>();
        for (var mod : net.minecraftforge.fml.ModList.get().getMods()) installed.put(mod.getModId(), mod.getVersion().toString());
        installed.put("java", Integer.toString(Runtime.version().feature()));
        dev.guildquests.update.ForgeUpdateMetadata.validate(jar, version, dedicatedServer, installed);
    }

    @Override
    public void onOpenGuildRequest(Consumer<ServerPlayer> handler) {
        ForgeNetwork.setOpenGuildHandler(handler);
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> handler) {
        serverStarted.add(handler);
    }

    @Override
    public void onServerStopped(Consumer<MinecraftServer> handler) {
        serverStopped.add(handler);
    }

    @Override
    public void onServerStopping(Consumer<MinecraftServer> handler) {
        serverStopping.add(handler);
    }

    @Override
    public void onPlayerKill(BiConsumer<ServerPlayer, LivingEntity> handler) {
        playerKill.add(handler);
    }

    @Override
    public void onServerTick(Consumer<MinecraftServer> handler) {
        serverTick.add(handler);
    }

    @Override
    public void onCommands(Platform.CommandHandler handler) {
        commands.add(handler);
    }

    @Override
    public void onAllowDamage(Platform.AllowDamageHandler handler) {
        allowDamage.add(handler);
    }

    @Override
    public void onEntityDeath(BiConsumer<LivingEntity, DamageSource> handler) {
        entityDeath.add(handler);
    }

    @Override
    public void onBlockBreak(Platform.BlockBreakHandler handler) {
        blockBreak.add(handler);
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

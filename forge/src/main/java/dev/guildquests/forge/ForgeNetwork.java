package dev.guildquests.forge;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.fx.FxModels;
import dev.guildquests.fx.FxNetwork;
import dev.guildquests.fx.FxService;
import dev.guildquests.fx.FxSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The single Forge play channel, shared by guild, boss pin and FX packets.
 */
public final class ForgeNetwork {
    public static final String PROTOCOL = "2";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("guildquests", "main"),
            () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL),
            NetworkRegistry.acceptMissingOr(PROTOCOL));

    private static Consumer<ServerPlayer> openGuildHandler;
    private static BossPinSink bossPinSink;
    private static Consumer<FxModels.Snapshot> fxSnapshotSink;

    private ForgeNetwork() {
    }

    public static SimpleChannel channel() {
        return CHANNEL;
    }

    /** Must be called during mod construction, before the network registry is locked. */
    public static void register() {
        CHANNEL.registerMessage(0, OpenGuildC2S.class,
                OpenGuildC2S::encode, OpenGuildC2S::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer sender = ctx.get().getSender();
                        if (sender != null && openGuildHandler != null) {
                            openGuildHandler.accept(sender);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CHANNEL.registerMessage(1, BossPinS2C.class,
                BossPinS2C::encode, BossPinS2C::decode,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        if (bossPinSink != null) {
                            bossPinSink.onBossPin(msg.x(), msg.y(), msg.z(), msg.lifetime());
                        }
                    });
                    ctx.get().setPacketHandled(true);
                },
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, FxRequestC2S.class,
                (msg, buf) -> buf.writeUtf(msg.json(), 4096),
                buf -> new FxRequestC2S(buf.readUtf(4096)),
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer sender = ctx.get().getSender();
                        if (sender == null) return;
                        try {
                            FxModels.Request request = FxSettings.JSON.fromJson(msg.json(), FxModels.Request.class);
                            if (request != null) FxService.handle(sender.getServer(), sender, request);
                        } catch (RuntimeException ex) {
                            GuildQuestsMod.LOGGER.debug("Rejected malformed FX request", ex);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, FxSnapshotS2C.class,
                (msg, buf) -> buf.writeUtf(msg.json(), 1_000_000),
                buf -> new FxSnapshotS2C(buf.readUtf(1_000_000)),
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        if (fxSnapshotSink == null) return;
                        try {
                            FxModels.Snapshot snapshot = FxSettings.JSON.fromJson(msg.json(), FxModels.Snapshot.class);
                            if (snapshot != null) fxSnapshotSink.accept(snapshot);
                        } catch (RuntimeException ex) {
                            GuildQuestsMod.LOGGER.warn("Invalid FX snapshot", ex);
                        }
                    });
                    ctx.get().setPacketHandled(true);
                }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        FxNetwork.server = new FxNetwork.ServerTransport() {
            @Override public boolean canSend(ServerPlayer player) { return CHANNEL.isRemotePresent(player.connection.connection); }
            @Override public void send(ServerPlayer player, FxModels.Snapshot snapshot) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new FxSnapshotS2C(FxSettings.JSON.toJson(snapshot)));
            }
        };
    }

    public static void setOpenGuildHandler(Consumer<ServerPlayer> handler) {
        openGuildHandler = handler;
    }

    public static void setBossPinSink(BossPinSink sink) {
        bossPinSink = sink;
    }

    public static void sendBossPin(ServerPlayer player, int x, int y, int z, int lifetime) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new BossPinS2C(x, y, z, lifetime));
    }

    public static void setFxSnapshotSink(Consumer<FxModels.Snapshot> sink) { fxSnapshotSink = sink; }
    public static void sendFxRequest(FxModels.Request request) { CHANNEL.sendToServer(new FxRequestC2S(FxSettings.JSON.toJson(request))); }
    public record FxRequestC2S(String json) { }
    public record FxSnapshotS2C(String json) { }

    /** C2S: the client asked to open the guild quest screen. */
    public record OpenGuildC2S() {
        public static void encode(OpenGuildC2S msg, FriendlyByteBuf buf) {
        }

        public static OpenGuildC2S decode(FriendlyByteBuf buf) {
            return new OpenGuildC2S();
        }
    }

    /** S2C: beacon-style boss pin marker. lifetime 0 clears the pin. */
    public record BossPinS2C(int x, int y, int z, int lifetime) {
        public static void encode(BossPinS2C msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.x());
            buf.writeVarInt(msg.y());
            buf.writeVarInt(msg.z());
            buf.writeVarInt(msg.lifetime());
        }

        public static BossPinS2C decode(FriendlyByteBuf buf) {
            return new BossPinS2C(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
    }

    /** Client-side sink for boss pin packets; kept free of client classes so the server may load this class. */
    public interface BossPinSink {
        void onBossPin(int x, int y, int z, int lifetime);
    }
}

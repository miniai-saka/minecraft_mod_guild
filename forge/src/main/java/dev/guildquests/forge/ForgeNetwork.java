package dev.guildquests.forge;

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
 * The single Forge play channel used by this mod. It carries both logical packets:
 * C2S guildquests:open_guild (message 0) and S2C guildquests:boss_pin (message 1).
 */
public final class ForgeNetwork {
    public static final String PROTOCOL = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("guildquests", "main"),
            () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL),
            NetworkRegistry.acceptMissingOr(PROTOCOL));

    private static Consumer<ServerPlayer> openGuildHandler;
    private static BossPinSink bossPinSink;

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

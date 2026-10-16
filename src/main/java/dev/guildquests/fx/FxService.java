package dev.guildquests.fx;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.guildquests.GuildQuestsMod;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.fx.FxModels.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** All mutations, wallet transfers, quote calculation and snapshots run on the server thread. */
public final class FxService {
    public static final Identifier REQUEST = new Identifier("guildquests", "fx_request");
    public static final Identifier SNAPSHOT = new Identifier("guildquests", "fx_snapshot");
    private static final Map<MinecraftServer, Session> SESSIONS = new WeakHashMap<>();
    private static final class Session {
        int marketTicks, snapshotTicks;
        final Map<UUID, String> viewers = new HashMap<>();
        final Map<UUID, Long> lastRequests = new HashMap<>();
    }
    private FxService() { }

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(REQUEST, (server, player, handler, buf, sender) -> {
            final Request request;
            try { request = FxSettings.JSON.fromJson(buf.readString(4096), Request.class); }
            catch (RuntimeException ignored) { return; }
            if (request == null) return;
            server.execute(() -> handle(server, player, request));
        });
        ServerTickEvents.END_SERVER_TICK.register(FxService::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(SESSIONS::remove);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Session session = SESSIONS.get(server);
            if (session != null) {
                session.viewers.remove(handler.player.getUuid()); session.lastRequests.remove(handler.player.getUuid());
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(CommandManager.literal("guild").then(CommandManager.literal("fx").executes(context -> {
                open(context.getSource().getPlayerOrThrow()); return 1;
            })));
            dispatcher.register(CommandManager.literal("guildadmin").requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.literal("fx")
                            .then(CommandManager.literal("reload").executes(context -> {
                                boolean ok = FxSettings.load();
                                context.getSource().sendFeedback(() -> Text.literal(ok ? "FX設定を再読込しました。" : "fx.jsonの読込に失敗しました。ログを確認してください。"), false);
                                return ok ? 1 : 0;
                            }))
                            .then(CommandManager.literal("shock").then(CommandManager.argument("symbol", StringArgumentType.word())
                                    .then(CommandManager.literal("crash").executes(context -> shock(context.getSource().getServer(), StringArgumentType.getString(context, "symbol"), true)))
                                    .then(CommandManager.literal("rally").executes(context -> shock(context.getSource().getServer(), StringArgumentType.getString(context, "symbol"), false)))))));
        });
    }

    private static int shock(MinecraftServer server, String symbol, boolean crash) {
        FxPersistentState state = FxPersistentState.get(server);
        try { state.engine().shock(symbol, crash); state.markDirty(); return 1; }
        catch (IllegalArgumentException ex) { return 0; }
    }

    public static void open(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, SNAPSHOT)) {
            player.sendMessage(Text.literal("FX画面にはクライアントにも同じGuildQuests Modが必要です。"), false); return;
        }
        if (!FxPersistentState.get(player.getServer()).healthy()) {
            player.sendMessage(Text.literal("FXの保存データを保護するため取引を停止しました。管理者がバックアップを確認してください。"), false); return;
        }
        player.closeHandledScreen();
        Session session = SESSIONS.computeIfAbsent(player.getServer(), ignored -> new Session());
        session.viewers.put(player.getUuid(), FxSettings.get().symbols.get(0).id);
        send(player, true, "", false);
    }

    private static void handle(MinecraftServer server, ServerPlayerEntity player, Request request) {
        Session session = SESSIONS.computeIfAbsent(server, ignored -> new Session());
        String op = request.op == null ? "" : request.op;
        if (op.equals("UNSUBSCRIBE")) { session.viewers.remove(player.getUuid()); return; }
        long now = System.nanoTime();
        Long last = session.lastRequests.get(player.getUuid());
        if (last != null && now - last < 100_000_000L) return; // Ten requests/sec, including invalid input.
        session.lastRequests.put(player.getUuid(), now);
        if (op.equals("OPEN")) { open(player); return; }
        if (!session.viewers.containsKey(player.getUuid())) return;
        FxPersistentState persistent = FxPersistentState.get(server);
        if (!persistent.healthy()) return;
        FxEngine engine = persistent.engine();
        Account account = engine.account(player.getUuidAsString());
        String message = "";
        boolean error = false;
        try {
            engine.protect(account);
            switch (op) {
                case "SELECT" -> {
                    if (!engine.state.markets.containsKey(request.symbol)) throw new IllegalArgumentException("不明な通貨です。");
                    session.viewers.put(player.getUuid(), request.symbol);
                }
                case "ORDER" -> message = engine.submit(account, request);
                case "CLOSE" -> { engine.close(account, request.id, request.units); message = "建玉を決済しました。"; }
                case "CLOSE_ALL" -> { engine.closeAll(account); message = "全建玉を決済しました。"; }
                case "CANCEL" -> { engine.cancel(account, request.id); message = "注文を取り消しました。"; }
                case "CANCEL_ALL" -> { engine.cancelAll(account); message = "全注文を取り消しました。"; }
                case "AMEND" -> { engine.amend(account, request.id, request.takeProfit, request.stopLoss, request.trailingDistance); message = "決済OCOを更新しました。"; }
                case "DEPOSIT", "WITHDRAW" -> {
                    GuildPersistentState guild = GuildPersistentState.get(server);
                    PlayerGuildData wallet = guild.get(player.getUuid());
                    if (request.amount <= 0) throw new IllegalArgumentException("入出金額は1以上の整数です。");
                    if (op.equals("DEPOSIT")) {
                        if (!engine.config().enabled) throw new IllegalArgumentException("FXへの入金は停止中です。");
                        if (wallet.delinquent) throw new IllegalArgumentException("リボ延滞中はFXへ入金できません。");
                        if (request.amount > wallet.coins) throw new IllegalArgumentException("所持ギルドコインが不足しています。");
                        engine.deposit(account, request.amount); wallet.coins -= request.amount;
                        message = request.amount + " GCをFX口座に入金しました。";
                    } else {
                        if (wallet.coins > Long.MAX_VALUE - request.amount) throw new IllegalArgumentException("所持コインが上限を超えます。");
                        engine.withdraw(account, request.amount); wallet.coins += request.amount;
                        message = request.amount + " GCを所持金に戻しました。";
                    }
                    guild.markDirty();
                }
                default -> throw new IllegalArgumentException("不明な操作です。");
            }
        } catch (IllegalArgumentException ex) { message = ex.getMessage(); error = true; }
        persistent.markDirty();
        send(player, false, message, error);
    }

    private static void tick(MinecraftServer server) {
        Session session = SESSIONS.computeIfAbsent(server, ignored -> new Session());
        FxConfig config = FxSettings.get();
        if (++session.marketTicks >= config.marketIntervalTicks) {
            FxPersistentState state = FxPersistentState.get(server);
            if (state.healthy()) state.engine().step(session.marketTicks * 50L);
            session.marketTicks = 0; state.markDirty();
        }
        if (++session.snapshotTicks >= config.snapshotIntervalTicks) {
            session.snapshotTicks = 0;
            for (UUID id : session.viewers.keySet()) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
                if (player != null) send(player, false, "", false);
            }
        }
    }

    private static void send(ServerPlayerEntity player, boolean open, String message, boolean error) {
        MinecraftServer server = player.getServer();
        Session session = SESSIONS.get(server);
        if (session == null || !ServerPlayNetworking.canSend(player, SNAPSHOT)) return;
        if (!FxPersistentState.get(server).healthy()) return;
        Snapshot snapshot = FxPersistentState.get(server).engine().snapshot(player.getUuidAsString(),
                session.viewers.get(player.getUuid()), GuildPersistentState.get(server).get(player.getUuid()).coins);
        snapshot.open = open; snapshot.message = message; snapshot.error = error;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(FxSettings.JSON.toJson(snapshot), 1_000_000);
        ServerPlayNetworking.send(player, SNAPSHOT, buf);
    }
}

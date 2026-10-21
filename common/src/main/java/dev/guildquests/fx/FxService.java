package dev.guildquests.fx;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.guildquests.GuildQuestsMod;
import dev.guildquests.data.GuildPersistentState;
import dev.guildquests.data.PlayerGuildData;
import dev.guildquests.fx.FxModels.*;
import dev.guildquests.platform.Platform;
import dev.guildquests.config.ConfigManager;
import dev.guildquests.service.GuildDebtPolicy;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** All mutations, wallet transfers, quote calculation and snapshots run on the server thread. */
public final class FxService {
    public static final ResourceLocation REQUEST = new ResourceLocation("guildquests", "fx_request");
    public static final ResourceLocation SNAPSHOT = new ResourceLocation("guildquests", "fx_snapshot");
    private static final Map<MinecraftServer, Session> SESSIONS = new WeakHashMap<>();
    private static final class Session {
        int marketTicks, snapshotTicks;
        final Map<UUID, String> viewers = new HashMap<>();
        final Map<UUID, Long> lastRequests = new HashMap<>();
    }
    private FxService() { }

    public static void init() {
        Platform.onServerTick(FxService::tick);
        Platform.onServerStopped(SESSIONS::remove);
        Platform.onCommands((dispatcher, registry) -> {
            dispatcher.register(Commands.literal("guild").then(Commands.literal("fx").executes(context -> {
                open(context.getSource().getPlayerOrException()); return 1;
            })));
            dispatcher.register(Commands.literal("guildadmin").requires(source -> source.hasPermission(2))
                    .then(Commands.literal("fx")
                            .then(Commands.literal("margincall")
                                    .executes(context -> {
                                        context.getSource().sendSuccess(() -> Component.literal("FX追証: " + (FxSettings.get().marginCallEnabled ? "あり" : "なし（ゼロカット）")), false);
                                        return 1;
                                    })
                                    .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
                                        boolean enabled = BoolArgumentType.getBool(context, "enabled");
                                        try { FxSettings.setMarginCall(enabled); }
                                        catch (java.io.IOException ex) { context.getSource().sendFailure(Component.literal("fx.jsonを保存できませんでした。変更は適用していません。")); return 0; }
                                        context.getSource().sendSuccess(() -> Component.literal("FX追証を" + (enabled ? "有効" : "無効") + "にしてfx.jsonへ保存しました。既存の債務は維持されます。"), true);
                                        return 1;
                                    })))
                            .then(Commands.literal("reload").executes(context -> {
                                boolean ok = FxSettings.load();
                                context.getSource().sendSuccess(() -> Component.literal(ok ? "FX設定を再読込しました。" : "fx.jsonの読込に失敗しました。ログを確認してください。"), false);
                                return ok ? 1 : 0;
                            }))
                            .then(Commands.literal("shock").then(Commands.argument("symbol", StringArgumentType.word())
                                    .then(Commands.literal("crash").executes(context -> shock(context.getSource().getServer(), StringArgumentType.getString(context, "symbol"), true)))
                                    .then(Commands.literal("rally").executes(context -> shock(context.getSource().getServer(), StringArgumentType.getString(context, "symbol"), false)))))));
        });
    }

    private static int shock(MinecraftServer server, String symbol, boolean crash) {
        FxPersistentState state = FxPersistentState.get(server);
        try { state.engine().shock(symbol, crash); state.setDirty(); return 1; }
        catch (IllegalArgumentException ex) { return 0; }
    }

    public static void open(ServerPlayer player) {
        if (FxNetwork.server == null || !FxNetwork.server.canSend(player)) {
            player.sendSystemMessage(Component.literal("FX画面にはクライアントにも同じGuildQuests Modが必要です。")); return;
        }
        if (!FxPersistentState.get(player.getServer()).healthy()) {
            player.sendSystemMessage(Component.literal("FXの保存データを保護するため取引を停止しました。管理者がバックアップを確認してください。")); return;
        }
        player.closeContainer();
        Session session = SESSIONS.computeIfAbsent(player.getServer(), ignored -> new Session());
        session.viewers.put(player.getUUID(), FxSettings.get().symbols.get(0).id);
        send(player, true, "", false);
    }

    public static void handle(MinecraftServer server, ServerPlayer player, Request request) {
        Session session = SESSIONS.computeIfAbsent(server, ignored -> new Session());
        String op = request.op == null ? "" : request.op;
        if (op.equals("UNSUBSCRIBE")) { session.viewers.remove(player.getUUID()); return; }
        long now = System.nanoTime();
        Long last = session.lastRequests.get(player.getUUID());
        if (last != null && now - last < 100_000_000L) return; // Ten requests/sec, including invalid input.
        session.lastRequests.put(player.getUUID(), now);
        if (op.equals("OPEN")) { open(player); return; }
        if (!session.viewers.containsKey(player.getUUID())) return;
        FxPersistentState persistent = FxPersistentState.get(server);
        if (!persistent.healthy()) return;
        FxEngine engine = persistent.engine();
        Account account = engine.account(player.getStringUUID());
        String message = "";
        boolean error = false;
        try {
            engine.protect(account);
            settle(server, engine, player.getStringUUID(), account);
            switch (op) {
                case "SELECT" -> {
                    if (!engine.state.markets.containsKey(request.symbol)) throw new IllegalArgumentException("不明な通貨です。");
                    session.viewers.put(player.getUUID(), request.symbol);
                }
                case "ORDER" -> message = engine.submit(account, request);
                case "CLOSE" -> { engine.close(account, request.id, request.units); message = "建玉を決済しました。"; }
                case "CLOSE_ALL" -> { engine.closeAll(account); message = "全建玉を決済しました。"; }
                case "CANCEL" -> { engine.cancel(account, request.id); message = "注文を取り消しました。"; }
                case "CANCEL_ALL" -> { engine.cancelAll(account); message = "全注文を取り消しました。"; }
                case "AMEND" -> { engine.amend(account, request.id, request.takeProfit, request.stopLoss, request.trailingDistance); message = "決済OCOを更新しました。"; }
                case "REPAY_DEBT" -> {
                    if (ConfigManager.config().miningDebtRepaymentOnly)
                        throw new IllegalArgumentException("返済は採掘のみです。設定したブロックを掘って返済してください。");
                    if (request.amount <= 0) throw new IllegalArgumentException("返済額は1以上の整数です。");
                    var guild = GuildPersistentState.get(server);
                    long paid = GuildDebtPolicy.repay(guild.get(player.getUUID()), request.amount);
                    if (paid == 0) throw new IllegalArgumentException("返済する債務がないか、所持コインが不足しています。");
                    account.tradingLocked = guild.get(player.getUUID()).delinquent;
                    guild.setDirty(); message = paid + " GCを債務返済に充てました。";
                }
                case "DEPOSIT", "WITHDRAW" -> {
                    GuildPersistentState guild = GuildPersistentState.get(server);
                    PlayerGuildData wallet = guild.get(player.getUUID());
                    if (request.amount <= 0) throw new IllegalArgumentException("入出金額は1以上の整数です。");
                    if (op.equals("DEPOSIT")) {
                        if (!engine.config().enabled) throw new IllegalArgumentException("FXへの入金は停止中です。");
                        if (wallet.delinquent) throw new IllegalArgumentException("債務の滞納中はFXへ入金できません。");
                        if (request.amount > wallet.coins) throw new IllegalArgumentException("所持ギルドコインが不足しています。");
                        engine.deposit(account, request.amount); wallet.coins -= request.amount;
                        message = request.amount + " GCをFX口座に入金しました。";
                    } else {
                        if (wallet.coins > Long.MAX_VALUE - request.amount) throw new IllegalArgumentException("所持コインが上限を超えます。");
                        engine.withdraw(account, request.amount); wallet.coins += request.amount;
                        message = request.amount + " GCを所持金に戻しました。";
                    }
                    guild.setDirty();
                }
                default -> throw new IllegalArgumentException("不明な操作です。");
            }
        } catch (IllegalArgumentException ex) { message = ex.getMessage(); error = true; }
        settle(server, engine, player.getStringUUID(), account);
        persistent.setDirty();
        send(player, false, message, error);
    }

    private static void tick(MinecraftServer server) {
        Session session = SESSIONS.computeIfAbsent(server, ignored -> new Session());
        FxConfig config = FxSettings.get();
        if (++session.marketTicks >= config.marketIntervalTicks) {
            FxPersistentState state = FxPersistentState.get(server);
            if (state.healthy()) {
                FxEngine engine = state.engine();
                long day = server.overworld().getDayTime() / 24000L;
                engine.beginDay(day);
                for (var entry : engine.state.accounts.entrySet()) settle(server, engine, entry.getKey(), entry.getValue());
                engine.step(session.marketTicks * 50L);
                for (var entry : engine.state.accounts.entrySet()) settle(server, engine, entry.getKey(), entry.getValue());
            }
            session.marketTicks = 0; state.setDirty();
        }
        if (++session.snapshotTicks >= config.snapshotIntervalTicks) {
            session.snapshotTicks = 0;
            session.lastRequests.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            session.viewers.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            for (UUID id : session.viewers.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) send(player, false, "", false);
            }
        }
    }

    private static void settle(MinecraftServer server, FxEngine engine, String owner, Account account) {
        PlayerGuildData wallet = GuildPersistentState.get(server).get(UUID.fromString(owner));
        var result = FxMarginSettlement.settle(engine, account, wallet,
                System.currentTimeMillis() + ConfigManager.config().riboIntervalMinutes * 60_000L);
        if (account.tradingLocked) engine.cancelAll(account);
        if (result.paid() > 0 || result.debtAdded() > 0) {
            GuildPersistentState.get(server).setDirty();
            ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(owner));
            if (online != null) online.sendSystemMessage(Component.literal("[Guild FX] 追証精算: 所持金 " + result.paid()
                    + " GC / 未払い " + result.debtAdded() + " GC"
                    + (result.debtAdded() > 0 ? "。既存の滞納処理でギルド機能をロックしました。"
                    + (ConfigManager.config().miningDebtRepaymentOnly
                    ? "採掘で返済してください。" : "クエスト報酬・定期返済で解消できます。") : "。支払い完了。")));
        }
    }

    private static void send(ServerPlayer player, boolean open, String message, boolean error) {
        MinecraftServer server = player.getServer();
        Session session = SESSIONS.get(server);
        if (session == null || FxNetwork.server == null || !FxNetwork.server.canSend(player)) return;
        if (!FxPersistentState.get(server).healthy()) return;
        Snapshot snapshot = FxPersistentState.get(server).engine().snapshot(player.getStringUUID(),
                session.viewers.get(player.getUUID()), GuildPersistentState.get(server).get(player.getUUID()).coins);
        snapshot.open = open; snapshot.message = message; snapshot.error = error;
        snapshot.miningDebtRepaymentOnly = ConfigManager.config().miningDebtRepaymentOnly;
        var wallet = GuildPersistentState.get(server).get(player.getUUID());
        snapshot.guildDebt = wallet.debt; snapshot.delinquent = wallet.delinquent;
        FxNetwork.server.send(player, snapshot);
    }
}

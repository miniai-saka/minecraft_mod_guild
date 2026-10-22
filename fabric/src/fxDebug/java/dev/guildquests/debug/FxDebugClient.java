package dev.guildquests.debug;

import com.mojang.blaze3d.platform.NativeImage;
import dev.guildquests.client.*;
import dev.guildquests.fx.FxModels.*;
import dev.guildquests.fx.FxSettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real packets, native widgets, legacy terminal, debt collection, configuration and persistence. */
public final class FxDebugClient implements ClientModInitializer {
    private int phase;
    private long next, started = System.currentTimeMillis(), titleReady;
    private boolean connecting, finished;
    private final List<String> checks = new ArrayList<>();
    @Override public void onInitializeClient() {
        if (Boolean.getBoolean("guildquests.adminDebug")) { new AdminDebugClient().onInitializeClient(); return; }
        try { Files.deleteIfExists(Path.of("smoke-failure.txt")); FxUiPreferences.set("SERVER"); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (finished || System.currentTimeMillis() < next) return;
            try {
                if (System.currentTimeMillis() - started > 240000) throw new IllegalStateException("Smoke test timed out at phase " + phase);
                client.getToasts().clear();
                if (client.screen instanceof DisconnectedScreen && client.player == null) {
                    connecting = false; titleReady = System.currentTimeMillis() + 3000;
                    client.setScreen(new TitleScreen()); return;
                }
                if (client.screen instanceof TitleScreen && !connecting) {
                    if (client.getOverlay() != null) return;
                    if (titleReady == 0) titleReady = System.currentTimeMillis() + 2000;
                    if (System.currentTimeMillis() < titleReady) return;
                    connecting = true;
                    ConnectScreen.startConnecting(client.screen, client, ServerAddress.parseString("127.0.0.1:25577"),
                            new ServerData("FX local debug", "127.0.0.1:25577", false), false);
                    return;
                }
                if (client.player == null) return;
                next = System.currentTimeMillis() + 1000;
                Snapshot s = FxClient.latest();
                switch (phase) {
                    case 0 -> { client.options.guiScale().set(2); client.resizeDisplay(); FxClient.send(req("OPEN")); phase++; }
                    case 1 -> {
                        if (s == null) return;
                        if (Boolean.getBoolean("guildquests.fxCapture")) { phase = 100; return; }
                        check(client.screen instanceof FxScreen && s.wallet == 50000, "Native Minecraft UI is the default, wallet synchronized");
                        Request r = req("DEPOSIT"); r.amount = 10000; FxClient.send(r); phase++;
                    }
                    case 2 -> {
                        if (s.account.balance != 10000) return;
                        check(s.wallet == 40000, "Atomic wallet to FX deposit");
                        setField(client, "units", "10"); setField(client, "leverage", "100");
                        screenshot(client, "native-trade.png"); click(client, "注文内容を確認"); phase++;
                    }
                    case 3 -> { screenshot(client, "native-confirmation.png"); click(client, "確定して送信"); phase++; }
                    case 4 -> {
                        if (s.account.positions.isEmpty()) return;
                        check(s.account.positions.size() == 1, "Native market form and confirmation execute real order");
                        click(client, "建玉"); phase++;
                    }
                    case 5 -> { screenshot(client, "native-positions.png"); click(client, "決済"); phase++; }
                    case 6 -> { click(client, "確定して送信"); phase++; }
                    case 7 -> {
                        if (!s.account.positions.isEmpty()) return;
                        check(s.account.balance == 10000, "Native full close settles actual position");
                        click(client, "取引"); click(client, "成行 ▼"); setField(client, "price", "95");
                        setField(client, "units", "5"); setField(client, "leverage", "50"); click(client, "注文内容を確認"); phase++;
                    }
                    case 8 -> { click(client, "確定して送信"); phase++; }
                    case 9 -> {
                        if (s.account.orders.size() != 1) return;
                        check(s.account.orders.get(0).type == OrderType.LIMIT, "Native limit order stored on server");
                        click(client, "指値 ▼"); setField(client, "price", "105"); click(client, "注文内容を確認"); phase++;
                    }
                    case 10 -> { click(client, "確定して送信"); phase++; }
                    case 11 -> {
                        if (s.account.orders.size() != 2) return;
                        check(s.account.orders.get(1).type == OrderType.STOP, "Native stop order stored on server");
                        click(client, "注文"); phase++;
                    }
                    case 12 -> { screenshot(client, "native-orders.png"); click(client, "設定"); phase++; }
                    case 13 -> { screenshot(client, "ui-settings.png"); click(client, "白背景・高機能ターミナル"); phase++; }
                    case 14 -> {
                        check(client.screen instanceof FxScreen && s.account.orders.size() == 2, "UI switch preserves live subscription and orders");
                        screenshot(client, "white-terminal.png"); whiteClick(client, 1023, 268);
                        setField(client, "units", "2"); setField(client, "leverage", "25"); setField(client, "price", "96");
                        setField(client, "tp", "102"); setField(client, "sl", "90"); phase++;
                    }
                    case 15 -> { screenshot(client, "white-ifo.png"); whiteClick(client, 940, 574); phase++; }
                    case 16 -> { screenshot(client, "white-confirmation.png"); whiteClick(client, 650, 440); phase++; }
                    case 17 -> {
                        if (s.account.orders.size() != 3) return;
                        check(s.account.orders.get(2).type == OrderType.IFO, "Legacy white IFO form still works after upstream merge");
                        whiteClick(client, 495, 35); phase++;
                    }
                    case 18 -> { click(client, "Minecraft風・簡易取引"); phase++; }
                    case 19 -> {
                        check(client.screen instanceof FxScreen, "Switch back to native UI without disconnecting");
                        FxClient.send(req("CANCEL_ALL")); phase++;
                    }
                    case 20 -> {
                        if (!s.account.orders.isEmpty()) return;
                        check(s.account.orders.isEmpty(), "Orders remain manageable across both UIs");
                        command(client, "fxdebug seed"); phase++;
                    }
                    case 21 -> {
                        if (s.account.balance != 100 || s.wallet != 50000) return;
                        command(client, "guildadmin fx margincall true"); phase++;
                    }
                    case 22 -> {
                        if (!s.config.marginCallEnabled) return;
                        check(s.config.marginCallEnabled, "Admin command enables margin calls and synchronizes clients");
                        Request r = req("ORDER"); r.units = 900; r.leverage = 1000; FxClient.send(r); phase++;
                    }
                    case 23 -> { if (s.account.positions.isEmpty()) return; command(client, "fxdebug gap"); phase++; }
                    case 24 -> {
                        if (s.guildDebt == 0) return;
                        check(s.wallet == 0 && s.guildDebt == 21900 && s.delinquent && s.account.tradingLocked, "1000x gap collects wallet and enters existing delinquency with 21,900 GC debt");
                        screenshot(client, "native-margin-call.png");
                        Request r = req("ORDER"); r.units = 1; r.leverage = 10; FxClient.send(r); phase++;
                    }
                    case 25 -> {
                        check(s.account.positions.isEmpty() && (boolean) read(client.screen, "error"), "Delinquent account rejects new trade over network");
                        command(client, "guildadmin fx margincall false"); phase++;
                    }
                    case 26 -> {
                        if (s.config.marginCallEnabled) return;
                        check(s.guildDebt == 21900 && s.delinquent, "Disabling margin calls preserves accrued debt and lock");
                        click(client, "入金 / 出金"); phase++;
                    }
                    case 27 -> { screenshot(client, "native-debt-repayment.png"); command(client, "fxdebug cash 10000"); phase++; }
                    case 28 -> { setField(client, "amount", "10000"); click(client, "所持金から債務を返済"); phase++; }
                    case 29 -> {
                        if (s.guildDebt != 11900) return;
                        check(s.delinquent && s.wallet == 0, "Partial repayment keeps existing delinquency lock");
                        command(client, "fxdebug cash 20000"); phase++;
                    }
                    case 30 -> { click(client, "入金 / 出金"); setField(client, "amount", "20000"); click(client, "所持金から債務を返済"); phase++; }
                    case 31 -> {
                        if (s.guildDebt != 0) return;
                        check(!s.delinquent && !s.account.tradingLocked && s.wallet == 8100, "Full repayment clears debt and trading lock");
                        Request r = req("DEPOSIT"); r.amount = 100; FxClient.send(r); phase++;
                    }
                    case 32 -> { Request r = req("ORDER"); r.units = 1; r.leverage = 10; FxClient.send(r); phase++; }
                    case 33 -> {
                        if (s.account.positions.isEmpty()) return;
                        check(s.account.positions.size() == 1, "Trading resumes after full repayment");
                        FxClient.send(req("CLOSE_ALL")); phase++;
                    }
                    case 34 -> { command(client, "fxdebug seed"); phase++; }
                    case 35 -> { command(client, "guildadmin fx margincall true"); phase++; }
                    case 36 -> {
                        if (!s.config.marginCallEnabled || s.account.balance != 100) return;
                        Request r = req("ORDER"); r.units = 900; r.leverage = 1000; FxClient.send(r); phase++;
                    }
                    case 37 -> { if (s.account.positions.isEmpty()) return; command(client, "fxdebug gap"); phase++; }
                    case 38 -> {
                        if (s.guildDebt != 21900) return;
                        command(client, "fxdebug legacycheck");
                        click(client, "設定"); phase++;
                    }
                    case 39 -> { click(client, "白背景・高機能ターミナル"); phase++; }
                    case 40 -> { screenshot(client, "white-margin-call.png"); whiteClick(client, 965, 35); phase++; }
                    case 41 -> { screenshot(client, "white-debt-repayment.png"); client.screen.keyPressed(256, 0, 0); whiteClick(client, 495, 35); phase++; }
                    case 42 -> {
                        if (client.screen instanceof FxScreen) { whiteClick(client, 495, 35); return; }
                        click(client, "サーバー設定に従う（既定）"); phase++;
                    }
                    case 43 -> {
                        check(client.screen instanceof FxScreen && FxUiPreferences.style().equals("SERVER"), "Server UI preference restored and persisted locally");
                        client.getWindow().setWindowed(1100, 700); phase++; next += 1200;
                    }
                    case 44 -> { screenshot(client, "native-compact.png"); client.options.guiScale().set(3); client.resizeDisplay(); phase++; }
                    case 45 -> { screenshot(client, "native-gui-scale3.png"); click(client, "入金 / 出金"); phase++; }
                    case 46 -> { screenshot(client, "native-compact-transfer.png"); client.screen.keyPressed(256, 0, 0); phase++; }
                    case 47 -> {
                        Files.writeString(Path.of("smoke-snapshot.json"), FxSettings.JSON.toJson(s)); Files.write(Path.of("smoke-results.txt"), checks);
                        var expected = new com.google.gson.JsonObject(); expected.addProperty("uuid", client.player.getStringUUID());
                        expected.addProperty("wallet", s.wallet); expected.addProperty("balance", s.account.balance);
                        expected.addProperty("debt", s.guildDebt); expected.addProperty("delinquent", s.delinquent);
                        expected.addProperty("locked", s.account.tradingLocked); expected.addProperty("marginCall", s.config.marginCallEnabled);
                        Files.writeString(Path.of("../fx-debug-server/expected-after-restart.json"), expected.toString());
                        command(client, "save-all flush"); phase++; next += 2000;
                    }
                    case 48 -> { System.out.println("FX_SMOKE_PASS " + checks.size() + " network assertions; rendered native and white UI screenshots"); finished = true; client.stop(); }
                    case 100 -> { client.getWindow().setWindowed(1100, 720); client.options.guiScale().set(3); client.resizeDisplay(); phase++; next += 1500; }
                    case 101 -> { screenshot(client, "native-minimum.png"); click(client, "入金 / 出金"); phase++; }
                    case 102 -> { screenshot(client, "native-minimum-transfer.png"); client.screen.keyPressed(256, 0, 0); phase++; }
                    case 103 -> { click(client, "注文内容を確認"); phase++; }
                    case 104 -> {
                        screenshot(client, "native-minimum-confirmation.png");
                        int height = client.getWindow().getGuiScaledHeight();
                        check(height >= 240 && height <= 245, "Minimum native layout rendered at GUI scale 3, logical height " + height);
                        Files.writeString(Path.of("capture-results.txt"), checks.get(checks.size() - 1));
                        phase++;
                    }
                    case 105 -> { finished = true; client.stop(); }
                    default -> { }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                try { Files.writeString(Path.of("smoke-failure.txt"), "Phase " + phase + ": " + ex); Files.write(Path.of("smoke-results.txt"), checks); } catch (Exception ignored) { }
                finished = true; client.stop();
            }
        });
    }
    private Request req(String op) { Request r = new Request(); r.op = op; r.symbol = "AUR"; return r; }
    private void check(boolean ok, String label) { if (!ok) throw new IllegalStateException(label); checks.add("PASS " + label); }
    private static Object read(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private static void setField(Minecraft client, String name, String value) throws Exception { ((EditBox) read(client.screen, name)).setValue(value); }
    private static void command(Minecraft client, String text) { client.player.connection.sendCommand(text); }
    private static void click(Minecraft client, String label) {
        for (var child : client.screen.children()) if (child instanceof Button b && b.active && b.getMessage().getString().equals(label)) {
            client.screen.mouseClicked(b.getX() + b.getWidth() / 2.0, b.getY() + 10, 0); return;
        }
        throw new IllegalStateException("Missing button " + label);
    }
    private static void whiteClick(Minecraft client, int x, int y) {
        double scale = Math.min(client.getWindow().getGuiScaledWidth() / 1100.0, client.getWindow().getGuiScaledHeight() / 680.0);
        double ox = (client.getWindow().getGuiScaledWidth() - 1100 * scale) / 2;
        double oy = (client.getWindow().getGuiScaledHeight() - 680 * scale) / 2;
        client.screen.mouseClicked(ox + x * scale, oy + y * scale, 0);
    }
    private static void screenshot(Minecraft client, String name) throws Exception {
        Files.createDirectories(Path.of("screenshots"));
        try (NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("screenshots", name)); }
    }
}

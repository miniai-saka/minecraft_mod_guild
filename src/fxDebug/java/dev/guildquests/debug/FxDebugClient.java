package dev.guildquests.debug;

import dev.guildquests.client.FxClient;
import dev.guildquests.client.FxScreen;
import dev.guildquests.fx.FxModels.*;
import dev.guildquests.fx.FxSettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.gui.widget.TextFieldWidget;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Exercises the real client packets and the real DrawContext UI without changing release code. */
public final class FxDebugClient implements ClientModInitializer {
    private int phase;
    private long next, started = System.currentTimeMillis();
    private boolean connecting, finished;
    private long titleReady;
    private double depositedBalance;
    private final List<String> checks = new ArrayList<>();
    @Override public void onInitializeClient() {
        try { Files.deleteIfExists(Path.of("smoke-failure.txt")); } catch (Exception ignored) { }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (phase >= 6) client.getToastManager().clear();
            if (finished || System.currentTimeMillis() < next) return;
            try {
                if (System.currentTimeMillis() - started > 180000) throw new IllegalStateException("Smoke test timed out at phase " + phase);
                if (client.currentScreen instanceof TitleScreen && !connecting) {
                    if (client.getOverlay() != null) return;
                    if (titleReady == 0) titleReady = System.currentTimeMillis() + 2000;
                    if (System.currentTimeMillis() < titleReady) return;
                    connecting = true;
                    ConnectScreen.connect(client.currentScreen, client, ServerAddress.parse("127.0.0.1:25577"),
                            new ServerInfo("FX local debug", "127.0.0.1:25577", false), false);
                    return;
                }
                if (client.player == null) return;
                next = System.currentTimeMillis() + 650;
                Snapshot s = client.currentScreen instanceof FxScreen screen ? (Snapshot) read(screen, "data") : null;
                switch (phase) {
                    case 0 -> { Request r = req("OPEN"); FxClient.send(r); phase++; }
                    case 1 -> {
                        if (s == null) return;
                        check(s.wallet == 50000, "Server wallet synchronized (50,000 GC)");
                        Request r = req("DEPOSIT"); r.amount = 10000; FxClient.send(r); phase++;
                    }
                    case 2 -> {
                        if (s == null || s.account.balance == 0) return;
                        check(s.wallet == 40000 && s.account.balance == 10000, "Atomic wallet -> FX deposit over real C2S/S2C");
                        Request r = req("ORDER"); r.units = 10; r.leverage = 100; FxClient.send(r); phase++;
                    }
                    case 3 -> {
                        if (s.account.positions.isEmpty()) return;
                        check(s.account.positions.size() == 1, "Market order fills on server and appears as a position");
                        depositedBalance = s.account.balance;
                        Request r = req("ORDER"); r.units = -1; r.leverage = 1000; FxClient.send(r); phase++;
                    }
                    case 4 -> {
                        check((boolean) read(client.currentScreen, "messageError") && s.account.positions.size() == 1 && s.account.balance == depositedBalance, "Malformed negative quantity rejected without money mutation");
                        Request r = req("ORDER"); r.type = OrderType.OCO; r.units = 5; r.leverage = 50;
                        r.price = s.quotes.get(0).price * .95; r.secondPrice = s.quotes.get(0).price * 1.05; FxClient.send(r); phase++;
                    }
                    case 5 -> {
                        if (s.account.orders.isEmpty()) return;
                        check(s.account.orders.size() == 1 && s.account.orders.get(0).type == OrderType.OCO, "Entry OCO synchronized with one reservation");
                        Request r = req("ORDER"); r.type = OrderType.IFO; r.units = 3; r.leverage = 25;
                        r.price = s.quotes.get(0).price * .96; r.takeProfit = s.quotes.get(0).price * 1.02; r.stopLoss = s.quotes.get(0).price * .9;
                        FxClient.send(r); phase++;
                    }
                    case 6 -> {
                        if (s.account.orders.size() < 2) return;
                        check(s.account.orders.size() == 2, "IFO stored with contingent TP/SL");
                        client.options.getGuiScale().setValue(2); client.onResolutionChanged();
                        client.getToastManager().clear();
                        write(client.currentScreen, "messageUntil", 0L); phase++; next += 1500;
                    }
                    case 7 -> {
                        screenshot(client, "fx-terminal.png"); click(client, 1023, 268);
                        ((TextFieldWidget) read(client.currentScreen, "units")).setText("2");
                        ((TextFieldWidget) read(client.currentScreen, "leverage")).setText("25");
                        ((TextFieldWidget) read(client.currentScreen, "price")).setText(String.format(java.util.Locale.ROOT, "%.4f", s.quotes.get(0).price * .96));
                        ((TextFieldWidget) read(client.currentScreen, "tp")).setText(String.format(java.util.Locale.ROOT, "%.4f", s.quotes.get(0).price * 1.02));
                        ((TextFieldWidget) read(client.currentScreen, "sl")).setText(String.format(java.util.Locale.ROOT, "%.4f", s.quotes.get(0).price * .9));
                        phase++; next += 1000;
                    }
                    case 8 -> { screenshot(client, "fx-ifo.png"); click(client, 940, 574); phase++; next += 1000; }
                    case 9 -> { screenshot(client, "fx-confirmation.png"); click(client, 650, 440); phase++; next += 1000; }
                    case 10 -> {
                        if (s.account.orders.size() < 3) return;
                        check(s.account.orders.size() == 3, "IFO created through the actual order form and confirmation dialog");
                        click(client, 195, 467); phase++; next += 1000;
                    }
                    case 11 -> { screenshot(client, "fx-orders.png"); click(client, 969, 34); phase++; next += 1000; }
                    case 12 -> { screenshot(client, "fx-transfer.png"); client.currentScreen.keyPressed(256, 0, 0); phase++; next += 1000; }
                    case 13 -> { click(client, 310, 467); phase++; next += 1000; }
                    case 14 -> { screenshot(client, "fx-history.png"); click(client, 420, 467); phase++; next += 1000; }
                    case 15 -> {
                        screenshot(client, "fx-guide.png");
                        Request r = req("CANCEL_ALL"); FxClient.send(r); phase++;
                    }
                    case 16 -> {
                        if (!s.account.orders.isEmpty()) return;
                        check(s.account.orders.isEmpty(), "Cancel all releases pending orders over network");
                        Request r = req("CLOSE_ALL"); FxClient.send(r); phase++;
                    }
                    case 17 -> {
                        if (!s.account.positions.isEmpty()) return;
                        check(s.account.positions.isEmpty() && s.account.balance > 0, "Manual close settles PnL over network");
                        Request r = req("WITHDRAW"); r.amount = 500; FxClient.send(r); phase++;
                    }
                    case 18 -> {
                        if (s.wallet != 40500) return;
                        check(s.wallet == 40500, "Atomic FX -> wallet withdrawal over network");
                        client.getNetworkHandler().sendChatCommand("save-all flush");
                        Files.writeString(Path.of("smoke-snapshot.json"), FxSettings.JSON.toJson(s));
                        Files.write(Path.of("smoke-results.txt"), checks);
                        var expected = new com.google.gson.JsonObject();
                        expected.addProperty("uuid", client.player.getUuidAsString()); expected.addProperty("wallet", s.wallet);
                        expected.addProperty("balance", s.account.balance);
                        Files.writeString(Path.of("../fx-debug-server/expected-after-restart.json"), expected.toString());
                        phase++; next += 2500;
                    }
                    case 19 -> {
                        // Confirm small-screen layout also uses the same complete terminal.
                        client.getWindow().setWindowedSize(1100, 700); phase++; next += 1500;
                    }
                    case 20 -> {
                        screenshot(client, "fx-compact.png");
                        System.out.println("FX_SMOKE_PASS " + checks.size() + " network assertions; 8 rendered screenshots");
                        finished = true; client.scheduleStop();
                    }
                    default -> { }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                try { Files.writeString(Path.of("smoke-failure.txt"), ex.toString()); Files.write(Path.of("smoke-results.txt"), checks); } catch (Exception ignored) { }
                finished = true; client.scheduleStop();
            }
        });
    }
    private Request req(String op) { Request r = new Request(); r.op = op; r.symbol = "AUR"; return r; }
    private void check(boolean ok, String label) { if (!ok) throw new IllegalStateException(label); checks.add("PASS " + label); }
    private static Object read(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private static void write(Object target, String name, Object value) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
    private static void click(MinecraftClient client, int x, int y) {
        double scale = Math.min(client.getWindow().getScaledWidth() / 1100.0, client.getWindow().getScaledHeight() / 680.0);
        double ox = (client.getWindow().getScaledWidth() - 1100 * scale) / 2;
        double oy = (client.getWindow().getScaledHeight() - 680 * scale) / 2;
        client.currentScreen.mouseClicked(ox + x * scale, oy + y * scale, 0);
    }
    private static void screenshot(MinecraftClient client, String name) throws Exception {
        Files.createDirectories(Path.of("screenshots"));
        try (NativeImage image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) { image.writeTo(Path.of("screenshots", name)); }
    }
}

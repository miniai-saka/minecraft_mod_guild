package dev.guildquests.client;

import dev.guildquests.fx.FxModels.Request;
import dev.guildquests.fx.FxModels.Snapshot;
import dev.guildquests.fx.FxNetwork;
import dev.guildquests.platform.PlatformClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

public final class FxClient {
    private static Snapshot latest;
    private static boolean switching;
    private FxClient() { }
    public static boolean switchingUi() { return switching; }
    public static Snapshot latest() { return latest; }
    public static void init() {
        FxUiPreferences.load();
        if (FxNetwork.client != null) FxNetwork.client.listen(FxClient::accept);
        PlatformClient.onDisconnect(() -> latest = null);
        PlatformClient.registerKeyBinding("key.guildquests.openfx", GLFW.GLFW_KEY_F8, () -> {
            if (!FxUiPreferences.hotkeyEnabled()) return;
            Request r = new Request(); r.op = "OPEN"; send(r);
        });
    }
    public static void accept(Snapshot snapshot) {
        latest = snapshot;
        var client = Minecraft.getInstance();
        if (client.screen instanceof FxUiSettingsScreen) return;
        if (client.screen instanceof FxScreen screen) screen.accept(snapshot);
        else if (snapshot.open) showUi();
    }
    public static void setScreen(Screen screen) {
        switching = true;
        try { Minecraft.getInstance().setScreen(screen); }
        finally { switching = false; }
    }
    public static void showUi() {
        if (latest == null) return;
        setScreen(new FxScreen(latest));
    }
    public static void settings() { setScreen(new FxUiSettingsScreen()); }
    public static void send(Request request) {
        if (Minecraft.getInstance().player != null && FxNetwork.client != null) FxNetwork.client.send(request);
    }
}

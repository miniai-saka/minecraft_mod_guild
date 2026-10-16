package dev.guildquests.debug;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.guildquests.client.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.inventory.ClickType;
import org.lwjgl.glfw.GLFW;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Opt-in real chest/C2S/keyboard test driver. Never packaged in release jars. */
public final class AdminDebugClient implements ClientModInitializer {
    private int phase;
    private long next, started = System.currentTimeMillis();
    private boolean connecting, finished;
    private final List<String> results = new ArrayList<>();
    @Override public void onInitializeClient() {
        try { Files.deleteIfExists(Path.of("admin-failure.txt")); FxUiPreferences.set("SERVER"); FxUiPreferences.setHotkeyEnabled(false); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            if (finished || System.currentTimeMillis() < next) return;
            try {
                if (System.currentTimeMillis() - started > 240000) throw new IllegalStateException("Timed out at " + phase);
                c.getToasts().clear();
                if (c.screen instanceof DisconnectedScreen) { connecting = false; c.setScreen(new TitleScreen()); next = System.currentTimeMillis() + 3000; return; }
                if (c.screen instanceof TitleScreen && !connecting && c.getOverlay() == null) {
                    connecting = true; ConnectScreen.startConnecting(c.screen, c, ServerAddress.parseString("127.0.0.1:25577"), new ServerData("Admin debug", "127.0.0.1:25577", false), false); return;
                }
                if (c.player == null) return;
                next = System.currentTimeMillis() + 800;
                switch (phase) {
                    case 0 -> { c.options.guiScale().set(2); c.resizeDisplay(); command(c,"fxdebug prepareadmin"); }
                    case 1 -> f8();
                    case 2 -> { check(!(c.screen instanceof FxMinecraftScreen) && !FxUiPreferences.hotkeyEnabled(), "F8 does not open FX by default"); command(c,"guild"); }
                    case 3 -> slot(c,3);
                    case 4 -> { check(c.screen.getTitle().getString().equals("ギャンブル"), "Guild chest opens gambling menu"); shot(c,"gambling-fx-entry.png"); slot(c,16); }
                    case 5 -> { check(c.screen instanceof FxMinecraftScreen, "Gambling FX entry works with F8 disabled"); shot(c,"fx-minecraft.png"); button(c,"UI設定"); }
                    case 6 -> { shot(c,"fx-settings.png"); button(c,"F8でFXを開く: 無効（既定）"); }
                    case 7 -> { check(FxUiPreferences.hotkeyEnabled(), "Native settings enable F8"); button(c,"戻る"); c.screen.onClose(); }
                    case 8 -> f8();
                    case 9 -> { check(c.screen instanceof FxMinecraftScreen, "F8 opens FX after enabling"); button(c,"UI設定"); }
                    case 10 -> { button(c,"F8でFXを開く: 有効"); button(c,"戻る"); c.screen.onClose(); command(c,"guildadmin"); }
                    case 11 -> { check(c.screen instanceof AbstractContainerScreen, "Bare guildadmin opens vanilla chest UI"); shot(c,"admin-players.png"); player(c,"FxTester"); }
                    case 12 -> { shot(c,"admin-actions.png"); slot(c,10); }
                    case 13 -> { shot(c,"admin-amount.png"); digits(c,"1234"); }
                    case 14 -> { check(title(c,13).contains("1234"), "Chest keypad accepts exact integer amount"); slot(c,50); }
                    case 15 -> { shot(c,"admin-confirm.png"); slot(c,33); }
                    case 16 -> { check(lore(c,22).contains("借入: 1234 GC"), "Chest loan add applies on server"); slot(c,12); }
                    case 17 -> digits(c,"9876");
                    case 18 -> slot(c,50);
                    case 19 -> slot(c,33);
                    case 20 -> { check(lore(c,22).contains("借入: 9876 GC"), "Chest loan set replaces amount"); slot(c,14); }
                    case 21 -> slot(c,29);
                    case 22 -> { check(lore(c,22).contains("借入: 9876 GC"), "Cancelling write-off leaves loan unchanged"); slot(c,14); }
                    case 23 -> slot(c,33);
                    case 24 -> { check(lore(c,22).contains("借入: 0 GC"), "Chest loan clear writes off consumer loan"); slot(c,29); }
                    case 25 -> slot(c,33);
                    case 26 -> { next += 6000; }
                    case 27 -> { check(lore(c,22).contains("固定ロック: あり"), "Manual delinquency remains after periodic cycle at zero debt"); slot(c,33); }
                    case 28 -> slot(c,33);
                    case 29 -> { check(lore(c,22).contains("滞納: なし"), "Chest delinquency off releases lock"); command(c,"guildadmin loan set FxTester 2500"); }
                    case 30 -> command(c,"guildadmin loan add FxTester 500");
                    case 31 -> command(c,"guildadmin");
                    case 32 -> player(c,"FxTester");
                    case 33 -> { check(lore(c,22).contains("借入: 3000 GC"), "Original loan commands share chest mutation semantics"); command(c,"guildadmin delinquent FxTester on"); }
                    case 34 -> command(c,"guildadmin");
                    case 35 -> player(c,"FxTester");
                    case 36 -> { check(lore(c,22).contains("固定ロック: あり"), "Original delinquent command persists manual lock"); command(c,"guildadmin delinquent FxTester off"); }
                    case 37 -> command(c,"guildadmin loan clear FxTester");
                    case 38 -> command(c,"guildadmin");
                    case 39 -> player(c,"FxTester");
                    case 40 -> { check(lore(c,22).contains("借入: 0 GC") && lore(c,22).contains("滞納: なし"), "Command clear and unlock are reflected in chest"); slot(c,49); }
                    case 41 -> player(c,"OfflineBorrower");
                    case 42 -> { check(lore(c,22).contains("借入: 2000 GC"), "Saved offline player can be selected"); slot(c,12); }
                    case 43 -> digits(c,"42");
                    case 44 -> slot(c,50);
                    case 45 -> slot(c,33);
                    case 46 -> { check(lore(c,22).contains("借入: 42 GC"), "Offline loan setting is persisted server-side"); slot(c,49); }
                    case 47 -> player(c,"FxTester");
                    case 48 -> slot(c,10);
                    case 49 -> digits(c,"9");
                    case 50 -> slot(c,50);
                    case 51 -> command(c,"fxdebug adminsecurity");
                    case 52 -> { check(!(c.screen instanceof AbstractContainerScreen), "Revoked permission closes active admin menu"); command(c,"guildadmin loan set FxTester 3210"); }
                    case 53 -> command(c,"guildadmin delinquent FxTester on");
                    case 54 -> {
                        check(!FxUiPreferences.hotkeyEnabled() && Files.readString(Path.of("config/guildquests/fx-client.json")).contains("\"hotkeyEnabled\":false"), "Disabled hotkey persisted without losing UI style");
                        Files.write(Path.of("admin-results.txt"), results);
                        Files.writeString(Path.of("../fx-debug-server/expected-admin-restart.json"), "{\"uuid\":\"" + c.player.getStringUUID() + "\"}");
                        command(c,"save-all flush"); next += 2000;
                    }
                    case 55 -> { finished = true; System.out.println("ADMIN_SMOKE_PASS " + results.size()); c.stop(); }
                    default -> { }
                }
                phase++;
            } catch (Exception ex) {
                ex.printStackTrace();
                try { Files.writeString(Path.of("admin-failure.txt"), "Phase " + phase + ": " + ex); Files.write(Path.of("admin-results.txt"), results); } catch (Exception ignored) { }
                finished = true; c.stop();
            }
        });
    }
    private void check(boolean value,String label) { if (!value) throw new IllegalStateException(label); results.add("PASS " + label); }
    private static void command(Minecraft c,String text) { c.player.connection.sendCommand(text); }
    private static void f8() { KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F8)); }
    private static void slot(Minecraft c,int slot) { c.gameMode.handleInventoryMouseClick(c.player.containerMenu.containerId,slot,0,ClickType.PICKUP,c.player); }
    private static String title(Minecraft c,int slot) { return c.player.containerMenu.getSlot(slot).getItem().getHoverName().getString(); }
    private static String lore(Minecraft c,int slot) { return String.valueOf(c.player.containerMenu.getSlot(slot).getItem().getTag()); }
    private static void player(Minecraft c,String name) { for(int i=9;i<36;i++) if(title(c,i).equals(name)) {slot(c,i);return;} throw new IllegalStateException("Missing player " + name); }
    private static void digits(Minecraft c,String value) { for(char ch:value.toCharArray()) {int digit=ch-'0';slot(c,digit==0?48:20+(digit-1)/3*9+(digit-1)%3);} }
    private static void button(Minecraft c,String name) { for(var child:c.screen.children()) if(child instanceof Button b && b.getMessage().getString().equals(name)) {c.screen.mouseClicked(b.getX()+b.getWidth()/2.0,b.getY()+10,0);return;} throw new IllegalStateException("Missing button " + name); }
    private static void shot(Minecraft c,String name) throws Exception { Files.createDirectories(Path.of("screenshots"));try(NativeImage image=Screenshot.takeScreenshot(c.getMainRenderTarget())) {image.writeToFile(Path.of("screenshots",name));} }
}

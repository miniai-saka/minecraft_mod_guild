package dev.guildquests.guidebug;

import dev.guildquests.client.gui.GuildMenuScreen;
import dev.guildquests.gui.GuildClientMenu;
import dev.guildquests.gui.GuildMenuKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Focused regression scenarios, enabled only in an isolated gui-debug runtime. */
public final class AmountInputDebugClient {
    private record Step(String label, Runnable action) { }
    private final List<Step> steps = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private Minecraft client;
    private int phase;
    private long next;
    private boolean prepared, finished;

    public void tick(Minecraft client) {
        if (!Boolean.getBoolean("guildquests.amountQa") || client.player == null || finished) return;
        this.client = client;
        if (!prepared) { prepare(); prepared = true; next = System.currentTimeMillis() + 3000; }
        if (System.currentTimeMillis() < next) return;
        try {
            if (phase == steps.size()) {
                Files.writeString(Path.of("amount-status.txt"), "PASS " + phase + "/" + steps.size());
                finished = true;
                return;
            }
            Step step = steps.get(phase);
            step.action.run();
            results.add("PASS " + phase + " " + step.label);
            Files.write(Path.of("amount-results.txt"), results);
            System.out.println(results.get(results.size() - 1));
            phase++;
            next = System.currentTimeMillis() + 1000;
        } catch (Exception ex) {
            finished = true;
            ex.printStackTrace();
            try {
                results.add("FAIL " + phase + " " + steps.get(phase).label + ": " + ex);
                Files.write(Path.of("amount-results.txt"), results);
                Files.writeString(Path.of("amount-status.txt"), "FAIL " + phase);
                shot("amount-failure");
            } catch (Exception ignored) { }
        }
    }

    private void step(String label, Runnable action) { steps.add(new Step(label, action)); }
    private void prepare() {
        step("seed disposable world", () -> command("guiqa setup"));
        step("open bank", () -> command("guild bank"));
        step("bank has two tabs and services in deposit/withdrawal", () -> {
            check(menu().kind == GuildMenuKind.BANK, "bank missing");
            check(menu().kind.sections.equals(List.of("入出金", "支払い方法")), "bank tabs");
            shot("bank-services-merged");
            clickSlot(3);
        });
        step("repayment service opens loan", () -> { check(menu().kind == GuildMenuKind.LOAN, "loan missing"); button("戻る"); });
        step("transfer service opens from bank", () -> { check(menu().kind == GuildMenuKind.BANK, "bank missing"); clickSlot(4); });
        step("transfer waits for recipient", () -> {
            check(menu().kind == GuildMenuKind.TRANSFER, "transfer missing");
            check(client.screen.children().stream().noneMatch(child -> child instanceof Button b && b.getMessage().getString().equals("入力")), "input shown without recipient");
            command("guild bank");
        });
        step("open keypad", () -> button("入力"));
        step("character input handles one digit per event", () -> { type("123"); check(input().getValue().equals("123"), "typed digits"); });
        step("cursor insertion Backspace Delete Home End", () -> {
            key(GLFW.GLFW_KEY_LEFT); key(GLFW.GLFW_KEY_BACKSPACE); type("9"); key(GLFW.GLFW_KEY_DELETE);
            key(GLFW.GLFW_KEY_HOME); type("4"); key(GLFW.GLFW_KEY_END); type("2");
            check(input().getValue().equals("4192"), "cursor editing");
        });
        step("reject nonnumeric and overflowing input", () -> {
            type("a-1.あ"); check(input().getValue().equals("41921"), "invalid character accepted");
            input().setValue("2147483647"); type("8"); check(input().getValue().equals("2147483647"), "bank max exceeded");
            input().setValue("9223372036854775808"); check(input().getValue().equals("2147483647"), "overflow accepted");
        });
        step("empty input cannot confirm", () -> { input().setValue(""); key(GLFW.GLFW_KEY_ENTER); check(isOpen(), "empty input applied"); });
        step("Enter applies text amount", () -> { type("42"); shot("amount-text-input"); key(GLFW.GLFW_KEY_ENTER); });
        step("amount synchronized before deposit", () -> { check(amount() == 42, "amount not synced"); clickSlot(25); });
        step("deposit uses edited amount", () -> { check(title(1).equals("所持コイン: 58"), "deposit not applied"); clickSlot(26); });
        step("withdrawal preserves fee", () -> { check(title(1).equals("所持コイン: 98"), "withdrawal fee"); button("入力"); });
        step("on-screen keypad remains usable", () -> {
            keypadButton("clearRect", -1); keypadButton("keyRect", 0); keypadButton("keyRect", 1);
            keypadButton("keyRect", 9); keypadButton("keyRect", 11);
            check(input().getValue().equals("120"), "keypad digits or 00/backspace");
            keypadButton("confirmRect", -1);
        });
        step("keypad amount synchronized", () -> { check(amount() == 120, "keypad amount not synced"); button("入力"); });
        step("Esc cancels edit", () -> { type("99"); key(GLFW.GLFW_KEY_ESCAPE); check(!isOpen(), "keypad remains open"); check(amount() == 120, "cancel applied"); button("入力"); });
        step("resize preserves draft and cursor", () -> { type("314"); key(GLFW.GLFW_KEY_LEFT); resize(854, 480, 4); });
        step("compact input editing survives resize", () -> { check(input().getValue().equals("314"), "draft lost"); type("2"); check(input().getValue().equals("3124"), "cursor lost"); shot("amount-compact"); key(GLFW.GLFW_KEY_KP_ENTER); });
        step("compact amount synchronized", () -> { check(amount() == 3124, "compact amount not synced"); resize(1280, 800, 2); command("guild auction"); });
        step("auction bid text input", () -> { check(menu().kind == GuildMenuKind.AUCTION_BROWSE, "auction missing"); button("入力"); type("25"); key(GLFW.GLFW_KEY_ENTER); });
        step("auction amount synchronized", () -> { check(amount() == 25, "auction amount"); button("出品・受取・案内"); clickSlot(1); });
        step("listing price input", () -> { check(menu().kind == GuildMenuKind.AUCTION_SELL, "sell missing"); button("入力"); type("150"); key(GLFW.GLFW_KEY_ENTER); });
        step("listing amount synchronized", () -> { check(amount() == 150, "listing amount"); command("guildadmin"); });
        step("select admin player", () -> { check(menu().kind == GuildMenuKind.ADMIN, "admin missing"); clickSlot(9); });
        step("open admin coin amount", () -> clickSlot(16));
        step("admin amount input", () -> { button("入力"); type("1234"); key(GLFW.GLFW_KEY_ENTER); });
        step("admin amount synchronized", () -> { check(amount() == 1234, "admin amount"); command("guild bank"); });
        step("leave bank ready for native keyboard checks", () -> { button("入力"); shot("amount-ready-for-keyboard"); });
    }

    private GuildClientMenu menu() { return ((GuildMenuScreen) client.screen).getMenu(); }
    private String title(int slot) { return menu().getSlot(slot).getItem().getHoverName().getString(); }
    private void command(String command) { client.player.connection.sendCommand(command); }
    private void type(String text) { for (char c : text.toCharArray()) client.screen.charTyped(c, 0); }
    private void key(int key) { client.screen.keyPressed(key, 0, 0); }
    private Object keypad() { return field(client.screen, "keypad"); }
    private EditBox input() { return (EditBox) field(keypad(), "input"); }
    private boolean isOpen() { return (boolean) call(keypad(), "isOpen"); }
    private long amount() {
        for (int i = 0; i < menu().kind.rows * 9; i++) {
            var tag = menu().getSlot(i).getItem().getTag();
            if (tag != null && tag.contains("GuildGuiAmount")) return tag.getCompound("GuildGuiAmount").getLong("value");
        }
        throw new IllegalStateException("no synchronized amount");
    }
    private void button(String label) {
        for (var child : client.screen.children()) if (child instanceof Button b && b.active && b.getMessage().getString().equals(label)) {
            client.screen.mouseClicked(b.getX() + b.getWidth() / 2.0, b.getY() + 10, 0);
            return;
        }
        throw new IllegalStateException("missing button " + label);
    }
    private void clickSlot(int slot) {
        for (int page = 0; page < 60; page++) {
            for (Object row : (List<?>) field(client.screen, "rows")) if ((int) call(row, "slot") == slot) {
                client.screen.mouseClicked((int) call(row, "x") + 30, (int) call(row, "y") + 8, 0);
                return;
            }
            key(GLFW.GLFW_KEY_PAGE_DOWN);
        }
        throw new IllegalStateException("slot not visible " + slot);
    }
    private void keypadButton(String rectMethod, int index) {
        var screen = client.screen;
        Object bounds = call(screen, "keypadBounds");
        Object metrics = invoke(keypad(), "metrics", new Class<?>[]{bounds.getClass(), int.class, int.class}, bounds, screen.width, screen.height);
        Object rect = index < 0 ? invoke(keypad(), rectMethod, new Class<?>[]{metrics.getClass()}, metrics)
                : invoke(keypad(), rectMethod, new Class<?>[]{metrics.getClass(), int.class}, metrics, index);
        screen.mouseClicked((int) call(rect, "x") + (int) call(rect, "w") / 2.0,
                (int) call(rect, "y") + (int) call(rect, "h") / 2.0, 0);
    }
    private static Object field(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
    }
    private static Object call(Object target, String name) { return invoke(target, name, new Class<?>[0]); }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) {
        try { var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, arguments); }
        catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
    }
    private void resize(int width, int height, int scale) {
        client.options.guiScale().set(scale);
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), width, height);
        client.resizeDisplay();
    }
    private void shot(String name) {
        try {
            Files.createDirectories(Path.of("screenshots"));
            try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("screenshots", name + ".png")); }
        } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    private void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}

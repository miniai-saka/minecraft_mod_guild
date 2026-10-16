package dev.guildquests.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class FxUiSettingsScreen extends Screen {
    private String error = "";
    public FxUiSettingsScreen() { super(Component.literal("FXのUI設定")); }
    @Override protected void init() {
        int x = width / 2 - 140, y = height / 2 - 45;
        String[] values = {"SERVER", "MINECRAFT", "WHITE"};
        String[] labels = {"サーバー設定に従う（既定）", "Minecraft風・簡易取引", "白背景・高機能ターミナル"};
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            addRenderableWidget(Button.builder(Component.literal((FxUiPreferences.style().equals(value) ? "✓ " : "") + labels[i]), button -> {
                try { FxUiPreferences.set(value); FxClient.showUi(); }
                catch (java.io.IOException ex) { error = "設定を保存できませんでした。"; }
            }).bounds(x, y + i * 26, 280, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("戻る"), button -> onClose()).bounds(x + 70, y + 88, 140, 20).build());
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, height / 2 - 75, 0xFFFFFF);
        super.render(g, mx, my, delta);
        if (!error.isEmpty()) g.drawCenteredString(font, error, width / 2, height / 2 + 80, 0xFF7777);
    }
    @Override public void onClose() { FxClient.showUi(); }
    @Override public boolean isPauseScreen() { return false; }
}

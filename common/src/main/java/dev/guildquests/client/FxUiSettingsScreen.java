package dev.guildquests.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class FxUiSettingsScreen extends Screen {
    private String error = "";
    public FxUiSettingsScreen() { super(Component.literal("FXの設定")); }
    @Override protected void init() {
        clearWidgets();
        int x = width / 2 - 140, y = height / 2 - 20;
        addRenderableWidget(Button.builder(Component.literal("F8でFXを開く: " + (FxUiPreferences.hotkeyEnabled() ? "有効" : "無効（既定）")), button -> {
            try { FxUiPreferences.setHotkeyEnabled(!FxUiPreferences.hotkeyEnabled()); init(); }
            catch (java.io.IOException ex) { error = "設定を保存できませんでした。"; }
        }).bounds(x, y, 280, 20).build());
        addRenderableWidget(Button.builder(Component.literal("戻る"), button -> onClose()).bounds(x + 70, y + 34, 140, 20).build());
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, height / 2 - 60, 0xFFFFFF);
        super.render(g, mx, my, delta);
        if (!error.isEmpty()) g.drawCenteredString(font, error, width / 2, height / 2 + 40, 0xFF7777);
    }
    @Override public void onClose() { FxClient.showUi(); }
    @Override public boolean isPauseScreen() { return false; }
}

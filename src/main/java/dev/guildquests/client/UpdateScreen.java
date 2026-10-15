package dev.guildquests.client;

import dev.guildquests.update.UpdateChecker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Full-screen popup shown on the title screen when a mod update is available. */
public class UpdateScreen extends Screen {
    private final Screen parent;
    private final String currentVersion;
    private final UpdateChecker.UpdateInfo info;

    private enum State { READY, DOWNLOADING, DONE, FAILED }

    private volatile State state = State.READY;

    public UpdateScreen(Screen parent, String currentVersion, UpdateChecker.UpdateInfo info) {
        super(Text.literal("GuildQuests Update"));
        this.parent = parent;
        this.currentVersion = currentVersion;
        this.info = info;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        ButtonWidget downloadBtn = ButtonWidget.builder(Text.literal("ダウンロード"), b -> startDownload())
                .dimensions(cx - 100, cy + 30, 98, 20).build();
        this.addDrawableChild(downloadBtn);

        ButtonWidget closeBtn = ButtonWidget.builder(Text.literal("閉じる"), b -> {
            if (this.client != null) {
                this.client.setScreen(parent);
            }
        }).dimensions(cx + 2, cy + 30, 98, 20).build();
        this.addDrawableChild(closeBtn);
    }

    private void startDownload() {
        if (state != State.READY) return;
        state = State.DOWNLOADING;
        new Thread(() -> {
            boolean ok = UpdateChecker.download(info,
                    dev.guildquests.platform.Platform.getModsDir(),
                    dev.guildquests.platform.Platform.getLoaderName());
            state = ok ? State.DONE : State.FAILED;
        }, "GuildQuests-UpdateDownload").start();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        int cx = this.width / 2;
        int cy = this.height / 2;

        context.drawCenteredTextWithShadow(this.textRenderer, "=== GuildQuests アップデート ===", cx, cy - 60, 0xFFD700);
        context.drawCenteredTextWithShadow(this.textRenderer, "現在のバージョン: v" + currentVersion, cx, cy - 40, 0xAAAAAA);
        context.drawCenteredTextWithShadow(this.textRenderer, "最新バージョン: v" + info.latestVersion(), cx, cy - 25, 0x55FF55);

        switch (state) {
            case READY -> context.drawCenteredTextWithShadow(this.textRenderer, "ダウンロードして再起動すると適用されます", cx, cy + 5, 0xFFFFFF);
            case DOWNLOADING -> context.drawCenteredTextWithShadow(this.textRenderer, "ダウンロード中...", cx, cy + 5, 0xFFFF55);
            case DONE -> context.drawCenteredTextWithShadow(this.textRenderer, "完了！Minecraftを再起動してください", cx, cy + 5, 0x55FF55);
            case FAILED -> context.drawCenteredTextWithShadow(this.textRenderer, "ダウンロードに失敗しました", cx, cy + 5, 0xFF5555);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}

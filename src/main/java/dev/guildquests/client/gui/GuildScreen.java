package dev.guildquests.client.gui;

import dev.guildquests.net.GuiState;
import dev.guildquests.net.GuiState.QuestRow;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Button-based main guild quest screen driven by a {@link GuiState} pushed from the server. */
public final class GuildScreen extends Screen {
    private static final int ROW_TOP = 58;
    private static final int STRIDE = 22;
    private static final int LABEL_ROW_Y = 36;

    private GuiState state;
    private int dailyPage;
    private int activePage;

    public GuildScreen(GuiState state) {
        super(Text.literal("冒険者ギルド - クエスト"));
        this.state = state;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Applies a refreshed state without closing the screen. */
    public void applyState(GuiState state) {
        this.state = state;
        clampPages();
        clearAndInit();
    }

    private int leftWidth() {
        return (width - 16 - 6) / 2;
    }

    private int rightX() {
        return 8 + leftWidth() + 6;
    }

    private int rowsPerPage() {
        return Math.max(1, (height - 26 - ROW_TOP) / STRIDE);
    }

    private int pageCount(int size, int rows) {
        return Math.max(1, (size + rows - 1) / rows);
    }

    private void clampPages() {
        int rows = rowsPerPage();
        dailyPage = Math.max(0, Math.min(dailyPage, pageCount(state.daily.size(), rows) - 1));
        activePage = Math.max(0, Math.min(activePage, pageCount(state.active.size(), rows) - 1));
    }

    @Override
    protected void init() {
        clampPages();
        int rows = rowsPerPage();
        int leftX = 8;
        int leftW = leftWidth();
        int rightX = rightX();
        int rightW = width - 8 - rightX;

        int dailyPages = pageCount(state.daily.size(), rows);
        if (dailyPages > 1) {
            addDrawableChild(pageButton(leftX + leftW - 44, LABEL_ROW_Y, -1, true));
            addDrawableChild(pageButton(leftX + leftW - 22, LABEL_ROW_Y, 1, true));
        }
        int activePages = pageCount(state.active.size(), rows);
        if (activePages > 1) {
            addDrawableChild(pageButton(rightX + rightW - 44, LABEL_ROW_Y, -1, false));
            addDrawableChild(pageButton(rightX + rightW - 22, LABEL_ROW_Y, 1, false));
        }

        int start = dailyPage * rows;
        for (int i = 0; i < rows && start + i < state.daily.size(); i++) {
            QuestRow row = state.daily.get(start + i);
            initDailyRow(row, leftX, leftW, ROW_TOP + i * STRIDE);
        }

        start = activePage * rows;
        for (int i = 0; i < rows && start + i < state.active.size(); i++) {
            QuestRow row = state.active.get(start + i);
            initActiveRow(row, rightX, rightW, ROW_TOP + i * STRIDE);
        }

        initNavBar();
    }

    private void initDailyRow(QuestRow row, int x, int w, int y) {
        String label = switch (row.status) {
            case "ACTIVE" -> "[受注中] " + row.title;
            case "LOCKED" -> "[LOCK] " + row.title;
            case "DONE_ONCE" -> "[達成済] " + row.title;
            default -> row.title;
        };
        ButtonWidget button = ButtonWidget.builder(fit(label, w),
                        btn -> ClientGui.send("guild|accept|" + row.questId))
                .dimensions(x, y, w, 20)
                .tooltip(Tooltip.of(Text.literal(dailyTooltip(row))))
                .build();
        button.active = row.status.equals("AVAILABLE");
        addDrawableChild(button);
    }

    private void initActiveRow(QuestRow row, int x, int w, int y) {
        boolean broken = row.status.equals("BROKEN");
        String label = broken ? row.title : (row.ready ? "[達成可能] " : "[進行中] ") + row.title;
        int mainW = w - 44;
        ButtonWidget main = ButtonWidget.builder(fit(label, mainW),
                        btn -> ClientGui.send("guild|submit|" + row.questId))
                .dimensions(x, y, mainW, 20)
                .tooltip(Tooltip.of(Text.literal(activeTooltip(row))))
                .build();
        main.active = !broken;
        addDrawableChild(main);

        ButtonWidget cancel = ButtonWidget.builder(Text.literal("破棄"),
                        btn -> ClientGui.send("guild|cancel|" + row.questId))
                .dimensions(x + w - 40, y, 40, 20)
                .tooltip(Tooltip.of(Text.literal("クリックでこのクエストを破棄します")))
                .build();
        addDrawableChild(cancel);
    }

    private void initNavBar() {
        String[] labels = {"銀行", "送金", "更新", "閉じる"};
        String[] actions = {"bank|open", "transfer|open", "guild|open", null};
        int navY = height - 24;
        int buttonW = Math.min(66, (width - 16 - 3 * 4) / labels.length);
        for (int i = 0; i < labels.length; i++) {
            String action = actions[i];
            ButtonWidget button = ButtonWidget.builder(Text.literal(labels[i]), btn -> {
                        if (action == null) {
                            close();
                        } else {
                            ClientGui.send(action);
                        }
                    })
                    .dimensions(8 + i * (buttonW + 4), navY, buttonW, 20)
                    .build();
            addDrawableChild(button);
        }
    }

    private ButtonWidget pageButton(int x, int y, int delta, boolean daily) {
        return ButtonWidget.builder(Text.literal(delta < 0 ? "◀" : "▶"), btn -> {
                    if (daily) {
                        dailyPage += delta;
                    } else {
                        activePage += delta;
                    }
                    clampPages();
                    clearAndInit();
                })
                .dimensions(x, y, 20, 20)
                .tooltip(Tooltip.of(Text.literal(daily ? "本日のクエスト一覧をスクロール" : "受注中一覧をスクロール")))
                .build();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        drawHeader(context);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawHeader(DrawContext context) {
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 6, 0xFFFFFF);
        String line1 = "コイン: " + state.balance
                + " ｜ ランク: " + state.rank
                + " ｜ 累計達成: " + state.completedTotal + " 件";
        String line2 = "同時受注: " + state.active.size() + "/" + state.maxActive
                + " ｜ 次の抽選まで: " + state.nextDraw;
        context.drawTextWithShadow(textRenderer, line1, 8, 18, 0xFFD200);
        context.drawTextWithShadow(textRenderer, line2, 8, 30, 0xFFD200);

        int rows = rowsPerPage();
        context.drawTextWithShadow(textRenderer, dailyLabel(rows), 8, LABEL_ROW_Y + 6, 0xAAAAAA);
        context.drawTextWithShadow(textRenderer, activeLabel(rows), rightX(), LABEL_ROW_Y + 6, 0xAAAAAA);

        if (state.daily.isEmpty()) {
            context.drawTextWithShadow(textRenderer, "本日のクエストはまだありません",
                    8, ROW_TOP + 6, 0x808080);
        }
        if (state.active.isEmpty()) {
            context.drawTextWithShadow(textRenderer, "受注中のクエストはありません",
                    rightX(), ROW_TOP + 6, 0x808080);
        }
    }

    private String dailyLabel(int rows) {
        String head = "本日のクエスト（" + state.daily.size() + " 問）";
        if (state.daily.size() <= rows) return head;
        int start = dailyPage * rows;
        int end = Math.min(state.daily.size(), start + rows);
        return head + " " + (start + 1) + "-" + end;
    }

    private String activeLabel(int rows) {
        String head = "受注中（" + state.active.size() + "/" + state.maxActive + "）";
        if (state.active.size() <= rows) return head;
        int start = activePage * rows;
        int end = Math.min(state.active.size(), start + rows);
        return head + " " + (start + 1) + "-" + end;
    }

    private String dailyTooltip(QuestRow row) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, row.objective);
        if (row.status.equals("LOCKED")) addIfPresent(parts, row.lockReason);
        if (row.status.equals("ACTIVE")) {
            parts.add("進捗: " + row.progress + "/" + row.amount + (row.ready ? "（達成可能）" : ""));
        }
        addIfPresent(parts, row.description);
        parts.add("報酬: " + row.reward + " コイン");
        String hint = switch (row.status) {
            case "AVAILABLE" -> "クリックで受注";
            case "LOCKED" -> "まだ解放されていません";
            case "DONE_ONCE" -> "このクエストは再受注できません";
            case "ACTIVE" -> "既に受注中です（右側のリストに表示）";
            default -> "";
        };
        addIfPresent(parts, hint);
        return String.join(" ／ ", parts);
    }

    private String activeTooltip(QuestRow row) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, row.objective);
        if (!row.status.equals("BROKEN")) {
            parts.add("進捗: " + row.progress + "/" + row.amount);
        }
        parts.add("報酬: " + row.reward + " コイン");
        if (row.status.equals("BROKEN")) {
            parts.add("設定から削除されたクエストです（破棄できます）");
        } else if (row.canSubmit) {
            parts.add("クリックで所持アイテムを納品");
        } else if (row.ready) {
            parts.add("クリックで報酬を受け取る");
        } else {
            parts.add("対象を達成すると進捗が増わります");
        }
        parts.add("「破棄」で受注を解除します");
        return String.join(" ／ ", parts);
    }

    private void addIfPresent(List<String> parts, String text) {
        if (text != null && !text.isBlank()) parts.add(text);
    }

    /** Truncates a button label so it never overflows the widget. */
    private Text fit(String label, int maxWidth) {
        if (textRenderer.getWidth(label) <= maxWidth - 8) return Text.literal(label);
        String cut = label;
        while (!cut.isEmpty() && textRenderer.getWidth(cut + "…") > maxWidth - 8) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return Text.literal(cut + "…");
    }
}

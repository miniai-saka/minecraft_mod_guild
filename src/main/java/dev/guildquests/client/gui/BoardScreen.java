package dev.guildquests.client.gui;

import dev.guildquests.net.GuiState;
import dev.guildquests.net.GuiState.BoardRow;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Button-based bounty board for player-published quests (accept / cancel / abandon). */
public final class BoardScreen extends Screen {
    private static final int ROW_TOP = 58;
    private static final int STRIDE = 22;
    private static final int LABEL_ROW_Y = 36;

    private GuiState state;
    private int page;

    public BoardScreen(GuiState state) {
        super(Text.literal("依頼クエスト掲示板"));
        this.state = state;
    }

    public void applyState(GuiState state) {
        this.state = state;
        clampPage();
        clearAndInit();
    }

    private int rowsPerPage() {
        return Math.max(1, (height - 26 - ROW_TOP) / STRIDE);
    }

    private int pageCount(int size, int rows) {
        return Math.max(1, (size + rows - 1) / rows);
    }

    private void clampPage() {
        page = Math.max(0, Math.min(page, pageCount(state.board.size(), rowsPerPage()) - 1));
    }

    @Override
    protected void init() {
        clampPage();
        int rows = rowsPerPage();
        int x = 8;
        int w = width - 16;

        if (pageCount(state.board.size(), rows) > 1) {
            addDrawableChild(pageButton(width - 8 - 44, LABEL_ROW_Y, -1));
            addDrawableChild(pageButton(width - 8 - 22, LABEL_ROW_Y, 1));
        }

        int start = page * rows;
        for (int i = 0; i < rows && start + i < state.board.size(); i++) {
            initRow(state.board.get(start + i), x, w, ROW_TOP + i * STRIDE);
        }

        String[] labels = {"戻る", "公開", "更新", "閉じる"};
        String[] actions = {"guild|open", "publish|open", "board|open", null};
        int navY = height - 24;
        int buttonW = Math.min(66, (w - 3 * 4) / labels.length);
        for (int i = 0; i < labels.length; i++) {
            String action = actions[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(labels[i]), btn -> {
                        if (action == null) {
                            close();
                        } else {
                            ClientGui.send(action);
                        }
                    })
                    .dimensions(8 + i * (buttonW + 4), navY, buttonW, 20)
                    .build());
        }
    }

    private void initRow(BoardRow row, int x, int w, int y) {
        boolean hasAction = row.canCancel || row.canAbandon;
        int mainW = hasAction ? w - 44 : w;
        String prefix = switch (row.relation) {
            case "own" -> "[自分の依頼] ";
            case "mine" -> "[自分の受注] ";
            case "taken" -> "[受注中] ";
            default -> "[募集中] ";
        };
        ButtonWidget main = ButtonWidget.builder(fit(prefix + row.title, mainW),
                        btn -> ClientGui.send("board|accept|" + row.boardId))
                .dimensions(x, y, mainW, 20)
                .tooltip(Tooltip.of(Text.literal(tooltip(row))))
                .build();
        main.active = row.canAccept;
        addDrawableChild(main);

        if (row.canCancel) {
            addRowButton(x + w - 40, y, "取消", row,
                    "クリックで依頼を取り消し、報酬 " + row.reward + " コインを返金します");
        } else if (row.canAbandon) {
            addRowButton(x + w - 40, y, "破棄", row,
                    "クリックで受注を解除して掲示板に戻ります");
        }
    }

    private void addRowButton(int x, int y, String label, BoardRow row, String tooltip) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label),
                        btn -> ClientGui.send("board|cancel|" + row.boardId))
                .dimensions(x, y, 40, 20)
                .tooltip(Tooltip.of(Text.literal(tooltip)))
                .build());
    }

    private ButtonWidget pageButton(int x, int y, int delta) {
        return ButtonWidget.builder(Text.literal(delta < 0 ? "◀" : "▶"), btn -> {
                    page += delta;
                    clampPage();
                    clearAndInit();
                })
                .dimensions(x, y, 20, 20)
                .tooltip(Tooltip.of(Text.literal("依頼一覧をスクロール")))
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
        String line1 = "所持コイン: " + state.balance
                + " ｜ 公開中の依頼: " + state.boardTotal + " 件";
        if (state.boardTotal > state.board.size()) {
            line1 += "（" + state.board.size() + " 件まで表示）";
        }
        context.drawTextWithShadow(textRenderer, line1, 8, 18, 0xFFD200);

        int rows = rowsPerPage();
        String label = "依頼一覧";
        if (state.board.size() > rows) {
            int start = page * rows;
            int end = Math.min(state.board.size(), start + rows);
            label += "（" + (start + 1) + "-" + end + " / " + state.board.size() + "）";
        } else {
            label += "（" + state.board.size() + " 件）";
        }
        context.drawTextWithShadow(textRenderer, label, 8, LABEL_ROW_Y + 6, 0xAAAAAA);

        if (state.board.isEmpty()) {
            context.drawTextWithShadow(textRenderer, "公開中の依頼はありません",
                    8, ROW_TOP + 6, 0x808080);
        }
    }

    private String tooltip(BoardRow row) {
        List<String> parts = new ArrayList<>();
        if (!row.objective.isBlank()) parts.add(row.objective);
        parts.add("依頼者: " + row.publisherName);
        parts.add("報酬: " + row.reward + " コイン");
        if (row.taken && !row.takerName.isBlank()) parts.add("受注者: " + row.takerName);
        parts.add("公開日: " + row.createdAtDay + " 日目");
        String hint;
        if (row.canAccept) {
            hint = "クリックで受注";
        } else if (row.relation.equals("own") && row.canCancel) {
            hint = "「取消」で取り消し（報酬を返金）";
        } else if (row.relation.equals("mine")) {
            hint = "「破棄」で受注解除";
        } else if (row.taken) {
            hint = "他のプレイヤーが受注中です";
        } else {
            hint = "";
        }
        if (!hint.isBlank()) parts.add(hint);
        return String.join(" ／ ", parts);
    }

    private Text fit(String label, int maxWidth) {
        if (textRenderer.getWidth(label) <= maxWidth - 8) return Text.literal(label);
        String cut = label;
        while (!cut.isEmpty() && textRenderer.getWidth(cut + "…") > maxWidth - 8) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return Text.literal(cut + "…");
    }
}

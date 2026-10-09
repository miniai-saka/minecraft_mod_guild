package dev.guildquests.client.gui;

import dev.guildquests.net.GuiState;
import dev.guildquests.net.GuiState.Candidate;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Button-based player-to-player transfer screen (fee-free, from the bank balance). */
public final class TransferScreen extends Screen {
    private static final int PICKER_Y = 40;
    private static final int GRID_Y = 66;
    private static final int GRID_STRIDE = 22;
    private static final int AMOUNTS_Y = 140;

    private GuiState state;
    /** Selected recipient uuid; null = none. */
    private String selectedUuid;

    public TransferScreen(GuiState state) {
        super(Text.literal("ギルド送金"));
        this.state = state;
    }

    public void applyState(GuiState state) {
        this.state = state;
        if (selectedUuid != null && find(selectedUuid) == null) selectedUuid = null;
        clearAndInit();
    }

    private Candidate find(String uuid) {
        for (Candidate candidate : state.candidates) {
            if (candidate.uuid.equals(uuid)) return candidate;
        }
        return null;
    }

    private Candidate selected() {
        return selectedUuid == null ? null : find(selectedUuid);
    }

    private void select(String uuid) {
        selectedUuid = uuid;
        clearAndInit();
    }

    @Override
    protected void init() {
        int available = state.candidates.size();
        addDrawableChild(ButtonWidget.builder(
                        Text.literal("送金先: " + (selected() == null ? "未選択" : selected().name)
                                + "（オンライン " + available + " 人・クリックで切替）"),
                        btn -> {
                            if (state.candidates.isEmpty()) return;
                            int index = -1;
                            for (int i = 0; i < state.candidates.size(); i++) {
                                if (state.candidates.get(i).uuid.equals(selectedUuid)) {
                                    index = i;
                                    break;
                                }
                            }
                            select(state.candidates.get((index + 1) % state.candidates.size()).uuid);
                        })
                .dimensions(8, PICKER_Y, width - 16, 20)
                .tooltip(Tooltip.of(Text.literal(available == 0
                        ? "オンラインの他のプレイヤーがいません（同じサーバーのオンラインプレイヤーのみ選択できます）"
                        : "クリックで次の送金先へ ｜ 下のリストから直接選択も可能")))
                .build());

        // Direct-pick grid (up to 12 online players).
        int columns = 4;
        int gap = 4;
        int cellW = (width - 16 - (columns - 1) * gap) / columns;
        int shown = Math.min(12, state.candidates.size());
        for (int i = 0; i < shown; i++) {
            Candidate candidate = state.candidates.get(i);
            boolean isSelected = candidate.uuid.equals(selectedUuid);
            ButtonWidget button = ButtonWidget.builder(
                            fit((isSelected ? "● " : "") + candidate.name, cellW),
                            btn -> select(candidate.uuid))
                    .dimensions(8 + (i % columns) * (cellW + gap),
                            GRID_Y + (i / columns) * GRID_STRIDE, cellW, 20)
                    .tooltip(Tooltip.of(Text.literal("銀行残高: " + candidate.balance
                            + " ｜ クリックで送金先に選択")))
                    .build();
            addDrawableChild(button);
        }

        // Amount row: disabled until a recipient is picked.
        long[] amounts = {1, 10, 64, state.balance};
        String[] labels = {"送金: 1", "送金: 10", "送金: 64", "送金: 全部"};
        int amountW = (width - 16 - 3 * 4) / 4;
        for (int i = 0; i < amounts.length; i++) {
            long amount = amounts[i];
            ButtonWidget button = ButtonWidget.builder(Text.literal(labels[i]), btn -> {
                        if (selectedUuid == null) return;
                        ClientGui.send("transfer|send|" + selectedUuid + "|" + amount);
                    })
                    .dimensions(8 + i * (amountW + 4), AMOUNTS_Y, amountW, 20)
                    .tooltip(Tooltip.of(Text.literal(sendTooltip(amount))))
                    .build();
            button.active = selectedUuid != null
                    && (amount > 0 ? state.balance >= amount : state.balance > 0);
            addDrawableChild(button);
        }

        String[] labels2 = {"ギルド", "銀行", "更新", "閉じる"};
        String[] actions = {"guild|open", "bank|open", "transfer|open", null};
        int navY = height - 24;
        int buttonW = Math.min(66, (width - 16 - 3 * 4) / labels2.length);
        for (int i = 0; i < labels2.length; i++) {
            String action = actions[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(labels2[i]), btn -> {
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

    private String sendTooltip(long amount) {
        Candidate target = selected();
        if (target == null) return "送金先が選ばれていません（上のリストから選択してください）";
        if (amount <= 0) return "銀行残高がありません";
        if (state.balance < amount) {
            return "銀行残高が不足しています（残高: " + state.balance + "）";
        }
        return "クリックで " + target.name + " へ送金 ｜ 残高 -" + amount
                + " → " + target.name + " +" + amount + " ｜ 手数料: 無料";
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 6, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                "銀行残高: " + state.balance
                        + " ｜ 手持ちコイン: " + state.held
                        + " ｜ 手数料: 無料（プレイヤー間の送金）",
                8, 18, 0xFFD200);
        if (state.candidates.size() > 12) {
            context.drawTextWithShadow(textRenderer,
                    "ほか " + (state.candidates.size() - 12) + " 人は送金先ピッカーで選べます",
                    8, 30, 0x808080);
        }

        if (state.candidates.isEmpty()) {
            context.drawTextWithShadow(textRenderer, "オンラインの他プレイヤーがいません",
                    8, GRID_Y + 6, 0x808080);
        }

        context.drawTextWithShadow(textRenderer,
                "送金は銀行残高から引き落とし、相手に即時加算されます（延滞中は送金できません）",
                8, AMOUNTS_Y + 30, 0x808080);

        super.render(context, mouseX, mouseY, delta);
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

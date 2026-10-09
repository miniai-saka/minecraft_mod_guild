package dev.guildquests.client.gui;

import dev.guildquests.net.GuiState;
import dev.guildquests.service.GuildService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Button-based guild bank screen: deposit / withdraw, payment mode, debt status. */
public final class BankScreen extends Screen {
    private static final int LABEL_ROW_Y = 36;
    private static final int ROW_TOP = 58;
    private static final int STRIDE = 22;

    private GuiState state;

    public BankScreen(GuiState state) {
        super(Text.literal("ギルド銀行"));
        this.state = state;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void applyState(GuiState state) {
        this.state = state;
        clearAndInit();
    }

    private long fee(long amount) {
        if (state.feeWaived || amount <= 0 || state.feeRate <= 0) return 0;
        return Math.round(amount * state.feeRate / 100.0);
    }

    private String feeText() {
        if (state.feeWaived) return "無料（ブロック経由）";
        double pct = state.feeRate;
        String number = pct == Math.floor(pct) ? String.valueOf((long) pct) : String.valueOf(pct);
        return number + "%";
    }

    @Override
    protected void init() {
        int colGap = 6;
        int leftW = (width - 16 - colGap) / 2;
        int rightX = 8 + leftW + colGap;
        int rightW = width - 8 - rightX;

        addDrawableChild(sectionButton("預け入れ: 1", 8, ROW_TOP, leftW, 1, true));
        addDrawableChild(sectionButton("預け入れ: 10", 8, ROW_TOP + STRIDE, leftW, 10, true));
        addDrawableChild(sectionButton("預け入れ: 64", 8, ROW_TOP + 2 * STRIDE, leftW, 64, true));
        addDrawableChild(sectionButton("預け入れ: 全部", 8, ROW_TOP + 3 * STRIDE, leftW, 0, true));
        addDrawableChild(sectionButton("引き出し: 1", rightX, ROW_TOP, rightW, 1, false));
        addDrawableChild(sectionButton("引き出し: 10", rightX, ROW_TOP + STRIDE, rightW, 10, false));
        addDrawableChild(sectionButton("引き出し: 64", rightX, ROW_TOP + 2 * STRIDE, rightW, 64, false));
        addDrawableChild(sectionButton("引き出し: 全部", rightX, ROW_TOP + 3 * STRIDE, rightW,
                Math.min(state.balance, Integer.MAX_VALUE), false));

        // Payment mode row.
        String[] modeIds = {GuildService.MODE_RIBO, GuildService.MODE_DEBIT, GuildService.MODE_DISABLED};
        String[] modeLabels = {"リボ", "引き落とし", "無効"};
        int modeY = 164;
        int modeW = (width - 16 - 2 * 4) / 3;
        for (int i = 0; i < modeIds.length; i++) {
            String id = modeIds[i];
            boolean activeMode = id.equals(state.paymentMode);
            ButtonWidget button = ButtonWidget.builder(
                            Text.literal((activeMode ? "● " : "") + "支払い: " + modeLabels[i]),
                            btn -> ClientGui.send("bank|mode|" + id))
                    .dimensions(8 + i * (modeW + 4), modeY, modeW, 20)
                    .tooltip(Tooltip.of(Text.literal(modeTooltip(id))))
                    .build();
            button.active = !activeMode;
            addDrawableChild(button);
        }

        String[] labels = {"送金", "ギルド", "更新", "閉じる"};
        String[] actions = {"transfer|open", "guild|open", "bank|open", null};
        int navY = height - 24;
        int buttonW = Math.min(66, (width - 16 - 3 * 4) / labels.length);
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

    /** deposit=true: 預け入れ (amount 0 = all held); false: 引き出し. */
    private ButtonWidget sectionButton(String label, int x, int y, int w, long amount, boolean deposit) {
        ButtonWidget button = ButtonWidget.builder(fit(label, w), btn -> {
                    if (deposit) {
                        ClientGui.send("bank|deposit|" + amount);
                    } else {
                        ClientGui.send("bank|withdraw|" + amount);
                    }
                })
                .dimensions(x, y, w, 20)
                .tooltip(Tooltip.of(Text.literal(tooltip(amount, deposit))))
                .build();
        if (deposit) {
            button.active = state.held > 0;
        } else {
            button.active = amount > 0 && state.balance >= amount;
        }
        return button;
    }

    private String tooltip(long amount, boolean deposit) {
        List<String> parts = new ArrayList<>();
        if (deposit) {
            long take = amount <= 0 ? state.held : Math.min(amount, state.held);
            if (state.held <= 0) return "所持コインがありません";
            long fee = fee(take);
            parts.add("クリックで預け入れ");
            parts.add("手持ち -" + take + " → 残高 +" + (take - fee));
            parts.add(fee > 0 ? "手数料: -" + fee + " コイン" : "手数料: 無料");
        } else {
            if (amount <= 0) return "引き出せる残高がありません";
            if (state.balance < amount) return "残高が不足しています（残高: " + state.balance + "）";
            long fee = fee(amount);
            parts.add("クリックで引き出し");
            parts.add("残高 -" + amount + " → 手持ち +" + (amount - fee));
            parts.add(fee > 0 ? "手数料: -" + fee + " コイン" : "手数料: 無料");
        }
        return String.join(" ／ ", parts);
    }

    private String modeTooltip(String id) {
        return switch (id) {
            case GuildService.MODE_RIBO -> "クリックでリボ払いに切替 ｜ 残高がなくても購入でき価格は債務化 ｜ 利息 "
                    + state.riboInterestPercent + "% / " + state.riboIntervalMinutes
                    + " 分ごとに " + state.riboInstallment + " コインを自動徴収 ｜ 延滞でロック";
            case GuildService.MODE_DISABLED -> "クリックで無効に切替 ｜ 残高からの支払いを禁止 ｜ 手持ちのコインのみで支払う";
            default -> "クリックで引き落としに切替 ｜ 購入時に残高から即時引き落とし ｜ コインはストックティッカーに入金され店主が引き出せる";
        };
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 6, 0xFFFFFF);

        String line1 = "銀行残高: " + state.balance
                + " ｜ 手持ちコイン: " + state.held
                + " ｜ 手数料: " + feeText();
        String line2 = "支払い方法: " + GuildService.paymentModeLabel(state.paymentMode)
                + " ｜ ギルド銀行ブロック経由なら手数料無料";
        context.drawTextWithShadow(textRenderer, line1, 8, 18, 0xFFD200);
        context.drawTextWithShadow(textRenderer, line2, 8, 30, 0xFFD200);

        int leftW = (width - 16 - 6) / 2;
        int rightX = 8 + leftW + 6;
        context.drawTextWithShadow(textRenderer, "預け入れ", 8, LABEL_ROW_Y + 6, 0xAAAAAA);
        context.drawTextWithShadow(textRenderer, "引き出し", rightX, LABEL_ROW_Y + 6, 0xAAAAAA);

        // Debt status block above the nav bar.
        if (state.debt > 0) {
            if (state.delinquent) {
                context.drawTextWithShadow(textRenderer,
                        "リボ債務: " + state.debt + " ｜ ⚠ 延滞中：ギルド機能ロック中",
                        8, 190, 0xFF5555);
                context.drawTextWithShadow(textRenderer,
                        "クエスト報酬は全額自動で返済に充当されます（完済まで銀行・残高支払い不可）",
                        8, 202, 0xFF5555);
            } else {
                context.drawTextWithShadow(textRenderer,
                        "リボ債務: " + state.debt + " ｜ 次回の自動徴収まで約 " + state.nextRiboMinutes + " 分",
                        8, 190, 0xFFAA00);
                context.drawTextWithShadow(textRenderer,
                        "残高から " + state.riboInstallment + " コインを定期徴収 ｜ 利息 "
                                + state.riboInterestPercent + "% / " + state.riboIntervalMinutes + " 分ごと",
                        8, 202, 0xFFAA00);
            }
        } else {
            context.drawTextWithShadow(textRenderer, "リボ債務: なし ｜ 支払い方法をリボにすると後払い購入が使えます",
                    8, 190, 0x808080);
        }

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

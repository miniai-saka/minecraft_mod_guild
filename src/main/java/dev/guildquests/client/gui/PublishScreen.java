package dev.guildquests.client.gui;

import dev.guildquests.model.QuestType;
import dev.guildquests.net.GuiState;
import dev.guildquests.net.NetCodec;
import dev.guildquests.net.PublishRequest;
import dev.guildquests.service.GuildService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Free-text wizard for publishing a player quest: type toggle, manual target/amount/reward
 * entry, and a custom title/description. All values are typed directly into text fields;
 * the preview lines resolve the registry name and highlight invalid input live.
 */
public final class PublishScreen extends Screen {
    private GuiState state;
    private QuestType type = QuestType.KILL;
    private String targetText = "";
    private String amountText = "10";
    private String rewardText = "50";
    private String titleText = "";
    private String descriptionText = "";

    private ButtonWidget typeButton;
    private ButtonWidget publishButton;

    public PublishScreen(GuiState state) {
        super(Text.literal("クエストを公開"));
        this.state = state;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Refreshes server-driven fields (balance) while keeping the typed wizard input. */
    public void applyState(GuiState state) {
        this.state = state;
        clearAndInit();
    }

    // ------------------------------------------------------------------
    // Input helpers
    // ------------------------------------------------------------------

    /** Trimmed target id; adds the default "minecraft:" namespace when omitted. */
    private String normalizeTarget() {
        String raw = targetText == null ? "" : targetText.trim();
        if (raw.isEmpty() || raw.contains(":")) return raw;
        return "minecraft:" + raw;
    }

    /** Parsed amount, or null when blank / not a number / below 1. */
    private Integer amountValue() {
        try {
            int value = Integer.parseInt(amountText.trim());
            return value >= 1 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parsed reward clamped to the configured range, or null when not a number. */
    private Long rewardValue() {
        try {
            long value = Long.parseLong(rewardText.trim());
            return Math.max(state.rewardMin, Math.min(state.rewardMax, value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Registry-resolved display name, or null when the id does not exist. */
    private String resolvedName(String id) {
        if (id.isEmpty()) return null;
        String name = type == QuestType.KILL ? GuildService.entityName(id) : GuildService.itemName(id);
        return name.equals(id) ? null : name;
    }

    private boolean valid() {
        String target = normalizeTarget();
        return !target.isEmpty() && amountValue() != null && rewardValue() != null;
    }

    private String effectiveTitle() {
        String custom = titleText == null ? "" : titleText.trim();
        if (!custom.isEmpty()) return custom;
        String name = resolvedName(normalizeTarget());
        Integer amount = amountValue();
        String label = name != null ? name : (normalizeTarget().isEmpty() ? "?" : normalizeTarget());
        String kind = type == QuestType.KILL ? "討伐 " : "納品 ";
        return "依頼：" + kind + label + " × " + (amount != null ? amount : "?");
    }

    // ------------------------------------------------------------------
    // Widgets
    // ------------------------------------------------------------------

    private TextFieldWidget addField(int x, int y, int w, String placeholder, int maxLen,
                                     String initial, java.util.function.Consumer<String> onChange,
                                     java.util.function.Predicate<String> filter) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, w, 20,
                Text.literal(placeholder));
        field.setMaxLength(maxLen);
        field.setPlaceholder(Text.literal(placeholder));
        field.setText(initial == null ? "" : initial);
        if (filter != null) field.setTextPredicate(filter);
        field.setChangedListener(onChange);
        addDrawableChild(field);
        return field;
    }

    private static java.util.function.Predicate<String> digitsOnly() {
        return s -> s.length() <= 9 && s.chars().allMatch(Character::isDigit);
    }

    @Override
    protected void init() {
        int y1 = 32;
        int y2 = 56;
        int y3 = 80;
        int y4 = 104;
        int rightX = 112;

        typeButton = ButtonWidget.builder(Text.literal("種類: " + (type == QuestType.KILL ? "討伐" : "納品")),
                        btn -> {
                            type = type == QuestType.KILL ? QuestType.SUBMIT : QuestType.KILL;
                            btn.setMessage(Text.literal("種類: " + (type == QuestType.KILL ? "討伐" : "納品")));
                        })
                .dimensions(8, y1, 96, 20)
                .tooltip(Tooltip.of(Text.literal("クリックで討伐 ↔ 納品を切り替え"
                        + "\n討伐 = エンティティを倒す ｜ 納品 = アイテムを納品")))
                .build();
        addDrawableChild(typeButton);

        TextFieldWidget targetField = addField(rightX, y1, width - 8 - rightX,
                "対象ID（例: zombie / rotten_flesh）", 128,
                targetText, v -> targetText = v, null);
        targetField.setTooltip(Tooltip.of(Text.literal("対象のIDを直接入力してください"
                + "\n討伐: エンティティID（minecraft: を省略可）"
                + "\n納品: アイテムID（minecraft: を省略可）"
                + "\n入力内容は下のプレビューで確認できます")));
        setInitialFocus(targetField);

        addField(8, y2, 110, "数量（例: 16）", 9,
                amountText, v -> amountText = v, digitsOnly())
                .setTooltip(Tooltip.of(Text.literal("達成に必要な数を直接入力（1以上）"
                        + "\n例: 16 / 32 / 64 など")));

        addField(126, y2, width - 8 - 126, "報酬（例: 50）", 9,
                rewardText, v -> rewardText = v, digitsOnly())
                .setTooltip(Tooltip.of(Text.literal("報酬コインを直接入力"
                        + "\n設定範囲: " + state.rewardMin + " 〜 " + state.rewardMax
                        + " コイン（範囲外は自動補正）")));

        addField(8, y3, width - 16, "タイトル（空欄で自動生成）", 100,
                titleText, v -> titleText = v, null)
                .setTooltip(Tooltip.of(Text.literal("依頼のタイトルを直接入力できます"
                        + "\n空欄にすると自動生成（例: 依頼：討伐 ゾンビ × 16）")));

        addField(8, y4, width - 16, "説明文（空欄で自動）", 300,
                descriptionText, v -> descriptionText = v, null)
                .setTooltip(Tooltip.of(Text.literal("クエストの説明文を直接入力できます"
                        + "\n空欄なら「〇〇 が発注した依頼クエスト」と表示されます")));

        publishButton = ButtonWidget.builder(fit(publishLabel(), width - 16), btn -> publish())
                .dimensions(8, 166, width - 16, 20)
                .tooltip(Tooltip.of(Text.literal("クリックで依頼を掲示板に公開します"
                        + "\n報酬は公開時に残高から預託され、達成者に支払われます"
                        + "\n受注されるまで誰でも受注できます")))
                .build();
        addDrawableChild(publishButton);

        String[] labels = {"ギルドへ戻る", "掲示板", "閉じる"};
        String[] actions = {"guild|open", "board|open", null};
        int navY = height - 24;
        int buttonW = Math.min(90, (width - 16 - 2 * 4) / labels.length);
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

    private void publish() {
        if (!valid()) return;
        PublishRequest req = new PublishRequest();
        req.type = type.name();
        req.target = normalizeTarget();
        req.amount = String.valueOf(amountValue());
        req.reward = String.valueOf(rewardValue());
        req.title = titleText == null ? "" : titleText.trim();
        req.description = descriptionText == null ? "" : descriptionText.trim();
        ClientGui.send("publish|submit|" + NetCodec.GSON.toJson(req));
        close();
    }

    private String publishLabel() {
        Long reward = rewardValue();
        long r = reward != null ? reward : 0;
        String label = "この内容で公開する（報酬 " + r + " コインを預託）";
        if (!valid()) return "入力内容を確認してください";
        if (reward == null) return label;
        if (state.balance < r) label += " ※残高不足";
        return label;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 6, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                "残高: " + state.balance
                        + " ｜ 報酬範囲: " + state.rewardMin + " 〜 " + state.rewardMax
                        + " ｜ 公開上限: " + state.publishMaxPerPlayer + " 件",
                8, 18, 0xFFD200);

        String id = normalizeTarget();
        String resolved = resolvedName(id);
        String targetLine;
        int targetColor;
        if (id.isEmpty()) {
            targetLine = "対象: （未入力）";
            targetColor = 0xFF5555;
        } else if (resolved == null) {
            targetLine = "⚠ 対象IDが見つかりません: " + id;
            targetColor = 0xFF5555;
        } else {
            targetLine = "対象: " + resolved + "（" + id + "）";
            targetColor = 0xFFFFFF;
        }

        Integer amount = amountValue();
        Long reward = rewardValue();
        boolean numbersOk = amount != null && reward != null;
        boolean clamped = reward != null && !rewardText.trim().isEmpty()
                && !String.valueOf(reward).equals(rewardText.trim());
        String numbersLine;
        int numbersColor;
        if (!numbersOk) {
            numbersLine = "数量: " + (amount != null ? amount : "（1以上の数を入力）")
                    + " ｜ 報酬: " + (reward != null ? reward : "（数値を入力）") + " コイン";
            numbersColor = 0xFF5555;
        } else {
            numbersLine = "数量: " + amount + " ｜ 報酬: " + reward + " コイン"
                    + (clamped ? "（設定範囲に補正）" : "")
                    + " ｜ 公開後の残高: " + (state.balance - reward);
            numbersColor = state.balance < reward ? 0xFF5555 : 0xAAAAAA;
        }

        context.drawTextWithShadow(textRenderer, cutWidth(targetLine, width - 16), 8, 130, targetColor);
        context.drawTextWithShadow(textRenderer, cutWidth(numbersLine, width - 16), 8, 142, numbersColor);
        context.drawTextWithShadow(textRenderer, cutWidth("タイトル: " + effectiveTitle(), width - 16),
                8, 154, 0xFFFFFF);

        publishButton.setMessage(fit(publishLabel(), width - 16));
        publishButton.active = valid();

        context.drawTextWithShadow(textRenderer,
                "受注者が達成すると報酬が支払われ、達成するまで誰でも受注できます",
                8, 196, 0x808080);

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

    private String cutWidth(String label, int maxWidth) {
        if (textRenderer.getWidth(label) <= maxWidth) return label;
        String cut = label;
        while (!cut.isEmpty() && textRenderer.getWidth(cut + "…") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }
}

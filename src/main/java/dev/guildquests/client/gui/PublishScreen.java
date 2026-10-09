package dev.guildquests.client.gui;

import dev.guildquests.model.QuestType;
import dev.guildquests.net.GuiState;
import dev.guildquests.net.PublishOptions;
import dev.guildquests.service.GuildService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Button-based wizard for publishing a player quest: type, target, amount, reward. */
public final class PublishScreen extends Screen {
    private GuiState state;
    private QuestType type = QuestType.KILL;
    private int targetIndex;
    private int amountIndex = 1;
    private int rewardIndex = 2;

    public PublishScreen(GuiState state) {
        super(Text.literal("クエストを公開"));
        this.state = state;
    }

    /** Refreshes server-driven fields (balance) while keeping the wizard selections. */
    public void applyState(GuiState state) {
        this.state = state;
        clearAndInit();
    }

    private void clampIndexes() {
        if (targetIndex >= PublishOptions.targets(type).length) targetIndex = 0;
        if (amountIndex >= PublishOptions.AMOUNTS.length) amountIndex = 0;
        if (rewardIndex >= PublishOptions.REWARDS.length) rewardIndex = 0;
    }

    private String currentTarget() {
        return PublishOptions.targets(type)[targetIndex];
    }

    private int currentAmount() {
        return PublishOptions.AMOUNTS[amountIndex];
    }

    private long currentReward() {
        long base = PublishOptions.REWARDS[rewardIndex];
        return Math.max(state.rewardMin, Math.min(state.rewardMax, base));
    }

    @Override
    protected void init() {
        clampIndexes();
        int controlX = 116;
        int rightW = width - 8 - controlX;
        String target = currentTarget();

        addDrawableChild(ButtonWidget.builder(Text.literal("種類: " + (type == QuestType.KILL ? "討伐" : "納品")),
                        btn -> {
                            type = type == QuestType.KILL ? QuestType.SUBMIT : QuestType.KILL;
                            targetIndex = 0;
                            clearAndInit();
                        })
                .dimensions(8, 40, 104, 20)
                .tooltip(Tooltip.of(Text.literal("クリックで討伐 ↔ 納品を切り替え")))
                .build());

        addDrawableChild(ButtonWidget.builder(fit("対象: " + displayName(target), rightW),
                        btn -> {
                            targetIndex = (targetIndex + 1) % PublishOptions.targets(type).length;
                            clearAndInit();
                        })
                .dimensions(controlX, 40, rightW, 20)
                .tooltip(Tooltip.of(Text.literal("クリックで次の対象へ ｜ ID: " + target)))
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("数量: " + currentAmount()),
                        btn -> {
                            amountIndex = (amountIndex + 1) % PublishOptions.AMOUNTS.length;
                            clearAndInit();
                        })
                .dimensions(8, 66, 104, 20)
                .tooltip(Tooltip.of(Text.literal("クリックで次の数量へ ｜ "
                        + "5 → 10 → 16 → 32 → 64")))
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("報酬: " + currentReward() + " コイン"),
                        btn -> {
                            rewardIndex = (rewardIndex + 1) % PublishOptions.REWARDS.length;
                            clearAndInit();
                        })
                .dimensions(controlX, 66, rightW, 20)
                .tooltip(Tooltip.of(Text.literal("クリックで次の報酬へ ｜ 設定範囲: "
                        + state.rewardMin + " 〜 " + state.rewardMax + " コイン（範囲外は自動調整）")))
                .build());

        addDrawableChild(ButtonWidget.builder(
                        fit("この内容で公開する（報酬 " + currentReward() + " コインを預託）", width - 16),
                        btn -> {
                            ClientGui.send("publish|submit|" + type.name() + "|" + currentTarget()
                                    + "|" + currentAmount() + "|" + currentReward());
                            close();
                        })
                .dimensions(8, 96, width - 16, 20)
                .tooltip(Tooltip.of(Text.literal("クリックで依頼を掲示板に公開します ｜ "
                        + "報酬は公開時に残高から預託され、達成者に支払われます")))
                .build());

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

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 6, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                "残高: " + state.balance
                        + " ｜ 報酬範囲: " + state.rewardMin + " 〜 " + state.rewardMax
                        + " ｜ 公開上限: " + state.publishMaxPerPlayer + " 件",
                8, 18, 0xFFD200);

        long reward = currentReward();
        long after = state.balance - reward;
        context.drawTextWithShadow(textRenderer, "タイトル: " + previewTitle(), 8, 128, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer,
                "報酬: " + reward + " コイン（公開時にお預け）", 8, 140, 0xAAAAAA);
        context.drawTextWithShadow(textRenderer,
                "公開後の残高: " + after + (after < 0 ? "（不足しています）" : ""), 8, 152,
                after < 0 ? 0xFF5555 : 0xAAAAAA);
        context.drawTextWithShadow(textRenderer,
                "受注者が達成すると報酬が支払われ、達成するまで誰でも受注できます",
                8, 164, 0x808080);

        super.render(context, mouseX, mouseY, delta);
    }

    private String previewTitle() {
        if (type == QuestType.KILL) {
            return "依頼：討伐 " + displayName(currentTarget()) + " × " + currentAmount();
        }
        return "依頼：納品 " + displayName(currentTarget()) + " × " + currentAmount();
    }

    private static String displayName(String target) {
        String name = GuildService.entityName(target);
        return name.equals(target) ? GuildService.itemName(target) : name;
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

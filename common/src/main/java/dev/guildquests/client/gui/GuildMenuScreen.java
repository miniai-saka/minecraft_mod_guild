package dev.guildquests.client.gui;

import dev.guildquests.gui.GuildClientMenu;
import dev.guildquests.gui.GuildMenuKind;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;

/**
 * Button-based presentation of the current server menu, preserving the original navigation.
 * No inventory slots, background texture, chart panels or client-side economic mutations.
 */
public final class GuildMenuScreen extends Screen implements MenuAccess<GuildClientMenu> {
    private final GuildClientMenu menu;
    private final List<ItemStack> previous = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private final int[] pages;
    private int section;
    private GuildGuiLayout layout;
    private EditBox amountInput;
    private Button applyAmount;
    private CompoundTag amount;
    private String draft;
    private long lastServerAmount = -1;
    private int discardSlot = -1;
    private long discardUntil;
    private record Card(int slot, int x, int y, int width, int height, List<Component> text, boolean action) { }

    public GuildMenuScreen(GuildClientMenu menu, Inventory inventory, Component title) {
        super(title);
        this.menu = menu;
        pages = new int[menu.kind.sections.size()];
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public GuildClientMenu getMenu() { return menu; }

    @Override public void onClose() {
        if (minecraft.player != null) minecraft.player.closeContainer();
        else minecraft.setScreen(null);
    }

    @Override protected void init() { rebuild(); }

    @Override public void tick() {
        if (minecraft.player == null || !minecraft.player.isAlive() || minecraft.player.isRemoved()) { onClose(); return; }
        if (amountInput != null) amountInput.tick();
        boolean changed = previous.size() != menu.kind.rows * 9;
        for (int i = 0; !changed && i < previous.size(); i++)
            changed = !ItemStack.matches(previous.get(i), menu.getSlot(i).getItem());
        if (changed) {
            previous.clear();
            for (int i = 0; i < menu.kind.rows * 9; i++) previous.add(menu.getSlot(i).getItem().copy());
            rebuild();
        }
        if (discardSlot >= 0 && System.currentTimeMillis() > discardUntil) { discardSlot = -1; rebuild(); }
    }

    private void rebuild() {
        boolean focused = amountInput != null && amountInput.isFocused();
        int cursor = amountInput == null ? 0 : amountInput.getCursorPosition();
        if (amountInput != null) draft = amountInput.getValue();
        amount = null;
        for (int i = 0; i < menu.kind.rows * 9; i++) {
            var tag = menu.getSlot(i).getItem().getTag();
            if (tag != null && tag.contains("GuildGuiAmount")) { amount = tag.getCompound("GuildGuiAmount"); break; }
        }
        if (amount != null) {
            long value = amount.getLong("value");
            if (draft == null || !focused && value != lastServerAmount) draft = Long.toString(value);
            lastServerAmount = value;
        } else { draft = null; lastServerAmount = -1; }
        clearWidgets();
        cards.clear();
        layout = GuildGuiLayout.of(width, height, amount != null);
        int tabCount = menu.kind.sections.size();
        int tabWidth = (layout.width() - GuildGuiLayout.GAP * (tabCount - 1)) / tabCount;
        for (int i = 0; i < tabCount; i++) {
            int selected = i;
            Button tab = button(menu.kind.sections.get(i), layout.left() + i * (tabWidth + GuildGuiLayout.GAP),
                    layout.tabsY(), tabWidth, () -> { section = selected; discardSlot = -1; rebuild(); });
            tab.active = i != section;
        }
        amountInput = null;
        if (amount != null) {
            int labelWidth = Math.min(72, layout.width() / 4);
            int inputWidth = Math.min(180, layout.width() - labelWidth - 78);
            amountInput = new EditBox(font, layout.left() + labelWidth, layout.amountY(), inputWidth, 20,
                    Component.literal(amount.getString("label")));
            amountInput.setMaxLength(19);
            amountInput.setFilter(value -> value.isEmpty() || value.chars().allMatch(c -> c >= '0' && c <= '9'));
            amountInput.setValue(draft == null ? "0" : draft);
            // Reset horizontal scrolling when rebuilding a field after tab/page changes.
            amountInput.moveCursorTo(0);
            amountInput.setTooltip(Tooltip.create(Component.literal("0 ～ " + amount.getLong("max") + " の整数を入力")));
            addRenderableWidget(amountInput);
            applyAmount = button("金額を適用", amountInput.getX() + inputWidth + 6, layout.amountY(), 72, this::sendAmount);
            amountInput.setResponder(value -> updateAmountButton());
            updateAmountButton();
            if (focused) { setFocused(amountInput); amountInput.setFocused(true); amountInput.moveCursorTo(Math.min(cursor, amountInput.getValue().length())); }
        }

        List<Integer> slots = visibleSlots();
        int pageCount = layout.pageCount(slots.size());
        pages[section] = Math.min(pages[section], pageCount - 1);
        int first = pages[section] * layout.capacity();
        for (int i = 0; i < layout.capacity() && first + i < slots.size(); i++) addCard(slots.get(first + i), i);

        int edgeButton = Math.min(76, layout.width() / 4);
        button("閉じる", layout.left() + layout.width() - edgeButton, layout.footerY(), edgeButton, this::onClose);
        if (menu.kind != GuildMenuKind.GUILD) button("戻る", layout.left(), layout.footerY(), edgeButton,
                () -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0));
        int center = layout.left() + layout.width() / 2;
        Button prev = button("<", center - 64, layout.footerY(), 24, () -> turnPage(-1));
        Button next = button(">", center + 40, layout.footerY(), 24, () -> turnPage(1));
        prev.active = pages[section] > 0;
        next.active = pages[section] + 1 < pageCount;
    }

    private List<Integer> visibleSlots() {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < menu.kind.rows * 9; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty() || menu.kind.digitSlot(i) || menu.kind.section(i) != section) continue;
            String name = stack.getHoverName().getString();
            if (name.isBlank() || name.equals("閉じる") || stack.is(Items.WHITE_BANNER) && name.equals("Goal")) continue;
            result.add(i);
        }
        result.sort(java.util.Comparator.comparingInt(this::priority));
        return result;
    }

    private int priority(int slot) {
        // Put the transaction beside its amount input, before optional adjustment/help cards.
        return switch (menu.kind) {
            case BANK -> slot == 25 ? -3 : slot == 26 ? -2 : slot;
            case TRANSFER -> slot == 26 ? -2 : slot;
            case AUCTION_SELL -> slot == 18 ? -2 : slot;
            default -> slot;
        };
    }

    private void addCard(int slot, int index) {
        ItemStack stack = menu.getSlot(slot).getItem();
        List<Component> text = new ArrayList<>();
        text.add(Component.literal(present(stack.getHoverName().getString())));
        var lore = stack.getOrCreateTagElement("display").getList("Lore", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < lore.size(); i++) {
            try {
                Component line = Component.Serializer.fromJson(lore.getString(i));
                if (line != null) text.add(Component.literal(present(line.getString())));
            } catch (com.google.gson.JsonParseException ignored) { }
        }
        boolean action = stack.hasTag() && stack.getTag().getBoolean("GuildGuiAction");
        int x = layout.x(index), y = layout.y(index), w = layout.cardWidth(), h = layout.cardHeight();
        cards.add(new Card(slot, x, y, w, h, text, action));
        if (!action) return;
        boolean secondary = menu.kind == GuildMenuKind.GUILD && slot >= 36 && slot <= 44
                || text.stream().anyMatch(line -> line.getString().contains("右クリック"));
        int alternateWidth = secondary ? Math.min(70, w / 3) : 0;
        Button primary = button(primaryLabel(stack, slot), x + 5, y + h - 24, w - 10 - alternateWidth - (secondary ? 4 : 0), () -> click(slot, 0));
        primary.setTooltip(Tooltip.create(Component.literal(String.join("\n", text.stream().map(Component::getString).toList()))));
        if (secondary) {
            boolean cancel = menu.kind == GuildMenuKind.GUILD;
            String label = cancel ? discardSlot == slot ? "破棄を確定" : "破棄" : "減らす";
            button(label, x + w - alternateWidth - 5, y + h - 24, alternateWidth, () -> {
                if (cancel && discardSlot != slot) { discardSlot = slot; discardUntil = System.currentTimeMillis() + 5000; rebuild(); }
                else { discardSlot = -1; click(slot, 1); }
            });
        }
    }

    private String primaryLabel(ItemStack stack, int slot) {
        String name = stack.getHoverName().getString();
        if (menu.kind == GuildMenuKind.GUILD) {
            if (slot >= 9 && slot <= 17) return "受注する";
            if (slot >= 36 && slot <= 44) return "納品・報酬受取";
        }
        if (name.startsWith("賭け ±")) return "増やす +" + name.substring(4);
        return name;
    }

    private Button button(String label, int x, int y, int w, Runnable action) {
        Button result = Button.builder(Component.literal(fit(label, Math.max(1, w - 10))), b -> action.run())
                .bounds(x, y, Math.max(1, w), 20).build();
        if (font.width(label) > w - 10) result.setTooltip(Tooltip.create(Component.literal(label)));
        return addRenderableWidget(result);
    }

    private void click(int slot, int button) {
        if (minecraft.player != null && minecraft.gameMode != null)
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, ClickType.PICKUP, minecraft.player);
    }

    private Long inputAmount() {
        if (amountInput == null || amount == null || amountInput.getValue().isEmpty()) return null;
        try {
            long value = Long.parseLong(amountInput.getValue());
            return value >= 0 && value <= amount.getLong("max") ? value : null;
        } catch (NumberFormatException ex) { return null; }
    }
    private void updateAmountButton() {
        if (applyAmount != null) applyAmount.active = inputAmount() != null;
        if (amountInput != null) amountInput.setTextColor(inputAmount() != null ? 0xFFFFFF : 0xFF7777);
    }
    private void sendAmount() {
        Long value = inputAmount();
        if (value == null) return;
        GuildGuiActions.amountSender.accept(menu.containerId, value);
        amountInput.setFocused(false);
        setFocused(null);
    }
    private void turnPage(int delta) {
        int next = Math.max(0, Math.min(layout.pageCount(visibleSlots().size()) - 1, pages[section] + delta));
        if (next != pages[section]) { pages[section] = next; rebuild(); }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Exactly the in-world dimming used by vanilla menus; never cache or replace the world frame.
        renderBackground(graphics);
        graphics.drawString(font, fit(title.getString(), layout.width()), layout.left(), layout.titleY(), 0xFFFFFF);
        if (height >= 220) graphics.drawString(font, "冒険者ギルド  /  " + menu.kind.sections.get(section),
                layout.left(), layout.titleY() + 15, 0xC0C0C0);
        if (amount != null) graphics.drawString(font, amount.getString("label"), layout.left(), layout.amountY() + 6, 0xFFFFFF);
        Card hover = null;
        for (Card card : cards) {
            graphics.fill(card.x(), card.y(), card.x() + card.width(), card.y() + card.height(), 0xB0202020);
            graphics.fill(card.x(), card.y(), card.x() + 2, card.y() + card.height(), card.action() ? 0xFF8FB467 : 0xFF696969);
            graphics.renderItem(menu.getSlot(card.slot()).getItem(), card.x() + 6, card.y() + 5);
            if (!card.text().isEmpty()) graphics.drawString(font, fit(card.text().get(0).getString(), card.width() - 32),
                    card.x() + 27, card.y() + 8, 0xFFFFFF);
            int lines = Math.max(0, (card.height() - (card.action() ? 48 : 26)) / 10);
            for (int i = 1; i < card.text().size() && i <= lines; i++)
                graphics.drawString(font, fit(card.text().get(i).getString(), card.width() - 14), card.x() + 7, card.y() + 24 + (i - 1) * 10, 0xCCCCCC);
            if (menu.kind == GuildMenuKind.HORSE_RACE && card.slot() < 36 && !card.action())
                renderRaceProgress(graphics, card);
            if (mouseX >= card.x() && mouseX < card.x() + card.width() && mouseY >= card.y() && mouseY < card.y() + card.height()) hover = card;
        }
        for (var child : children()) if (child instanceof net.minecraft.client.gui.components.Renderable widget)
            widget.render(graphics, mouseX, mouseY, partialTick);
        String page = (pages[section] + 1) + " / " + layout.pageCount(visibleSlots().size());
        graphics.drawCenteredString(font, page, layout.left() + layout.width() / 2, layout.footerY() + 6, 0xFFFFFF);
        if (cards.isEmpty()) graphics.drawCenteredString(font, previous.isEmpty() ? "読み込み中…" : "表示する項目はありません",
                width / 2, layout.top() + 16, 0xCCCCCC);
        if (hover != null && mouseY < hover.y() + hover.height() - (hover.action() ? 24 : 0)) {
            List<FormattedCharSequence> tooltip = new ArrayList<>();
            int wrapWidth = Math.max(100, Math.min(360, width - 30));
            for (Component line : menu.getSlot(hover.slot()).getItem().getTooltipLines(minecraft.player, TooltipFlag.Default.NORMAL))
                tooltip.addAll(font.split(Component.literal(present(line.getString())), wrapWidth));
            graphics.renderTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private void renderRaceProgress(GuiGraphics graphics, Card card) {
        for (Component line : card.text()) {
            String value = line.getString();
            if (!value.matches("[0-9]+%")) continue;
            int progress = Math.min(100, Integer.parseInt(value.substring(0, value.length() - 1)));
            int y = card.y() + card.height() - 10;
            graphics.fill(card.x() + 6, y, card.x() + card.width() - 6, y + 4, 0xFF444444);
            graphics.fill(card.x() + 6, y, card.x() + 6 + (card.width() - 12) * progress / 100, y + 4, 0xFF8FB467);
        }
    }

    private String fit(String value, int width) {
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
    }
    private static String present(String value) {
        return value.replace("数字頭・紙", "金額入力欄・加算ボタン").replace("数字頭または紙", "金額入力欄")
                .replace("紙ボタンまたは数字頭", "金額入力欄").replace("右の紙・数字頭", "金額入力欄")
                .replace("左のプレイヤー頭", "送金先の一覧").replace("左の頭を選択", "送金先を選択")
                .replace("4段目に現在の受注クエストを表示します。", "受注中のクエストを表示します。")
                .replace("下段の", "操作の").replace("下の一覧", "一覧");
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        for (GuiEventListener child : children()) if (child.mouseClicked(x, y, button)) {
            setFocused(child); if (button == 0) setDragging(true); return true;
        }
        if (amountInput != null) amountInput.setFocused(false);
        setFocused(null);
        return false;
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        setDragging(false);
        return getFocused() != null && getFocused().mouseReleased(x, y, button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return getFocused() != null && getFocused().mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (delta != 0) turnPage(delta < 0 ? 1 : -1);
        return true;
    }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (amountInput != null && amountInput.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { sendAmount(); return true; }
            if (key != GLFW.GLFW_KEY_TAB) return amountInput.keyPressed(key, scanCode, modifiers);
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP) { turnPage(key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1); return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }
    @Override public boolean charTyped(char character, int modifiers) {
        return amountInput != null && amountInput.isFocused() && amountInput.charTyped(character, modifiers);
    }
}

package dev.guildquests.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

final class GuildKeypad {
    enum Result { NONE, HANDLED, APPLY, CLOSE }

    private static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", "←"};
    private static final int COLUMNS = 3;
    private static final int MAX_DIGITS = 19;
    private static final int CLOSE_WIDTH = 52;

    private boolean open;
    private String draft = "0";
    private long max = Long.MAX_VALUE;

    record Bounds(int left, int top, int right, int bottom) {
        int width() { return right - left; }
        int height() { return bottom - top; }
    }

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }

    private record Metrics(int panelX, int panelY, int panelW, int panelH,
                           int keyX, int keyY, int keyW, int keyH,
                           int headerY, int headerH, int clearY, int bottomY,
                           int pad, int gap) { }

    boolean isOpen() { return open; }
    void open() { open = true; }
    void close() { open = false; }
    String draft() { return draft; }

    void setMax(long max) {
        this.max = Math.max(0, max);
        clampDraft();
    }

    void reset(long value) {
        draft = Long.toString(Math.max(0, Math.min(max, value)));
    }

    Long value() {
        try {
            long parsed = Long.parseLong(draft);
            return parsed >= 0 && parsed <= max ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    void append(char digit) {
        if (digit < '0' || digit > '9') return;
        String next = (draft.equals("0") ? "" : draft) + digit;
        if (next.length() > MAX_DIGITS) return;
        long parsed;
        try {
            parsed = Long.parseLong(next);
        } catch (NumberFormatException ex) {
            return;
        }
        if (parsed > max) return;
        draft = next;
    }

    void appendDoubleZero() { append('0'); append('0'); }

    void backspace() { draft = draft.length() <= 1 ? "0" : draft.substring(0, draft.length() - 1); }

    void clear() { draft = "0"; }

    private void clampDraft() {
        try {
            long parsed = Long.parseLong(draft);
            if (parsed > max) draft = Long.toString(max);
        } catch (NumberFormatException ex) {
            draft = "0";
        }
    }

    void render(GuiGraphics graphics, Font font, Bounds area, int screenWidth, int screenHeight,
                int mouseX, int mouseY) {
        if (!open) return;
        graphics.fill(area.left(), area.top(), area.right(), area.bottom(), 0x80000000);
        Metrics m = metrics(area, screenWidth, screenHeight);
        graphics.fill(m.panelX, m.panelY, m.panelX + m.panelW, m.panelY + m.panelH, 0xF0181818);
        border(graphics, m.panelX, m.panelY, m.panelW, m.panelH);

        int fieldX = m.panelX + m.pad;
        int fieldW = m.panelW - m.pad * 2;
        graphics.fill(fieldX, m.headerY, fieldX + fieldW, m.headerY + m.headerH, 0xFF0B0B0B);
        graphics.drawString(font, "金額", fieldX + 4, m.headerY + 6, 0xFFA0A0A0, false);
        int textRight = fieldX + fieldW - 4;
        int textLimit = textRight - (fieldX + 4 + font.width("金額") + 6);
        String shown = draft;
        while (font.width(shown) > textLimit && shown.length() > 1) shown = shown.substring(1);
        graphics.drawString(font, shown, textRight - font.width(shown), m.headerY + 6, 0xFFFFFFFF, false);

        for (int i = 0; i < KEYS.length; i++) {
            Rect key = keyRect(m, i);
            key(graphics, font, key, KEYS[i], key.contains(mouseX, mouseY), 0xFF3C3C3C, 0xFF585858);
        }
        Rect clear = clearRect(m);
        key(graphics, font, clear, "クリア", clear.contains(mouseX, mouseY), 0xFF4A2C2C, 0xFF6B3D3D);
        Rect confirm = confirmRect(m);
        key(graphics, font, confirm, "確定", confirm.contains(mouseX, mouseY), 0xFF2C4A30, 0xFF3D6B43);
        Rect close = closeRect(m);
        key(graphics, font, close, "閉じる", close.contains(mouseX, mouseY), 0xFF303030, 0xFF4A4A4A);
    }

    Result click(double mouseX, double mouseY, int button, Bounds area, int screenWidth, int screenHeight) {
        if (!open) return Result.NONE;
        if (button != 0) return Result.HANDLED;
        Metrics m = metrics(area, screenWidth, screenHeight);
        if (closeRect(m).contains(mouseX, mouseY)) return Result.CLOSE;
        if (confirmRect(m).contains(mouseX, mouseY)) return value() != null ? Result.APPLY : Result.HANDLED;
        if (clearRect(m).contains(mouseX, mouseY)) { clear(); return Result.HANDLED; }
        for (int i = 0; i < KEYS.length; i++) {
            if (!keyRect(m, i).contains(mouseX, mouseY)) continue;
            press(i);
            return Result.HANDLED;
        }
        if (mouseX >= m.panelX && mouseX < m.panelX + m.panelW
                && mouseY >= m.panelY && mouseY < m.panelY + m.panelH) return Result.HANDLED;
        return Result.CLOSE;
    }

    private void press(int index) {
        if (index == 9) appendDoubleZero();
        else if (index == 11) backspace();
        else append(KEYS[index].charAt(0));
    }

    private Metrics metrics(Bounds area, int screenWidth, int screenHeight) {
        int pad = 8;
        int gap = 4;
        int headerH = 20;
        int panelW = Math.min(168, Math.max(96, area.width() - 8));
        int available = Math.max(96, Math.min(area.height() - 8, screenHeight - 8));
        int fixed = pad * 2 + headerH + gap * 6 + 40;
        int keyH = Math.max(12, Math.min(22, (available - fixed) / 4));
        int keyW = Math.max(20, (panelW - pad * 2 - gap * 2) / 3);
        int panelH = fixed + 4 * keyH;
        int panelX = area.left() + (area.width() - panelW) / 2;
        int panelY = area.top() + (area.height() - panelH) / 2;
        panelX = Math.max(2, Math.min(screenWidth - panelW - 2, panelX));
        panelY = Math.max(2, Math.min(screenHeight - panelH - 2, panelY));
        int headerY = panelY + pad;
        int keyX = panelX + pad;
        int keyY = headerY + headerH + gap;
        int clearY = keyY + 4 * keyH + 3 * gap + gap;
        int bottomY = clearY + 20 + gap;
        return new Metrics(panelX, panelY, panelW, panelH, keyX, keyY, keyW, keyH,
                headerY, headerH, clearY, bottomY, pad, gap);
    }

    private Rect keyRect(Metrics m, int index) {
        return new Rect(m.keyX + (index % COLUMNS) * (m.keyW + m.gap),
                m.keyY + (index / COLUMNS) * (m.keyH + m.gap), m.keyW, m.keyH);
    }

    private Rect clearRect(Metrics m) {
        return new Rect(m.panelX + m.pad, m.clearY, m.panelW - m.pad * 2, 20);
    }

    private Rect confirmRect(Metrics m) {
        return new Rect(m.panelX + m.pad, m.bottomY,
                m.panelW - m.pad * 2 - CLOSE_WIDTH - m.gap, 20);
    }

    private Rect closeRect(Metrics m) {
        return new Rect(m.panelX + m.panelW - m.pad - CLOSE_WIDTH, m.bottomY, CLOSE_WIDTH, 20);
    }

    private void key(GuiGraphics graphics, Font font, Rect rect, String label, boolean hovered,
                     int base, int highlight) {
        graphics.fill(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h, hovered ? highlight : base);
        graphics.fill(rect.x, rect.y, rect.x + rect.w, rect.y + 1, 0x40FFFFFF);
        String text = label;
        int limit = rect.w - 4;
        if (font.width(text) > limit)
            text = font.plainSubstrByWidth(text, Math.max(0, limit - font.width("…"))) + "…";
        graphics.drawCenteredString(font, text, rect.x + rect.w / 2, rect.y + (rect.h - 8) / 2, 0xFFFFFFFF);
    }

    private void border(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + 1, 0xFF707070);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFF303030);
        graphics.fill(x, y, x + 1, y + h, 0xFF707070);
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFF303030);
    }
}

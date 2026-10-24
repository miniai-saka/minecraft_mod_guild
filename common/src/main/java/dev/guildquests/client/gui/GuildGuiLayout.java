package dev.guildquests.client.gui;

/** All coordinates derive from the current scaled viewport, including GUI-scale changes. */
public record GuildGuiLayout(int left, int width, int titleY, int tabsY, int amountY,
                             int top, int footerY, int columns, int rows, int cardWidth, int cardHeight) {
    public static final int GAP = 6;
    public static GuildGuiLayout of(int width, int height, boolean amount) {
        int margin = Math.max(8, Math.min(24, width / 35));
        int bodyWidth = Math.max(1, Math.min(920, width - margin * 2));
        boolean compact = height < 220;
        int titleY = compact ? 6 : 12;
        int tabsY = compact ? 24 : 42;
        int amountY = tabsY + 26;
        int top = amount ? amountY + 31 : tabsY + 27;
        int footerY = height - 28;
        int available = Math.max(1, footerY - GAP - top);
        int cardHeight = Math.min(74, available);
        int columns = Math.max(1, Math.min(3, (bodyWidth + GAP) / (216 + GAP)));
        int rows = Math.max(1, (available + GAP) / (cardHeight + GAP));
        int cardWidth = (bodyWidth - GAP * (columns - 1)) / columns;
        return new GuildGuiLayout((width - bodyWidth) / 2, bodyWidth, titleY, tabsY, amountY,
                top, footerY, columns, rows, cardWidth, cardHeight);
    }
    public int capacity() { return columns * rows; }
    public int x(int index) { return left + index % columns * (cardWidth + GAP); }
    public int y(int index) { return top + index / columns * (cardHeight + GAP); }
    public int pageCount(int count) { return Math.max(1, (count + capacity() - 1) / capacity()); }
}

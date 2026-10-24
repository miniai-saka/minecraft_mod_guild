package dev.guildquests.gui;

import dev.guildquests.client.gui.GuildGuiLayout;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuildGuiLayoutTest {
    @Test public void cardsStayInsideViewportAcrossWindowSizesAndGuiScales() {
        int[][] viewports = {{320,180}, {320,240}, {427,240}, {480,270}, {640,360}, {854,480}, {960,540}, {1280,720}, {1920,1080}, {3440,1440}};
        for (int[] viewport : viewports) for (boolean amount : new boolean[]{false, true}) {
            var layout = GuildGuiLayout.of(viewport[0], viewport[1], amount);
            assertTrue(layout.capacity() > 0);
            for (int i = 0; i < layout.capacity(); i++) {
                assertTrue(layout.x(i) >= 0);
                assertTrue(layout.x(i) + layout.cardWidth() <= viewport[0]);
                assertTrue(layout.y(i) >= layout.top());
                assertTrue(layout.y(i) + layout.cardHeight() <= layout.footerY() - GuildGuiLayout.GAP);
            }
            assertTrue(layout.pageCount(0) == 1);
            assertTrue(layout.pageCount(54) * layout.capacity() >= 54);
        }
    }
    @Test public void narrowScreensReflowAndLargeListsRemainReachable() {
        assertEquals(1, GuildGuiLayout.of(320,240,true).columns());
        assertEquals(3, GuildGuiLayout.of(1920,1080,false).columns());
        for (GuildMenuKind kind : GuildMenuKind.values()) for (int slot = 0; slot < kind.rows * 9; slot++) {
            assertTrue(kind.section(slot) >= 0);
            assertTrue(kind.section(slot) < kind.sections.size());
        }
    }
}

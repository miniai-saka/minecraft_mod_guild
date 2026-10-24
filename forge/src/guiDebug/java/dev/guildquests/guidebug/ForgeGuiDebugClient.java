package dev.guildquests.guidebug;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

final class ForgeGuiDebugClient {
    static void init() {
        GuiDebugClient driver = new GuiDebugClient();
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) driver.tick(Minecraft.getInstance());
        });
    }
}

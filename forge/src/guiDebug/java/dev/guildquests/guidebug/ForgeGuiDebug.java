package dev.guildquests.guidebug;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

/** Fixtures are installed only by the separate, opt-in test mod. */
@Mod("guild_gui_debug")
public final class ForgeGuiDebug {
    public ForgeGuiDebug() {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> GuiDebugFixtures.register(e.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) GuiDebugFixtures.onJoin(player);
        });
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ForgeGuiDebugClient::init);
    }
}

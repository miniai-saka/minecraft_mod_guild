package dev.guildquests.guidebug;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public final class GuiDebugMod implements ModInitializer {
    @Override public void onInitialize() {
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> GuiDebugFixtures.onJoin(listener.player));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> GuiDebugFixtures.register(dispatcher));
    }
}

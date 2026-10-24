package dev.guildquests.guidebug;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class FabricGuiDebugClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(new GuiDebugClient()::tick);
        ClientTickEvents.END_CLIENT_TICK.register(new AmountInputDebugClient()::tick);
    }
}

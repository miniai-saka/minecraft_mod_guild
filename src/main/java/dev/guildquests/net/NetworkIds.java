package dev.guildquests.net;

import net.minecraft.util.Identifier;

/** Packet channel identifiers shared by the server and the client. */
public final class NetworkIds {
    /** S2C: JSON-serialized {@link GuiState} for the button-based screens. */
    public static final Identifier STATE = new Identifier("guildquests", "gui_state");
    /** C2S: "view|action|arg..." payload dispatched by the server. */
    public static final Identifier ACTION = new Identifier("guildquests", "gui_action");

    private NetworkIds() {
    }
}

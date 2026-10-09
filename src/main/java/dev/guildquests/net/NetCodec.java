package dev.guildquests.net;

import com.google.gson.Gson;

/** Gson helpers for the GUI state payload. */
public final class NetCodec {
    public static final Gson GSON = new Gson();

    private NetCodec() {
    }

    public static String encode(GuiState state) {
        return GSON.toJson(state);
    }

    public static GuiState decode(String json) {
        return GSON.fromJson(json, GuiState.class);
    }
}

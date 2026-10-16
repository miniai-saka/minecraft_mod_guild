package dev.guildquests.client;

import dev.guildquests.GuildQuestsMod;
import dev.guildquests.fx.FxSettings;
import dev.guildquests.platform.Platform;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class FxUiPreferences {
    private static String style = "SERVER";
    private FxUiPreferences() { }
    public static String style() { return style; }
    public static String resolved(String serverDefault) { return style.equals("SERVER") ? serverDefault : style; }
    public static void load() {
        Path file = Platform.configDir().resolve("fx-client.json");
        if (!Files.exists(file)) return;
        try {
            var object = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            style = valid(object.get("uiStyle").getAsString());
        } catch (Exception ex) { GuildQuestsMod.LOGGER.warn("Unable to read fx-client.json; using server UI default", ex); }
    }
    public static void set(String value) throws java.io.IOException {
        String next = valid(value);
        Path file = Platform.configDir().resolve("fx-client.json");
        Files.createDirectories(file.getParent());
        var json = new com.google.gson.JsonObject(); json.addProperty("uiStyle", next);
        Path temporary = file.resolveSibling("fx-client.json.tmp");
        Files.writeString(temporary, FxSettings.JSON.toJson(json));
        try { Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        style = next;
    }
    private static String valid(String value) {
        String text = value == null ? "SERVER" : value.toUpperCase(Locale.ROOT);
        return text.equals("MINECRAFT") || text.equals("WHITE") ? text : "SERVER";
    }
}

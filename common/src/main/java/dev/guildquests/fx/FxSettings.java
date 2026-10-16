package dev.guildquests.fx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.guildquests.GuildQuestsMod;
import dev.guildquests.platform.Platform;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FxSettings {
    public static final Gson JSON = new GsonBuilder().create();
    private static FxConfig current = new FxConfig().sanitize();
    private FxSettings() { }
    public static FxConfig get() { return current; }
    public static synchronized void setMarginCall(boolean enabled) throws java.io.IOException {
        FxConfig next = JSON.fromJson(JSON.toJson(current), FxConfig.class);
        next.marginCallEnabled = enabled;
        Path path = Platform.configDir().resolve("fx.json");
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling("fx.json.tmp");
        try (Writer writer = Files.newBufferedWriter(temporary)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(next, writer);
        }
        try { Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        current = next.sanitize();
    }
    public static boolean load() {
        Path path = Platform.configDir().resolve("fx.json");
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                try (Writer writer = Files.newBufferedWriter(path)) {
                    new GsonBuilder().setPrettyPrinting().create().toJson(current, writer);
                }
            }
            try (Reader reader = Files.newBufferedReader(path)) {
                FxConfig value = JSON.fromJson(reader, FxConfig.class);
                if (value == null) throw new IllegalArgumentException("Empty FX configuration");
                current = value.sanitize();
            }
            GuildQuestsMod.LOGGER.info("Loaded FX settings from {}", path);
            return true;
        } catch (Exception ex) {
            GuildQuestsMod.LOGGER.error("Unable to load fx.json; keeping previous FX settings", ex);
            return false;
        }
    }
}

package net.psunset.translatorpp.yomitan;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes lookup diagnostics to {@code logs/translatorpp-yomitan.log} in the game
 * directory. Lunar Client's own log drops output from mod loggers, so this is
 * the only place these lines can be read back.
 */
final class DebugLog {
    private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("translatorpp-yomitan.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static boolean truncated;

    private DebugLog() {
    }

    static synchronized void write(String message) {
        try {
            Files.createDirectories(FILE.getParent());
            if (!truncated) {
                // One file per game session.
                Files.writeString(FILE, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                truncated = true;
            }
            Files.writeString(FILE, "[" + LocalTime.now().format(TIME) + "] " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Diagnostics must never break the game.
        }
    }
}

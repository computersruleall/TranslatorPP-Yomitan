package net.psunset.translatorpp.core;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.psunset.translatorpp.TranslatorPP;
import net.psunset.translatorpp.api.ChatComponentAccessor;
import net.psunset.translatorpp.config.TPPConfig;
import net.psunset.translatorpp.exception.ServiceException;
import net.psunset.translatorpp.tool.MessageUtl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Automatic chat translation: each message arriving from the server that isn't in
 * the target language is replaced in place by its translation. Hovering the
 * translated text shows the original.
 */
public final class ChatAutoTranslator {
    /** Below this, Azure is guessing, typically on short Latin text. Leave those alone. */
    private static final double MIN_DETECT_SCORE = 0.7;
    private static final int CACHE_SIZE = 2000;
    private static final long DEFAULT_PAUSE_MILLIS = 60_000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Translation-Auto");
        thread.setDaemon(true);
        return thread;
    });
    /** Original message to translation, or to itself when it needs none. */
    private static final Map<String, String> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(CACHE_SIZE, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    });

    private static volatile long pausedUntil;

    private ChatAutoTranslator() {
    }

    /** The source language to send for a chat message, honouring the per-server Japanese override. */
    public static String sourceFor(String text) {
        if (treatChatAsJapanese()) {
            return ChatText.isLatin(text) ? AzureTranslationProvider.ROMAJI : "ja";
        }
        return TPPConfig.getInstance().getSourceLanguage();
    }

    public static boolean treatChatAsJapanese() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return server != null && ChatText.serverListed(TPPConfig.getInstance().getJapaneseServers(), server.ip);
    }

    public static void clearCache() {
        CACHE.clear();
        pausedUntil = 0;
    }

    /** Called on the client thread for each new chat message. */
    public static void onMessage(GuiMessage message) {
        if (TPPConfig.getInstance().getChatTranslation() != ChatTranslationMode.AUTO
                || message.source() == GuiMessageSource.SYSTEM_CLIENT
                || System.currentTimeMillis() < pausedUntil) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        String line = message.content().getString();
        int start = ChatText.bodyStart(line);
        int end = ChatText.bodyEnd(line, start);
        if (start >= end) {
            return;
        }
        // Your own messages are already in the language you meant.
        if (start > 0 && client.player != null && line.substring(0, start).contains(client.player.getGameProfile().name())) {
            return;
        }
        String body = line.substring(start, end);
        ChatText.Prepared prepared = ChatText.prepare(body);
        if (!ChatText.worthTranslating(prepared)) {
            return;
        }
        String source = sourceFor(body);
        String target = TPPConfig.getInstance().getTargetLanguage();
        EXECUTOR.execute(() -> {
            try {
                String translated = translated(body, prepared, source, target);
                if (!translated.equals(body)) {
                    client.execute(() -> replace(client, message, start, end, body, translated));
                }
            } catch (ServiceException e) {
                pause(e);
            } catch (Exception e) {
                TranslatorPP.LOGGER.warn("Automatic translation failed: {}", e.toString());
            }
        });
    }

    private static String translated(String body, ChatText.Prepared prepared, String source, String target) throws Exception {
        String key = source + "|" + target + "|" + prepared.shielded();
        String cached = CACHE.get(key);
        if (cached != null) {
            return cached.isEmpty() ? body : prepared.restore(cached);
        }
        AzureTranslationProvider azure = AzureTranslationProvider.getInstance();
        if ("auto".equals(source)) {
            AzureTranslationProvider.Detection detection = azure.detect(prepared.shielded());
            if (detection.score() < MIN_DETECT_SCORE || ChatText.sameLanguage(detection.language(), target)) {
                CACHE.put(key, "");
                return body;
            }
        }
        String result = ChatText.sanitize(azure.translate(prepared.shielded(), source, target));
        if (result.isEmpty() || ChatText.sameText(result, prepared.shielded())) {
            CACHE.put(key, "");
            return body;
        }
        CACHE.put(key, result);
        return prepared.restore(result);
    }

    /** One warning per outage, not one per chat line, and no requests until it should have passed. */
    private static void pause(ServiceException e) {
        long wait = e.retryAfterSeconds > 0 ? e.retryAfterSeconds * 1000 : DEFAULT_PAUSE_MILLIS;
        pausedUntil = System.currentTimeMillis() + wait;
        MessageUtl.threadSafeToLocal(Component.literal(e.getMessage() + " Automatic translation paused for "
                + wait / 1000 + "s.").withStyle(ChatFormatting.RED));
    }

    private static void replace(Minecraft client, GuiMessage message, int start, int end, String body, String translated) {
        MutableComponent content = Component.empty();
        int[] position = {0};
        Style[] bodyStyle = {null};
        // Keep everything around the message as it was: rank, name, their hovers and clicks, trailing icons.
        message.content().visit((FormattedText.StyledContentConsumer<Void>) (style, text) -> {
            int from = position[0];
            int to = from + text.length();
            if (from < start) {
                content.append(Component.literal(text.substring(0, Math.min(text.length(), start - from))).withStyle(style));
            }
            if (bodyStyle[0] == null && to > start) {
                bodyStyle[0] = style;
                content.append(Component.literal(translated).withStyle(style.withHoverEvent(new HoverEvent.ShowText(Component.literal(body)))));
            }
            if (to > end) {
                content.append(Component.literal(text.substring(Math.max(0, end - from))).withStyle(style));
            }
            position[0] = to;
            return Optional.empty();
        }, Style.EMPTY);
        GuiMessage replacement = new GuiMessage(message.addedTime(), content, message.signature(), message.source(), message.tag());
        ((ChatComponentAccessor) client.gui.hud.getChat()).translatorpp$replaceMessage(message, replacement);
    }
}

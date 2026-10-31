package net.psunset.translatorpp.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.psunset.translatorpp.TranslatorPP;
import net.psunset.translatorpp.config.TPPConfig;
import net.psunset.translatorpp.event.ItemTooltipCallbacks;
import net.psunset.translatorpp.event.ScreenCallbacks;
import net.psunset.translatorpp.exception.ServiceException;
import net.psunset.translatorpp.keybind.TPPKeyMappings;
import net.psunset.translatorpp.tool.MessageUtl;
import net.psunset.translatorpp.tool.TooltipUtl;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * The main class to manage all translation process.
 */
public final class TranslationKit {

    static final TranslationKit INSTANCE = new TranslationKit();
    static final Gson GSON = new GsonBuilder().create();

    public static final String SUCCESS = "<O>";
    public static final String PROCESSING = "<?>";
    public static final String ERROR = "<X>";

    private static final AtomicInteger taskCounter = new AtomicInteger(0);
    private static final ExecutorService translationExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread translationThread = new Thread(r, "Translation-Worker-" + taskCounter.incrementAndGet());
        translationThread.setDaemon(true); // Allow JVM to exit even if this thread is running
        return translationThread;
    });

    public static TranslationKit getInstance() {
        return INSTANCE;
    }

    final Minecraft client = Minecraft.getInstance();

    // TODO: Make cache size configurable
    private static final int MAX_CACHE_SIZE = 100;
    private static final long ERROR_INTERVAL_MILLIS = 10_000;

    /**
     * A cache with LRU eviction policy to store recent translations.
     */
    private final Map<String, String> translationCache = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            }
    );

    /**
     * The text currently being hovered over, null if none.
     */
    @Nullable
    private String hoveredText = null;

    /**
     * Whether {@link #hoveredText} is a chat line.
     */
    private boolean hoveredIsChat = false;

    private volatile long lastErrorAt = 0;

    /**
     * The text that is being translated, null if not translating yet.
     */
    @Nullable
    private String translatedText = null;

    /**
     * The result of the translation, null if not translated yet.
     */
    @Nullable
    private volatile String translatedResult = null;

    /**
     * Whether a translation is in progress or completed.
     */
    private volatile boolean translated = false;

    /**
     * Used when a screen open, key.isDown() won't work.
     * When there is no screen open, set to false.
     */
    private boolean translateKeyDown = false;

    private CompletableFuture<Void> translationFuture = null;

    private TranslationKit() {
    }

    public @Nullable String getHoveredText() {
        return hoveredText;
    }

    public void setHoveredText(@Nullable ItemStack stack) {
        if (stack == null) {
            this.hoveredText = null;
            return;
        }
        this.hoveredText = TooltipUtl.getCombinedTooltipText(stack, client);
        this.hoveredIsChat = false;
    }

    public void setHoveredText(List<Component> tooltip) {
        if (tooltip.isEmpty()) {
            this.hoveredText = null;
            return;
        }
        this.hoveredText = TooltipUtl.getCombinedTooltipText(tooltip);
        this.hoveredIsChat = false;
    }

    public void setHoveredText(@Nullable String text) {
        this.hoveredText = text;
        this.hoveredIsChat = false;
    }

    /** As {@link #setHoveredText(String)}, for a whole chat line. */
    public void setHoveredChat(@Nullable String line) {
        this.hoveredText = line;
        this.hoveredIsChat = line != null;
    }

    public @Nullable String getTranslatedText() {
        return translatedText;
    }

    public @Nullable String getTranslatedResult() {
        return translatedResult;
    }

    public boolean isTranslated() {
        return translated;
    }

    public boolean isKeyDown() {
        return translateKeyDown;
    }

    private void setKeyDown(boolean isKeyDown) {
        translateKeyDown = isKeyDown;
    }

    /**
     * Start translating the currently hovered text.
     */
    public void start() {
        if (hoveredText == null || hoveredText.equals(translatedText)) {
            // Already translating or translated this exact stack instance
            return;
        }

        // Cancel any previous ongoing translation
        this.stop();

        translatedText = hoveredText;

        // Chat is translated without the rank and name in front of the message, and honours
        // the per-server Japanese override. Item names and the like go as they are.
        String text = translatedText;
        boolean chat = hoveredIsChat;
        int bodyStart = chat ? ChatText.bodyStart(text) : 0;
        String body = chat ? text.substring(bodyStart, ChatText.bodyEnd(text, bodyStart)) : text;
        String source = chat ? ChatAutoTranslator.sourceFor(body) : TPPConfig.getInstance().getSourceLanguage();
        String target = TPPConfig.getInstance().getTargetLanguage();
        String cacheKey = source + "|" + target + "|" + text;

        // Check cache first
        String cachedResult = translationCache.get(cacheKey);
        if (cachedResult != null) {
            translatedResult = I18n.get("misc.translatorpp.translation", cachedResult) + SUCCESS;
            translated = true;
            translationFuture = CompletableFuture.completedFuture(null); // Create a completed future
            return; // Skip API call
        }

        translatedResult = I18n.get("misc.translatorpp.translation.processing") + PROCESSING; // Initial placeholder
        translated = true; // Set translated flag immediately

        translationFuture = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        AzureTranslationProvider azure = AzureTranslationProvider.getInstance();
                        if (!chat) {
                            return azure.translate(body, source, target);
                        }
                        ChatText.Prepared prepared = ChatText.prepare(body);
                        return prepared.restore(ChatText.sanitize(azure.translate(prepared.shielded(), source, target)));
                    } catch (Exception e) {
                        throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                    }
                }, translationExecutor)
                .thenAcceptAsync(it -> {
                    // Update the result and cache it
                    translatedResult = it + SUCCESS;
                    translationCache.put(cacheKey, it); // Add to cache
                }, translationExecutor)
                .exceptionally(err -> {
                    TranslatorPP.LOGGER.error("Translation failed. Cause: {}", err.getCause());
                    translatedResult = I18n.get("misc.translatorpp.translation.failure") + ERROR;
                    reportError(err.getCause());
                    return null; // Indicate exception was handled
                });
    }

    /** Holding the key over several messages during an outage shouldn't fill chat with the same error. */
    private void reportError(Throwable err) {
        long now = System.currentTimeMillis();
        if (now - lastErrorAt >= ERROR_INTERVAL_MILLIS) {
            lastErrorAt = now;
            MessageUtl.threadSafeToLocal(createErrorMessage(err));
        }
    }

    private Component createErrorMessage(Throwable err) {
        Throwable cause = err instanceof RuntimeException && err.getCause() != null ? err.getCause() : err;
        MutableComponent content = err instanceof ServiceException se ?
                Component.literal(se.getMessage()) :
                Component.translatable("misc.translatorpp.translation.failure.chat", cause.toString());
        return content.withColor(TextColor.RED);
    }

    /**
     * Stop any ongoing translation and clear the translated state.
     */
    public void stop() {
        if (this.translated) {
            if (translationFuture != null && !translationFuture.isDone()) {
                translationFuture.cancel(true);
            }
            translated = false;
            translatedText = null;
            translatedResult = null;
            translationFuture = null;
        }
    }

    /**
     * Clear the translation cache.
     */
    public void clearCache() {
        this.translationCache.clear();
    }

    /**
     * Get the default style and split result lines.
     */
    public Pair<Style, String[]> getStyledResultLines() {
        Style appliedStyle = Style.EMPTY;
        String resultText = this.translatedResult;

        switch (resultText.substring(resultText.length() - 3)) {
            case PROCESSING -> appliedStyle = appliedStyle.withColor(TextColor.DARK_GRAY);
            case ERROR -> appliedStyle = appliedStyle.withColor(TextColor.RED);
            default -> appliedStyle = appliedStyle.withColor(TextColor.GRAY); // SUCCESS
        }

        String[] texts = resultText.substring(0, resultText.length() - 3).split(literalSeparator());
        return Pair.of(appliedStyle, texts);
    }

    /**
     * Add the translation result to the component list.
     */
    private void addResultToTooltip(List<Component> lines) {
        var styledResult = getStyledResultLines();
        Style appliedStyle = styledResult.getLeft();
        String[] texts = styledResult.getRight();

        switch (TPPConfig.getInstance().getMode()) {
            case NAME_ONLY -> {
                lines.add(1, Component.literal(texts[0]).withStyle(appliedStyle));
            }
            case NAME_TOP -> {
                lines.add(1, Component.literal(texts[0]).withStyle(appliedStyle));
                if (texts.length > 1) {
                    // 15 < ${max_length_of_lines} < 30
                    lines.add(Component.literal("-".repeat(Math.clamp(Arrays.stream(texts).map(String::length).flatMapToInt(IntStream::of).max().getAsInt(), 15, 30))).withColor(TextColor.DARK_GRAY));
                    for (int i = 1; i < texts.length; i++) {
                        lines.add(Component.literal(texts[i]).withStyle(appliedStyle));
                    }
                }
            }
            case ALL_IN_END -> {
                // 15 < ${max_length_of_lines} < 30
                lines.add(Component.literal("-".repeat(Math.clamp(Arrays.stream(texts).map(String::length).flatMapToInt(IntStream::of).max().getAsInt(), 15, 30))).withColor(TextColor.DARK_GRAY));
                for (String text : texts) {
                    lines.add(Component.literal(text).withStyle(appliedStyle));
                }
            }
            case LINE_BY_LINE -> {
                for (int i = 0; i < texts.length; i++) {
                    lines.add(i * 2 + 1, Component.literal(texts[i]).withStyle(appliedStyle));
                }
            }
            case REPLACE -> {
                // if the translation process is successful
                if (appliedStyle.getColor() == TextColor.GRAY) {
                    for (int i = 0; i < texts.length; i++) {
                        lines.set(i, Component.literal(texts[i]).withStyle(lines.get(i).getStyle()));
                    }
                } else {
                    for (int i = 0; i < texts.length; i++) {
                        lines.set(i, Component.literal(texts[i]).withStyle(appliedStyle));
                    }
                }
            }
        }
    }

    /**
     * Create a component with translation result for chat.
     */
    public Component createResultForChat() {
        var styledResult = getStyledResultLines();
        Style appliedStyle = styledResult.getLeft();
        String[] texts = styledResult.getRight();

        var component = Component.literal("").withStyle(appliedStyle);
        for (String text : texts) {
            component.append(text);
        }
        return component;
    }

    public static void init() {
        Runtime.getRuntime().addShutdownHook(new Thread(translationExecutor::shutdownNow));

        ItemTooltipCallbacks.EVENT.register((stack, context, flag, lines) -> {
            TranslationKit.getInstance().setHoveredText(lines);

            if (TranslationKit.getInstance().isTranslated() &&
                    TranslationKit.getInstance().getTranslatedResult() != null &&
                    TooltipUtl.getCombinedTooltipText(lines).equals(TranslationKit.getInstance().translatedText)) {
                TranslationKit.getInstance().addResultToTooltip(lines);
            }
        });

        ScreenCallbacks.KEY_PRESSED_POST.register((screen, context) -> {
            // While typing (e.g. a chat message) the translate key is just a letter.
            if (screen.getFocused() instanceof EditBox box && !box.getValue().isEmpty()) {
                return;
            }
            if (TPPKeyMappings.TRANSLATE_KEY.matches(context)) {
                TranslationKit.getInstance().start();
                TranslationKit.getInstance().setKeyDown(true);
            }
        });

        ScreenCallbacks.KEY_RELEASED_POST.register(((screen, context) -> {
            if (TPPKeyMappings.TRANSLATE_KEY.matches(context)) {
                TranslationKit.getInstance().stop();
                TranslationKit.getInstance().setKeyDown(false);
            }
        }));

        ScreenCallbacks.REMOVED.register(screen -> {
            TranslationKit.getInstance().stop();
            TranslationKit.getInstance().setKeyDown(false);
        });
    }

    public static String separator() {
        return AzureTranslationProvider.getInstance().separator();
    }

    public static String literalSeparator() {
        return Pattern.quote(separator());
    }
}
package net.psunset.translatorpp.yomitan;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.Slot;
import net.psunset.translatorpp.TranslatorPP;
import net.psunset.translatorpp.config.TPPConfig;
import net.psunset.translatorpp.core.YomitanSource;
import net.psunset.translatorpp.yomitan.local.LocalYomitan;
import net.psunset.translatorpp.api.ChatComponentAccessor;
import net.psunset.translatorpp.keybind.TPPKeyMappings;
import net.psunset.translatorpp.tool.TooltipUtl;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Yomitan lookups on any text in the game.
 * <ul>
 *     <li>With a screen open, holding the lookup key over any drawn text (titles,
 *     item names, books, chat, buttons, the HUD behind the screen) looks it up.</li>
 *     <li>Tooltips follow the cursor, so the pin key freezes the visible tooltip
 *     into a box that can be hovered instead.</li>
 *     <li>In the world, the pin key opens a screen: with the sign / text display /
 *     nametag under the crosshair pinned, or empty to free the cursor over the HUD.</li>
 * </ul>
 * Results come from the player's own Yomitan and show in a scrollable popup.
 */
public final class YomitanOverlay {
    /** Characters sent to Yomitan from the hovered one onwards; it finds the longest match itself. */
    private static final int SCAN_LENGTH = 20;
    private static final int MATCH_HIGHLIGHT = 0x6033AAFF;

    private static final Minecraft client = Minecraft.getInstance();

    /** Something under the cursor that can be looked up. */
    private interface Target {
        boolean sameAs(@Nullable Target other);

        String query();

        int anchorX();

        int anchorY();

        void highlight(GuiGraphicsExtractor graphics, Font font, int utf16Length);
    }

    private record PinnedTarget(PinnedText box, PinnedText.Hit hit) implements Target {
        @Override
        public boolean sameAs(@Nullable Target other) {
            return other instanceof PinnedTarget o && o.box == box && o.hit.line() == hit.line() && o.hit.index() == hit.index();
        }

        @Override
        public String query() {
            return box.queryAt(hit, SCAN_LENGTH);
        }

        @Override
        public int anchorX() {
            return hit.x();
        }

        @Override
        public int anchorY() {
            return hit.y() + client.font.lineHeight + 2;
        }

        @Override
        public void highlight(GuiGraphicsExtractor graphics, Font font, int utf16Length) {
            box.highlight(graphics, font, hit, utf16Length, MATCH_HIGHLIGHT);
        }
    }

    /** One drawn line of a text block, measured. */
    private record ScreenLine(TextCapture.Run run, LineGlyphs glyphs) {
    }

    /**
     * A character of text the screen or HUD drew last frame, plus the lines drawn
     * directly below it in the same block (a book page, wrapped lore), so a word
     * that wraps onto the next line is still found.
     */
    private record ScreenTarget(TextCapture.Run run, LineGlyphs glyphs, int index, List<ScreenLine> following) implements Target {
        @Override
        public boolean sameAs(@Nullable Target other) {
            return other instanceof ScreenTarget o && o.index == index && o.run.x() == run.x() && o.run.y() == run.y()
                    && o.run.pose().equals(run.pose()) && o.glyphs.text().equals(glyphs.text());
        }

        @Override
        public String query() {
            StringBuilder query = new StringBuilder(glyphs.text(index, SCAN_LENGTH));
            for (ScreenLine line : following) {
                int room = SCAN_LENGTH - query.codePointCount(0, query.length());
                if (room <= 0) {
                    break;
                }
                query.append(line.glyphs().text(0, room));
            }
            return query.toString();
        }

        @Override
        public int anchorX() {
            return Math.round(run.toScreen(glyphs.startX()[index], 0).x);
        }

        @Override
        public int anchorY() {
            return Math.round(run.toScreen(0, run.font().lineHeight).y) + 2;
        }

        @Override
        public void highlight(GuiGraphicsExtractor graphics, Font font, int utf16Length) {
            int remaining = utf16Length - highlightSpan(graphics, run, glyphs, index, utf16Length);
            for (ScreenLine line : following) {
                if (remaining <= 0) {
                    break;
                }
                remaining -= highlightSpan(graphics, line.run(), line.glyphs(), 0, remaining);
            }
        }

        /** Highlights up to {@code utf16Length} units of one line from {@code from}; returns the units it covered. */
        private static int highlightSpan(GuiGraphicsExtractor graphics, TextCapture.Run run, LineGlyphs glyphs, int from,
                                         int utf16Length) {
            Vector2f a = run.toScreen(glyphs.boundaryX(from), -1);
            Vector2f b = run.toScreen(glyphs.matchEndX(from, utf16Length), run.font().lineHeight - 1);
            graphics.fill(Math.round(Math.min(a.x, b.x)), Math.round(Math.min(a.y, b.y)),
                    Math.round(Math.max(a.x, b.x)), Math.round(Math.max(a.y, b.y)), MATCH_HIGHLIGHT);
            return glyphs.utf16LengthFrom(from);
        }
    }

    /**
     * A character of a chat line, located from vanilla's chat layout rather than
     * from captured text: Lunar draws chat with its own renderer, which the
     * capture never sees, but it keeps vanilla's line positions.
     */
    private record ChatTarget(LineGlyphs glyphs, int index, int row, double scale, int lineHeight) implements Target {
        private static final int CHAT_BOTTOM_MARGIN = 40;
        private static final int CHAT_LEFT_MARGIN = 4;

        private int screenX(int chatX) {
            return (int) Math.round((chatX + CHAT_LEFT_MARGIN) * scale);
        }

        private int rowBottom() {
            return (int) Math.round(client.getWindow().getGuiScaledHeight() - CHAT_BOTTOM_MARGIN - row * scale * lineHeight);
        }

        @Override
        public boolean sameAs(@Nullable Target other) {
            return other instanceof ChatTarget o && o.index == index && o.row == row && o.glyphs.text().equals(glyphs.text());
        }

        @Override
        public String query() {
            return glyphs.text(index, SCAN_LENGTH);
        }

        @Override
        public int anchorX() {
            return screenX(glyphs.startX()[index]);
        }

        @Override
        public int anchorY() {
            return rowBottom() + 2;
        }

        @Override
        public void highlight(GuiGraphicsExtractor graphics, Font font, int utf16Length) {
            int top = (int) Math.round(rowBottom() - scale * lineHeight);
            graphics.fill(screenX(glyphs.startX()[index]), top, screenX(glyphs.matchEndX(index, utf16Length)), rowBottom(), MATCH_HIGHLIGHT);
        }
    }

    private static @Nullable PinnedText pinned;
    private static @Nullable LookupPopup popup;
    private static @Nullable Target active;
    private static int activeMatchLength;
    private static int generation;
    private static boolean dragging;
    private static boolean selecting;
    private static double dragOffsetX;
    private static double dragOffsetY;
    private static int lastMouseX;
    private static int lastMouseY;
    private static long lastDiagnosticAt;
    private static long lastContinuationLogAt;
    private static long lastTargetAt;
    private static @Nullable Screen lastDescribedScreen;
    private static int framesOnScreen;

    private YomitanOverlay() {
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
            ScreenMouseEvents.allowMouseClick(screen).register(YomitanOverlay::allowMouseClick);
            ScreenMouseEvents.allowMouseDrag(screen).register(YomitanOverlay::allowMouseDrag);
            ScreenMouseEvents.afterMouseRelease(screen).register((s, click, consumed) -> {
                dragging = false;
                selecting = false;
                return consumed;
            });
            ScreenMouseEvents.allowMouseScroll(screen).register(YomitanOverlay::allowMouseScroll);
            ScreenKeyboardEvents.allowKeyPress(screen).register(YomitanOverlay::allowKeyPress);
            ScreenEvents.afterExtract(screen).register(YomitanOverlay::afterExtract);
            ScreenEvents.remove(screen).register(s -> clear());
        });
        ClientTickEvents.END_CLIENT_TICK.register(YomitanOverlay::tick);
    }

    /** Whether the cursor is over the pinned box or popup, so the screen beneath ignores it. */
    public static boolean isMouseOver(double mouseX, double mouseY) {
        return (pinned != null && pinned.contains(mouseX, mouseY)) || (popup != null && popup.contains(mouseX, mouseY));
    }

    private static Component title(Component source) {
        return Component.empty()
                .append(source.copy().withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("hold ").append(TPPKeyMappings.LOOKUP_KEY.getTranslatedKeyMessage())
                        .append(" and hover · drag here").withStyle(ChatFormatting.ITALIC));
    }

    static void pin(Component source, List<Component> lines, int anchorX, int anchorY) {
        clear();
        pinned = PinnedText.ofComponents(client.font, title(source), lines, anchorX, anchorY, screenWidth(), screenHeight());
    }

    private static void pinDrawn(Component source, List<FormattedCharSequence> lines, int anchorX, int anchorY) {
        clear();
        pinned = new PinnedText(client.font, title(source), lines, anchorX, anchorY, screenWidth(), screenHeight());
    }

    static void centerPinned() {
        if (pinned != null) {
            pinned.moveTo((screenWidth() - pinned.width()) / 2, (screenHeight() - pinned.height()) / 3,
                    screenWidth(), screenHeight());
        }
    }

    static void clear() {
        pinned = null;
        popup = null;
        active = null;
        activeMatchLength = 0;
        dragging = false;
        selecting = false;
        generation++;
    }

    /** The default binding is Shift + middle click, so plain middle click stays free for menus and creative. */
    private static boolean pinNeedsShift() {
        return TPPKeyMappings.PIN_KEY.matches(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_MIDDLE));
    }

    private static boolean isPinClick(MouseButtonEvent click) {
        return TPPKeyMappings.PIN_KEY.matchesMouse(click) && (!pinNeedsShift() || click.hasShiftDown());
    }

    /** Key mappings don't track presses while a screen is open, so read the bound key directly. */
    private static boolean isLookupHeld() {
        InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(TPPKeyMappings.LOOKUP_KEY);
        return key.getType() == InputConstants.Type.KEYBOARD && key.getValue() != InputConstants.UNKNOWN.getValue()
                && InputConstants.isKeyDown(key.getValue());
    }

    /** Freezes the tooltip drawn last frame, in place; falls back to the hovered slot's item. */
    private static boolean tryPin(Screen screen, double mouseX, double mouseY) {
        List<TextCapture.Run> tooltip = new ArrayList<>();
        for (TextCapture.Run run : TextCapture.lastFrame()) {
            if (run.tooltip()) {
                tooltip.add(run);
            }
        }
        if (!tooltip.isEmpty()) {
            tooltip.sort(Comparator.comparingDouble((TextCapture.Run run) -> run.toScreen(0, 0).y)
                    .thenComparingDouble(run -> run.toScreen(0, 0).x));
            Vector2f topLeft = tooltip.getFirst().toScreen(0, 0);
            List<FormattedCharSequence> lines = tooltip.stream().map(TextCapture.Run::text).toList();
            pinDrawn(Component.literal("Tooltip"), lines, Math.round(topLeft.x) - PinnedText.PADDING,
                    Math.round(topLeft.y) - PinnedText.PADDING - PinnedText.HEADER_HEIGHT);
            return true;
        }
        if (screen instanceof AbstractContainerScreen<?> container) {
            Slot slot = container.hoveredSlot;
            if (slot != null && slot.hasItem()) {
                pin(Component.literal("Item"), TooltipUtl.getTooltipComponents(slot.getItem(), client),
                        (int) mouseX + 12, (int) mouseY - 12);
                return true;
            }
        }
        return false;
    }

    private static boolean allowMouseClick(Screen screen, MouseButtonEvent click) {
        double mouseX = click.x();
        double mouseY = click.y();
        boolean closedPopup = false;
        if (popup != null) {
            if (popup.contains(mouseX, mouseY)) {
                return false;
            }
            // Any click outside the popup closes it, including one on the pinned box.
            popup = null;
            active = null;
            closedPopup = true;
        }
        if (pinned != null && pinned.contains(mouseX, mouseY)) {
            if (isPinClick(click) && !(screen instanceof LookupScreen)) {
                clear();
            } else if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && pinned.onHeader(mouseX, mouseY)) {
                dragging = true;
                dragOffsetX = mouseX - pinned.x();
                dragOffsetY = mouseY - pinned.y();
            } else if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && pinned.onText(mouseX, mouseY)) {
                selecting = true;
                pinned.startSelection(client.font, mouseX, mouseY);
            }
            return false;
        }
        if (isPinClick(click)) {
            if (screen instanceof LookupScreen) {
                screen.onClose();
                return false;
            }
            if (tryPin(screen, mouseX, mouseY)) {
                return false;
            }
        }
        if (screen instanceof LookupScreen && click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            // A left click on empty space hands the cursor back to the camera; if it
            // only dismissed the popup, stay so the next word can be looked up.
            if (!closedPopup) {
                screen.onClose();
            }
            return false;
        }
        return true;
    }

    private static boolean allowMouseDrag(Screen screen, MouseButtonEvent click, double dragX, double dragY) {
        if (selecting && pinned != null) {
            pinned.extendSelection(client.font, click.x(), click.y());
            return false;
        }
        if (!dragging || pinned == null) {
            return true;
        }
        pinned.moveTo((int) (click.x() - dragOffsetX), (int) (click.y() - dragOffsetY), screenWidth(), screenHeight());
        popup = null;
        active = null;
        return false;
    }

    private static boolean allowMouseScroll(Screen screen, double mouseX, double mouseY, double horizontal, double vertical) {
        if (popup != null && popup.contains(mouseX, mouseY)) {
            popup.scroll(vertical);
            return false;
        }
        return pinned == null || !pinned.contains(mouseX, mouseY);
    }

    private static boolean allowKeyPress(Screen screen, KeyEvent key) {
        if (key.isCopy() && pinned != null && pinned.hasSelection()) {
            client.keyboardHandler.setClipboard(pinned.selectedText());
            client.gui.hud.setOverlayMessage(Component.literal("Copied to clipboard"), false);
            return false;
        }
        if (key.isEscape() && !(screen instanceof LookupScreen)) {
            if (popup != null) {
                popup = null;
                active = null;
                return false;
            }
            if (pinned != null) {
                clear();
                return false;
            }
        }
        if (screen instanceof LookupScreen && TPPKeyMappings.FREE_CURSOR_KEY.matches(key)) {
            screen.onClose();
            return false;
        }
        if (TPPKeyMappings.PIN_KEY.matches(key)) {
            if (screen instanceof LookupScreen) {
                screen.onClose();
                return false;
            }
            if (pinned != null && pinned.contains(lastMouseX, lastMouseY)) {
                clear();
                return false;
            }
            return !tryPin(screen, lastMouseX, lastMouseY);
        }
        return true;
    }

    private static void afterExtract(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        Font font = client.font;
        if (screen != lastDescribedScreen) {
            lastDescribedScreen = screen;
            framesOnScreen = 0;
        } else if (++framesOnScreen == 10) {
            // A few frames in, so the capture holds this screen's text.
            DebugLog.write("opened " + screen.getClass().getName() + " (title '" + screen.getTitle().getString()
                    + "'); captured: " + TextCapture.describe());
        }

        if (isLookupHeld() && !(popup != null && popup.contains(mouseX, mouseY))) {
            Target target = targetAt(font, mouseX, mouseY);
            if (target != null) {
                lastTargetAt = System.currentTimeMillis();
                if (!target.sameAs(active)) {
                    lookUp(target);
                }
            } else {
                logNoTarget(screen, mouseX, mouseY);
                // Holding the key over nothing (empty space, a stack count) hides the last
                // popup, so it doesn't look like it belongs to what's under the cursor now.
                // The short grace lets the cursor cross the gap into the popup.
                if (popup != null && System.currentTimeMillis() - lastTargetAt > 250) {
                    popup = null;
                    active = null;
                    activeMatchLength = 0;
                    generation++;
                }
            }
        }

        if (pinned == null && popup == null && active == null) {
            return;
        }
        TextCapture.runUncaptured(() -> {
            graphics.nextStratum();
            if (pinned != null) {
                pinned.extract(graphics, font);
            }
            if (active != null && activeMatchLength > 0) {
                active.highlight(graphics, font, activeMatchLength);
            }
            if (popup != null) {
                graphics.nextStratum();
                popup.extract(graphics, font);
            }
        });
    }

    /** The pinned box wins; otherwise the topmost text drawn under the cursor last frame. */
    private static @Nullable Target targetAt(Font font, int mouseX, int mouseY) {
        if (pinned != null && pinned.contains(mouseX, mouseY)) {
            PinnedText.Hit hit = pinned.hitTest(font, mouseX, mouseY);
            return hit == null ? null : new PinnedTarget(pinned, hit);
        }
        List<TextCapture.Run> runs = TextCapture.lastFrame();
        for (int i = runs.size() - 1; i >= 0; i--) {
            TextCapture.Run run = runs.get(i);
            if (run.tooltip() || (run.scissor() != null && !run.scissor().containsPoint(mouseX, mouseY))) {
                continue;
            }
            Vector2f local = run.toLocal(mouseX + 0.5, mouseY + 0.5);
            if (local == null || local.y < 0 || local.y >= run.font().lineHeight) {
                continue;
            }
            LineGlyphs glyphs = LineGlyphs.measure(run.font(), run.text());
            if (glyphs.text().chars().allMatch(c -> c >= '0' && c <= '9')) {
                continue; // stack counts and other bare numbers: never worth a lookup
            }
            int index = glyphs.indexAt((int) Math.floor(local.x));
            if (index >= 0) {
                return new ScreenTarget(run, glyphs, index,
                        linesBelow(runs, run, glyphs.codePoints().length - index));
            }
        }
        if (client.gui.screen() instanceof ChatScreen) {
            return chatTargetAt(font, mouseX, mouseY);
        }
        return null;
    }

    /**
     * The lines drawn under {@code first}, each the nearest line starting just
     * below the previous one and left-aligned with it. Screens space their lines
     * differently (books aren't one font line apart), so this allows anything
     * from just over half a line to nearly two lines lower. Stops once the query is full.
     */
    private static List<ScreenLine> linesBelow(List<TextCapture.Run> runs, TextCapture.Run first, int charsSoFar) {
        List<ScreenLine> lines = new ArrayList<>();
        TextCapture.Run current = first;
        int chars = charsSoFar;
        while (chars < SCAN_LENGTH && lines.size() < 4) {
            Vector2f origin = current.toScreen(0, 0);
            float lineStep = origin.distance(current.toScreen(0, current.font().lineHeight));
            TextCapture.Run next = null;
            float bestDy = Float.MAX_VALUE;
            for (TextCapture.Run candidate : runs) {
                if (candidate == current || candidate.tooltip()) {
                    continue;
                }
                Vector2f start = candidate.toScreen(0, 0);
                float dx = Math.abs(start.x - origin.x);
                float dy = start.y - origin.y;
                if (dx <= 3 && dy > lineStep * 0.6F && dy < lineStep * 1.9F && dy < bestDy) {
                    next = candidate;
                    bestDy = dy;
                }
            }
            if (next == null) {
                if (lines.isEmpty()) {
                    logNoContinuation(runs, current, origin, lineStep);
                }
                break;
            }
            LineGlyphs glyphs = LineGlyphs.measure(next.font(), next.text());
            lines.add(new ScreenLine(next, glyphs));
            chars += glyphs.codePoints().length;
            current = next;
        }
        return lines;
    }

    /** When no next line is found, log the closest lines below, at most every few seconds. */
    private static void logNoContinuation(List<TextCapture.Run> runs, TextCapture.Run current, Vector2f origin, float lineStep) {
        long now = System.currentTimeMillis();
        if (now - lastContinuationLogAt < 3000) {
            return;
        }
        lastContinuationLogAt = now;
        StringBuilder below = new StringBuilder();
        runs.stream()
                .filter(run -> run != current && !run.tooltip() && run.toScreen(0, 0).y > origin.y)
                .sorted(Comparator.comparingDouble(run -> run.toScreen(0, 0).distanceSquared(origin)))
                .limit(3)
                .forEach(run -> below.append("'").append(LineGlyphs.measure(run.font(), run.text()).text(0, 12))
                        .append("' at ").append(run.toScreen(0, 0)).append("; "));
        DebugLog.write("no next line under '" + LineGlyphs.measure(current.font(), current.text()).text(0, 12) + "' at "
                + origin + " (line step " + lineStep + "); nearest below: " + (below.isEmpty() ? "none" : below));
    }

    private static @Nullable Target chatTargetAt(Font font, int mouseX, int mouseY) {
        ChatComponentAccessor chat = (ChatComponentAccessor) client.gui.hud.getChat();
        FormattedCharSequence line = chat.translatorpp$getLineContentAt(mouseX, mouseY);
        if (line == null) {
            return null;
        }
        double scale = chat.translatorpp$getChatScale();
        int lineHeight = chat.translatorpp$getChatLineHeight();
        LineGlyphs glyphs = LineGlyphs.measure(font, line);
        int index = glyphs.indexAt((int) Math.floor(chat.translatorpp$screenToChatX(mouseX)));
        if (index < 0) {
            return null;
        }
        int row = (int) Math.floor(chat.translatorpp$screenToChatY(mouseY));
        return new ChatTarget(glyphs, index, row, scale, lineHeight);
    }

    /** When the lookup key finds nothing, say what was captured, at most every few seconds. */
    private static void logNoTarget(Screen screen, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        if (now - lastDiagnosticAt < 3000) {
            return;
        }
        lastDiagnosticAt = now;
        String nearest = TextCapture.lastFrame().stream()
                .filter(run -> !run.tooltip())
                .min(Comparator.comparingDouble(run -> run.toScreen(0, 0).distanceSquared(mouseX, mouseY)))
                .map(run -> "'" + LineGlyphs.measure(run.font(), run.text()).text(0, 30) + "' at " + run.toScreen(0, 0))
                .orElse("none");
        DebugLog.write("nothing under cursor (" + mouseX + ", " + mouseY + ") on " + screen.getClass().getName()
                + "; captured: " + TextCapture.describe() + "; nearest run: " + nearest);
    }

    private static CompletableFuture<List<TermEntry>> lookup(String query) {
        return TPPConfig.getInstance().getYomitanSource() == YomitanSource.BUILT_IN
                ? LocalYomitan.get().termEntries(query)
                : YomitanClient.termEntries(query);
    }

    private static String problemMessage(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof LookupProblem) {
            return cause.getMessage();
        }
        return TPPConfig.getInstance().getYomitanSource() == YomitanSource.BUILT_IN
                ? "The dictionary lookup failed. See the game log."
                : "Couldn't reach Yomitan at " + YomitanClient.BASE + ". Is your browser open, with "
                + "\"Enable Yomitan API\" on in Yomitan's settings?";
    }

    private static void lookUp(Target target) {
        active = target;
        activeMatchLength = 0;
        int request = ++generation;
        String query = target.query();
        DebugLog.write("looking up '" + query + "' (" + target.getClass().getSimpleName() + ")");
        lookup(query).whenComplete((entries, error) -> client.execute(() -> {
            DebugLog.write("'" + query + "' -> " + (error != null ? error.toString()
                    : entries.size() + " entries" + (request != generation ? " (stale, dropped)" : "")));
            if (request != generation) {
                return; // the cursor moved on, or the box was closed
            }
            if (error != null) {
                TranslatorPP.LOGGER.warn("Yomitan lookup for '{}' failed: {}", query, error.toString());
                popup = LookupPopup.message(client.font, Component.literal(problemMessage(error))
                        .withStyle(ChatFormatting.RED), target.anchorX(), target.anchorY(), screenWidth(), screenHeight());
            } else if (entries.isEmpty()) {
                popup = null;
            } else {
                activeMatchLength = entries.stream().mapToInt(TermEntry::matchLength).max().orElse(0);
                popup = LookupPopup.results(client.font, entries, target.anchorX(), target.anchorY(), screenWidth(), screenHeight());
            }
        }));
    }

    private static void tick(Minecraft minecraft) {
        while (TPPKeyMappings.DICTIONARIES_KEY.consumeClick()) {
            if (minecraft.gui.screen() == null) {
                minecraft.setScreenAndShow(new DictionaryScreen(null));
            }
        }
        while (TPPKeyMappings.FREE_CURSOR_KEY.consumeClick()) {
            if (minecraft.gui.screen() == null && minecraft.player != null) {
                minecraft.setScreenAndShow(new LookupScreen());
            }
        }
        while (TPPKeyMappings.PIN_KEY.consumeClick()) {
            if (minecraft.gui.screen() != null || minecraft.player == null || (pinNeedsShift() && !minecraft.hasShiftDown())) {
                continue;
            }
            WorldTextPicker.Picked picked = WorldTextPicker.pick(minecraft);
            minecraft.setScreenAndShow(picked == null ? new LookupScreen() : new LookupScreen(picked.source(), picked.lines()));
        }
    }

    private static int screenWidth() {
        return client.getWindow().getGuiScaledWidth();
    }

    private static int screenHeight() {
        return client.getWindow().getGuiScaledHeight();
    }
}

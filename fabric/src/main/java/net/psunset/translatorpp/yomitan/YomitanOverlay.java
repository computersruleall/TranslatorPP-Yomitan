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
import net.minecraft.world.inventory.Slot;
import net.psunset.translatorpp.TranslatorPP;
import net.psunset.translatorpp.api.ChatComponentAccessor;
import net.psunset.translatorpp.keybind.TPPKeyMappings;
import net.psunset.translatorpp.tool.TooltipUtl;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Pin-and-scan dictionary lookups. The pin key freezes some text into a box
 * (the hovered item's tooltip, the hovered chat line, or text in the world);
 * holding Shift over a character of that box asks the player's Yomitan for
 * that point and shows every result in a scrollable popup.
 */
public final class YomitanOverlay {
    /** Characters sent to Yomitan from the hovered one onwards; it finds the longest match itself. */
    private static final int SCAN_LENGTH = 20;
    private static final int MATCH_HIGHLIGHT = 0x6033AAFF;

    private static final Minecraft client = Minecraft.getInstance();

    private static @Nullable PinnedText pinned;
    private static @Nullable LookupPopup popup;
    private static PinnedText.@Nullable Hit activeHit;
    private static int activeMatchLength;
    private static int generation;
    private static boolean dragging;
    private static double dragOffsetX;
    private static double dragOffsetY;
    private static int lastMouseX;
    private static int lastMouseY;

    private YomitanOverlay() {
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
            ScreenMouseEvents.allowMouseClick(screen).register(YomitanOverlay::allowMouseClick);
            ScreenMouseEvents.allowMouseDrag(screen).register(YomitanOverlay::allowMouseDrag);
            ScreenMouseEvents.afterMouseRelease(screen).register((s, click, consumed) -> {
                dragging = false;
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

    static void pin(Component source, List<Component> lines, int anchorX, int anchorY) {
        Font font = client.font;
        Component title = Component.empty()
                .append(source.copy().withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("hold ").append(TPPKeyMappings.LOOKUP_KEY.getTranslatedKeyMessage())
                        .append(" and hover to look up · drag here").withStyle(ChatFormatting.ITALIC));
        clear();
        pinned = new PinnedText(font, title, lines, anchorX, anchorY, screenWidth(), screenHeight());
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
        activeHit = null;
        activeMatchLength = 0;
        dragging = false;
        generation++;
    }

    /** The default binding is Shift + middle click, so plain middle click stays free for menus and creative. */
    private static boolean pinNeedsShift() {
        return TPPKeyMappings.PIN_KEY.matches(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_MIDDLE));
    }

    /** Key mappings don't track presses while a screen is open, so read the bound key directly. */
    private static boolean isLookupHeld() {
        InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(TPPKeyMappings.LOOKUP_KEY);
        return key.getType() == InputConstants.Type.KEYBOARD && key.getValue() != InputConstants.UNKNOWN.getValue()
                && InputConstants.isKeyDown(key.getValue());
    }

    private static boolean isPinClick(MouseButtonEvent click) {
        return TPPKeyMappings.PIN_KEY.matchesMouse(click) && (!pinNeedsShift() || click.hasShiftDown());
    }

    private static boolean tryPin(Screen screen, double mouseX, double mouseY) {
        int anchorX = (int) mouseX + 12;
        int anchorY = (int) mouseY - 12;
        if (screen instanceof AbstractContainerScreen<?> container) {
            Slot slot = container.hoveredSlot;
            if (slot != null && slot.hasItem()) {
                pin(Component.literal("Item"), TooltipUtl.getTooltipComponents(slot.getItem(), client), anchorX, anchorY);
                return true;
            }
        }
        if (screen instanceof ChatScreen) {
            String message = ((ChatComponentAccessor) client.gui.hud.getChat()).translatorpp$getMessageContentAt(mouseX, mouseY);
            if (message != null && !message.isBlank()) {
                List<Component> lines = Arrays.stream(message.split("\n")).map(line -> (Component) Component.literal(line)).toList();
                pin(Component.literal("Chat"), lines, anchorX, anchorY - 40);
                return true;
            }
        }
        return false;
    }

    private static boolean allowMouseClick(Screen screen, MouseButtonEvent click) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (popup != null && popup.contains(mouseX, mouseY)) {
            return false;
        }
        if (pinned != null && pinned.contains(mouseX, mouseY)) {
            if (isPinClick(click) && !(screen instanceof LookupScreen)) {
                clear();
            } else if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && pinned.onHeader(mouseX, mouseY)) {
                dragging = true;
                dragOffsetX = mouseX - pinned.x();
                dragOffsetY = mouseY - pinned.y();
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
        if (popup != null) {
            popup = null; // clicking elsewhere dismisses the popup, like Yomitan
            activeHit = null;
        }
        return true;
    }

    private static boolean allowMouseDrag(Screen screen, MouseButtonEvent click, double dragX, double dragY) {
        if (!dragging || pinned == null) {
            return true;
        }
        pinned.moveTo((int) (click.x() - dragOffsetX), (int) (click.y() - dragOffsetY), screenWidth(), screenHeight());
        popup = null;
        activeHit = null;
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
        if (key.isEscape() && !(screen instanceof LookupScreen)) {
            if (popup != null) {
                popup = null;
                activeHit = null;
                return false;
            }
            if (pinned != null) {
                clear();
                return false;
            }
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
        if (pinned == null) {
            return;
        }
        Font font = client.font;
        graphics.nextStratum();

        PinnedText.Hit hit = pinned.hitTest(font, mouseX, mouseY);
        boolean overPopup = popup != null && popup.contains(mouseX, mouseY);
        if (isLookupHeld() && hit != null && !overPopup && !sameCharacter(hit, activeHit)) {
            lookUp(hit);
        }

        pinned.extract(graphics, font);
        if (activeHit != null && activeMatchLength > 0) {
            int endX = pinned.matchEndX(activeHit, activeMatchLength);
            graphics.fill(activeHit.x(), activeHit.y() - 1, endX, activeHit.y() + font.lineHeight - 1, MATCH_HIGHLIGHT);
        }
        if (popup != null) {
            graphics.nextStratum();
            popup.extract(graphics, font);
        }
    }

    private static boolean sameCharacter(PinnedText.Hit a, PinnedText.@Nullable Hit b) {
        return b != null && a.line() == b.line() && a.index() == b.index() && a.x() == b.x() && a.y() == b.y();
    }

    private static void lookUp(PinnedText.Hit hit) {
        PinnedText box = pinned;
        if (box == null) {
            return;
        }
        activeHit = hit;
        activeMatchLength = 0;
        int request = ++generation;
        String query = box.queryAt(hit, SCAN_LENGTH);
        YomitanClient.termEntries(query).whenComplete((entries, error) -> client.execute(() -> {
            if (request != generation || pinned != box) {
                return; // the cursor moved on, or the box was closed
            }
            int anchorX = hit.x();
            int anchorY = hit.y() + client.font.lineHeight + 2;
            if (error != null) {
                TranslatorPP.LOGGER.warn("Yomitan lookup for '{}' failed: {}", query, error.toString());
                popup = LookupPopup.message(client.font, Component.literal(
                                "Couldn't reach Yomitan at " + YomitanClient.BASE + ". Is Chrome open, with "
                                        + "\"Enable Yomitan API\" on in Yomitan's settings?")
                        .withStyle(ChatFormatting.RED), anchorX, anchorY, screenWidth(), screenHeight());
            } else if (entries.isEmpty()) {
                popup = null;
            } else {
                activeMatchLength = entries.stream().mapToInt(TermEntry::matchLength).max().orElse(0);
                popup = LookupPopup.results(client.font, entries, anchorX, anchorY, screenWidth(), screenHeight());
            }
        }));
    }

    private static void tick(Minecraft minecraft) {
        while (TPPKeyMappings.PIN_KEY.consumeClick()) {
            if (minecraft.gui.screen() != null || minecraft.player == null || (pinNeedsShift() && !minecraft.hasShiftDown())) {
                continue;
            }
            WorldTextPicker.Picked picked = WorldTextPicker.pick(minecraft);
            if (picked == null) {
                minecraft.gui.hud.setOverlayMessage(Component.literal("No text under the crosshair"), false);
            } else {
                minecraft.setScreenAndShow(new LookupScreen(picked.source(), picked.lines()));
            }
        }
    }

    private static int screenWidth() {
        return client.getWindow().getGuiScaledWidth();
    }

    private static int screenHeight() {
        return client.getWindow().getGuiScaledHeight();
    }
}

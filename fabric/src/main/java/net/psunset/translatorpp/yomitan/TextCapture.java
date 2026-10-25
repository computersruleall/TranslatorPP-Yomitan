package net.psunset.translatorpp.yomitan;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.List;

/**
 * Records every piece of text the GUI drew in the last frame, with where it
 * was drawn. All GUI text (screens, HUD, tooltips, books, chat) becomes a
 * {@code GuiTextRenderState}, so this is what lets a lookup work on any text
 * without knowing which screen or widget drew it.
 */
public final class TextCapture {
    /** A frame with more text than this is unusual; stop recording rather than grow without bound. */
    private static final int MAX_RUNS = 6000;

    /**
     * One drawn line of text.
     *
     * @param pose    the GUI transform it was drawn with (scaled titles, rotated text)
     * @param tooltip whether it was part of a tooltip, which follows the cursor and so must be pinned to read
     */
    public record Run(Font font, FormattedCharSequence text, Matrix3x2f pose, int x, int y,
                      @Nullable ScreenRectangle scissor, boolean tooltip) {

        /** The cursor in this run's own coordinates, or null if the transform can't be inverted. */
        @Nullable Vector2f toLocal(double mouseX, double mouseY) {
            Matrix3x2f inverse = new Matrix3x2f(pose);
            if (Math.abs(inverse.determinant()) < 1.0E-6F) {
                return null;
            }
            inverse.invert();
            Vector2f local = inverse.transformPosition((float) mouseX, (float) mouseY, new Vector2f());
            return local.sub(x, y);
        }

        Vector2f toScreen(float localX, float localY) {
            return pose.transformPosition(x + localX, y + localY, new Vector2f());
        }
    }

    private static List<Run> building = new ArrayList<>();
    private static List<Run> lastFrame = List.of();
    private static int suppressed;
    private static int tooltipDepth;

    private TextCapture() {
    }

    public static void onText(GuiTextRenderState state) {
        if (suppressed > 0 || building.size() >= MAX_RUNS) {
            return;
        }
        building.add(new Run(state.font, state.text, new Matrix3x2f(state.pose), state.x, state.y,
                state.scissor, tooltipDepth > 0));
    }

    /** Called once per frame, after the GUI is drawn. */
    public static void endFrame() {
        lastFrame = building;
        building = new ArrayList<>(Math.max(64, lastFrame.size()));
        tooltipDepth = 0;
    }

    public static void beginTooltip() {
        tooltipDepth++;
    }

    public static void endTooltip() {
        tooltipDepth = Math.max(0, tooltipDepth - 1);
    }

    /** Our own box and popup are drawn inside this, so they don't end up in the capture. */
    static void runUncaptured(Runnable draw) {
        suppressed++;
        try {
            draw.run();
        } finally {
            suppressed--;
        }
    }

    static List<Run> lastFrame() {
        return lastFrame;
    }

    /** For the log: how much text the last frame drew. */
    static String describe() {
        return lastFrame.size() + " lines (" + lastFrame.stream().filter(Run::tooltip).count() + " in tooltips)";
    }
}

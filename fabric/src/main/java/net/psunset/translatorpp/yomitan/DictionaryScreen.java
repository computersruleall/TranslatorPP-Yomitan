package net.psunset.translatorpp.yomitan;

import com.mojang.blaze3d.Blaze3D;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.psunset.translatorpp.config.TPPConfig;
import net.psunset.translatorpp.core.YomitanSource;
import net.psunset.translatorpp.yomitan.local.DictionaryInfoView;
import net.psunset.translatorpp.yomitan.local.LocalYomitan;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Manages the built-in engine's dictionaries: import by dropping zips onto the
 * window or from the import folder, reorder, turn on and off, remove. Order sets
 * which dictionary's entries come first when several match equally well.
 */
public final class DictionaryScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int BUTTON = 20;
    private static final int LIST_TOP = 34;

    private final @Nullable Screen parent;
    private final LocalYomitan engine = LocalYomitan.get();
    private List<DictionaryInfoView> shown = List.of();
    private int scroll;
    private @Nullable String confirmingRemoval;

    public DictionaryScreen(@Nullable Screen parent) {
        super(Component.literal("Yomitan Dictionaries"));
        this.parent = parent;
    }

    private int rowsThatFit() {
        return Math.max(1, (height - LIST_TOP - 62) / ROW_HEIGHT);
    }

    @Override
    protected void init() {
        shown = DictionaryInfoView.of(engine);
        scroll = Math.max(0, Math.min(scroll, shown.size() - rowsThatFit()));
        int contentWidth = Math.min(width - 16, 460);
        int left = (width - contentWidth) / 2;
        int right = left + contentWidth;

        int rows = Math.min(shown.size() - scroll, rowsThatFit());
        for (int i = 0; i < rows; i++) {
            int index = scroll + i;
            DictionaryInfoView info = shown.get(index);
            int y = LIST_TOP + i * ROW_HEIGHT;
            int x = right;

            boolean confirming = info.id().equals(confirmingRemoval);
            x -= confirming ? 74 : 54;
            addRenderableWidget(Button.builder(Component.literal(confirming ? "Confirm?" : "Remove").withStyle(confirming ? ChatFormatting.RED : ChatFormatting.WHITE), button -> {
                if (confirming) {
                    confirmingRemoval = null;
                    engine.remove(info.id());
                } else {
                    confirmingRemoval = info.id();
                }
                rebuildWidgets();
            }).bounds(x, y, confirming ? 74 : 54, BUTTON).build());

            x -= 34;
            addRenderableWidget(Button.builder(Component.literal(info.enabled() ? "On" : "Off").withStyle(info.enabled() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                    button -> engine.setEnabled(info.id(), !info.enabled())).bounds(x, y, 32, BUTTON).build());

            x -= BUTTON + 2;
            Button down = addRenderableWidget(Button.builder(Component.literal("▼"), button -> engine.move(info.id(), 1))
                    .bounds(x, y, BUTTON, BUTTON).build());
            down.active = index < shown.size() - 1;

            x -= BUTTON;
            Button up = addRenderableWidget(Button.builder(Component.literal("▲"), button -> engine.move(info.id(), -1))
                    .bounds(x, y, BUTTON, BUTTON).build());
            up.active = index > 0;
        }

        int bottom = height - 26;
        int third = (contentWidth - 8) / 3;
        addRenderableWidget(Button.builder(Component.literal("Import from Folder"), button -> engine.importFromFolder())
                .bounds(left, bottom, third, BUTTON).build());
        addRenderableWidget(Button.builder(Component.literal("Open Import Folder"), button -> openImportFolder())
                .bounds(left + third + 4, bottom, third, BUTTON).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(left + (third + 4) * 2, bottom, third, BUTTON).build());
    }

    private void openImportFolder() {
        Path folder = engine.importFolder();
        try {
            Files.createDirectories(folder);
            Blaze3D.openPath(folder);
        } catch (IOException | RuntimeException e) {
            net.psunset.translatorpp.TranslatorPP.LOGGER.warn("Couldn't open {}: {}", folder, e.toString());
        }
    }

    @Override
    public void tick() {
        // The engine changes the list from its own thread; redraw when it does.
        List<DictionaryInfoView> current = DictionaryInfoView.of(engine);
        if (!current.equals(shown)) {
            rebuildWidgets();
        }
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        List<Path> zips = files.stream().filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")).toList();
        if (!zips.isEmpty()) {
            engine.importZips(zips);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int max = Math.max(0, shown.size() - rowsThatFit());
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(vertical)));
        if (next != scroll) {
            scroll = next;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        boolean builtIn = TPPConfig.getInstance().getYomitanSource() == YomitanSource.BUILT_IN;
        graphics.centeredText(font, Component.literal(builtIn
                        ? "Drop dictionary .zip files onto this window. Top of the list comes first."
                        : "Lookups currently use Yomitan in your browser. Change Lookup Source in the settings to use these.")
                .withStyle(builtIn ? ChatFormatting.GRAY : ChatFormatting.YELLOW), width / 2, 20, 0xFFFFFFFF);

        int contentWidth = Math.min(width - 16, 460);
        int left = (width - contentWidth) / 2;
        int textWidth = contentWidth - 186;
        int rows = Math.min(shown.size() - scroll, rowsThatFit());
        for (int i = 0; i < rows; i++) {
            DictionaryInfoView info = shown.get(scroll + i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            int color = info.enabled() ? 0xFFFFFFFF : 0xFF808080;
            graphics.text(font, font.plainSubstrByWidth(info.title(), textWidth), left, y + 1, color, true);
            graphics.text(font, font.plainSubstrByWidth(info.details(), textWidth), left, y + 11, 0xFF9A9A9A, false);
        }
        if (shown.isEmpty()) {
            graphics.centeredText(font, Component.literal("No dictionaries yet.").withStyle(ChatFormatting.GRAY), width / 2, LIST_TOP + 10, 0xFFFFFFFF);
        }
        graphics.centeredText(font, Component.literal(engine.status()), width / 2, height - 40, 0xFFE0E0A0);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

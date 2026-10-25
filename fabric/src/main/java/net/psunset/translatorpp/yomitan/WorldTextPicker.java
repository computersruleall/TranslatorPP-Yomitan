package net.psunset.translatorpp.yomitan;

import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the text under the crosshair: a sign, a text display, or a nametag.
 * Text displays have no hitbox, so each candidate gets a rectangle sized like
 * its rendered text and the view ray is tested against that. Text the game
 * would hide behind a block is skipped, text it draws through walls is not.
 */
final class WorldTextPicker {
    private static final double REACH = 48;
    /** World units per text pixel, as used by the text display and nametag renderers. */
    private static final double PIXEL = 0.025;

    record Picked(Component source, List<Component> lines) {
    }

    /** Some world text: where it is drawn and how big its rendered rectangle is, in blocks. */
    private record Candidate(Component source, List<Component> lines, Vec3 center, double width, double height) {
    }

    private record Hit(Candidate candidate, double distance) {
    }

    private WorldTextPicker() {
    }

    static @Nullable Picked pick(Minecraft client) {
        Player player = client.player;
        if (player == null || client.level == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 up = right.cross(look).normalize();

        List<Hit> hits = new ArrayList<>();
        AABB searchBox = new AABB(eye, eye.add(look.scale(REACH))).inflate(3);
        for (Entity entity : client.level.getEntities(player, searchBox, e -> true)) {
            Candidate candidate = textOf(client.font, entity, player);
            if (candidate == null) {
                continue;
            }
            double distance = rayHit(eye, look, right, up, candidate);
            if (distance >= 0 && (seesThroughWalls(entity) || visible(client, player, eye, candidate.center()))) {
                hits.add(new Hit(candidate, distance));
            }
        }

        HitResult blockHit = client.hitResult;
        if (blockHit instanceof BlockHitResult hit && blockHit.getType() == HitResult.Type.BLOCK
                && client.level.getBlockEntity(hit.getBlockPos()) instanceof SignBlockEntity sign) {
            List<Component> lines = sign.getText(sign.getSlotPlayerIsFacing(player)).getMessages(false).stream()
                    .filter(line -> !line.getString().isBlank())
                    .toList();
            if (!lines.isEmpty()) {
                hits.add(new Hit(new Candidate(Component.literal("Sign"), lines, hit.getLocation(), 1, 0.5),
                        hit.getLocation().distanceTo(eye)));
            }
        }

        Hit best = hits.stream().min(Comparator.comparingDouble(Hit::distance)).orElse(null);
        if (best == null) {
            return null;
        }
        return new Picked(best.candidate().source(), stackedWith(best.candidate(), hits));
    }

    /**
     * Holograms are usually one entity per line, stacked straight up. Taking the
     * whole column lets the box show the complete hologram, top line first.
     */
    private static List<Component> stackedWith(Candidate best, List<Hit> hits) {
        List<Candidate> column = new ArrayList<>();
        for (Hit hit : hits) {
            Candidate other = hit.candidate();
            Vec3 offset = other.center().subtract(best.center());
            if (Math.abs(offset.x) < 0.15 && Math.abs(offset.z) < 0.15 && Math.abs(offset.y) < 3) {
                column.add(other);
            }
        }
        column.sort(Comparator.comparingDouble((Candidate c) -> c.center().y).reversed());
        List<Component> lines = new ArrayList<>();
        for (Candidate candidate : column) {
            lines.addAll(candidate.lines());
        }
        return lines;
    }

    private static @Nullable Candidate textOf(Font font, Entity entity, Player self) {
        if (entity instanceof Display.TextDisplay display) {
            Display.TextDisplay.TextRenderState text = display.textRenderState();
            if (text == null || text.text().getString().isBlank()) {
                return null;
            }
            List<FormattedCharSequence> wrapped = font.split(text.text(), text.lineWidth());
            int widthPx = wrapped.stream().mapToInt(font::width).max().orElse(0);
            int heightPx = wrapped.size() * (font.lineHeight + 1);
            double scaleX = 1;
            double scaleY = 1;
            Vec3 origin = display.position();
            Display.RenderState render = display.renderState();
            if (render != null) {
                Transformation transformation = render.transformation().get(1.0F);
                Vector3fc scale = transformation.scale();
                Vector3fc translation = transformation.translation();
                scaleX = Math.abs(scale.x());
                scaleY = Math.abs(scale.y());
                origin = origin.add(translation.x(), translation.y(), translation.z());
            }
            double width = widthPx * PIXEL * scaleX;
            double height = heightPx * PIXEL * scaleY;
            // Text is laid out upwards from the entity's origin.
            Vec3 center = origin.add(0, height / 2, 0);
            List<Component> lines = splitLines(text.text());
            return new Candidate(Component.literal("Text display"), lines, center, width, height);
        }
        boolean nameShown = entity.isCustomNameVisible() || (entity instanceof Player && entity != self);
        if (nameShown && entity.getDisplayName() != null) {
            Component name = entity.getDisplayName();
            double width = font.width(name) * PIXEL;
            Vec3 center = entity.position().add(0, entity.getBbHeight() + 0.5, 0);
            return new Candidate(Component.literal("Nametag"), List.of(name), center, width, font.lineHeight * PIXEL);
        }
        return null;
    }

    /** Text displays keep explicit line breaks; split on them so each pinned row is one line. */
    private static List<Component> splitLines(Component text) {
        List<Component> lines = new ArrayList<>();
        for (String line : text.getString().split("\n")) {
            if (!line.isBlank()) {
                lines.add(Component.literal(line).withStyle(text.getStyle()));
            }
        }
        return lines;
    }

    /** Distance along the view ray to the candidate if the ray passes through its text, otherwise -1. */
    private static double rayHit(Vec3 eye, Vec3 look, Vec3 right, Vec3 up, Candidate candidate) {
        Vec3 toCenter = candidate.center().subtract(eye);
        double along = toCenter.dot(look);
        if (along <= 0 || along > REACH) {
            return -1;
        }
        Vec3 offset = toCenter.subtract(look.scale(along));
        // A little slack that grows with distance, so small far-away text can still be aimed at.
        double slack = 0.05 + along * 0.008;
        boolean inside = Math.abs(offset.dot(right)) <= candidate.width() / 2 + slack
                && Math.abs(offset.dot(up)) <= candidate.height() / 2 + slack;
        return inside ? along : -1;
    }

    private static boolean seesThroughWalls(Entity entity) {
        if (entity instanceof Display.TextDisplay display) {
            Display.TextDisplay.TextRenderState text = display.textRenderState();
            return text != null && (text.flags() & Display.TextDisplay.FLAG_SEE_THROUGH) != 0;
        }
        // Vanilla draws nametags through walls unless the entity is sneaking.
        return !entity.isDiscrete();
    }

    private static boolean visible(Minecraft client, Player player, Vec3 eye, Vec3 target) {
        // VISUAL ignores glass and other see-through blocks.
        BlockHitResult hit = client.level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(eye) >= eye.distanceTo(target) - 0.3;
    }
}

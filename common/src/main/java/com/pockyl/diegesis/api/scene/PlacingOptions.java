package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import org.joml.Vector2f;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Free placing: items are put down anywhere on the scene's surfaces and stay there, turned and sized as the player likes. It
 * shows the slot group {@code group}, whose rules must be {@code placed()}; an item of the group is an object of the scene, not
 * a node of the file. Written as {@code "placing"} in a scene file; {@code group} and {@code surfaces} are required.
 *
 * @param group        the slot group that holds the items
 * @param surfaces     where they can stand
 * @param itemSize     pixels the longest side of an item measures at size 1
 * @param turnStep     degrees of one notch of the wheel
 * @param scaleRange   the sizes a player can give an item, as factors of {@code item_size}
 * @param scaleStep    what one notch of the wheel changes the size by
 * @param turnSpeed    degrees an item turns per GUI pixel of a drag
 * @param stacking     items can stand on each other; what stood on an item comes down when it is taken away
 * @param blockModels  a block item with a flat icon (a lantern, a torch, a flower pot) stands there as the block
 * @param itemLight    items of blocks that give light shine on the scene, candles and torches burn
 * @param lightRadius  how far their light reaches, in pixels
 * @param lightColor   ARGB colour of the pool of light they throw (alpha: its strength)
 * @param shadows      items cast their shape on what they stand on
 * @param snap         positions are rounded to this many pixels; 0 keeps them as they are
 * @param previewColor ARGB colour of the see-through copy that shows where the item in the hand would stand
 */
public record PlacingOptions(String group, List<PlaceSurface> surfaces, float itemSize, float turnStep, float[] scaleRange, float scaleStep,
        float turnSpeed, boolean stacking, boolean blockModels, boolean itemLight, float lightRadius, int lightColor, boolean shadows,
        float snap, int previewColor) {
    public static final Codec<PlacingOptions> CODEC = StrictCodecs.<PlacingOptions>record(instance -> instance.group(
            NodeDefinition.ID_CODEC.fieldOf("group").forGetter(PlacingOptions::group),
            PlaceSurface.CODEC.listOf().fieldOf("surfaces").forGetter(PlacingOptions::surfaces),
            StrictCodecs.optional(Codec.floatRange(0.5F, 64.0F), "item_size", 6.0F).forGetter(PlacingOptions::itemSize),
            StrictCodecs.optional(Codec.floatRange(1.0F, 180.0F), "turn_step", 15.0F).forGetter(PlacingOptions::turnStep),
            StrictCodecs.optional(StrictCodecs.RANGE, "scale_range", new float[] {0.5F, 1.5F}).forGetter(PlacingOptions::scaleRange),
            StrictCodecs.optional(Codec.floatRange(0.01F, 4.0F), "scale_step", 0.1F).forGetter(PlacingOptions::scaleStep),
            StrictCodecs.optional(Codec.floatRange(0.05F, 8.0F), "turn_speed", 1.2F).forGetter(PlacingOptions::turnSpeed),
            StrictCodecs.optional(Codec.BOOL, "stacking", true).forGetter(PlacingOptions::stacking),
            StrictCodecs.optional(Codec.BOOL, "block_models", true).forGetter(PlacingOptions::blockModels),
            StrictCodecs.optional(Codec.BOOL, "item_light", true).forGetter(PlacingOptions::itemLight),
            StrictCodecs.optional(Codec.floatRange(1.0F, 256.0F), "light_radius", 11.0F).forGetter(PlacingOptions::lightRadius),
            StrictCodecs.optional(Visual.COLOR, "light_color", 0xC0FFC888).forGetter(PlacingOptions::lightColor),
            StrictCodecs.optional(Codec.BOOL, "shadows", true).forGetter(PlacingOptions::shadows),
            StrictCodecs.optional(Codec.floatRange(0.0F, 16.0F), "snap", 0.25F).forGetter(PlacingOptions::snap),
            StrictCodecs.optional(Visual.COLOR, "preview_color", 0xA0FFFFFF).forGetter(PlacingOptions::previewColor)
    ).apply(instance, PlacingOptions::new)).comapFlatMap(PlacingOptions::validate, placing -> placing);

    public PlacingOptions {
        surfaces = List.copyOf(surfaces);
    }

    private static DataResult<PlacingOptions> validate(PlacingOptions placing) {
        if (placing.surfaces.isEmpty()) {
            return DataResult.error(() -> "\"placing\" needs at least one surface");
        }
        if (placing.scaleRange[0] <= 0.0F || placing.scaleRange[0] > placing.scaleRange[1]) {
            return DataResult.error(() -> "\"scale_range\" needs 0 < smallest <= largest");
        }
        return DataResult.success(placing);
    }

    /** Starts the options in code, for the slot group {@code group}. */
    public static Builder builder(String group) {
        return new Builder(group);
    }

    /** Builds the options in code. */
    public static final class Builder {
        private final String group;
        private final List<PlaceSurface> surfaces = new ArrayList<>();
        private float itemSize = 6.0F;
        private float turnStep = 15.0F;
        private float[] scaleRange = {0.5F, 1.5F};
        private float scaleStep = 0.1F;
        private float turnSpeed = 1.2F;
        private boolean stacking = true;
        private boolean blockModels = true;
        private boolean itemLight = true;
        private float lightRadius = 11.0F;
        private int lightColor = 0xC0FFC888;
        private boolean shadows = true;
        private float snap = 0.25F;
        private int previewColor = 0xA0FFFFFF;

        private Builder(String group) {
            this.group = group;
        }

        public Builder surface(float fromX, float fromZ, float toX, float toZ, float height, float clearance, float aimHeight) {
            surfaces.add(new PlaceSurface(new Vector2f(fromX, fromZ), new Vector2f(toX, toZ), height, clearance, aimHeight, Optional.empty()));
            return this;
        }

        /** As {@link #surface}, with a wall behind it at {@code wallZ} that the light in the room falls on. */
        public Builder surface(float fromX, float fromZ, float toX, float toZ, float height, float clearance, float aimHeight, float wallZ) {
            surfaces.add(new PlaceSurface(new Vector2f(fromX, fromZ), new Vector2f(toX, toZ), height, clearance, aimHeight, Optional.of(wallZ)));
            return this;
        }

        public Builder itemSize(float pixels) {
            itemSize = pixels;
            return this;
        }

        public Builder turnStep(float degrees) {
            turnStep = degrees;
            return this;
        }

        public Builder scaleRange(float smallest, float largest) {
            scaleRange = new float[] {smallest, largest};
            return this;
        }

        public Builder scaleStep(float value) {
            scaleStep = value;
            return this;
        }

        public Builder turnSpeed(float degreesPerPixel) {
            turnSpeed = degreesPerPixel;
            return this;
        }

        public Builder stacking(boolean value) {
            stacking = value;
            return this;
        }

        public Builder blockModels(boolean value) {
            blockModels = value;
            return this;
        }

        public Builder itemLight(boolean value) {
            itemLight = value;
            return this;
        }

        public Builder lightRadius(float pixels) {
            lightRadius = pixels;
            return this;
        }

        public Builder lightColor(int argb) {
            lightColor = argb;
            return this;
        }

        public Builder shadows(boolean value) {
            shadows = value;
            return this;
        }

        public Builder snap(float pixels) {
            snap = pixels;
            return this;
        }

        public Builder previewColor(int argb) {
            previewColor = argb;
            return this;
        }

        public PlacingOptions build() {
            PlacingOptions placing = new PlacingOptions(group, surfaces, itemSize, turnStep, scaleRange, scaleStep, turnSpeed, stacking,
                    blockModels, itemLight, lightRadius, lightColor, shadows, snap, previewColor);
            return validate(placing).getOrThrow();
        }
    }
}

package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

/**
 * How the short lines of {@code StationView.showCaption} look.
 *
 * @param edge       the edge of the screen they sit at
 * @param color      the text's colour
 * @param background the band behind the text; a zero alpha draws none
 */
public record CaptionOptions(Edge edge, int color, int background) {
    public static final CaptionOptions DEFAULT = new CaptionOptions(Edge.TOP, 0xFFFFFFFF, 0x80000000);

    public static final Codec<CaptionOptions> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.optional(Edge.CODEC, "edge", DEFAULT.edge).forGetter(CaptionOptions::edge),
            StrictCodecs.optional(Visual.COLOR, "color", DEFAULT.color).forGetter(CaptionOptions::color),
            StrictCodecs.optional(Visual.COLOR, "background", DEFAULT.background).forGetter(CaptionOptions::background)
    ).apply(instance, CaptionOptions::new));

    /** The screen edge a caption sits at. */
    public enum Edge implements StringRepresentable {
        TOP("top"),
        BOTTOM("bottom");

        public static final Codec<Edge> CODEC = StringRepresentable.fromEnum(Edge::values);

        private final String name;

        Edge(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}

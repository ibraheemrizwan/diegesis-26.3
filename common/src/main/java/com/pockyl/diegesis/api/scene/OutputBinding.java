package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;

import com.pockyl.diegesis.api.scene.SlotBinding.ItemDisplay;
import com.pockyl.diegesis.impl.codec.StrictCodecs;

/**
 * Makes a node show a result the station computes (a crafting result): clicking it takes the result.
 *
 * @param output  the name of the station output
 * @param display how the result is shown
 * @param size    the size of the shown item in pixels; also the size of the click zone
 */
public record OutputBinding(String output, ItemDisplay display, float size) {
    public static final Codec<OutputBinding> CODEC = StrictCodecs.record(instance -> instance.group(
            Codec.STRING.fieldOf("output").forGetter(OutputBinding::output),
            StrictCodecs.optional(ItemDisplay.CODEC, "display", ItemDisplay.FLOATING).forGetter(OutputBinding::display),
            StrictCodecs.optional(Codec.floatRange(1.0F, 64.0F), "size", 8.0F).forGetter(OutputBinding::size)
    ).apply(instance, OutputBinding::new));

    public static OutputBinding of(String output) {
        return new OutputBinding(output, ItemDisplay.FLOATING, 8.0F);
    }
}

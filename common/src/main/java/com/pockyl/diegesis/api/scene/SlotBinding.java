package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

import com.pockyl.diegesis.api.state.SlotRef;
import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.Optional;

/**
 * Makes a node a place for an item: players drop items from their inventory onto it and pick them up again.
 *
 * @param slot    the station slot the node shows, e.g. {@code "grid[4]"}
 * @param display how the item is shown
 * @param size     the size of the slot (its drop zone and highlight) in pixels; also of the shown item without
 *                 {@code item_size}
 * @param itemSize the size of the shown item in pixels, when it differs from the slot's
 * @param counts   when the stack's count is drawn: slots close together stay readable with {@code hover}
 * @param highlight the square laid over the slot under the cursor; off for things that are no slots to the eye, as food
 *                  swimming in a pot (the hover outline stays)
 */
public record SlotBinding(SlotRef slot, ItemDisplay display, float size, Optional<Float> itemSize, Counts counts, boolean highlight) {
    public static final Codec<SlotBinding> CODEC = StrictCodecs.record(instance -> instance.group(
            SlotRef.CODEC.fieldOf("slot").forGetter(SlotBinding::slot),
            StrictCodecs.optional(ItemDisplay.CODEC, "display", ItemDisplay.FLAT).forGetter(SlotBinding::display),
            StrictCodecs.optional(Codec.floatRange(1.0F, 64.0F), "size", 6.0F).forGetter(SlotBinding::size),
            StrictCodecs.optional(Codec.floatRange(0.5F, 64.0F), "item_size").forGetter(SlotBinding::itemSize),
            StrictCodecs.optional(Counts.CODEC, "counts", Counts.ALWAYS).forGetter(SlotBinding::counts),
            StrictCodecs.optional(Codec.BOOL, "highlight", true).forGetter(SlotBinding::highlight)
    ).apply(instance, SlotBinding::new));

    public static SlotBinding of(SlotRef slot) {
        return new SlotBinding(slot, ItemDisplay.FLAT, 6.0F, Optional.empty(), Counts.ALWAYS, true);
    }

    /** The size the item is drawn at. */
    public float shownItemSize() {
        return itemSize.orElse(size);
    }

    /** When a slot shows how many items lie in it. */
    public enum Counts implements StringRepresentable {
        ALWAYS("always"),
        /** Only while the cursor is on the slot. */
        HOVER("hover"),
        NEVER("never");

        public static final Codec<Counts> CODEC = StringRepresentable.fromEnum(Counts::values);

        private final String name;

        Counts(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** How an item in a slot is drawn. */
    public enum ItemDisplay implements StringRepresentable {
        /** Lying on the surface, like an item put down on a table. */
        FLAT("flat"),
        /** Standing up, facing the camera side of the block. */
        UPRIGHT("upright"),
        /** Hovering and slowly turning, like a crafting result. */
        FLOATING("floating"),
        /** Not drawn, and no count: the scene shows what is in the slot its own way, as clay on a wheel is the pot. */
        HIDDEN("hidden");

        public static final Codec<ItemDisplay> CODEC = StringRepresentable.fromEnum(ItemDisplay::values);

        private final String name;

        ItemDisplay(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}

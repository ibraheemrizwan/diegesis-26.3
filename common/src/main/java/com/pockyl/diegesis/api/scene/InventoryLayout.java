package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.Optional;

/**
 * Lays the player's inventory out in the scene instead of the screen: 36 slots in the vanilla order (three rows of the
 * main inventory, then the hotbar nearest the player), on an optional surface such as a shelf pulled out of the block.
 * The slots work exactly like the inventory panel: clicks, shift-clicks, drags, tooltips.
 *
 * @param pos      the centre of the slot grid, in scene pixels
 * @param rotation the grid's turn in degrees (applied y, x, z); without one it lies flat, rows running towards the player
 * @param spacing  pixels between slot centres
 * @param size     the size of the items in the slots, in pixels
 * @param surface  a block drawn as the board under the slots, scaled to the grid (e.g. {@code "minecraft:oak_planks"});
 *                 without one the slots float in the air
 * @param slotColor ARGB colour of the square marking each slot (transparent black by default; alpha 0 hides them)
 * @param show     when the slots are there: {@code always}, or {@code toggle} - hidden until the player presses Tab (and
 *                 again to hide them), shown by themselves while an item is on the cursor
 */
public record InventoryLayout(Vector3fc pos, Vector3fc rotation, float spacing, float size, Optional<BlockState> surface,
        int slotColor, Show show) {
    public static final int DEFAULT_SLOT_COLOR = 0x66000000;
    /** Columns and rows of the grid. */
    public static final int COLUMNS = 9;
    public static final int ROWS = 4;
    /** The gap between the main rows and the hotbar, in slot spacings. */
    public static final float HOTBAR_GAP = 0.3F;
    /** The board's margin round the slots, in slot spacings. */
    public static final float MARGIN = 0.35F;

    public static final Codec<InventoryLayout> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.VEC3.fieldOf("pos").forGetter(layout -> new Vector3f(layout.pos)),
            StrictCodecs.optional(StrictCodecs.VEC3, "rotation", new Vector3f()).forGetter(layout -> new Vector3f(layout.rotation)),
            StrictCodecs.optional(Codec.floatRange(0.5F, 32.0F), "spacing", 2.6F).forGetter(InventoryLayout::spacing),
            StrictCodecs.optional(Codec.floatRange(0.5F, 32.0F), "size", 2.1F).forGetter(InventoryLayout::size),
            StrictCodecs.optional(Visual.BLOCK_STATE, "surface").forGetter(InventoryLayout::surface),
            StrictCodecs.optional(Visual.COLOR, "slot_color", DEFAULT_SLOT_COLOR).forGetter(InventoryLayout::slotColor),
            StrictCodecs.optional(Show.CODEC, "show", Show.ALWAYS).forGetter(InventoryLayout::show)
    ).apply(instance, InventoryLayout::new));

    /** When the laid-out inventory is there. */
    public enum Show implements StringRepresentable {
        /** All the time the player works at the station. */
        ALWAYS("always"),
        /** Hidden until Tab shows it (Tab again hides it); also shown while an item is on the cursor. */
        TOGGLE("toggle");

        public static final Codec<Show> CODEC = StringRepresentable.fromEnum(Show::values);

        private final String name;

        Show(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public InventoryLayout {
        pos = new Vector3f(pos);
        rotation = new Vector3f(rotation);
    }

    /** The grid's matrix in scene space: its centre, turned. */
    public Matrix4f matrix() {
        return new Matrix4f().translate(pos.x(), pos.y(), pos.z()).rotate(Transform.toQuaternion(rotation, new org.joml.Quaternionf()));
    }

    /**
     * Where an inventory slot sits in the grid's own space (x across, z away from the player). Slot 0-8 = hotbar, the
     * row nearest the player; 9-17 the farthest row. Column 0 is on the player's left (+x, the scene faces north).
     */
    public Vector3f slotOffset(int slot) {
        int column = slot % COLUMNS;
        int row = slot < COLUMNS ? 0 : 4 - slot / COLUMNS;
        float x = (COLUMNS - 1) / 2.0F - column;
        float depth = ROWS + HOTBAR_GAP;
        float z = -depth / 2.0F + 0.5F + row + (row > 0 ? HOTBAR_GAP : 0.0F);
        return new Vector3f(x * spacing, 0.0F, z * spacing);
    }

    /** The board's width (x) and depth (z) in pixels. */
    public float boardWidth() {
        return (COLUMNS + 2 * MARGIN) * spacing;
    }

    public float boardDepth() {
        return (ROWS + HOTBAR_GAP + 2 * MARGIN) * spacing;
    }
}

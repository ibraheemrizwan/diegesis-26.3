package com.pockyl.diegesis.api.scene;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

import java.util.Optional;

/**
 * What a node looks like: exactly one of a block model made in Blockbench ({@code model}, a model id such as
 * {@code mymod:diegesis/flask} for {@code assets/mymod/models/diegesis/flask.json}), a polygon model ({@code mesh}, an OBJ
 * file, {@code mymod:chess/king} for {@code assets/mymod/diegesis/meshes/chess/king.obj}), an item or a block state.
 *
 * @param mesh        a polygon model; needs {@code texture}. One unit in the file is one pixel
 * @param texture     the mesh's texture, a sprite of the block atlas: {@code mymod:block/chess/king} for
 *                    {@code assets/mymod/textures/block/chess/king.png}, or any vanilla block texture
 * @param anchor      the point of the model (in its own pixels) that sits on the node's position; the default is the
 *                    centre of a block model's bottom, so a node's {@code pos} is where the object stands, and a mesh's
 *                    own origin
 * @param tint        ARGB colour multiplied into the model; an alpha below 255 makes the object see-through
 * @param translucent whether the model has see-through textures (glass): drawn after the solid parts, sorted
 * @param emissive    drawn at full brightness, like a glowing part
 * @param doubleSided the faces are drawn from behind too: the inner walls of an open glass vessel, a flat plane seen
 *                    from both sides
 * @param glass       how clear the model's faces are where they look straight at the camera, 0 (as textured) to 1 (not
 *                    there): they thicken towards the edges of the view, as glass does, so what is behind stays clear
 */
public record Visual(Optional<Identifier> model, Optional<Identifier> mesh, Optional<Identifier> texture,
        Optional<Item> item, Optional<BlockState> block, Vector3fc anchor, int tint, boolean translucent, boolean emissive,
        boolean doubleSided, float glass) {
    public static final Vector3fc DEFAULT_ANCHOR = new Vector3f(8.0F, 0.0F, 8.0F);
    public static final Vector3fc MESH_ANCHOR = new Vector3f();
    public static final int WHITE = 0xFFFFFFFF;

    /** {@code "#RRGGBB"}, {@code "#AARRGGBB"} or a plain ARGB integer. */
    public static final Codec<Integer> COLOR = Codec.either(Codec.INT, Codec.STRING).comapFlatMap(
            either -> either.map(DataResult::success, Visual::parseColor), argb -> Either.right(String.format("#%08X", argb)));

    /** A block state as in commands, {@code "minecraft:lever[face=floor,facing=north]"}, or vanilla's object form. */
    public static final Codec<BlockState> BLOCK_STATE = Codec.either(Codec.STRING.comapFlatMap(Visual::parseBlockState,
            BlockStateParser::serialize), BlockState.CODEC).xmap(either -> either.map(state -> state, state -> state), Either::left);

    public static final Codec<Visual> CODEC = StrictCodecs.<Visual>record(instance -> instance.group(
            StrictCodecs.optional(Identifier.CODEC, "model").forGetter(Visual::model),
            StrictCodecs.optional(Identifier.CODEC, "mesh").forGetter(Visual::mesh),
            StrictCodecs.optional(Identifier.CODEC, "texture").forGetter(Visual::texture),
            StrictCodecs.optional(BuiltInRegistries.ITEM.byNameCodec(), "item").forGetter(Visual::item),
            StrictCodecs.optional(BLOCK_STATE, "block").forGetter(Visual::block),
            StrictCodecs.optional(StrictCodecs.VEC3, "anchor").forGetter(v -> Optional.of(new Vector3f(v.anchor))),
            StrictCodecs.optional(COLOR, "tint", WHITE).forGetter(Visual::tint),
            StrictCodecs.optional(Codec.BOOL, "translucent", false).forGetter(Visual::translucent),
            StrictCodecs.optional(Codec.BOOL, "emissive", false).forGetter(Visual::emissive),
            StrictCodecs.optional(Codec.BOOL, "double_sided", false).forGetter(Visual::doubleSided),
            StrictCodecs.optional(Codec.floatRange(0.0F, 1.0F), "glass", 0.0F).forGetter(Visual::glass)
    ).apply(instance, (model, mesh, texture, item, block, anchor, tint, translucent, emissive, doubleSided, glass) ->
            new Visual(model, mesh, texture, item, block, anchor.map(v -> (Vector3fc) v).orElse(mesh.isPresent() ? MESH_ANCHOR : DEFAULT_ANCHOR),
                    tint, translucent, emissive, doubleSided, glass))).comapFlatMap(Visual::validate, visual -> visual);

    public Visual {
        anchor = new Vector3f(anchor);
        translucent = translucent || (tint >>> 24) < 0xFF || glass > 0.0F;
    }

    public static Visual ofModel(Identifier model) {
        return new Visual(Optional.of(model), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), DEFAULT_ANCHOR, WHITE,
                false, false, false, 0.0F);
    }

    /** A polygon model with a texture of the block atlas. */
    public static Visual ofMesh(Identifier mesh, Identifier texture) {
        return new Visual(Optional.empty(), Optional.of(mesh), Optional.of(texture), Optional.empty(), Optional.empty(), MESH_ANCHOR, WHITE,
                false, false, false, 0.0F);
    }

    public static Visual ofItem(Item item) {
        return new Visual(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(item), Optional.empty(), DEFAULT_ANCHOR, WHITE,
                false, false, false, 0.0F);
    }

    public static Visual ofBlock(BlockState state) {
        return new Visual(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(state), DEFAULT_ANCHOR, WHITE,
                false, false, false, 0.0F);
    }

    public Visual withTint(int argb) {
        return new Visual(model, mesh, texture, item, block, anchor, argb, translucent, emissive, doubleSided, glass);
    }

    public Visual withAnchor(Vector3fc newAnchor) {
        return new Visual(model, mesh, texture, item, block, newAnchor, tint, translucent, emissive, doubleSided, glass);
    }

    public Visual asTranslucent() {
        return new Visual(model, mesh, texture, item, block, anchor, tint, true, emissive, doubleSided, glass);
    }

    public Visual asEmissive() {
        return new Visual(model, mesh, texture, item, block, anchor, tint, translucent, true, doubleSided, glass);
    }

    /** The same visual with its faces drawn from behind too (see {@code doubleSided}). */
    public Visual asDoubleSided() {
        return new Visual(model, mesh, texture, item, block, anchor, tint, translucent, emissive, true, glass);
    }

    /** The same visual as clear glass (see {@code glass}). */
    public Visual asGlass(float clarity) {
        return new Visual(model, mesh, texture, item, block, anchor, tint, true, emissive, doubleSided, clarity);
    }

    private static DataResult<Visual> validate(Visual visual) {
        int count = (visual.model.isPresent() ? 1 : 0) + (visual.mesh.isPresent() ? 1 : 0) + (visual.item.isPresent() ? 1 : 0)
                + (visual.block.isPresent() ? 1 : 0);
        if (count != 1) {
            return DataResult.error(() -> "A visual needs exactly one of \"model\", \"mesh\", \"item\", \"block\"");
        }
        if (visual.mesh.isPresent() != visual.texture.isPresent()) {
            return DataResult.error(() -> "\"mesh\" and \"texture\" go together: a mesh needs a texture, nothing else takes one");
        }
        return DataResult.success(visual);
    }

    private static DataResult<BlockState> parseBlockState(String text) {
        try {
            return DataResult.success(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState());
        } catch (CommandSyntaxException e) {
            return DataResult.error(() -> "Not a block state: " + text + " (" + e.getMessage() + ")");
        }
    }

    private static DataResult<Integer> parseColor(String text) {
        String hex = text.startsWith("#") ? text.substring(1) : text;
        try {
            long value = Long.parseLong(hex, 16);
            if (hex.length() == 6) {
                return DataResult.success((int) (0xFF000000L | value));
            }
            return hex.length() == 8 ? DataResult.success((int) value)
                    : DataResult.error(() -> "Colour must be #RRGGBB or #AARRGGBB: " + text);
        } catch (NumberFormatException e) {
            return DataResult.error(() -> "Not a colour: " + text);
        }
    }
}

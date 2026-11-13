package com.pockyl.diegesis.impl.client.scene;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.scene.Box;
import com.pockyl.diegesis.api.scene.NodeDefinition;
import com.pockyl.diegesis.api.scene.SlotBinding;
import com.pockyl.diegesis.api.scene.Visual;
import com.pockyl.diegesis.platform.ClientPlatform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Hit boxes for nodes that do not declare a {@code collider}: from the behaviour, else from the visual. */
final class DefaultColliders {
    /** Integers per vertex in a baked quad (position, colour, uv, light, normal). */
    private static final int VERTEX_STRIDE = 8;
    private static final Map<Identifier, Box> MODEL_BOUNDS = new ConcurrentHashMap<>();
    private static final Map<BlockState, Box> BLOCK_BOUNDS = new ConcurrentHashMap<>();

    private DefaultColliders() {
    }

    /** Model bounds depend on the loaded models; forget them when the resources reload. */
    static void clearCache() {
        MODEL_BOUNDS.clear();
        BLOCK_BOUNDS.clear();
    }

    static Box of(NodeDefinition node) {
        if (node.slot().isPresent()) {
            SlotBinding slot = node.slot().get();
            float half = slot.size() / 2.0F;
            // A lying slot is a thin pad: a tall box would catch rays aimed at the slot behind it.
            boolean lying = slot.display() == SlotBinding.ItemDisplay.FLAT || slot.display() == SlotBinding.ItemDisplay.HIDDEN;
            float height = lying ? 0.6F : slot.size();
            return new Box(new Vector3f(-half, 0.0F, -half), new Vector3f(half, height, half));
        }
        if (node.output().isPresent()) {
            float size = node.output().get().size();
            return new Box(new Vector3f(-size / 2.0F, 0.0F, -size / 2.0F), new Vector3f(size / 2.0F, size * 1.4F, size / 2.0F));
        }
        if (node.visual().isEmpty()) {
            return null;
        }
        Visual visual = node.visual().get();
        Vector3fc anchor = visual.anchor();
        Box box;
        if (visual.mesh().isPresent()) {
            com.pockyl.diegesis.impl.client.mesh.Mesh mesh = com.pockyl.diegesis.impl.client.mesh.MeshLibrary.INSTANCE.mesh(visual.mesh().get());
            box = mesh != null ? mesh.bounds() : new Box(new Vector3f(-1.0F), new Vector3f(1.0F));
        } else if (visual.model().isPresent()) {
            box = MODEL_BOUNDS.computeIfAbsent(visual.model().get(), DefaultColliders::modelBounds);
        } else if (visual.block().isPresent()) {
            BlockState state = visual.block().get();
            box = BLOCK_BOUNDS.computeIfAbsent(state,
                    key -> bounds(Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(key), key));
        } else {
            box = new Box(new Vector3f(3.0F), new Vector3f(13.0F));
        }
        Box shifted = new Box(new Vector3f(box.from()).sub(anchor), new Vector3f(box.to()).sub(anchor));
        // Thin parts (a lever, a needle) are hard to hit exactly; give the cursor a little room.
        return node.control().isPresent() ? shifted.inflate(0.5F) : shifted;
    }

    private static Box modelBounds(Identifier id) {
        return bounds(ClientPlatform.INSTANCE.getModel(id), null);
    }

    /** The box round every quad of a model, in pixels (a full block when it has none). */
    private static Box bounds(BlockStateModel model, BlockState state) {
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        var parts=new java.util.ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
        model.collectParts(RandomSource.create(42L),parts);
        for(var part:parts) for(Direction side:new Direction[]{null,Direction.DOWN,Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST}) {
            for(BakedQuad quad:part.getQuads(side)) for(int corner=0;corner<4;corner++) {
                Vector3f point=new Vector3f(quad.position(corner)).mul(16);
                min.min(point);max.max(point);
            }
        }
        if (min.x > max.x) {
            return new Box(new Vector3f(0.0F), new Vector3f(16.0F));
        }
        return new Box(min, max);
    }
}

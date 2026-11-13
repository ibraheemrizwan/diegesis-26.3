package com.pockyl.diegesis.api.scene;

import com.mojang.serialization.Codec;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.impl.codec.StrictCodecs;

/** An axis-aligned box in a node's own space, in Blockbench pixels: what the cursor can hit. */
public record Box(Vector3fc from, Vector3fc to) {
    public static final Codec<Box> CODEC = StrictCodecs.record(instance -> instance.group(
            StrictCodecs.VEC3.fieldOf("from").forGetter(b -> new Vector3f(b.from)),
            StrictCodecs.VEC3.fieldOf("to").forGetter(b -> new Vector3f(b.to))
    ).apply(instance, Box::new));

    public Box {
        Vector3f min = new Vector3f(from).min(to);
        Vector3f max = new Vector3f(from).max(to);
        from = min;
        to = max;
    }

    /** A box of the given size whose bottom face is centred on the node's origin. */
    public static Box standing(float width, float height, float depth) {
        return new Box(new Vector3f(-width / 2.0F, 0.0F, -depth / 2.0F), new Vector3f(width / 2.0F, height, depth / 2.0F));
    }

    public Box inflate(float amount) {
        return new Box(new Vector3f(from).sub(amount, amount, amount), new Vector3f(to).add(amount, amount, amount));
    }

    public float height() {
        return to.y() - from.y();
    }
}

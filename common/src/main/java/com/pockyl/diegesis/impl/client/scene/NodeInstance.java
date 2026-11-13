package com.pockyl.diegesis.impl.client.scene;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.api.client.NodeView;
import com.pockyl.diegesis.api.scene.Box;
import com.pockyl.diegesis.api.scene.ControlBinding;
import com.pockyl.diegesis.api.scene.NodeDefinition;
import com.pockyl.diegesis.api.scene.TankBinding;
import com.pockyl.diegesis.api.scene.Transform;
import com.pockyl.diegesis.api.scene.Visual;
import com.pockyl.diegesis.api.state.TankSpec;
import com.pockyl.diegesis.impl.client.anim.Easing;
import com.pockyl.diegesis.impl.client.anim.Spring;
import com.pockyl.diegesis.impl.client.anim.Tween;
import com.pockyl.diegesis.impl.client.fx.LiquidSim;

import java.util.ArrayList;
import java.util.List;

/**
 * A node of a shown scene: its definition plus everything that moves it on this client - code animations, the control
 * it drives, hovering, being carried. {@link #update} computes its matrix in station space (pixels, before the facing
 * turn) once per frame.
 */
public final class NodeInstance implements NodeView {
    private static final float HOVER_HALF_LIFE = 0.06F;
    private static final float TILT_HALF_LIFE = 0.05F;

    private final SceneInstance scene;
    private final NodeDefinition definition;
    private final NodeInstance parent;
    private final List<NodeInstance> children = new ArrayList<>();
    private final Box collider;

    private final Tween offset = new Tween(0.0F, 0.0F, 0.0F);
    private final Tween rotation = new Tween(0.0F, 0.0F, 0.0F);
    private final Tween scale = new Tween(1.0F, 1.0F, 1.0F);
    private final Matrix4f matrix = new Matrix4f();
    /** A control node's matrix without the control's movement: where its still {@code base} is drawn. */
    private final Matrix4f restMatrix = new Matrix4f();
    /**
     * The matrix without the lift of hovering: where the cursor finds the node. A hit box that rose with the node would slip
     * out from under a cursor near its lower edge, the node would sink back under the cursor, and so on for ever.
     */
    private final Matrix4f pickMatrix = new Matrix4f();
    private final Quaternionf scratchRotation = new Quaternionf();

    private boolean visible;
    private boolean enabled = true;
    /** The outline as code wants it; null leaves it to the hover. */
    private Boolean outlined;
    private int tint;
    private Visual visualOverride;
    private Component tooltipOverride;
    /** Degrees per second of a code spin, and the angle it reached. */
    private final Vector3f spinSpeed = new Vector3f();
    private final Vector3f spinAngle = new Vector3f();
    private float shakeAmplitude;
    private float shakeTime;
    private float shakeLeft;
    private final Vector3f shakeOffset = new Vector3f();
    private boolean hovered;
    private float hoverAmount;
    private float controlFraction;
    private float controlTarget;
    private boolean controlSnapped;

    /** Station-space position of a carried node, and its lean; null while the node is in its place. */
    private Spring carry;
    private final Vector3f carryTarget = new Vector3f();
    private final Vector3f lean = new Vector3f();
    private boolean held;
    private boolean returning;

    // Effects: emitter switch and its spawn debt, light level.
    private boolean emitting;
    private float emitRate = Float.NaN;
    private float agitation;
    private float emitDebt;
    private int lightLevel;

    /** What the liquid does; null without a tank. */
    private final LiquidSim liquid;

    /** A shape made by code, drawn at the node, and its texture. */
    private com.pockyl.diegesis.api.client.MeshShape shape;
    private net.minecraft.resources.Identifier shapeTexture;
    private boolean shapeGlows;
    private com.pockyl.diegesis.api.client.NodePainter painter;
    private com.pockyl.diegesis.impl.client.fx.LiquidPieces floating;

    /** The item this node shows and follows, for a node of a placed slot; null for the nodes of a scene file. */
    private PlacedItem placed;

    // Pouring: tipped about a horizontal axis and turned about its own, following what the hand asks for.
    private final Vector3f tiltAxis = new Vector3f(1.0F, 0.0F, 0.0F);
    private float tiltWanted;
    private float tilt;
    private float turnWanted;
    private float turn;

    NodeInstance(SceneInstance scene, NodeDefinition definition, NodeInstance parent) {
        this.scene = scene;
        this.definition = definition;
        this.parent = parent;
        this.visible = !definition.hidden();
        this.tint = definition.visual().map(Visual::tint).orElse(Visual.WHITE);
        this.collider = definition.pickable() ? definition.collider().orElseGet(() -> DefaultColliders.of(definition)) : null;
        this.emitting = definition.emitter().map(emitter -> emitter.enabled()).orElse(false);
        this.lightLevel = definition.light().map(light -> light.level()).orElse(0);
        this.liquid = definition.tank().isPresent() ? new LiquidSim() : null;
        for (NodeDefinition child : definition.children()) {
            children.add(new NodeInstance(scene, child, this));
        }
    }

    @Override
    public String id() {
        return definition.id();
    }

    @Override
    public NodeDefinition definition() {
        return definition;
    }

    public NodeInstance parent() {
        return parent;
    }

    public List<NodeInstance> children() {
        return children;
    }

    /** The hit box in the node's space, or null when the node cannot be hit. */
    public Box collider() {
        return placed != null ? placed.collider() : collider;
    }

    void setPlaced(PlacedItem value) {
        placed = value;
    }

    /** The item of a placed slot this node shows, or null. */
    public PlacedItem placed() {
        return placed;
    }

    @Override
    public boolean isVisible() {
        return visible && (parent == null || parent.isVisible());
    }

    @Override
    public void setVisible(boolean value) {
        visible = value;
    }

    @Override
    public void moveTo(float x, float y, float z, float seconds) {
        offset.start(x, y, z, seconds);
    }

    @Override
    public void hopTo(float x, float y, float z, float height, float seconds) {
        offset.start(x, y, z, seconds, height);
    }

    @Override
    public boolean isMoving() {
        return offset.isRunning();
    }

    @Override
    public void setEnabled(boolean value) {
        enabled = value;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setOutlined(boolean value) {
        outlined = value;
    }

    /** Whether code draws the outline now. */
    public boolean isOutlined() {
        return Boolean.TRUE.equals(outlined);
    }

    /** Whether code took the outline over from the hover. */
    public boolean isOutlineByCode() {
        return outlined != null;
    }

    /** The ray parameter on the visual's own polygons, or -1; {@code boxHit} for a visual that is no mesh. */
    float preciseHit(Vector3f origin, Vector3f direction, float boxHit) {
        Visual shown = visual().orElse(null);
        com.pockyl.diegesis.impl.client.mesh.Mesh mesh = shown == null ? null : shown.mesh()
                .map(com.pockyl.diegesis.impl.client.mesh.MeshLibrary.INSTANCE::mesh).orElse(null);
        return mesh == null ? boxHit : mesh.intersect(new Vector3f(origin).add(shown.anchor()), direction);
    }

    @Override
    public void rotateTo(float x, float y, float z, float seconds) {
        rotation.start(x, y, z, seconds);
    }

    @Override
    public void scaleTo(float factor, float seconds) {
        scale.start(factor, factor, factor, seconds);
    }

    @Override
    public void spin(float x, float y, float z) {
        spinSpeed.set(x, y, z);
    }

    @Override
    public void shake(float pixels, float seconds) {
        shakeAmplitude = Math.max(0.0F, pixels);
        shakeTime = Math.max(0.01F, seconds);
        shakeLeft = shakeTime;
    }

    @Override
    public void setVisual(Visual visual) {
        visualOverride = visual;
        tint = (visual != null ? java.util.Optional.of(visual) : definition.visual()).map(Visual::tint).orElse(Visual.WHITE);
    }

    @Override
    public java.util.Optional<Visual> visual() {
        return visualOverride != null ? java.util.Optional.of(visualOverride) : definition.visual();
    }

    @Override
    public void setTooltip(Component text) {
        tooltipOverride = text;
    }

    /** The tooltip set by code, or null. */
    public Component tooltipOverride() {
        return tooltipOverride;
    }

    /** The text to show next to the cursor over the node, or null. */
    public Component tooltip() {
        return tooltipOverride != null ? tooltipOverride : definition.tooltip().map(Component::translatable).orElse(null);
    }

    @Override
    public Matrix4fc sceneMatrix() {
        return new Matrix4f(matrix);
    }

    @Override
    public void setTint(int argb) {
        tint = argb;
    }

    public int tint() {
        return tint;
    }

    @Override
    public void setLiquidColor(int argb) {
        if (liquid != null) {
            liquid.setColorOverride(argb);
        }
    }

    @Override
    public void setAgitation(float amount) {
        agitation = Math.max(0.0F, Math.min(1.0F, amount));
    }

    public float agitation() {
        return agitation;
    }

    @Override
    public void setEmitRate(float perSecond) {
        emitRate = Math.max(0.0F, Math.min(200.0F, perSecond));
    }

    /** The emitter's rate now: set by code, or the scene file's. */
    public float emitRate() {
        return Float.isNaN(emitRate) ? definition.emitter().map(e -> e.rate()).orElse(0.0F) : emitRate;
    }

    @Override
    public void setEmitting(boolean value) {
        emitting = value;
    }

    public boolean isEmitting() {
        return emitting && definition.emitter().isPresent();
    }

    /** Adds {@code dt} seconds of emitting; returns how many particles are due now. */
    public int emitDue(float dt, float rate) {
        emitDebt += dt * rate;
        int due = (int) emitDebt;
        emitDebt -= due;
        return due;
    }

    @Override
    public void setShape(com.pockyl.diegesis.api.client.MeshShape value, net.minecraft.resources.Identifier texture) {
        setShape(value, texture, false);
    }

    @Override
    public void setShape(com.pockyl.diegesis.api.client.MeshShape value, net.minecraft.resources.Identifier texture, boolean glowing) {
        shape = value;
        shapeTexture = texture;
        shapeGlows = glowing;
    }

    @Override
    public void setPainter(com.pockyl.diegesis.api.client.NodePainter value) {
        painter = value;
    }

    public com.pockyl.diegesis.api.client.NodePainter painter() {
        return painter;
    }

    public boolean shapeGlows() {
        return shapeGlows;
    }

    @Override
    public void setFloating(java.util.List<com.pockyl.diegesis.api.client.Floater> floaters, int count) {
        if (liquid != null) {
            pieces().set(floaters, count);
        }
    }

    @Override
    public void leaveWake(float x, float z) {
        if (liquid != null) {
            Box inner = definition.tank().get().inner();
            liquid.leaveWake((x - inner.from().x()) / Math.max(0.01F, inner.to().x() - inner.from().x()),
                    (z - inner.from().z()) / Math.max(0.01F, inner.to().z() - inner.from().z()));
        }
    }

    @Override
    public void leaveWake(float x, float z, float width, float strength) {
        if (liquid != null) {
            Box inner = definition.tank().get().inner();
            float spanX = Math.max(0.01F, inner.to().x() - inner.from().x());
            liquid.leaveWake((x - inner.from().x()) / spanX, (z - inner.from().z()) / Math.max(0.01F, inner.to().z() - inner.from().z()), width / spanX,
                    Math.max(0.0F, Math.min(1.0F, strength)));
        }
    }

    @Override
    public void setWakeLook(float seconds, float contrast) {
        if (liquid != null) {
            liquid.setWakeLook(seconds, Math.max(0.0F, Math.min(1.0F, contrast)));
        }
    }

    @Override
    public void setFloatingWake(float strength) {
        if (liquid != null) {
            pieces().setWake(Math.max(0.0F, Math.min(1.0F, strength)));
        }
    }

    @Override
    public void setEdgeBoil(float amount) {
        if (liquid != null) {
            liquid.setEdgeBoil(amount);
        }
    }

    @Override
    public void setMottle(float amount) {
        if (liquid != null) {
            liquid.setMottle(amount);
        }
    }

    @Override
    public void setFloatingObstacles(float... xzRadius) {
        if (liquid != null) {
            pieces().setObstacles(xzRadius);
        }
    }

    @Override
    public void setCurrent(float radiansPerSecond) {
        if (liquid != null) {
            pieces().setCurrent(radiansPerSecond);
            liquid.setCurrent(radiansPerSecond);
        }
    }

    private com.pockyl.diegesis.impl.client.fx.LiquidPieces pieces() {
        if (floating == null) {
            floating = new com.pockyl.diegesis.impl.client.fx.LiquidPieces();
        }
        return floating;
    }

    /** What floats on the node's liquid; null while nothing ever did. */
    public com.pockyl.diegesis.impl.client.fx.LiquidPieces floating() {
        return floating;
    }

    /** The shape made by code to draw at the node, or null. */
    public com.pockyl.diegesis.api.client.MeshShape shape() {
        return shape;
    }

    public net.minecraft.resources.Identifier shapeTexture() {
        return shapeTexture;
    }

    @Override
    public void setLight(int level) {
        lightLevel = Math.max(0, Math.min(15, level));
    }

    public int lightLevel() {
        return lightLevel;
    }

    /** What the liquid in the node's tank is doing; null for a node without a tank. */
    public LiquidSim liquid() {
        return liquid;
    }

    @Override
    public void ripple(float x, float z, float strength) {
        if (liquid == null) {
            return;
        }
        Box inner = definition.tank().get().inner();
        float u = (x - inner.from().x()) / Math.max(0.01F, inner.to().x() - inner.from().x());
        float v = 1.0F - (z - inner.from().z()) / Math.max(0.01F, inner.to().z() - inner.from().z());
        liquid.impulse(Math.max(0.0F, Math.min(1.0F, u)), Math.max(0.0F, Math.min(1.0F, v)), strength);
    }

    /**
     * Tips the carried node about a horizontal axis (station space), and turns it about its own vertical axis by {@code turn}
     * degrees so that its spout leads. It eases there; an angle of 0 lets it right itself.
     */
    public void setTilt(Vector3fc axis, float degrees, float turnDegrees) {
        if (degrees > 0.0F) {
            tiltAxis.set(axis).normalize();
        }
        tiltWanted = degrees;
        turnWanted = turnDegrees;
    }

    /** How far the node is tipped now, degrees. */
    public float tilt() {
        return tilt;
    }

    public Vector3fc tiltAxis() {
        return tiltAxis;
    }

    public float turn() {
        return turn;
    }

    /** The node (this one or a part of it) that pours through a spout, or null. */
    public NodeInstance pourer() {
        if (definition.tank().map(tank -> tank.spout().isPresent()).orElse(false)) {
            return this;
        }
        for (NodeInstance child : children) {
            NodeInstance found = child.pourer();
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Override
    public Vec3 worldPosition() {
        Vector3f origin = matrix.transformPosition(new Vector3f());
        return scene.station().toWorld(origin.x, origin.y, origin.z);
    }

    @Override
    public boolean isHovered() {
        return hovered;
    }

    public void setHovered(boolean value) {
        hovered = value;
    }

    public float hoverAmount() {
        return hoverAmount;
    }

    @Override
    public boolean isHeld() {
        return held;
    }

    /** Whether the node is off its place (carried, or flying back). */
    public boolean isCarried() {
        return carry != null;
    }

    /** The node's matrix in station space, as of the last {@link #update}. */
    public Matrix4f matrix() {
        return matrix;
    }

    /**
     * A control node's matrix without the control's movement, in station space, as of the last {@link #update}: its
     * still base is drawn there, and the control's axis and range are in its space.
     */
    public Matrix4f restMatrix() {
        return restMatrix;
    }

    /** The node's matrix without the lift of hovering: the one its hit box is tested in. */
    public Matrix4f pickMatrix() {
        return pickMatrix;
    }

    /** The control value to show, 0 to 1 of the range; snaps on the first call. */
    public void setControlTarget(float fraction) {
        controlTarget = fraction;
        if (!controlSnapped) {
            controlFraction = fraction;
            controlSnapped = true;
        }
    }

    /** A button's press: dips and springs back. */
    public void pulse() {
        controlFraction = 1.0F;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Carrying
    // ---------------------------------------------------------------------------------------------------------------

    /** The node's resting position in station space (where it flies back to). */
    public Vector3f homePosition() {
        Matrix4f home = new Matrix4f();
        computeLocal(home, false, false, false);
        if (parent != null) {
            parent.matrix.mul(home, home);
        }
        return home.getTranslation(new Vector3f());
    }

    /** Picks the node up; {@code target} is where it should be, in station space. */
    public void grab(Vector3fc target) {
        if (carry == null) {
            carry = new Spring(0.05F);
            carry.snap(homePosition());
        }
        carry.setHalfLife(0.05F);
        carryTarget.set(target);
        held = true;
        returning = false;
    }

    public void carryTo(Vector3fc target) {
        carryTarget.set(target);
    }

    /** Lets go: the node flies back to its place, or stays where it is for a {@code stay} grabbable. */
    public void release(boolean stay) {
        held = false;
        if (carry == null) {
            return;
        }
        if (stay) {
            Vector3f home = homePosition();
            Vector3f at = new Vector3f(carry.value());
            // Put down where it was let go, at the height it rested at before (on the table, not in the air or in it).
            offset.start(offset.value().x() + at.x - home.x, offset.value().y() + at.y - home.y, offset.value().z() + at.z - home.z,
                    0.0F);
            offset.start(offset.value().x(), offset.value().y() - (at.y - home.y), offset.value().z(), 0.12F);
            carry = null;
            lean.zero();
        } else {
            carry.setHalfLife(0.09F);
            returning = true;
        }
    }

    /** Another player's carried node, from their presence; {@code null} puts it back. */
    public void showRemoteCarry(Vector3fc position) {
        if (position == null) {
            if (carry != null && !held) {
                carry.setHalfLife(0.09F);
                returning = true;
            }
            return;
        }
        if (carry == null) {
            carry = new Spring(0.08F);
            carry.snap(homePosition());
        }
        carry.setHalfLife(0.08F);
        carryTarget.set(position);
        returning = false;
    }

    /** The node's current lean while carried, degrees around x and z: for presence and shadows. */
    public Vector3fc lean() {
        return lean;
    }

    public Vector3fc carryPosition() {
        return carry != null ? carry.value() : homePosition();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Per frame
    // ---------------------------------------------------------------------------------------------------------------

    void update(float dt) {
        if (placed != null) {
            hoverAmount = Easing.approach(hoverAmount, hovered ? 1.0F : 0.0F, HOVER_HALF_LIFE, dt);
            placed.update(dt, hoverAmount, matrix, pickMatrix);
            return;
        }
        offset.update(dt);
        rotation.update(dt);
        scale.update(dt);
        if (spinSpeed.x != 0.0F || spinSpeed.y != 0.0F || spinSpeed.z != 0.0F) {
            spinAngle.add(spinSpeed.x * dt, spinSpeed.y * dt, spinSpeed.z * dt);
            spinAngle.set(spinAngle.x % 360.0F, spinAngle.y % 360.0F, spinAngle.z % 360.0F);
        }
        if (shakeLeft > 0.0F) {
            shakeLeft = Math.max(0.0F, shakeLeft - dt);
            float amount = shakeAmplitude * shakeLeft / shakeTime;
            shakeOffset.set(((float) Math.random() * 2.0F - 1.0F) * amount, ((float) Math.random() * 2.0F - 1.0F) * amount,
                    ((float) Math.random() * 2.0F - 1.0F) * amount);
        } else {
            shakeOffset.zero();
        }
        hoverAmount = Easing.approach(hoverAmount, hovered ? 1.0F : 0.0F, HOVER_HALF_LIFE, dt);
        boolean button = definition.control().map(c -> c.type() == ControlBinding.Type.BUTTON).orElse(false);
        float smoothing = definition.control().map(ControlBinding::smoothing).orElse(ControlBinding.DEFAULT_SMOOTHING);
        controlFraction = Easing.approach(controlFraction, button ? 0.0F : controlTarget, button ? 0.08F : smoothing, dt);

        if (carry != null) {
            Vector3fc target = returning ? homePosition() : carryTarget;
            Vector3fc position = carry.update(target, dt);
            float sway = definition.grabbable().map(g -> g.sway()).orElse(1.0F);
            Vector3fc velocity = carry.velocity();
            // Lean into the movement like a held glass: the top trails behind (a turn about +Z tips the top to -X).
            float leanX = Math.max(-25.0F, Math.min(25.0F, -velocity.z() * 0.35F * sway));
            float leanZ = Math.max(-25.0F, Math.min(25.0F, velocity.x() * 0.35F * sway));
            lean.set(Easing.approach(lean.x, leanX, 0.05F, dt), 0.0F, Easing.approach(lean.z, leanZ, 0.05F, dt));
            if (returning && position.distanceSquared(target) < 0.0025F && carry.velocity().lengthSquared() < 0.01F) {
                carry = null;
                returning = false;
                lean.zero();
            }
        }

        tilt = Easing.approach(tilt, tiltWanted, TILT_HALF_LIFE, dt);
        turn = Easing.approach(turn, turnWanted, TILT_HALF_LIFE * 2.0F, dt);
        if (carry != null) {
            Vector3fc position = carry.value();
            Transform transform = definition.transform();
            matrix.identity().translate(position.x() + shakeOffset.x, position.y() + shakeOffset.y, position.z() + shakeOffset.z);
            // Code animations (NodeView.rotateTo) tip a carried node about the station's axes: a torch leaning over.
            matrix.rotate(Transform.toQuaternion(rotation.value(), scratchRotation));
            if (tilt > 0.01F) {
                matrix.rotate((float) Math.toRadians(tilt), tiltAxis.x, tiltAxis.y, tiltAxis.z);
            }
            matrix.rotate(scratchRotation.rotationXYZ((float) Math.toRadians(lean.x), 0.0F, (float) Math.toRadians(lean.z)));
            matrix.rotate(Transform.toQuaternion(transform.rotation(), scratchRotation));
            if (Math.abs(turn) > 0.01F) {
                matrix.rotateY((float) Math.toRadians(turn));
            }
            matrix.rotate(Transform.toQuaternion(spinAngle, scratchRotation));
            Vector3fc base = transform.scale();
            Vector3fc extra = scale.value();
            matrix.scale(base.x() * extra.x(), base.y() * extra.y(), base.z() * extra.z());
        } else {
            computeLocal(matrix, true, true, true);
            computeLocal(pickMatrix, true, true, false);
            if (parent != null) {
                parent.matrix.mul(matrix, matrix);
                parent.pickMatrix.mul(pickMatrix, pickMatrix);
            }
        }
        if (carry != null) {
            pickMatrix.set(matrix);
        }
        if (definition.control().isPresent()) {
            computeLocal(restMatrix, true, false, true);
            if (parent != null) {
                parent.matrix.mul(restMatrix, restMatrix);
            }
        }
        if (liquid != null) {
            updateLiquid(dt);
        }
        for (NodeInstance child : children) {
            child.update(dt);
        }
    }

    private void updateLiquid(float dt) {
        TankBinding binding = definition.tank().get();
        TankSpec spec = scene.station().type().schema().tanks().get(binding.tank());
        if (spec != null) {
            liquid.update(dt, matrix, binding.inner(), scene.station().state().getTank(binding.tank()), spec.capacity(), agitation);
            if (floating != null) {
                floating.update(dt);
            }
        }
    }

    /** Local matrix from the definition, code animations (with {@code live}), the control and the lift of hovering. */
    private void computeLocal(Matrix4f dest, boolean live, boolean control, boolean lift) {
        Transform transform = definition.transform();
        Vector3fc pos = transform.pos();
        Vector3fc move = offset.value();
        dest.identity().translate(pos.x() + move.x(), pos.y() + move.y(), pos.z() + move.z());
        if (live) {
            dest.translate(shakeOffset);
        }
        if (live && lift && hoverAmount > 0.001F && definition.isInteractive() && definition.control().isEmpty()) {
            dest.translate(0.0F, 0.6F * hoverAmount, 0.0F);
        }
        dest.rotate(Transform.toQuaternion(transform.rotation(), scratchRotation));
        dest.rotate(Transform.toQuaternion(rotation.value(), scratchRotation));
        if (live) {
            dest.rotate(Transform.toQuaternion(spinAngle, scratchRotation));
        }
        Vector3fc base = transform.scale();
        Vector3fc extra = scale.value();
        dest.scale(base.x() * extra.x(), base.y() * extra.y(), base.z() * extra.z());
        // In the node's own space: a scaled control travels and pivots with its model.
        if (live && control) {
            definition.control().ifPresent(binding -> applyControl(dest, binding));
        }
    }

    private void applyControl(Matrix4f dest, ControlBinding control) {
        float movement = control.movement(controlFraction);
        Vector3fc axis = control.axis();
        if (control.type().rotates()) {
            Vector3fc pivot = control.pivot();
            dest.translate(pivot.x(), pivot.y(), pivot.z());
            dest.rotate((float) Math.toRadians(movement), axis.x(), axis.y(), axis.z());
            dest.translate(-pivot.x(), -pivot.y(), -pivot.z());
        } else {
            dest.translate(axis.x() * movement, axis.y() * movement, axis.z() * movement);
        }
    }

    @Override
    public String toString() {
        return "Node[" + definition.id() + "]";
    }
}

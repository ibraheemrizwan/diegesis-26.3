package com.pockyl.diegesis.api.client;
import com.mojang.blaze3d.vertex.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import com.pockyl.diegesis.impl.client.render.CapturedMesh;
/** Geometry sinks used by scene painters. Batches are frozen during extraction and submitted by Fabric. */
@FunctionalInterface
public interface SceneBuffers {
    VertexConsumer getBuffer(RenderType type);
    ThreadLocal<List<Consumer<SubmitNodeCollector>>> FRAME=new ThreadLocal<>();
    static void beginFrame() { FRAME.set(new ArrayList<>()); }
    static List<Consumer<SubmitNodeCollector>> finishFrame() { var frame=List.copyOf(FRAME.get());FRAME.remove();return frame; }
    static void record(Consumer<SubmitNodeCollector> submission) {
        var frame=FRAME.get();
        if(frame==null) throw new IllegalStateException("Scene geometry must be extracted before drawing");
        frame.add(submission);
    }
    static BufferSource immediate() {return new BufferSource();}
    final class BufferSource implements SceneBuffers,AutoCloseable {
        private final Map<RenderType,ByteBufferBuilder> storage=new LinkedHashMap<>();
        private final Map<RenderType,BufferBuilder> batches=new LinkedHashMap<>();
        @Override public VertexConsumer getBuffer(RenderType type) {
            return batches.computeIfAbsent(type,t -> new BufferBuilder(storage.computeIfAbsent(t,key -> new ByteBufferBuilder(65536)),t.primitiveTopology(),t.format()));
        }
        public void endLastBatch() { }
        public void endBatch(RenderType type) {
            var builder=batches.remove(type);
            if(builder==null) return;
            var mesh=builder.build();
            if(mesh==null) return;
            try(var capture=new CapturedMesh(mesh)) {
                var submission=capture.at(type,0,0,0);
                SceneBuffers.record(submission::submit);
            }
        }
        public void endBatch() {for(var type:List.copyOf(batches.keySet())) endBatch(type);}
        @Override public void close() {batches.clear();storage.values().forEach(ByteBufferBuilder::close);storage.clear();}
    }
}

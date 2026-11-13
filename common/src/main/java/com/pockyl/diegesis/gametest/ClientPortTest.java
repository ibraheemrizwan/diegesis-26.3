package com.pockyl.diegesis.gametest;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.Diegesis;
import com.pockyl.diegesis.api.client.*;
import com.pockyl.diegesis.api.scene.*;
import com.pockyl.diegesis.impl.client.DiegesisClientRuntime;
import com.pockyl.diegesis.impl.client.focus.*;
import com.pockyl.diegesis.impl.client.render.*;
/** Actual native client rendering, focus, projection and resource reload in an isolated world. */
public final class ClientPortTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.computeOnClient(client -> {
            var id=DiegesisMod.id("port_test");
            Diegesis.station(id).blocks(Blocks.FURNACE).clientOnly().scene(id).hidesBlock().register();
            DiegesisClient.registerModel(net.minecraft.resources.Identifier.withDefaultNamespace("block/furnace"));
            DiegesisClient.registerScene(id,SceneDefinition.builder()
                    .node(NodeDefinition.builder("body").visual(Visual.ofModel(net.minecraft.resources.Identifier.withDefaultNamespace("block/furnace"))).build())
                    .node(NodeDefinition.builder("item").at(8,18,8).visual(Visual.ofItem(Items.DIAMOND_SWORD)).build())
                    .node(NodeDefinition.builder("smooth").at(16,20,8).build()).build());
            DiegesisClient.registerBehavior(id,view -> new StationBehavior() {
                @Override public void onSceneReady(StationView station) {
                    station.node("smooth").orElseThrow().setPainter(new NodePainter() {
                        @Override public boolean smooth() {return true;}
                        @Override public void paint(com.mojang.blaze3d.vertex.PoseStack pose,SceneBuffers buffers,int light) {
                            pose.pushPose();pose.scale(6,6,6);
                            SceneModels.draw(pose.last(),buffers.getBuffer(net.minecraft.client.renderer.Sheets.cutoutBlockItemSheet()),Blocks.GOLD_BLOCK.defaultBlockState(),SceneModels.block(Blocks.GOLD_BLOCK.defaultBlockState()),1,1,1,light,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                            pose.popPose();
                        }
                    });
                }
            });
            DiegesisClient.setDebugView(true);
            return null;
        });
        var initialReload=context.computeOnClient(client -> client.reloadResourcePacks());
        context.waitFor(client -> initialReload.isDone(),1200);initialReload.join();
        try(var world=context.worldBuilder().create()) {
            world.getServer().runCommand("setblock 0 -60 0 minecraft:furnace");
            world.getServer().runCommand("tp @a 0.5 -60 -2.5 0 0");
            world.getConnection().waitForChunksRender();context.waitTicks(20);
            context.computeOnClient(client -> {
                var pos=new BlockPos(0,-60,0);
                var hit=new BlockHitResult(Vec3.atCenterOf(pos),Direction.NORTH,pos,false);
                var result=DiegesisClientRuntime.onUseBlock(client.player,net.minecraft.world.InteractionHand.MAIN_HAND,hit);
                if(result==null) throw new AssertionError("Station did not intercept block interaction");
                return null;
            });
            context.waitFor(client -> FocusController.get().isInteractive(),1200);
            context.waitFor(client -> SceneRenderer.geometrySubmissions()>0 && SmoothPass.successfulFrames()>0,1200);
            context.waitTicks(20);context.takeScreenshot("diegesis-focused-station");
            var reload=context.computeOnClient(client -> client.reloadResourcePacks());
            context.waitFor(client -> reload.isDone(),1200);reload.join();context.waitTicks(30);
            context.takeScreenshot("diegesis-after-resource-reload");
            context.computeOnClient(client -> {FocusController.get().requestExit();return null;});
            context.waitFor(client -> !FocusController.get().isActive(),1200);
        }
        DiegesisMod.LOGGER.info("DIEGESIS_CLIENT: native scene, item, focus, supersampling and reload passed");
    }
}

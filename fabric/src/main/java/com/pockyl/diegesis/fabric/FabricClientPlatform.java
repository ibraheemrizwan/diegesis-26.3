package com.pockyl.diegesis.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;

import com.pockyl.diegesis.impl.network.Packets;
import com.pockyl.diegesis.platform.ClientPlatform;

public final class FabricClientPlatform implements ClientPlatform {
    static final java.util.Map<Identifier, net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey<BlockStateModel>> MODELS=new java.util.concurrent.ConcurrentHashMap<>();
    static net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey<BlockStateModel> key(Identifier id) {
        return MODELS.computeIfAbsent(id,k -> net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey.create(k::toString));
    }
    @Override
    public boolean serverHasChannel() {
        return Minecraft.getInstance().getConnection() != null && FabricNetwork.serverHasChannel();
    }

    @Override
    public void sendToServer(Packets.ClientPacket packet) {
        if (serverHasChannel()) {
            FabricNetwork.sendToServer(packet);
        }
    }

    @Override
    public BlockStateModel getModel(Identifier id) {
        var manager=Minecraft.getInstance().getModelManager();
        var key=MODELS.get(id);
        var model=key==null ? null : manager.getModel(key);
        return model!=null ? model : manager.getBlockStateModelSet().missingModel();
    }
}

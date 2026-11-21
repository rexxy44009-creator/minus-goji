package com.example.godzilla.client;

import com.example.godzilla.client.fx.BlastFx;
import com.example.godzilla.client.fx.Fx;
import com.example.godzilla.entity.FxEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class FxRenderer extends EntityRenderer<FxEntity> {
    public FxRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override public ResourceLocation getTextureLocation(FxEntity entity) { return Fx.GLOW; }

    /** Effects are huge and must never be frustum-culled. */
    @Override public boolean shouldRender(FxEntity entity, Frustum frustum, double camX, double camY, double camZ) { return true; }

    @Override
    public void render(FxEntity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        BlastFx.render(entity, partialTick, poseStack, buffers);
    }
}

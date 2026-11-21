package com.example.godzilla.client;

import com.example.godzilla.GodzillaMod;
import com.example.godzilla.client.fx.BeamFx;
import com.example.godzilla.client.fx.Fx;
import com.example.godzilla.client.mesh.GodzillaMesh;
import com.example.godzilla.client.mesh.RigData;
import com.example.godzilla.entity.GodzillaAnim;
import com.example.godzilla.entity.GodzillaEntity;
import com.example.godzilla.entity.PlateProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Renders Godzilla as one smooth skinned mesh (his own textures, lit by vanilla lighting), animated procedurally,
 * then layers the custom plate-energy / charge / heat-ray effects on top.
 */
public class GodzillaRenderer extends EntityRenderer<GodzillaEntity> {
    private static final ResourceLocation BODY = ResourceLocation.fromNamespaceAndPath(GodzillaMod.MODID, "textures/entity/godzilla_body.png");
    private static final ResourceLocation PLATES = ResourceLocation.fromNamespaceAndPath(GodzillaMod.MODID, "textures/entity/godzilla_plates.png");

    private final float[] mats = new float[RigData.BONE_COUNT * 12];
    private final float[] energy = new float[RigData.PLATE_CLUSTERS];

    public GodzillaRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 9.0F;
    }

    @Override public ResourceLocation getTextureLocation(GodzillaEntity entity) { return BODY; }

    /** 29 blocks tall with a 40 block tail and a 130 block ray: never frustum-cull him. */
    @Override public boolean shouldRender(GodzillaEntity entity, Frustum frustum, double camX, double camY, double camZ) { return true; }

    @Override
    public void render(GodzillaEntity g, float entityYaw, float pt, PoseStack ps, MultiBufferSource buffers, int light) {
        GodzillaMesh mesh = GodzillaMesh.get();
        GodzillaAnim.update(g, g.animState, pt, mats);
        mesh.skin(mats, g.animState.plateExt, PlateProfile.PUSH_BLOCKS, PlateProfile.PUSH_SCALE);

        float time = (g.tickCount + pt) / 20.0F;
        float bodyYaw = Mth.rotLerp(pt, g.yBodyRotO, g.yBodyRot);
        float yawRad = (180.0F - bodyYaw) * Mth.DEG_TO_RAD;
        float[] amt = BeamFx.energy(g, pt, time, energy);

        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYaw));
        PoseStack.Pose pose = ps.last();
        int overlay = g.isDeadOrDying() ? OverlayTexture.NO_OVERLAY : LivingEntityRenderer.getOverlayCoords(g, 0.0F);
        for (int part = 0; part < mesh.partCount; part++) {
            ResourceLocation tex = mesh.partTex[part] == 1 ? PLATES : BODY;
            mesh.drawPart(buffers.getBuffer(RenderType.entityCutoutNoCull(tex)), pose, part, light, overlay);
        }
        BeamFx.plateEnergy(mesh, amt, pose, buffers, time);
        ps.popPose();

        // world-oriented effects, in entity-local coordinates
        Vec3 ePos = new Vec3(Mth.lerp(pt, g.xo, g.getX()), Mth.lerp(pt, g.yo, g.getY()), Mth.lerp(pt, g.zo, g.getZ()));
        Fx.Cam cam = new Fx.Cam(ePos);
        int m = RigData.HEAD * 12;
        float rx = RigData.MOUTH_X, ry = RigData.MOUTH_Y, rz = RigData.MOUTH_Z;
        float mx = mats[m] * rx + mats[m + 1] * ry + mats[m + 2] * rz + mats[m + 3];
        float my = mats[m + 4] * rx + mats[m + 5] * ry + mats[m + 6] * rz + mats[m + 7];
        float mz = mats[m + 8] * rx + mats[m + 9] * ry + mats[m + 10] * rz + mats[m + 11];
        float cs = Mth.cos(yawRad), sn = Mth.sin(yawRad);
        Vec3 mouth = new Vec3(mx * cs + mz * sn, my, -mx * sn + mz * cs);
        BeamFx.world(g, mesh, yawRad, ePos, cam, ps.last(), buffers, pt, time, amt, mouth);

        super.render(g, entityYaw, pt, ps, buffers, light);
    }
}

package com.example.godzilla.client.fx;

import com.example.godzilla.GodzillaMod;
import com.example.godzilla.GodzillaTuning;
import com.example.godzilla.client.mesh.GodzillaMesh;
import com.example.godzilla.entity.GodzillaEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Everything that makes the heat ray look like the film, drawn with custom geometry (no vanilla particles):
 *  - the dorsal plates blaze with jagged cyan-blue energy flames and crackling arcs as they push out,
 *  - a plasma orb gathers at the mouth, air streaks in while he inhales,
 *  - the ray itself: a white-hot core wrapped in cyan plasma with twisting streaks, a flare at the mouth and a blinding
 *    impact with shock rings and flying sparks where it meets the ground.
 */
public final class BeamFx {
    private BeamFx() {}

    private static final ResourceLocation PLATES_TEX = ResourceLocation.fromNamespaceAndPath(GodzillaMod.MODID, "textures/entity/godzilla_plates.png");

    /** Energy amount (0..1) of each plate cluster right now. */
    public static float[] energy(GodzillaEntity g, float pt, float time, float[] out) {
        for (int c = 0; c < out.length; c++) {
            if (!g.isPlateLit(c, pt)) { out[c] = 0.0F; continue; }
            float ext = g.getPlateExtension(c, pt);
            float base = g.getAction() == GodzillaEntity.ACT_CHARGE ? Math.max(0.12F, ext) : 1.0F;
            float flick = 0.86F + 0.14F * Mth.sin(time * 31.0F + c * 1.7F);
            out[c] = base * flick;
        }
        return out;
    }

    // ================================================================== inside the model's pose: tinted + inflated plate shells
    public static void plateEnergy(GodzillaMesh mesh, float[] amt, PoseStack.Pose pose, MultiBufferSource buf, float time) {
        float[] a1 = new float[amt.length], a2 = new float[amt.length], a3 = new float[amt.length];
        boolean any = false;
        for (int i = 0; i < amt.length; i++) {
            a1[i] = amt[i] * 0.95F; a2[i] = amt[i] * 0.55F; a3[i] = amt[i] * 0.28F;
            any |= amt[i] > 0.01F;
        }
        if (!any) return;
        mesh.drawPlateGlow(buf.getBuffer(Fx.additive(PLATES_TEX)), pose, Fx.rgb(0.28F, 0.72F, 1.0F), a1, 0.0F, true, 0, 0);          // the plates themselves glow
        mesh.drawPlateGlow(buf.getBuffer(Fx.additive(Fx.PLASMA)), pose, Fx.rgb(0.35F, 0.8F, 1.0F), a2, 0.32F, false, time * 0.6F, -time * 1.4F);   // tight energy skin
        mesh.drawPlateGlow(buf.getBuffer(Fx.additive(Fx.FIRE)), pose, Fx.rgb(0.2F, 0.55F, 1.0F), a3, 0.85F, false, -time * 0.5F, -time * 0.9F);   // soft halo
    }

    // ================================================================== world-oriented effects (entity-local axes)
    /**
     * @param mouth mouth position, entity-local world axes (blocks)
     */
    public static void world(GodzillaEntity g, GodzillaMesh mesh, float yawRad, Vec3 entityPos, Fx.Cam cam, PoseStack.Pose pose,
                             MultiBufferSource buf, float pt, float time, float[] amt, Vec3 mouth) {
        int id = g.getId();
        float cs = Mth.cos(yawRad), sn = Mth.sin(yawRad);
        int n = amt.length;

        // ---- jagged energy flames growing from every spike tip of every glowing plate
        VertexConsumer fl = buf.getBuffer(Fx.additive(Fx.FLAME));
        for (int c = 0; c < n; c++) {
            if (amt[c] < 0.04F) continue;
            int[] tips = mesh.tips[c];
            for (int k = 0; k < tips.length; k++) {
                int v = tips[k];
                float mx = mesh.outPos[v * 3], my = mesh.outPos[v * 3 + 1], mz = mesh.outPos[v * 3 + 2];
                float x = mx * cs + mz * sn, y = my, z = -mx * sn + mz * cs;
                int seed = id * 131 + c * 17 + k;
                float flick = 0.7F + 0.3F * Mth.sin(time * 17.0F + seed) * Mth.sin(time * 9.0F + seed * 1.3F);
                float h = (3.4F + 3.6F * Fx.rand(seed)) * (0.35F + amt[c]) * flick;
                float w = (0.9F + 0.6F * Fx.rand(seed, 1)) * (0.5F + 0.7F * amt[c]);
                float lean = 0.5F * Mth.sin(time * 6.0F + seed);
                Fx.upright(fl, pose, cam, x, y - 0.2F, z, w * 1.9F, h, lean, Fx.rgb(0.28F, 0.7F, 1.0F), Fx.ch(0.9F * amt[c]));
                Fx.upright(fl, pose, cam, x, y - 0.1F, z, w * 1.0F, h * 0.72F, lean * 0.8F, Fx.rgb(0.8F, 0.96F, 1.0F), Fx.ch(0.85F * amt[c]));
            }
        }
        VertexConsumer gl = buf.getBuffer(Fx.additive(Fx.GLOW));
        for (int c = 0; c < n; c++) {          // soft light around each plate group
            if (amt[c] < 0.04F) continue;
            float mx = mesh.clusterCenter[c * 3], my = mesh.clusterCenter[c * 3 + 1], mz = mesh.clusterCenter[c * 3 + 2];
            Fx.billboard(gl, pose, cam, mx * cs + mz * sn, my + 1.0F, -mx * sn + mz * cs, 5.5F, 5.5F, 0, Fx.rgb(0.25F, 0.65F, 1.0F), Fx.ch(0.42F * amt[c]));
        }

        // ---- crackling arcs between neighbouring plates, and from the plates to the mouth near the end of the charge
        VertexConsumer bolt = buf.getBuffer(Fx.additive(Fx.BOLT));
        int qt = (int) (time * 13.0F);
        for (int c = 0; c + 1 < n; c++) {
            if (amt[c] < 0.3F || amt[c + 1] < 0.3F) continue;
            int va = mesh.tips[c][0], vb = mesh.tips[c + 1][0];
            arc(bolt, pose, cam, mesh, va, vb, cs, sn, qt * 7 + c, 0.6F, 0.28F, 0.75F * Math.min(amt[c], amt[c + 1]));
        }
        int act = g.getAction();
        if (act == GodzillaEntity.ACT_INHALE || (act == GodzillaEntity.ACT_CHARGE && g.getActionTicks() > GodzillaTuning.CHARGE_TICKS * 0.5F)) {
            for (int k = 0; k < 5; k++) {
                int c = (int) (Fx.rand(qt, k) * n);
                if (amt[c] < 0.3F) continue;
                int v = mesh.tips[c][(int) (Fx.rand(qt, k + 9) * mesh.tips[c].length)];
                float mx = mesh.outPos[v * 3], my = mesh.outPos[v * 3 + 1], mz = mesh.outPos[v * 3 + 2];
                arcPoints(bolt, pose, cam, mx * cs + mz * sn, my, -mx * sn + mz * cs, (float) mouth.x, (float) mouth.y, (float) mouth.z, qt * 13 + k, 1.4F, 0.4F, 0.8F);
            }
        }

        // ---- charge orb / inhale streaks
        float progress = act == GodzillaEntity.ACT_CHARGE ? Fx.clamp01((g.getActionTicks() + pt) / GodzillaTuning.CHARGE_TICKS) : 1.0F;
        if (act == GodzillaEntity.ACT_CHARGE && progress > 0.4F || act == GodzillaEntity.ACT_INHALE || act == GodzillaEntity.ACT_VENT && g.getActionTicks() < 12) {
            float size = act == GodzillaEntity.ACT_CHARGE ? 1.5F + 8.0F * Fx.smooth((progress - 0.4F) / 0.6F)
                    : act == GodzillaEntity.ACT_INHALE ? 9.5F + 6.0F * Fx.smooth((g.getActionTicks() + pt) / GodzillaTuning.INHALE_TICKS)
                    : 12.0F * (1.0F - (g.getActionTicks() + pt) / 12.0F);
            float mx = (float) mouth.x, my = (float) mouth.y, mz = (float) mouth.z;
            Fx.billboard(gl, pose, cam, mx, my, mz, size * 1.5F, size * 1.5F, 0, Fx.rgb(0.35F, 0.75F, 1.0F), Fx.ch(0.55F));
            Fx.billboard(gl, pose, cam, mx, my, mz, size * 0.8F, size * 0.8F, 0, Fx.rgb(0.9F, 1.0F, 1.0F), Fx.ch(0.95F));
            VertexConsumer fa = buf.getBuffer(Fx.additive(Fx.FLARE));
            Fx.billboard(fa, pose, cam, mx, my, mz, size * 2.2F, size * 2.2F, time * 1.3F, Fx.rgb(0.5F, 0.85F, 1.0F), Fx.ch(0.8F));
            VertexConsumer fi = buf.getBuffer(Fx.additive(Fx.FIRE));
            Fx.sphere(fi, pose, mx, my, mz, size * 0.5F, 12, 18, 0.18F, time * 4.0F, time * 0.6F, -time * 0.9F, Fx.rgb(0.45F, 0.85F, 1.0F), Fx.ch(0.9F));
        }
        if (act == GodzillaEntity.ACT_INHALE) {     // air streaming in
            VertexConsumer sp = buf.getBuffer(Fx.additive(Fx.SPARK));
            for (int i = 0; i < 44; i++) {
                float u = (time * 1.7F + Fx.rand(id, i)) % 1.0F;
                float th = Fx.rand(id, i + 50) * Mth.TWO_PI, el = (Fx.rand(id, i + 90) - 0.5F) * 2.0F;
                float dx = Mth.cos(th) * (1.0F - Math.abs(el) * 0.4F), dy = el * 0.8F, dz = Mth.sin(th) * (1.0F - Math.abs(el) * 0.4F);
                float dist = 3.0F + 34.0F * (1.0F - u) * (1.0F - u);
                float x = (float) mouth.x + dx * dist, y = (float) mouth.y + dy * dist, z = (float) mouth.z + dz * dist;
                float tail = dist + 3.0F;
                Fx.ribbon(sp, pose, cam, x, y, z, (float) mouth.x + dx * tail, (float) mouth.y + dy * tail, (float) mouth.z + dz * tail, 0.18F, 0.05F, 0, 1,
                        Fx.rgb(0.7F, 0.95F, 1.0F), Fx.ch(u), 0);
            }
        }

        // ---- the ray
        if (act == GodzillaEntity.ACT_FIRE && g.getBeamLength() > 1.0F) {
            beam(g, cam, pose, buf, time, entityPos, mouth);
        }
    }

    private static void arc(VertexConsumer vc, PoseStack.Pose pose, Fx.Cam cam, GodzillaMesh mesh, int va, int vb, float cs, float sn,
                            int seed, float amp, float width, float alpha) {
        float ax = mesh.outPos[va * 3], ay = mesh.outPos[va * 3 + 1], az = mesh.outPos[va * 3 + 2];
        float bx = mesh.outPos[vb * 3], by = mesh.outPos[vb * 3 + 1], bz = mesh.outPos[vb * 3 + 2];
        arcPoints(vc, pose, cam, ax * cs + az * sn, ay, -ax * sn + az * cs, bx * cs + bz * sn, by, -bx * sn + bz * cs, seed, amp, width, alpha);
    }

    /** A jagged lightning bolt from A to B (re-rolled by `seed`, which the caller quantises in time so it flickers). */
    private static void arcPoints(VertexConsumer vc, PoseStack.Pose pose, Fx.Cam cam, float ax, float ay, float az, float bx, float by, float bz,
                                  int seed, float amp, float width, float alpha) {
        int segs = 7;
        float px = ax, py = ay, pz = az;
        for (int i = 1; i <= segs; i++) {
            float f = i / (float) segs;
            float jx = i == segs ? 0 : (Fx.rand(seed, i) - 0.5F) * 2.0F * amp, jy = i == segs ? 0 : (Fx.rand(seed, i + 20) - 0.5F) * 2.0F * amp,
                    jz = i == segs ? 0 : (Fx.rand(seed, i + 40) - 0.5F) * 2.0F * amp;
            float x = ax + (bx - ax) * f + jx, y = ay + (by - ay) * f + jy, z = az + (bz - az) * f + jz;
            Fx.ribbon(vc, pose, cam, px, py, pz, x, y, z, width, width, 0, 1, Fx.rgb(0.75F, 0.95F, 1.0F), Fx.ch(alpha), Fx.ch(alpha));
            px = x; py = y; pz = z;
        }
    }

    // ================================================================== the heat ray
    private static void beam(GodzillaEntity g, Fx.Cam cam, PoseStack.Pose pose, MultiBufferSource buf, float time, Vec3 entityPos, Vec3 mouth) {
        float yaw = g.getBeamYaw() * Mth.DEG_TO_RAD, pitch = g.getBeamPitch() * Mth.DEG_TO_RAD;
        Vec3 dir = new Vec3(-Mth.sin(yaw) * Mth.cos(pitch), -Mth.sin(pitch), Mth.cos(yaw) * Mth.cos(pitch));
        float L = g.getBeamLength();
        Vec3 from = mouth;
        Vec3 to = from.add(dir.scale(L));
        // the ray ends where it meets the world, like the film
        Vec3 worldFrom = entityPos.add(from), worldTo = entityPos.add(to);
        BlockHitResult hit = g.level().clip(new ClipContext(worldFrom, worldTo, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, g));
        boolean hitSomething = hit.getType() == HitResult.Type.BLOCK;
        if (hitSomething) { to = hit.getLocation().subtract(entityPos); L = (float) to.subtract(from).length(); }
        float fx = (float) from.x, fy = (float) from.y, fz = (float) from.z;
        float dx = (float) dir.x, dy = (float) dir.y, dz = (float) dir.z;
        float fadeIn = Fx.clamp01(L / 14.0F);
        float pulse = 1.0F + 0.07F * Mth.sin(time * 44.0F);

        VertexConsumer pl = buf.getBuffer(Fx.additive(Fx.PLASMA));
        int N = 44;
        for (int i = 0; i < N; i++) {
            float s0 = i / (float) N, s1 = (i + 1) / (float) N;
            float d0 = s0 * L, d1 = s1 * L;
            float ax = fx + dx * d0, ay = fy + dy * d0, az = fz + dz * d0, bx = fx + dx * d1, by = fy + dy * d1, bz = fz + dz * d1;
            float grow0 = 1.0F + 0.5F * s0, grow1 = 1.0F + 0.5F * s1;
            float u0 = d0 / 9.0F - time * 3.4F, u1 = d1 / 9.0F - time * 3.4F;
            float ripple0 = 1.0F + 0.08F * Mth.sin(d0 * 0.6F - time * 30.0F), ripple1 = 1.0F + 0.08F * Mth.sin(d1 * 0.6F - time * 30.0F);
            Fx.ribbon(pl, pose, cam, ax, ay, az, bx, by, bz, 7.5F * grow0 * pulse * ripple0, 7.5F * grow1 * pulse * ripple1, u0 * 0.6F, u1 * 0.6F, Fx.rgb(0.16F, 0.56F, 1.0F), Fx.ch(0.42F * fadeIn), Fx.ch(0.42F * fadeIn));
            Fx.ribbon(pl, pose, cam, ax, ay, az, bx, by, bz, 3.9F * grow0 * pulse * ripple0, 3.9F * grow1 * pulse * ripple1, u0, u1, Fx.rgb(0.5F, 0.88F, 1.0F), Fx.ch(0.78F * fadeIn), Fx.ch(0.78F * fadeIn));
            Fx.ribbon(pl, pose, cam, ax, ay, az, bx, by, bz, 1.7F * grow0 * pulse, 1.7F * grow1 * pulse, u0 * 1.4F, u1 * 1.4F, 0xFFFFFF, Fx.ch(fadeIn), Fx.ch(fadeIn));
        }
        // twisting plasma streaks wound around the core
        float px = dy * 0.0F - dz * 1.0F, py = dz * 0.0F - dx * 0.0F + 0.0F, pz = dx * 1.0F - dy * 0.0F;   // cross(dir, up) ~ any perpendicular
        float pl0 = (float) Math.sqrt(px * px + py * py + pz * pz);
        if (pl0 < 1.0E-3F) { px = 1.0F; py = 0.0F; pz = 0.0F; pl0 = 1.0F; }
        px /= pl0; py /= pl0; pz /= pl0;
        float qx = dy * pz - dz * py, qy = dz * px - dx * pz, qz = dx * py - dy * px;
        for (int k = 0; k < 4; k++) {
            float prevx = 0, prevy = 0, prevz = 0; boolean first = true;
            for (int i = 0; i <= 28; i++) {
                float d = L * i / 28.0F;
                float ang = d * 0.35F - time * 14.0F + k * 1.5708F;
                float r = (2.4F + 1.2F * d / Math.max(L, 1.0F)) * pulse;
                float x = fx + dx * d + (px * Mth.cos(ang) + qx * Mth.sin(ang)) * r;
                float y = fy + dy * d + (py * Mth.cos(ang) + qy * Mth.sin(ang)) * r;
                float z = fz + dz * d + (pz * Mth.cos(ang) + qz * Mth.sin(ang)) * r;
                if (!first) Fx.ribbon(pl, pose, cam, prevx, prevy, prevz, x, y, z, 0.55F, 0.55F, d / 5.0F - time * 6.0F, (d + L / 28.0F) / 5.0F - time * 6.0F, Fx.rgb(0.8F, 0.96F, 1.0F), Fx.ch(0.85F * fadeIn), Fx.ch(0.85F * fadeIn));
                prevx = x; prevy = y; prevz = z; first = false;
            }
        }

        VertexConsumer gl = buf.getBuffer(Fx.additive(Fx.GLOW));
        VertexConsumer fa = buf.getBuffer(Fx.additive(Fx.FLARE));
        Fx.billboard(gl, pose, cam, fx, fy, fz, 13.0F * pulse, 13.0F * pulse, 0, Fx.rgb(0.7F, 0.95F, 1.0F), Fx.ch(0.95F * fadeIn));       // blaze at the mouth
        Fx.billboard(fa, pose, cam, fx, fy, fz, 20.0F, 20.0F, time * 1.1F, Fx.rgb(0.6F, 0.9F, 1.0F), Fx.ch(0.9F * fadeIn));
        // wide haze along the whole ray (the light it throws)
        VertexConsumer pl2 = buf.getBuffer(Fx.additive(Fx.GLOW));
        Fx.ribbon(pl2, pose, cam, fx, fy, fz, fx + dx * L, fy + dy * L, fz + dz * L, 17.0F, 26.0F, 0, 1, Fx.rgb(0.12F, 0.45F, 1.0F), Fx.ch(0.3F * fadeIn), Fx.ch(0.3F * fadeIn));
        // sparks racing along the ray
        VertexConsumer sp = buf.getBuffer(Fx.additive(Fx.SPARK));
        for (int i = 0; i < 70; i++) {
            float d = ((time * (30.0F + 40.0F * Fx.rand(i)) + Fx.rand(i, 5) * L) % L);
            float off = (Fx.rand(i, 9) - 0.5F) * 9.0F, off2 = (Fx.rand(i, 13) - 0.5F) * 9.0F;
            float sz = 0.3F + 0.5F * Fx.rand(i, 17);
            Fx.billboard(sp, pose, cam, fx + dx * d + px * off + qx * off2, fy + dy * d + py * off + qy * off2, fz + dz * d + pz * off + qz * off2, sz, sz, 0, Fx.rgb(0.8F, 0.97F, 1.0F), Fx.ch(0.9F * fadeIn));
        }

        // ---- impact
        if (hitSomething) {
            float ex = (float) to.x, ey = (float) to.y, ez = (float) to.z;
            float ip = 1.0F + 0.1F * Mth.sin(time * 36.0F);
            Fx.billboard(gl, pose, cam, ex, ey + 1.0F, ez, 30.0F * ip, 30.0F * ip, 0, Fx.rgb(0.8F, 0.97F, 1.0F), Fx.ch(0.95F));
            Fx.billboard(gl, pose, cam, ex, ey + 2.0F, ez, 52.0F, 40.0F, 0, Fx.rgb(0.25F, 0.65F, 1.0F), Fx.ch(0.5F));
            Fx.billboard(fa, pose, cam, ex, ey + 1.5F, ez, 26.0F, 26.0F, -time * 0.9F, Fx.rgb(0.9F, 1.0F, 1.0F), Fx.ch(0.9F));
            VertexConsumer rg = buf.getBuffer(Fx.additive(Fx.RING));
            for (int k = 0; k < 3; k++) {
                float f = ((time * 1.6F + k / 3.0F) % 1.0F);
                float r = 3.0F + 30.0F * f;
                Fx.annulus(rg, pose, ex, ey + 0.4F, ez, r - 2.0F, r + 2.5F * (1.0F + f), 48, time, Fx.rgb(0.6F, 0.92F, 1.0F), Fx.ch(1.0F - f), Fx.ch(1.0F - f));
            }
            for (int i = 0; i < 60; i++) {   // molten debris sprayed up and out
                float ang = Fx.rand(i, 21) * Mth.TWO_PI, spd = 4.0F + 20.0F * Fx.rand(i, 22), vy = 8.0F + 26.0F * Fx.rand(i, 23);
                float t = ((time * 1.4F + Fx.rand(i, 24)) % 1.0F) * 1.8F;
                float x = ex + Mth.cos(ang) * spd * t, z = ez + Mth.sin(ang) * spd * t, y = ey + Math.max(0.0F, vy * t - 9.0F * t * t);
                float sz = 0.3F + 0.5F * Fx.rand(i, 25);
                Fx.billboard(sp, pose, cam, x, y, z, sz, sz, 0, Fx.lerpRgb(Fx.rgb(0.9F, 0.98F, 1.0F), Fx.rgb(1.0F, 0.6F, 0.25F), t / 1.8F), Fx.ch(1.0F - t / 1.8F));
            }
        }
    }
}

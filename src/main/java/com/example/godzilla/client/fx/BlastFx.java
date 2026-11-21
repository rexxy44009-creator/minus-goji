package com.example.godzilla.client.fx;

import com.example.godzilla.entity.FxEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Procedural renderers for FxEntity. Every effect is a pure function of its age T (ticks + partial tick):
 * a fireball that boils, a ground shockwave with a dust wall, a fire-and-smoke stem, a rolling mushroom cap,
 * flying debris and embers, volumetric smoke puffs. Nothing is stored between frames.
 */
public final class BlastFx {
    private BlastFx() {}

    public static void render(FxEntity e, float pt, PoseStack ps, MultiBufferSource buf) {
        float T = e.tickCount + pt;
        Vec3 origin = new Vec3(Mth.lerp(pt, e.xo, e.getX()), Mth.lerp(pt, e.yo, e.getY()), Mth.lerp(pt, e.zo, e.getZ()));
        Fx.Cam cam = new Fx.Cam(origin);
        PoseStack.Pose pose = ps.last();
        float S = e.getScale();
        switch (e.getKind()) {
            case FxEntity.KIND_BLAST -> blast(e.getId(), T, S, cam, pose, buf);
            case FxEntity.KIND_STOMP -> stomp(e.getId(), T, S, cam, pose, buf);
            case FxEntity.KIND_STEP -> step(e.getId(), T, S, cam, pose, buf);
            case FxEntity.KIND_BURST -> burst(e.getId(), T, S, cam, pose, buf);
            default -> radiation(e.getId(), T, S, e.getLife(), cam, pose, buf);
        }
    }

    // ================================================================== the atomic blast
    private static void blast(int id, float T, float S, Fx.Cam cam, PoseStack.Pose pose, MultiBufferSource buf) {
        float fade = Fx.clamp01((240.0F - T) / 45.0F);
        float h = 135.0F * S * (1.0F - (float) Math.exp(-T / 45.0F));

        // ---------------- alpha-blended smoke
        VertexConsumer sm = buf.getBuffer(Fx.alpha(Fx.SMOKE));
        if (T < 70.0F) {   // dust wall riding the shockwave
            float R = Math.min(105.0F * S, 2.9F * T * S);
            float a = Fx.clamp01(1.0F - T / 60.0F);
            Fx.band(sm, pose, 0, 0.2F, 0, R, (9.0F + 0.2F * R) * S, 72, T * 0.02F, 0.0F, Fx.rgb(0.62F, 0.48F, 0.34F), Fx.ch(0.85F * a));
            Fx.band(sm, pose, 0, 0.2F, 0, R * 0.86F, (6.0F + 0.14F * R) * S, 60, -T * 0.015F + 0.5F, 0.0F, Fx.rgb(0.5F, 0.4F, 0.3F), Fx.ch(0.7F * a));
        }
        if (T > 2.0F) {    // the stem
            final float tt = T, ss = S;
            Fx.Radius rf = y01 -> (7.0F + 15.0F * (float) Math.pow(1.0F - y01, 3.0) + 3.0F * Mth.sin(y01 * 7.0F + tt * 0.12F)) * ss;
            int bottom = Fx.lerpRgb(Fx.rgb(0.62F, 0.38F, 0.22F), Fx.rgb(0.32F, 0.31F, 0.30F), T / 110.0F);
            Fx.column(sm, pose, 0, 0, 0, h, 26, 28, rf, 0.0F, -T * 0.015F, bottom, Fx.rgb(0.26F, 0.25F, 0.25F),
                    Fx.ch(0.93F * fade), Fx.ch(0.93F * fade));
        }
        if (T > 6.0F) {    // the rolling cap
            float R = 58.0F * S * (1.0F - (float) Math.exp(-(T - 6.0F) / 55.0F));
            float m = 0.36F * R + 3.0F * S;
            Fx.torus(sm, pose, 0, h + 6.0F * S, 0, R, m, 40, 16, 0.8F, T * 0.01F, -T * 0.03F,
                    Fx.rgb(0.27F, 0.26F, 0.26F), Fx.rgb(0.80F, 0.44F, 0.22F), Fx.ch(0.92F * fade));
        }
        for (int i = 0; i < 110; i++) {   // volumetric puffs around the stem and cap
            float r1 = Fx.rand(id, i), r2 = Fx.rand(id, i + 200), r3 = Fx.rand(id, i + 400), r4 = Fx.rand(id, i + 600);
            float x, y, z;
            if (r1 < 0.45F) {
                float f = r2;
                float ang = r3 * Mth.TWO_PI;
                float rad = (8.0F + 14.0F * (1.0F - f) * (1.0F - f)) * S * 1.15F;
                x = Mth.cos(ang) * rad; z = Mth.sin(ang) * rad; y = h * f;
            } else {
                float R = 58.0F * S * (1.0F - (float) Math.exp(-(T - 6.0F) / 55.0F));
                float m = 0.36F * R + 3.0F * S;
                float th = r2 * Mth.TWO_PI, ph = r3 * Mth.TWO_PI;
                float ring = R + m * 1.05F * Mth.cos(ph);
                x = Mth.cos(th) * ring; z = Mth.sin(th) * ring; y = h + 6.0F * S + 0.8F * m * Mth.sin(ph);
            }
            float drift = T * 0.012F * S;
            x *= 1.0F + drift * 0.05F; z *= 1.0F + drift * 0.05F; y += drift * 3.0F * r4;
            float size = (9.0F + 14.0F * r4) * S * Fx.smooth(T / 40.0F);
            float a = 0.5F * fade * Fx.smooth(T / 25.0F);
            int col = Fx.lerpRgb(Fx.rgb(0.55F, 0.36F, 0.22F), Fx.rgb(0.28F, 0.27F, 0.27F), Math.min(1.0F, T / 90.0F + 0.5F * r1));
            Fx.billboard(sm, pose, cam, x, y, z, size, size, r4 * 6.28F + T * 0.01F * (r1 - 0.5F), col, Fx.ch(a));
        }

        // ---------------- debris (dark chunks, ballistic)
        VertexConsumer ch = buf.getBuffer(Fx.alpha(Fx.CHIP));
        for (int i = 0; i < 70; i++) {
            float ang = Fx.rand(id, i + 900) * Mth.TWO_PI, sp = (8.0F + 34.0F * Fx.rand(id, i + 1000)) * S, vy = (18.0F + 38.0F * Fx.rand(id, i + 1100)) * S;
            float t = T / 20.0F;
            float x = Mth.cos(ang) * sp * t, z = Mth.sin(ang) * sp * t, y = vy * t - 0.5F * 18.0F * t * t;
            if (y < 0.0F) { y = 0.0F; }
            float landed = Fx.clamp01(1.0F - Math.max(0.0F, t - 6.0F) / 3.0F);
            float size = (0.5F + 1.6F * Fx.rand(id, i + 1200)) * S;
            if (T > 3.0F) Fx.billboard(ch, pose, cam, x, y, z, size, size, T * 0.05F * (Fx.rand(id, i + 1300) - 0.5F) * 6.0F, Fx.rgb(0.2F, 0.16F, 0.13F), Fx.ch(landed * fade));
        }

        // ---------------- additive light and fire
        VertexConsumer fi = buf.getBuffer(Fx.additive(Fx.FIRE));
        float rb = ((28.0F * (1.0F - (float) Math.exp(-T / 7.0F))) + Math.max(0.0F, T - 40.0F) * 0.1F) * S;
        float fa = 1.0F - Fx.smooth((T - 25.0F) / 70.0F);
        if (fa > 0.01F) {
            int heat = T < 10 ? Fx.rgb(1.0F, 0.98F, 0.92F) : T < 30 ? Fx.lerpRgb(Fx.rgb(1.0F, 0.98F, 0.92F), Fx.rgb(1.0F, 0.78F, 0.35F), (T - 10) / 20.0F)
                    : T < 70 ? Fx.lerpRgb(Fx.rgb(1.0F, 0.78F, 0.35F), Fx.rgb(1.0F, 0.42F, 0.15F), (T - 30) / 40.0F) : Fx.lerpRgb(Fx.rgb(1.0F, 0.42F, 0.15F), Fx.rgb(0.55F, 0.18F, 0.08F), (T - 70) / 60.0F);
            Fx.sphere(fi, pose, 0, rb * 0.55F, 0, rb, 18, 26, 0.16F, T * 0.1F, T * 0.05F, -T * 0.04F, heat, Fx.ch(fa));
            Fx.sphere(fi, pose, 0, rb * 0.5F, 0, rb * 0.72F, 14, 20, 0.12F, T * 0.13F, -T * 0.06F, T * 0.05F, Fx.rgb(1.0F, 0.96F, 0.86F), Fx.ch(fa * (1.0F - Fx.smooth(T / 30.0F))));
        }
        if (T < 130.0F) {    // glow inside the stem and under the cap
            float ga = Fx.clamp01(1.0F - T / 120.0F);
            final float tt = T, ss = S;
            Fx.Radius rf = y01 -> (6.0F + 12.0F * (float) Math.pow(1.0F - y01, 3.0)) * ss * 0.82F;
            Fx.column(fi, pose, 0, 0, 0, h * 0.97F, 18, 24, rf, 0.0F, -tt * 0.03F, Fx.rgb(1.0F, 0.62F, 0.22F), Fx.rgb(1.0F, 0.30F, 0.10F), Fx.ch(0.75F * ga), 0);
            if (T > 6.0F) {
                float R = 58.0F * S * (1.0F - (float) Math.exp(-(T - 6.0F) / 55.0F)), m = 0.36F * R + 3.0F * S;
                Fx.torus(fi, pose, 0, h + 6.0F * S, 0, R, m * 0.97F, 36, 14, 0.8F, T * 0.02F, -T * 0.05F, 0x000000, Fx.rgb(1.0F, 0.46F, 0.16F), Fx.ch(0.85F * ga));
            }
        }
        float R2 = Math.min(115.0F * S, 3.1F * T * S);
        if (T < 55.0F) {     // shock rings on the ground
            float a = Fx.clamp01(1.0F - T / 50.0F);
            VertexConsumer rg = buf.getBuffer(Fx.additive(Fx.RING));
            Fx.annulus(rg, pose, 0, 0.45F, 0, R2 - 6.0F * S, R2 + 5.0F * S, 80, T * 0.1F, Fx.rgb(1.0F, 0.80F, 0.50F), Fx.ch(a), Fx.ch(a));
            Fx.annulus(rg, pose, 0, 0.5F, 0, R2 * 1.1F - 3.0F * S, R2 * 1.1F + 2.0F * S, 80, -T * 0.1F, Fx.rgb(0.8F, 0.95F, 1.0F), Fx.ch(a * 0.8F), Fx.ch(a * 0.8F));
        }

        VertexConsumer gl = buf.getBuffer(Fx.additive(Fx.GLOW));
        float flash = Fx.clamp01(1.0F - T / 55.0F);
        flash *= flash;
        float fs = (60.0F + T * 4.0F) * S;
        if (flash > 0.01F) {
            Fx.billboard(gl, pose, cam, 0, rb * 0.55F + 4.0F, 0, fs, fs, 0, Fx.rgb(1.0F, 1.0F, 0.95F), Fx.ch(flash));
            Fx.billboard(gl, pose, cam, 0, rb * 0.55F + 4.0F, 0, fs * 1.9F, fs * 1.9F, 0, Fx.rgb(1.0F, 0.62F, 0.25F), Fx.ch(0.6F * flash));
        }
        if (T < 140.0F) {    // warm glow low on the horizon that lights up the dust
            float ga = Fx.clamp01(1.0F - T / 140.0F);
            Fx.billboard(gl, pose, cam, 0, 12.0F * S, 0, 55.0F * S, 40.0F * S, 0, Fx.rgb(1.0F, 0.45F, 0.12F), Fx.ch(0.55F * ga));
        }
        VertexConsumer fl = buf.getBuffer(Fx.additive(Fx.FLARE));
        if (flash > 0.01F) Fx.billboard(fl, pose, cam, 0, rb * 0.55F + 4.0F, 0, fs * 1.4F, fs * 1.4F, T * 0.01F, Fx.rgb(1.0F, 0.95F, 0.8F), Fx.ch(0.9F * flash));

        // ---------------- embers
        VertexConsumer sp = buf.getBuffer(Fx.additive(Fx.SPARK));
        for (int i = 0; i < 120; i++) {
            float ang = Fx.rand(id, i + 2000) * Mth.TWO_PI, spd = (4.0F + 30.0F * Fx.rand(id, i + 2100)) * S, vy = (12.0F + 48.0F * Fx.rand(id, i + 2200)) * S;
            float t = T / 20.0F, life = 2.0F + 4.0F * Fx.rand(id, i + 2300);
            if (t > life) continue;
            float dr = 1.5F * (1.0F - (float) Math.exp(-t / 1.5F));
            float x = Mth.cos(ang) * spd * dr, z = Mth.sin(ang) * spd * dr, y = Math.max(0.0F, vy * t - 9.0F * t * t);
            float a = (1.0F - t / life) * (0.6F + 0.4F * Mth.sin(T * 0.6F + i)) * (T < 4.0F ? T / 4.0F : 1.0F);
            float size = (0.35F + 0.6F * Fx.rand(id, i + 2400)) * S;
            Fx.billboard(sp, pose, cam, x, y, z, size, size, 0, Fx.lerpRgb(Fx.rgb(1.0F, 0.85F, 0.5F), Fx.rgb(1.0F, 0.35F, 0.1F), t / life), Fx.ch(a));
        }
    }

    // ================================================================== the stomp shockwave
    private static void stomp(int id, float T, float S, Fx.Cam cam, PoseStack.Pose pose, MultiBufferSource buf) {
        float R = Math.min(22.0F * S, 2.6F * T * S);
        float a = Fx.clamp01(1.0F - T / 40.0F);
        VertexConsumer sm = buf.getBuffer(Fx.alpha(Fx.SMOKE));
        Fx.band(sm, pose, 0, 0.2F, 0, R, (3.0F + 0.35F * R) * S, 48, T * 0.03F, 0.0F, Fx.rgb(0.6F, 0.48F, 0.36F), Fx.ch(0.9F * a));
        for (int i = 0; i < 26; i++) {
            float ang = Fx.rand(id, i) * Mth.TWO_PI, f = 0.3F + 0.7F * Fx.rand(id, i + 50);
            float r = R * f, size = (2.5F + 4.0F * Fx.rand(id, i + 90)) * S * Fx.smooth(T / 12.0F);
            Fx.billboard(sm, pose, cam, Mth.cos(ang) * r, 0.6F * size + T * 0.05F, Mth.sin(ang) * r, size, size, Fx.rand(id, i + 130) * 6.0F,
                    Fx.rgb(0.56F, 0.46F, 0.36F), Fx.ch(0.55F * a));
        }
        VertexConsumer ch = buf.getBuffer(Fx.alpha(Fx.CHIP));
        for (int i = 0; i < 36; i++) {
            float ang = Fx.rand(id, i + 200) * Mth.TWO_PI, spd = (2.0F + 10.0F * Fx.rand(id, i + 240)) * S, vy = (8.0F + 16.0F * Fx.rand(id, i + 280)) * S;
            float t = T / 20.0F;
            float y = Math.max(0.0F, vy * t - 9.0F * t * t);
            float fa = Fx.clamp01(1.0F - Math.max(0.0F, t - 1.6F) / 0.8F);
            float size = (0.3F + 0.8F * Fx.rand(id, i + 320)) * S;
            Fx.billboard(ch, pose, cam, Mth.cos(ang) * spd * t * 1.2F, y, Mth.sin(ang) * spd * t * 1.2F, size, size, T * 0.2F * (i % 3 - 1), Fx.rgb(0.25F, 0.2F, 0.16F), Fx.ch(fa));
        }
        VertexConsumer rg = buf.getBuffer(Fx.additive(Fx.RING));
        Fx.annulus(rg, pose, 0, 0.4F, 0, R - 2.0F * S, R + 1.5F * S, 56, T * 0.1F, Fx.rgb(1.0F, 0.85F, 0.6F), Fx.ch(a), Fx.ch(a));
        VertexConsumer gl = buf.getBuffer(Fx.additive(Fx.GLOW));
        float fa = Fx.clamp01(1.0F - T / 10.0F);
        if (fa > 0.0F) Fx.billboard(gl, pose, cam, 0, 1.5F * S, 0, 14.0F * S, 10.0F * S, 0, Fx.rgb(1.0F, 0.8F, 0.5F), Fx.ch(fa));
    }

    // ================================================================== footstep dust
    private static void step(int id, float T, float S, Fx.Cam cam, PoseStack.Pose pose, MultiBufferSource buf) {
        float a = Fx.clamp01(1.0F - T / 30.0F);
        VertexConsumer sm = buf.getBuffer(Fx.alpha(Fx.SMOKE));
        for (int i = 0; i < 18; i++) {
            float ang = Fx.rand(id, i) * Mth.TWO_PI, spd = (2.0F + 5.0F * Fx.rand(id, i + 30)) * S;
            float t = T / 20.0F, r = spd * (1.0F - (float) Math.exp(-t * 3.0F)) * 1.4F;
            float size = (1.4F + 2.6F * Fx.rand(id, i + 60)) * S * (0.5F + Fx.smooth(T / 14.0F));
            Fx.billboard(sm, pose, cam, Mth.cos(ang) * r, 0.5F * size + t * 2.5F, Mth.sin(ang) * r, size, size, Fx.rand(id, i + 90) * 6.0F + T * 0.02F,
                    Fx.rgb(0.58F, 0.5F, 0.4F), Fx.ch(0.6F * a));
        }
    }

    // ================================================================== a small fiery burst (death throes)
    private static void burst(int id, float T, float S, Fx.Cam cam, PoseStack.Pose pose, MultiBufferSource buf) {
        float a = Fx.clamp01(1.0F - T / 40.0F);
        VertexConsumer fi = buf.getBuffer(Fx.additive(Fx.FIRE));
        float rb = 9.0F * S * (1.0F - (float) Math.exp(-T / 5.0F));
        Fx.sphere(fi, pose, 0, rb * 0.6F, 0, rb, 12, 18, 0.16F, T * 0.12F, T * 0.05F, -T * 0.04F,
                Fx.lerpRgb(Fx.rgb(1.0F, 0.95F, 0.8F), Fx.rgb(1.0F, 0.4F, 0.12F), T / 30.0F), Fx.ch(a));
        VertexConsumer gl = buf.getBuffer(Fx.additive(Fx.GLOW));
        float fl = Fx.clamp01(1.0F - T / 12.0F);
        Fx.billboard(gl, pose, cam, 0, rb * 0.6F, 0, 22.0F * S, 22.0F * S, 0, Fx.rgb(0.8F, 0.95F, 1.0F), Fx.ch(fl));
        VertexConsumer sp = buf.getBuffer(Fx.additive(Fx.SPARK));
        for (int i = 0; i < 30; i++) {
            float ang = Fx.rand(id, i) * Mth.TWO_PI, el = (Fx.rand(id, i + 40) - 0.3F) * 2.0F, spd = (4.0F + 16.0F * Fx.rand(id, i + 80)) * S;
            float t = T / 20.0F;
            float size = (0.3F + 0.5F * Fx.rand(id, i + 120)) * S;
            Fx.billboard(sp, pose, cam, Mth.cos(ang) * spd * t, rb * 0.6F + el * spd * t, Mth.sin(ang) * spd * t, size, size, 0,
                    Fx.rgb(0.6F, 0.9F, 1.0F), Fx.ch(a));
        }
    }

    // ================================================================== lingering radiation patch
    private static void radiation(int id, float T, float S, int life, Fx.Cam cam, PoseStack.Pose pose, MultiBufferSource buf) {
        float inout = Fx.smooth(T / 40.0F) * Fx.clamp01((life - T) / 60.0F);
        float pulse = 0.75F + 0.25F * Mth.sin(T * 0.12F);
        VertexConsumer rg = buf.getBuffer(Fx.additive(Fx.RING));
        Fx.annulus(rg, pose, 0, 0.35F, 0, S * 0.55F, S, 64, T * 0.01F, Fx.rgb(0.2F, 1.0F, 0.45F), Fx.ch(0.55F * inout * pulse), Fx.ch(0.55F * inout * pulse));
        Fx.annulus(rg, pose, 0, 0.4F, 0, S * 0.2F, S * 0.55F, 64, -T * 0.015F, Fx.rgb(0.15F, 0.8F, 0.6F), Fx.ch(0.35F * inout * pulse), Fx.ch(0.35F * inout * pulse));
        VertexConsumer gl = buf.getBuffer(Fx.additive(Fx.GLOW));
        Fx.billboard(gl, pose, cam, 0, 1.0F, 0, S * 0.9F, S * 0.35F, 0, Fx.rgb(0.2F, 1.0F, 0.5F), Fx.ch(0.22F * inout * pulse));
        for (int i = 0; i < 26; i++) {      // motes drifting upward
            float ang = Fx.rand(id, i) * Mth.TWO_PI, r = S * 0.9F * (float) Math.sqrt(Fx.rand(id, i + 40));
            float cyc = ((T * 0.012F + Fx.rand(id, i + 80)) % 1.0F);
            float size = 0.35F + 0.35F * Fx.rand(id, i + 120);
            Fx.billboard(gl, pose, cam, Mth.cos(ang) * r, cyc * 7.0F, Mth.sin(ang) * r, size, size, 0, Fx.rgb(0.35F, 1.0F, 0.6F),
                    Fx.ch(inout * (1.0F - cyc) * Math.min(1.0F, cyc * 6.0F)));
        }
    }
}

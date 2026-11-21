package com.example.godzilla.client.fx;

import com.example.godzilla.GodzillaMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Drawing toolkit for all custom effects (no vanilla particles). Everything is a quad, drawn with vanilla entity render
 * types so no custom shader is needed: eyes() = additive unlit glow, entityTranslucentEmissive() = alpha-blended unlit.
 * All positions are in the entity-local space of whatever entity is being rendered (world axes, origin at the entity).
 */
public final class Fx {
    private Fx() {}

    public static final ResourceLocation GLOW = fx("glow"), FLARE = fx("flare"), RING = fx("ring"), FIRE = fx("fire"),
            SMOKE = fx("smoke"), PLASMA = fx("plasma"), FLAME = fx("flame"), BOLT = fx("bolt"), SPARK = fx("spark"), CHIP = fx("chip");

    private static ResourceLocation fx(String name) {
        return ResourceLocation.fromNamespaceAndPath(GodzillaMod.MODID, "textures/fx/" + name + ".png");
    }

    public static RenderType additive(ResourceLocation t) { return RenderType.eyes(t); }
    public static RenderType alpha(ResourceLocation t) { return RenderType.entityTranslucentEmissive(t); }

    /** Camera basis + position in entity-local space, plus the effect clock. */
    public static final class Cam {
        public final float rx, ry, rz, ux, uy, uz, cx, cy, cz;
        public Cam(Vec3 entityWorldPos) {
            Camera c = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vector3f left = c.getLeftVector(), up = c.getUpVector();
            rx = -left.x(); ry = -left.y(); rz = -left.z();
            ux = up.x(); uy = up.y(); uz = up.z();
            Vec3 p = c.getPosition();
            cx = (float) (p.x - entityWorldPos.x); cy = (float) (p.y - entityWorldPos.y); cz = (float) (p.z - entityWorldPos.z);
        }
    }

    // ---------------------------------------------------------------- randomness (deterministic: effects are pure functions of time)
    public static float rand(int seed) {
        int h = seed * 0x9E3779B1;
        h ^= h >>> 15; h *= 0x85EBCA77; h ^= h >>> 13; h *= 0xC2B2AE3D; h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }
    public static float rand(int seed, int k) { return rand(seed * 31 + k * 7919 + 17); }
    public static float clamp01(float x) { return x < 0 ? 0 : (x > 1 ? 1 : x); }
    public static float smooth(float x) { x = clamp01(x); return x * x * (3 - 2 * x); }
    public static int ch(float v) { return (int) (clamp01(v) * 255.0F + 0.5F); }

    // ---------------------------------------------------------------- vertices / quads
    public static void vtx(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v, int r, int g, int b, int a) {
        vc.addVertex(pose, x, y, z).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(0.0F, 1.0F, 0.0F);
    }

    /** One quad, both windings (so it is visible from either side whatever the cull state). */
    public static void quad2(VertexConsumer vc, PoseStack.Pose pose,
                             float x0, float y0, float z0, float u0, float v0, int c0,
                             float x1, float y1, float z1, float u1, float v1, int c1,
                             float x2, float y2, float z2, float u2, float v2, int c2,
                             float x3, float y3, float z3, float u3, float v3, int c3, int alpha) {
        vtx(vc, pose, x0, y0, z0, u0, v0, (c0 >> 16) & 255, (c0 >> 8) & 255, c0 & 255, alpha);
        vtx(vc, pose, x1, y1, z1, u1, v1, (c1 >> 16) & 255, (c1 >> 8) & 255, c1 & 255, alpha);
        vtx(vc, pose, x2, y2, z2, u2, v2, (c2 >> 16) & 255, (c2 >> 8) & 255, c2 & 255, alpha);
        vtx(vc, pose, x3, y3, z3, u3, v3, (c3 >> 16) & 255, (c3 >> 8) & 255, c3 & 255, alpha);
        vtx(vc, pose, x3, y3, z3, u3, v3, (c3 >> 16) & 255, (c3 >> 8) & 255, c3 & 255, alpha);
        vtx(vc, pose, x2, y2, z2, u2, v2, (c2 >> 16) & 255, (c2 >> 8) & 255, c2 & 255, alpha);
        vtx(vc, pose, x1, y1, z1, u1, v1, (c1 >> 16) & 255, (c1 >> 8) & 255, c1 & 255, alpha);
        vtx(vc, pose, x0, y0, z0, u0, v0, (c0 >> 16) & 255, (c0 >> 8) & 255, c0 & 255, alpha);
    }

    public static int rgb(float r, float g, float b) { return (ch(r) << 16) | (ch(g) << 8) | ch(b); }
    public static int lerpRgb(int a, int b, float t) {
        t = clamp01(t);
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    // ---------------------------------------------------------------- camera-facing sprites
    /** Camera-facing quad of half-size (hw, hh), rolled by `roll` radians. */
    public static void billboard(VertexConsumer vc, PoseStack.Pose pose, Cam c, float x, float y, float z, float hw, float hh,
                                 float roll, int rgb, int alpha) {
        float cr = Mth.cos(roll), sr = Mth.sin(roll);
        float ax = (c.rx * cr + c.ux * sr) * hw, ay = (c.ry * cr + c.uy * sr) * hw, az = (c.rz * cr + c.uz * sr) * hw;
        float bx = (-c.rx * sr + c.ux * cr) * hh, by = (-c.ry * sr + c.uy * cr) * hh, bz = (-c.rz * sr + c.uz * cr) * hh;
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        vtx(vc, pose, x - ax - bx, y - ay - by, z - az - bz, 0.0F, 1.0F, r, g, b, alpha);
        vtx(vc, pose, x + ax - bx, y + ay - by, z + az - bz, 1.0F, 1.0F, r, g, b, alpha);
        vtx(vc, pose, x + ax + bx, y + ay + by, z + az + bz, 1.0F, 0.0F, r, g, b, alpha);
        vtx(vc, pose, x - ax + bx, y - ay + by, z - az + bz, 0.0F, 0.0F, r, g, b, alpha);
    }

    /** A flame/spike sprite standing on the ground at (x,y,z): vertical axis fixed, turns to face the camera. */
    public static void upright(VertexConsumer vc, PoseStack.Pose pose, Cam c, float x, float y, float z, float halfW, float height,
                               float lean, int rgb, int alpha) {
        float dx = c.cx - x, dz = c.cz - z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4F) return;
        float rx = dz / len * halfW, rz = -dx / len * halfW;
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        vtx(vc, pose, x - rx, y, z - rz, 0.0F, 1.0F, r, g, b, alpha);
        vtx(vc, pose, x + rx, y, z + rz, 1.0F, 1.0F, r, g, b, alpha);
        vtx(vc, pose, x + rx + lean, y + height, z + rz, 1.0F, 0.0F, r, g, b, alpha);
        vtx(vc, pose, x - rx + lean, y + height, z - rz, 0.0F, 0.0F, r, g, b, alpha);
    }

    /** Camera-facing ribbon from A to B with the given half-width; uv runs u0..u1 along its length. */
    public static void ribbon(VertexConsumer vc, PoseStack.Pose pose, Cam c, float ax, float ay, float az, float bx, float by, float bz,
                              float wa, float wb, float u0, float u1, int rgb, int alphaA, int alphaB) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float mx = (ax + bx) * 0.5F, my = (ay + by) * 0.5F, mz = (az + bz) * 0.5F;
        float tx = c.cx - mx, ty = c.cy - my, tz = c.cz - mz;
        float sx = dy * tz - dz * ty, sy = dz * tx - dx * tz, sz = dx * ty - dy * tx;
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1.0E-5F) return;
        sx /= sl; sy /= sl; sz /= sl;
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        vtx(vc, pose, ax - sx * wa, ay - sy * wa, az - sz * wa, u0, 0.0F, r, g, b, alphaA);
        vtx(vc, pose, ax + sx * wa, ay + sy * wa, az + sz * wa, u0, 1.0F, r, g, b, alphaA);
        vtx(vc, pose, bx + sx * wb, by + sy * wb, bz + sz * wb, u1, 1.0F, r, g, b, alphaB);
        vtx(vc, pose, bx - sx * wb, by - sy * wb, bz - sz * wb, u1, 0.0F, r, g, b, alphaB);
    }

    // ---------------------------------------------------------------- solid-ish shapes (all double-sided)
    /** A roiling sphere: radius is perturbed by moving waves so it boils like a fireball. */
    public static void sphere(VertexConsumer vc, PoseStack.Pose pose, float cx, float cy, float cz, float radius, int lat, int lon,
                              float wobble, float time, float uScroll, float vScroll, int rgb, int alpha) {
        for (int i = 0; i < lat; i++) {
            for (int j = 0; j < lon; j++) {
                float[] p0 = sp(cx, cy, cz, radius, lat, lon, i, j, wobble, time);
                float[] p1 = sp(cx, cy, cz, radius, lat, lon, i, j + 1, wobble, time);
                float[] p2 = sp(cx, cy, cz, radius, lat, lon, i + 1, j + 1, wobble, time);
                float[] p3 = sp(cx, cy, cz, radius, lat, lon, i + 1, j, wobble, time);
                float u0 = (j / (float) lon) * 3.0F + uScroll, u1 = ((j + 1) / (float) lon) * 3.0F + uScroll;
                float v0 = (i / (float) lat) * 2.0F + vScroll, v1 = ((i + 1) / (float) lat) * 2.0F + vScroll;
                quad2(vc, pose, p0[0], p0[1], p0[2], u0, v0, rgb, p1[0], p1[1], p1[2], u1, v0, rgb,
                        p2[0], p2[1], p2[2], u1, v1, rgb, p3[0], p3[1], p3[2], u0, v1, rgb, alpha);
            }
        }
    }

    private static float[] sp(float cx, float cy, float cz, float radius, int lat, int lon, int i, int j, float wobble, float time) {
        float th = Mth.PI * i / lat, ph = Mth.TWO_PI * (j % lon) / lon;
        float n = 1.0F + wobble * (Mth.sin(th * 5.0F + time * 3.1F) * Mth.sin(ph * 4.0F - time * 2.3F)
                + 0.6F * Mth.sin(th * 9.0F - time * 4.0F + ph * 3.0F));
        float r = radius * n;
        return new float[] {cx + r * Mth.sin(th) * Mth.cos(ph), cy + r * Mth.cos(th), cz + r * Mth.sin(th) * Mth.sin(ph)};
    }

    /** Tapered/curved tube standing on y = baseY. radius(y01) is given by the callback. */
    public interface Radius { float at(float y01); }

    public static void column(VertexConsumer vc, PoseStack.Pose pose, float cx, float baseY, float cz, float height, int rings, int seg,
                              Radius radius, float uScroll, float vScroll, int rgbBottom, int rgbTop, int alphaBottom, int alphaTop) {
        for (int k = 0; k < rings; k++) {
            float f0 = k / (float) rings, f1 = (k + 1) / (float) rings;
            float y0 = baseY + height * f0, y1 = baseY + height * f1;
            float r0 = radius.at(f0), r1 = radius.at(f1);
            int c0 = lerpRgb(rgbBottom, rgbTop, f0), c1 = lerpRgb(rgbBottom, rgbTop, f1);
            int a = (int) (alphaBottom + (alphaTop - alphaBottom) * (f0 + f1) * 0.5F);
            for (int j = 0; j < seg; j++) {
                float a0 = Mth.TWO_PI * j / seg, a1 = Mth.TWO_PI * (j + 1) / seg;
                float u0 = j / (float) seg * 3.0F + uScroll, u1 = (j + 1) / (float) seg * 3.0F + uScroll;
                float v0 = f0 * 3.0F + vScroll, v1 = f1 * 3.0F + vScroll;
                quad2(vc, pose, cx + Mth.cos(a0) * r0, y0, cz + Mth.sin(a0) * r0, u0, v0, c0,
                        cx + Mth.cos(a1) * r0, y0, cz + Mth.sin(a1) * r0, u1, v0, c0,
                        cx + Mth.cos(a1) * r1, y1, cz + Mth.sin(a1) * r1, u1, v1, c1,
                        cx + Mth.cos(a0) * r1, y1, cz + Mth.sin(a0) * r1, u0, v1, c1, a);
            }
        }
    }

    /** A torus (the mushroom cap). The tube colour fades from rgbTop (outside/top) to rgbUnder (the underside, phi in the lower half). */
    public static void torus(VertexConsumer vc, PoseStack.Pose pose, float cx, float cy, float cz, float R, float m, int segA, int segB,
                             float squash, float uScroll, float vScroll, int rgbTop, int rgbUnder, int alpha) {
        for (int i = 0; i < segA; i++) {
            for (int j = 0; j < segB; j++) {
                float[] p00 = tp(cx, cy, cz, R, m, segA, segB, i, j, squash), p10 = tp(cx, cy, cz, R, m, segA, segB, i + 1, j, squash);
                float[] p11 = tp(cx, cy, cz, R, m, segA, segB, i + 1, j + 1, squash), p01 = tp(cx, cy, cz, R, m, segA, segB, i, j + 1, squash);
                float cA = Mth.sin(Mth.TWO_PI * j / segB), cB = Mth.sin(Mth.TWO_PI * (j + 1) / segB);
                int c0 = lerpRgb(rgbUnder, rgbTop, (cA + 1.0F) * 0.5F), c1 = lerpRgb(rgbUnder, rgbTop, (cB + 1.0F) * 0.5F);
                float u0 = i / (float) segA * 4.0F + uScroll, u1 = (i + 1) / (float) segA * 4.0F + uScroll;
                float v0 = j / (float) segB * 2.0F + vScroll, v1 = (j + 1) / (float) segB * 2.0F + vScroll;
                quad2(vc, pose, p00[0], p00[1], p00[2], u0, v0, c0, p10[0], p10[1], p10[2], u1, v0, c0,
                        p11[0], p11[1], p11[2], u1, v1, c1, p01[0], p01[1], p01[2], u0, v1, c1, alpha);
            }
        }
    }

    private static float[] tp(float cx, float cy, float cz, float R, float m, int segA, int segB, int i, int j, float squash) {
        float th = Mth.TWO_PI * (i % segA) / segA, ph = Mth.TWO_PI * (j % segB) / segB;
        float ring = R + m * Mth.cos(ph);
        return new float[] {cx + ring * Mth.cos(th), cy + squash * m * Mth.sin(ph), cz + ring * Mth.sin(th)};
    }

    /** Flat ring on the ground plane (y fixed), uv v runs across the ring width. */
    public static void annulus(VertexConsumer vc, PoseStack.Pose pose, float cx, float y, float cz, float r0, float r1, int seg,
                               float uScroll, int rgb, int alphaInner, int alphaOuter) {
        for (int j = 0; j < seg; j++) {
            float a0 = Mth.TWO_PI * j / seg, a1 = Mth.TWO_PI * (j + 1) / seg;
            float u0 = j / (float) seg * 8.0F + uScroll, u1 = (j + 1) / (float) seg * 8.0F + uScroll;
            int c = rgb;
            quad2(vc, pose, cx + Mth.cos(a0) * r0, y, cz + Mth.sin(a0) * r0, u0, 0.0F, c,
                    cx + Mth.cos(a1) * r0, y, cz + Mth.sin(a1) * r0, u1, 0.0F, c,
                    cx + Mth.cos(a1) * r1, y, cz + Mth.sin(a1) * r1, u1, 1.0F, c,
                    cx + Mth.cos(a0) * r1, y, cz + Mth.sin(a0) * r1, u0, 1.0F, c, (alphaInner + alphaOuter) / 2);
        }
    }

    /** A vertical band (dust wall) around (cx,cz) at radius r, from y0 up by height. */
    public static void band(VertexConsumer vc, PoseStack.Pose pose, float cx, float y0, float cz, float r, float height, int seg,
                            float uScroll, float vScroll, int rgb, int alpha) {
        for (int j = 0; j < seg; j++) {
            float a0 = Mth.TWO_PI * j / seg, a1 = Mth.TWO_PI * (j + 1) / seg;
            float u0 = j / (float) seg * 10.0F + uScroll, u1 = (j + 1) / (float) seg * 10.0F + uScroll;
            quad2(vc, pose, cx + Mth.cos(a0) * r, y0, cz + Mth.sin(a0) * r, u0, 1.0F + vScroll, rgb,
                    cx + Mth.cos(a1) * r, y0, cz + Mth.sin(a1) * r, u1, 1.0F + vScroll, rgb,
                    cx + Mth.cos(a1) * r, y0 + height, cz + Mth.sin(a1) * r, u1, vScroll, rgb,
                    cx + Mth.cos(a0) * r, y0 + height, cz + Mth.sin(a0) * r, u0, vScroll, rgb, alpha);
        }
    }
}

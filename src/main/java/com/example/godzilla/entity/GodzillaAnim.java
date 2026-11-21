package com.example.godzilla.entity;

import static com.example.godzilla.client.mesh.RigData.*;

import com.example.godzilla.client.mesh.RigData;
import net.minecraft.util.Mth;

/**
 * Procedural skeleton animation (no keyframe files): every frame the target pose is computed from the entity's action,
 * its synced walk phase and time, blended with the previous pose, then turned into one affine matrix per bone.
 *
 * Conventions (model space: +x = his left, +y up, front = -z). A pose is 6 floats per bone:
 *   rx, ry, rz in degrees (rotation about that bone's pivot, applied Z*Y*X) and tx, ty, tz in blocks.
 *   +rx swings anything that hangs DOWN (legs, arms) forward and tips anything that points FORWARD (head, jaw) UP;
 *   torso +rx = lean back. Positive ry turns the front toward -x (his right). +rz swings hanging parts toward +x.
 * Pure math: safe to load on a dedicated server (the renderer is the only client code).
 */
public final class GodzillaAnim {
    private GodzillaAnim() {}

    private static final int B = RigData.BONE_COUNT;
    private static final float PI = (float) Math.PI;

    public static final class State {
        public final float[] cur = new float[B * 6];
        public final float[] from = new float[B * 6];
        public final float[] target = new float[B * 6];
        public final float[] plateExt = new float[RigData.PLATE_CLUSTERS];
        int lastAction = -1;
        float blendT = 1.0F, blendDur = 0.2F, walkAmount, lastTime = -1.0F;
    }

    // ------------------------------------------------------------------ entry point (called every frame by the renderer)
    /** Evaluates the pose for this frame and writes 12 floats (3x4 affine, row-major) per bone into mats. */
    public static void update(GodzillaEntity g, State st, float pt, float[] mats) {
        float time = (g.tickCount + pt) / 20.0F;
        float dt = st.lastTime < 0.0F ? 0.0F : Mth.clamp(time - st.lastTime, 0.0F, 0.1F);
        st.lastTime = time;

        int action = g.getAction();
        if (action != st.lastAction) {
            System.arraycopy(st.cur, 0, st.from, 0, st.cur.length);
            st.blendT = st.lastAction < 0 ? 1.0F : 0.0F;
            st.blendDur = action == GodzillaEntity.ACT_DEATH ? 0.4F : 0.22F;
            st.lastAction = action;
        }
        st.blendT = Math.min(1.0F, st.blendT + dt / st.blendDur);
        st.walkAmount += ((g.isWalkingClient() ? 1.0F : 0.0F) - st.walkAmount) * Math.min(1.0F, dt * 5.0F);

        java.util.Arrays.fill(st.target, 0.0F);
        compute(g, st, time, pt);

        float e = st.blendT * st.blendT * (3.0F - 2.0F * st.blendT);
        for (int i = 0; i < st.cur.length; i++) st.cur[i] = st.from[i] + (st.target[i] - st.from[i]) * e;
        for (int c = 0; c < st.plateExt.length; c++) st.plateExt[c] = g.getPlateExtension(c, pt);
        buildMatrices(st.cur, mats);
    }

    // ------------------------------------------------------------------ pose construction
    private static void rot(float[] p, int bone, float rx, float ry, float rz) {
        p[bone * 6] += rx; p[bone * 6 + 1] += ry; p[bone * 6 + 2] += rz;
    }

    private static void mov(float[] p, int bone, float tx, float ty, float tz) {
        p[bone * 6 + 3] += tx; p[bone * 6 + 4] += ty; p[bone * 6 + 5] += tz;
    }

    /** Eased keyframes: key(t, t0, v0, t1, v1, ...). Clamps outside the range. */
    private static float key(float t, float... kv) {
        if (t <= kv[0]) return kv[1];
        for (int i = 2; i < kv.length; i += 2) {
            if (t <= kv[i]) {
                float u = (t - kv[i - 2]) / (kv[i] - kv[i - 2]);
                u = u * u * (3.0F - 2.0F * u);
                return kv[i - 1] + (kv[i + 1] - kv[i - 1]) * u;
            }
        }
        return kv[kv.length - 1];
    }

    private static void compute(GodzillaEntity g, State st, float time, float pt) {
        float[] p = st.target;
        float ta = (g.getActionTicks() + pt) / 20.0F;          // seconds since the current action began
        int action = g.getAction();

        // head follows where he is looking
        float headYaw = Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(pt, g.yHeadRotO, g.yHeadRot) - Mth.rotLerp(pt, g.yBodyRotO, g.yBodyRot)), -50.0F, 50.0F);
        float headPitch = Mth.clamp(Mth.lerp(pt, g.xRotO, g.getXRot()), -40.0F, 60.0F);

        switch (action) {
            case GodzillaEntity.ACT_ROAR -> roar(p, ta, time);
            case GodzillaEntity.ACT_CHARGE -> charge(p, ta, time);
            case GodzillaEntity.ACT_INHALE -> inhale(p, ta, time);
            case GodzillaEntity.ACT_FIRE -> firePose(p, 1.0F, time);
            case GodzillaEntity.ACT_VENT -> firePose(p, 1.0F - key(ta, 0.0F, 0.0F, 1.5F, 1.0F), time);
            case GodzillaEntity.ACT_BITE -> bite(p, ta, time);
            case GodzillaEntity.ACT_TAIL -> tailSwipe(p, ta, time);
            case GodzillaEntity.ACT_STOMP -> stompAttack(p, ta, time);
            case GodzillaEntity.ACT_DEATH -> death(p, ta, time);
            default -> {
                idle(p, time, 1.0F - st.walkAmount);
                walk(p, st.walkAmount, g.getWalkPhase(pt));
            }
        }
        rot(p, NECK, -headPitch * 0.4F, headYaw * 0.4F, 0.0F);
        rot(p, HEAD, -headPitch * 0.6F, headYaw * 0.6F, 0.0F);
    }

    private static void idle(float[] p, float time, float k) {
        float br = (float) Math.sin(time * 1.6F);
        rot(p, TORSO, -1.4F * br * k, 0.0F, 0.0F);
        rot(p, NECK, 0.8F * br * k, 0.0F, 0.0F);
        rot(p, HEAD, 0.0F, 3.0F * (float) Math.sin(time * 0.7F) * k, 0.0F);
        rot(p, JAW, -1.5F * (0.5F + 0.5F * br) * k, 0.0F, 0.0F);
        for (int i = 0; i < RigData.TAIL_COUNT; i++) {
            rot(p, TAIL_FIRST + i, 0.0F, 2.2F * (1.0F + 0.25F * i) * (float) Math.sin(time * 0.9F - 0.6F * i) * k, 0.0F);
        }
        rot(p, ARM_L_UP, 2.0F * br * k, 0.0F, 2.0F * k);
        rot(p, ARM_R_UP, 2.0F * br * k, 0.0F, -2.0F * k);
    }

    /**
     * The film's walk: slow, heavy; the hips shift over the planted foot and the whole body rocks from side to side, the
     * shoulders counter-twist, the head stays level, the tail whips the opposite way in a travelling wave. phi advances with
     * distance; the left foot lands at phi = 0 (mod 2 PI), the right at PI.
     */
    private static void walk(float[] p, float A, float phi) {
        if (A < 0.001F) return;
        float s = (float) Math.sin(phi), c = (float) Math.cos(phi), c2 = (float) Math.cos(2.0F * phi);
        float dip = 0.5F + 0.5F * c2;                                   // 1 at every footfall

        mov(p, PELVIS, 1.2F * A * s, -0.65F * A * dip, 0.0F);           // weight shift toward the planted leg + settle on landing
        rot(p, PELVIS, 0.0F, 5.5F * A * c, -3.2F * A * s);
        rot(p, TORSO, -3.0F * A - 1.6F * A * dip, -8.5F * A * c, -4.5F * A * s);   // lean forward, counter-twist, rock
        rot(p, NECK, 0.0F, 2.0F * A * c, 3.0F * A * s);
        rot(p, HEAD, 0.0F, 2.0F * A * c, 2.5F * A * s);                  // keeps the eyes level through the sway

        for (int side = 0; side < 2; side++) {
            float ph = phi + side * PI;
            float swing = (float) Math.cos(ph);                          // +1 foot forward (landing) .. -1 foot back (toe-off)
            float lift = Math.max(0.0F, -(float) Math.sin(ph));          // > 0 only while the leg swings forward
            float thigh = 27.0F * A * swing;
            float knee = -56.0F * A * (float) Math.pow(lift, 0.8);
            int th = side == 0 ? THIGH_L : THIGH_R, sh = side == 0 ? SHIN_L : SHIN_R, ft = side == 0 ? FOOT_L : FOOT_R;
            rot(p, th, thigh, 0.0F, 0.0F);
            rot(p, sh, knee, 0.0F, 0.0F);
            rot(p, ft, -(thigh + knee) * 0.9F + 10.0F * A * lift, 0.0F, 0.0F);
            mov(p, th, 0.0F, 0.5F * A * lift, 0.0F);                     // lift the whole leg for ground clearance
        }

        float armSwing = 13.0F * A * c;                                  // arms swing against the legs
        rot(p, ARM_L_UP, -armSwing, 0.0F, 0.0F);
        rot(p, ARM_R_UP, armSwing, 0.0F, 0.0F);
        rot(p, ARM_L_FORE, 14.0F * A + 5.0F * A * s, 0.0F, 0.0F);
        rot(p, ARM_R_FORE, 14.0F * A - 5.0F * A * s, 0.0F, 0.0F);

        for (int i = 0; i < RigData.TAIL_COUNT; i++) {
            rot(p, TAIL_FIRST + i, 1.2F * A * (float) Math.sin(2.0F * phi - 0.7F * i),
                    -A * (2.8F + 0.9F * i) * (float) Math.sin(phi - 0.65F * i), 0.0F);
        }
    }

    private static void roar(float[] p, float ta, float time) {
        idle(p, time, 0.4F);
        float in = key(ta, 0.0F, 0.0F, 0.6F, 1.0F, 2.2F, 1.0F, 3.0F, 0.0F);
        float jaw = key(ta, 0.0F, 0.0F, 0.7F, 1.0F, 2.2F, 1.0F, 2.9F, 0.0F);
        rot(p, TORSO, 12.0F * in, 0.0F, 0.0F);
        rot(p, NECK, 8.0F * in, 0.0F, 0.0F);
        rot(p, HEAD, 22.0F * in, 0.0F, 0.0F);
        rot(p, JAW, -44.0F * jaw, 0.0F, 0.0F);
        rot(p, ARM_L_UP, 20.0F * in, 0.0F, 20.0F * in); rot(p, ARM_R_UP, 20.0F * in, 0.0F, -20.0F * in);
        rot(p, ARM_L_FORE, 22.0F * in, 0.0F, 0.0F); rot(p, ARM_R_FORE, 22.0F * in, 0.0F, 0.0F);
        for (int i = 0; i < RigData.TAIL_COUNT; i++) rot(p, TAIL_FIRST + i, -3.0F * in, 0.0F, 0.0F);
        float sh = key(ta, 0.6F, 0.0F, 0.9F, 1.0F, 2.2F, 1.0F, 2.8F, 0.0F);
        rot(p, PELVIS, 0.0F, 0.0F, 0.9F * sh * (float) Math.sin(time * 70.0F));
        braceLegs(p, in * 0.6F);
    }

    private static void braceLegs(float[] p, float k) {
        rot(p, THIGH_L, 7.0F * k, 0.0F, 0.0F); rot(p, THIGH_R, -3.0F * k, 0.0F, 0.0F);
        rot(p, SHIN_L, -12.0F * k, 0.0F, 0.0F); rot(p, SHIN_R, -9.0F * k, 0.0F, 0.0F);
        rot(p, FOOT_L, 5.0F * k, 0.0F, 0.0F); rot(p, FOOT_R, 6.0F * k, 0.0F, 0.0F);
        mov(p, PELVIS, 0.0F, -0.6F * k, 0.0F);
    }

    private static void charge(float[] p, float ta, float time) {
        idle(p, time, 0.3F);
        float lean = key(ta, 0.0F, 0.0F, 3.0F, 1.0F, 4.0F, 1.0F);
        rot(p, TORSO, 9.0F * lean, 0.0F, 0.0F);
        rot(p, NECK, 6.0F * lean, 0.0F, 0.0F);
        rot(p, HEAD, 14.0F * lean, 0.0F, 0.0F);
        rot(p, JAW, -26.0F * key(ta, 2.5F, 0.0F, 3.5F, 1.0F, 4.0F, 1.0F), 0.0F, 0.0F);
        rot(p, ARM_L_UP, 12.0F * lean, 0.0F, 10.0F * lean); rot(p, ARM_R_UP, 12.0F * lean, 0.0F, -10.0F * lean);
        float tremble = (0.3F + 0.9F * Math.min(1.0F, ta / 4.0F)) * (float) Math.sin(time * 62.0F);
        rot(p, PELVIS, 0.0F, 0.0F, 0.8F * tremble);
        rot(p, TORSO, 0.0F, 0.0F, 0.6F * tremble);
        braceLegs(p, lean);
    }

    private static void inhale(float[] p, float ta, float time) {
        float k = key(ta, 0.0F, 0.0F, 1.2F, 1.0F, 1.5F, 1.0F);
        rot(p, TORSO, 9.0F + 12.0F * k, 0.0F, 0.0F);
        rot(p, NECK, 6.0F + 4.0F * k, 0.0F, 0.0F);
        rot(p, HEAD, 14.0F + 18.0F * k, 0.0F, 0.0F);
        rot(p, JAW, -26.0F - 10.0F * k, 0.0F, 0.0F);
        rot(p, ARM_L_UP, 12.0F + 8.0F * k, 0.0F, 10.0F + 8.0F * k); rot(p, ARM_R_UP, 12.0F + 8.0F * k, 0.0F, -10.0F - 8.0F * k);
        rot(p, PELVIS, 0.0F, 0.0F, 0.6F * (float) Math.sin(time * 55.0F));
        braceLegs(p, 1.0F);
    }

    /** The locked firing stance: braced, leaning back, jaw wide, arms out. f fades it out during the vent. */
    private static void firePose(float[] p, float f, float time) {
        rot(p, TORSO, 15.0F * f, 0.0F, 0.0F);
        rot(p, NECK, 7.0F * f, 0.0F, 0.0F);
        rot(p, HEAD, 10.0F * f, 0.0F, 0.0F);
        rot(p, JAW, -50.0F * f, 0.0F, 0.0F);
        rot(p, ARM_L_UP, 18.0F * f, 0.0F, 16.0F * f); rot(p, ARM_R_UP, 18.0F * f, 0.0F, -16.0F * f);
        rot(p, ARM_L_FORE, 14.0F * f, 0.0F, 0.0F); rot(p, ARM_R_FORE, 14.0F * f, 0.0F, 0.0F);
        rot(p, PELVIS, 0.0F, 0.0F, 0.35F * f * (float) Math.sin(time * 55.0F));
        braceLegs(p, f);
        for (int i = 0; i < RigData.TAIL_COUNT; i++) rot(p, TAIL_FIRST + i, -2.0F * f, 0.0F, 0.0F);
    }

    private static void bite(float[] p, float ta, float time) {
        idle(p, time, 0.3F);
        float back = key(ta, 0.0F, 0.0F, 0.6F, 1.0F, 0.7F, 0.0F);
        float lunge = key(ta, 0.0F, 0.0F, 0.6F, 0.0F, 0.7F, 1.0F, 0.9F, 1.0F, 1.5F, 0.0F);
        rot(p, TORSO, 9.0F * back - 18.0F * lunge, 0.0F, 0.0F);
        rot(p, NECK, 6.0F * back - 10.0F * lunge, 0.0F, 0.0F);
        rot(p, HEAD, 14.0F * back - 20.0F * lunge, 0.0F, 0.0F);
        rot(p, JAW, -48.0F * key(ta, 0.0F, 0.0F, 0.55F, 1.0F, 0.7F, 0.0F), 0.0F, 0.0F);
        mov(p, PELVIS, 0.0F, 0.0F, 0.6F * back - 3.2F * lunge);
        rot(p, ARM_L_UP, -15.0F * back + 22.0F * lunge, 0.0F, 0.0F); rot(p, ARM_R_UP, -15.0F * back + 22.0F * lunge, 0.0F, 0.0F);
        rot(p, THIGH_L, 8.0F * lunge, 0.0F, 0.0F); rot(p, THIGH_R, -6.0F * lunge, 0.0F, 0.0F);
        rot(p, SHIN_L, -10.0F * lunge, 0.0F, 0.0F); rot(p, SHIN_R, -8.0F * lunge, 0.0F, 0.0F);
    }

    private static void tailSwipe(float[] p, float ta, float time) {
        idle(p, time, 0.2F);
        // the torso winds up and whips round; the tail follows as a travelling wave; the feet stay planted
        float whip = key(ta, 0.0F, 0.0F, 1.0F, -1.0F, 1.1F, -1.0F, 1.35F, 1.0F, 1.6F, 1.0F, 2.2F, 0.0F);
        rot(p, PELVIS, 0.0F, 10.0F * whip, 0.0F);
        rot(p, THIGH_L, 0.0F, -10.0F * whip, 0.0F); rot(p, THIGH_R, 0.0F, -10.0F * whip, 0.0F);
        rot(p, TORSO, 3.0F, 30.0F * whip, 0.0F);
        rot(p, HEAD, 0.0F, -20.0F * whip, 0.0F);
        for (int i = 0; i < RigData.TAIL_COUNT; i++) {
            float w = key(ta - 0.045F * i, 0.0F, 0.0F, 1.0F, 28.0F, 1.1F, 28.0F, 1.35F, -62.0F, 1.6F, -62.0F, 2.2F, 0.0F);
            rot(p, TAIL_FIRST + i, 0.0F, w * (0.55F + 0.1F * i), 0.0F);
        }
    }

    private static void stompAttack(float[] p, float ta, float time) {
        idle(p, time, 0.2F);
        float thigh = key(ta, 0.0F, 0.0F, 0.45F, 52.0F, 0.65F, 56.0F, 0.72F, 6.0F, 1.0F, 0.0F);
        float shin = key(ta, 0.0F, 0.0F, 0.45F, -70.0F, 0.65F, -62.0F, 0.72F, -6.0F, 1.0F, 0.0F);
        rot(p, THIGH_R, thigh, 0.0F, 0.0F);
        rot(p, SHIN_R, shin, 0.0F, 0.0F);
        rot(p, FOOT_R, -(thigh + shin) * 0.75F, 0.0F, 0.0F);
        rot(p, THIGH_L, 5.0F, 0.0F, 0.0F); rot(p, SHIN_L, -10.0F, 0.0F, 0.0F); rot(p, FOOT_L, 6.0F, 0.0F, 0.0F);
        mov(p, PELVIS, key(ta, 0.0F, 0.0F, 0.45F, 1.4F, 0.72F, 0.6F, 1.4F, 0.0F),
                key(ta, 0.0F, 0.0F, 0.45F, 0.5F, 0.72F, -0.9F, 1.0F, -0.3F, 1.4F, 0.0F), 0.0F);
        float tor = key(ta, 0.0F, 0.0F, 0.45F, 8.0F, 0.7F, -14.0F, 1.0F, -5.0F, 1.4F, 0.0F);
        rot(p, TORSO, tor, 0.0F, 0.0F);
        rot(p, HEAD, key(ta, 0.0F, 0.0F, 0.45F, 10.0F, 0.7F, -14.0F, 1.4F, 0.0F), 0.0F, 0.0F);
        float arm = key(ta, 0.0F, 0.0F, 0.45F, 18.0F, 0.72F, -10.0F, 1.4F, 0.0F);
        rot(p, ARM_L_UP, arm, 0.0F, 8.0F); rot(p, ARM_R_UP, arm, 0.0F, -8.0F);
        float tail = key(ta, 0.0F, 0.0F, 0.45F, -5.0F, 0.72F, 4.0F, 1.4F, 0.0F);
        for (int i = 0; i < RigData.TAIL_COUNT; i++) rot(p, TAIL_FIRST + i, tail * 0.6F, 0.0F, 0.0F);
    }

    private static void death(float[] p, float ta, float time) {
        rot(p, ROOT, 0.0F, 0.0F, key(ta, 0.0F, 0.0F, 1.5F, -6.0F, 3.0F, -92.0F, 3.3F, -86.0F, 3.6F, -90.0F, 5.0F, -90.0F));
        rot(p, HEAD, key(ta, 0.0F, 0.0F, 1.0F, 28.0F, 3.0F, -10.0F, 5.0F, -10.0F), 0.0F, 0.0F);
        rot(p, JAW, -40.0F * key(ta, 0.0F, 0.0F, 1.0F, 1.0F, 5.0F, 1.0F), 0.0F, 0.0F);
        float a = key(ta, 0.0F, 0.0F, 2.0F, 1.0F, 5.0F, 1.0F);
        rot(p, ARM_L_UP, 20.0F * a, 0.0F, 15.0F * a); rot(p, ARM_R_UP, 20.0F * a, 0.0F, -15.0F * a);
        float sh = key(ta, 0.0F, 1.0F, 1.5F, 1.0F, 3.0F, 0.0F);
        rot(p, TORSO, 0.0F, 0.0F, 1.2F * sh * (float) Math.sin(time * 40.0F));
    }

    // ------------------------------------------------------------------ pose -> matrices
    /** Each bone rotates about its own rest-pose pivot, then its translation is added; matrices are composed down the hierarchy. */
    public static void buildMatrices(float[] pose, float[] out) {
        for (int i = 0; i < B; i++) {
            float rx = pose[i * 6] * Mth.DEG_TO_RAD, ry = pose[i * 6 + 1] * Mth.DEG_TO_RAD, rz = pose[i * 6 + 2] * Mth.DEG_TO_RAD;
            float cx = Mth.cos(rx), sx = Mth.sin(rx), cy = Mth.cos(ry), sy = Mth.sin(ry), cz = Mth.cos(rz), sz = Mth.sin(rz);
            float r00 = cy * cz, r01 = sx * sy * cz - cx * sz, r02 = cx * sy * cz + sx * sz;
            float r10 = cy * sz, r11 = sx * sy * sz + cx * cz, r12 = cx * sy * sz - sx * cz;
            float r20 = -sy, r21 = sx * cy, r22 = cx * cy;
            float px = PIVOT[i * 3], py = PIVOT[i * 3 + 1], pz = PIVOT[i * 3 + 2];
            float tx = px + pose[i * 6 + 3] - (r00 * px + r01 * py + r02 * pz);
            float ty = py + pose[i * 6 + 4] - (r10 * px + r11 * py + r12 * pz);
            float tz = pz + pose[i * 6 + 5] - (r20 * px + r21 * py + r22 * pz);
            int o = i * 12, par = PARENT[i];
            if (par < 0) {
                out[o] = r00; out[o + 1] = r01; out[o + 2] = r02; out[o + 3] = tx;
                out[o + 4] = r10; out[o + 5] = r11; out[o + 6] = r12; out[o + 7] = ty;
                out[o + 8] = r20; out[o + 9] = r21; out[o + 10] = r22; out[o + 11] = tz;
            } else {
                int q = par * 12;
                float a00 = out[q], a01 = out[q + 1], a02 = out[q + 2], a03 = out[q + 3];
                float a10 = out[q + 4], a11 = out[q + 5], a12 = out[q + 6], a13 = out[q + 7];
                float a20 = out[q + 8], a21 = out[q + 9], a22 = out[q + 10], a23 = out[q + 11];
                out[o] = a00 * r00 + a01 * r10 + a02 * r20;      out[o + 1] = a00 * r01 + a01 * r11 + a02 * r21;
                out[o + 2] = a00 * r02 + a01 * r12 + a02 * r22;  out[o + 3] = a00 * tx + a01 * ty + a02 * tz + a03;
                out[o + 4] = a10 * r00 + a11 * r10 + a12 * r20;  out[o + 5] = a10 * r01 + a11 * r11 + a12 * r21;
                out[o + 6] = a10 * r02 + a11 * r12 + a12 * r22;  out[o + 7] = a10 * tx + a11 * ty + a12 * tz + a13;
                out[o + 8] = a20 * r00 + a21 * r10 + a22 * r20;  out[o + 9] = a20 * r01 + a21 * r11 + a22 * r21;
                out[o + 10] = a20 * r02 + a21 * r12 + a22 * r22; out[o + 11] = a20 * tx + a21 * ty + a22 * tz + a23;
            }
        }
    }
}

package com.example.godzilla.client.mesh;

import com.example.godzilla.GodzillaMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * The smooth skinned Godzilla mesh (assets/godzilla_mod/mesh/godzilla.bin, written by tools/glb_to_mesh.py).
 * Skinning runs on the CPU once per frame: every vertex is blended between up to 4 bone matrices, so joints bend
 * smoothly with no gaps. Dorsal plates can additionally slide out / grow per cluster before skinning.
 */
public final class GodzillaMesh {
    private static final ResourceLocation DATA = ResourceLocation.fromNamespaceAndPath(GodzillaMod.MODID, "mesh/godzilla.bin");
    private static GodzillaMesh instance;

    public static synchronized GodzillaMesh get() {
        if (instance == null) instance = load();
        return instance;
    }

    public final int vertexCount, indexCount, clusterCount, partCount;
    public final float[] clusterPivot, clusterCenter;
    public final int[] partTex, partStart, partLen, partFlags;
    public final float[] pos, nrm, uv;
    public final byte[] boneIdx, boneW, cluster;
    public final int[] indices;
    /** Skinned result of the last skin() call (model space, blocks). */
    public final float[] outPos, outNrm;
    /** Per plate cluster: vertex indices of the spike tips (where the energy flames grow). */
    public final int[][] tips;

    private GodzillaMesh(ByteBuffer bb) {
        byte[] magic = new byte[4];
        bb.get(magic);
        vertexCount = bb.getInt(); indexCount = bb.getInt();
        int boneCount = bb.getInt();
        partCount = bb.getInt(); clusterCount = bb.getInt();
        for (int i = 0; i < boneCount; i++) {        // names / parents / pivots are baked into RigData already; skip them
            int len = bb.get() & 0xFF;
            bb.position(bb.position() + len + 4 + 12);
        }
        clusterPivot = new float[clusterCount * 3]; clusterCenter = new float[clusterCount * 3];
        for (int i = 0; i < clusterCount; i++) {
            for (int k = 0; k < 3; k++) clusterPivot[i * 3 + k] = bb.getFloat();
            for (int k = 0; k < 3; k++) clusterCenter[i * 3 + k] = bb.getFloat();
        }
        partTex = new int[partCount]; partStart = new int[partCount]; partLen = new int[partCount]; partFlags = new int[partCount];
        for (int i = 0; i < partCount; i++) { partTex[i] = bb.getInt(); partStart[i] = bb.getInt(); partLen[i] = bb.getInt(); partFlags[i] = bb.getInt(); }
        pos = new float[vertexCount * 3]; nrm = new float[vertexCount * 3]; uv = new float[vertexCount * 2];
        for (int i = 0; i < pos.length; i++) pos[i] = bb.getFloat();
        for (int i = 0; i < nrm.length; i++) nrm[i] = bb.getFloat();
        for (int i = 0; i < uv.length; i++) uv[i] = bb.getFloat();
        boneIdx = new byte[vertexCount * 4]; boneW = new byte[vertexCount * 4]; cluster = new byte[vertexCount];
        bb.get(boneIdx); bb.get(boneW); bb.get(cluster);
        indices = new int[indexCount];
        for (int i = 0; i < indexCount; i++) indices[i] = bb.getInt();
        outPos = new float[vertexCount * 3]; outNrm = new float[vertexCount * 3];

        tips = new int[clusterCount][];
        for (int c = 0; c < clusterCount; c++) {
            List<Integer> verts = new ArrayList<>();
            for (int v = 0; v < vertexCount; v++) if ((cluster[v] & 0xFF) == c) verts.add(v);
            verts.sort((a, b) -> Float.compare(pos[b * 3 + 1], pos[a * 3 + 1]));
            List<Integer> picked = new ArrayList<>();
            for (int v : verts) {
                boolean far = true;
                for (int q : picked) {
                    float dx = pos[v * 3] - pos[q * 3], dy = pos[v * 3 + 1] - pos[q * 3 + 1], dz = pos[v * 3 + 2] - pos[q * 3 + 2];
                    if (dx * dx + dy * dy + dz * dz < 1.6F * 1.6F) { far = false; break; }
                }
                if (far) picked.add(v);
                if (picked.size() >= 9) break;
            }
            tips[c] = picked.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    private static GodzillaMesh load() {
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(DATA)) {
            ByteBuffer bb = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            return new GodzillaMesh(bb);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + DATA, e);
        }
    }

    // ------------------------------------------------------------------ skinning
    /**
     * @param mats      12 floats (3x4 affine, row-major) per bone
     * @param ext       plate extension per cluster, 0..1
     * @param push      blocks a fully extended plate slides up
     * @param pushScale how much bigger (fraction) a fully extended plate gets, about its own base
     */
    public void skin(float[] mats, float[] ext, float push, float pushScale) {
        for (int v = 0; v < vertexCount; v++) {
            float px = pos[v * 3], py = pos[v * 3 + 1], pz = pos[v * 3 + 2];
            int c = cluster[v] & 0xFF;
            if (c < clusterCount && ext[c] > 0.001F) {
                float e = ext[c], s = 1.0F + e * pushScale;
                float bx = clusterPivot[c * 3], by = clusterPivot[c * 3 + 1], bz = clusterPivot[c * 3 + 2];
                px = bx + (px - bx) * s; py = by + (py - by) * s + e * push; pz = bz + (pz - bz) * s;
            }
            float nx0 = nrm[v * 3], ny0 = nrm[v * 3 + 1], nz0 = nrm[v * 3 + 2];
            float ox = 0, oy = 0, oz = 0, nx = 0, ny = 0, nz = 0;
            for (int k = 0; k < 4; k++) {
                int w = boneW[v * 4 + k] & 0xFF;
                if (w == 0) continue;
                float wf = w * (1.0F / 255.0F);
                int m = (boneIdx[v * 4 + k] & 0xFF) * 12;
                ox += wf * (mats[m] * px + mats[m + 1] * py + mats[m + 2] * pz + mats[m + 3]);
                oy += wf * (mats[m + 4] * px + mats[m + 5] * py + mats[m + 6] * pz + mats[m + 7]);
                oz += wf * (mats[m + 8] * px + mats[m + 9] * py + mats[m + 10] * pz + mats[m + 11]);
                nx += wf * (mats[m] * nx0 + mats[m + 1] * ny0 + mats[m + 2] * nz0);
                ny += wf * (mats[m + 4] * nx0 + mats[m + 5] * ny0 + mats[m + 6] * nz0);
                nz += wf * (mats[m + 8] * nx0 + mats[m + 9] * ny0 + mats[m + 10] * nz0);
            }
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l > 1.0E-6F) { nx /= l; ny /= l; nz /= l; }
            outPos[v * 3] = ox; outPos[v * 3 + 1] = oy; outPos[v * 3 + 2] = oz;
            outNrm[v * 3] = nx; outNrm[v * 3 + 1] = ny; outNrm[v * 3 + 2] = nz;
        }
    }

    // ------------------------------------------------------------------ drawing (entity render types are QUADS: a triangle is v0,v1,v2,v2)
    public void drawPart(VertexConsumer vc, PoseStack.Pose pose, int part, int light, int overlay) {
        int start = partStart[part], end = start + partLen[part];
        for (int i = start; i < end; i += 3) {
            for (int k = 0; k < 4; k++) {
                int v = indices[i + (k == 3 ? 2 : k)];
                vc.addVertex(pose, outPos[v * 3], outPos[v * 3 + 1], outPos[v * 3 + 2]).setColor(255, 255, 255, 255)
                        .setUv(uv[v * 2], uv[v * 2 + 1]).setOverlay(overlay).setLight(light)
                        .setNormal(pose, outNrm[v * 3], outNrm[v * 3 + 1], outNrm[v * 3 + 2]);
            }
        }
    }

    /**
     * Re-draws the plate triangles of the clusters flagged in `lit`, optionally pushed outward along their normals
     * (an energy shell). alpha[c] is the per-cluster opacity. useMeshUv = the plate texture, otherwise a scrolling noise mapping.
     */
    public void drawPlateGlow(VertexConsumer vc, PoseStack.Pose pose, int plateRgb, float[] alpha, float inflate,
                              boolean useMeshUv, float uScroll, float vScroll) {
        int r = (plateRgb >> 16) & 255, g = (plateRgb >> 8) & 255, b = plateRgb & 255;
        for (int part = 0; part < partCount; part++) {
            if (partFlags[part] != 1) continue;
            int start = partStart[part], end = start + partLen[part];
            for (int i = start; i < end; i += 3) {
                int c0 = cluster[indices[i]] & 0xFF;
                if (c0 >= clusterCount || alpha[c0] < 0.01F) continue;
                int a = (int) (Math.min(1.0F, alpha[c0]) * 255.0F);
                for (int k = 0; k < 4; k++) {
                    int v = indices[i + (k == 3 ? 2 : k)];
                    float x = outPos[v * 3] + outNrm[v * 3] * inflate, y = outPos[v * 3 + 1] + outNrm[v * 3 + 1] * inflate, z = outPos[v * 3 + 2] + outNrm[v * 3 + 2] * inflate;
                    float u = useMeshUv ? uv[v * 2] : (x + z) * 0.22F + uScroll;
                    float w = useMeshUv ? uv[v * 2 + 1] : y * 0.22F + vScroll;
                    vc.addVertex(pose, x, y, z).setColor(r, g, b, a).setUv(u, w).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(0.0F, 1.0F, 0.0F);
                }
            }
        }
    }
}

#!/usr/bin/env python3
"""
Rigs the supplied Godzilla GLB for the game and exports it:
  assets/godzilla_mod/mesh/godzilla.bin            smooth skinned mesh (positions, normals, uvs, 4 bone weights, plate clusters)
  assets/godzilla_mod/textures/entity/godzilla_body.png, godzilla_plates.png   (the model's own textures)
  src/.../client/mesh/RigData.java                 skeleton constants shared with the Java pose code
Run:  python3 tools/glb_to_mesh.py [--preview]       needs numpy pillow scipy trimesh matplotlib
The GLB has no skeleton, so bones + skin weights are generated here (region masks, then Laplacian smoothing across the
mesh so joints bend smoothly with no gaps). Model credit: 'Minus One Godzilla' by CGreature (Sketchfab).
"""
import hashlib, os, struct, sys
import numpy as np
from PIL import Image
from scipy.spatial import cKDTree
import scipy.sparse as sp

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "glb"))
from loadglb import load_scene

PREVIEW = "--preview" in sys.argv
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = f"{ROOT}/src/main/resources/assets/godzilla_mod"
GLB = f"{ROOT}/tools/glb/minus_one_godzilla.glb"
os.makedirs(f"{RES}/mesh", exist_ok=True); os.makedirs(f"{RES}/textures/entity", exist_ok=True)

# ---------------------------------------------------------------- scale: 94 ft = 28.65 blocks (head height)
BODY_H_MESH = 5.37
SB = 94 * 0.3048 / BODY_H_MESH              # blocks per mesh unit
Z0 = 0.4                                    # mesh z that becomes the entity origin (middle of the feet)
def to_blocks(p):  return np.stack([-p[:, 0] * SB, p[:, 1] * SB, -(p[:, 2] - Z0) * SB], 1)   # mesh faces +z, model faces -z (180 deg yaw)
def to_blocks_n(n): return np.stack([-n[:, 0], n[:, 1], -n[:, 2]], 1)

meshes = dict(load_scene(GLB))
body, plates = meshes["Object_0"], meshes["Object_4"]
details = [meshes[k] for k in ("Object_1", "Object_2", "Object_3")]

# ---------------------------------------------------------------- skeleton (mesh-space joint positions)
def yc_tail(z):   # centre height of the tail at mesh z (measured)
    zs = [-1.0, -1.9, -2.5, -3.1, -3.7, -5.0, -6.1, -7.3, -7.95]
    ys = [1.55, 1.19, 0.76, 0.54, 0.47, 0.50, 0.44, 0.46, 0.44]
    return float(np.interp(z, zs[::-1], ys[::-1]))
TAIL_Z = [-1.0, -1.9, -2.8, -3.7, -4.7, -5.7, -6.6, -7.3, -7.95]     # 8 tail segments
BONES = [  # name, parent, pivot (mesh space)
    ("root", None, (0, 0, Z0)),
    ("pelvis", "root", (0, 2.3, 0.0)),
    ("torso", "pelvis", (0, 3.0, 0.2)),
    ("neck", "torso", (0, 4.35, 0.7)),
    ("head", "neck", (0, 4.65, 0.95)),
    ("jaw", "head", (0, 4.88, 1.15)),
    ("arm_l_up", "torso", (-0.92, 4.0, 0.85)), ("arm_l_fore", "arm_l_up", (-1.10, 3.40, 0.72)),
    ("arm_r_up", "torso", (0.92, 4.0, 0.85)),  ("arm_r_fore", "arm_r_up", (1.10, 3.40, 0.72)),
    ("thigh_l", "pelvis", (-0.55, 2.3, 0.0)), ("shin_l", "thigh_l", (-0.80, 1.15, 0.50)), ("foot_l", "shin_l", (-0.86, 0.45, 0.30)),
    ("thigh_r", "pelvis", (0.55, 2.3, 0.0)),  ("shin_r", "thigh_r", (0.80, 1.15, 0.50)),  ("foot_r", "shin_r", (0.86, 0.45, 0.30)),
] + [(f"tail_{k + 1}", "pelvis" if k == 0 else f"tail_{k}", (0.0, yc_tail(TAIL_Z[k]), TAIL_Z[k])) for k in range(8)]
BIDX = {b[0]: i for i, b in enumerate(BONES)}
NB = len(BONES)

def sstep(x, a, b):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)

# ---------------------------------------------------------------- body weights
def raw_weights(P):
    x, y, z = P[:, 0], P[:, 1], P[:, 2]
    ax = np.abs(x + 0.03)
    W = np.zeros((len(P), NB))
    tail = sstep(-z, 0.7, 1.45)                                              # 0 in the body, 1 deep in the tail
    leg = sstep(-y, -2.45, -2.0) * sstep(ax, 0.12, 0.45) * (1 - tail)        # below the hip and off to the side
    arm = sstep(ax, 0.80, 0.95) * sstep(-y, -4.45, -4.15) * sstep(y, 2.35, 2.65) * (1 - tail) * (1 - leg)
    head = sstep(y, 4.45, 4.72) * sstep(z, 0.30, 0.75) * (1 - arm) * (1 - tail)
    jaw = head * sstep(-y, -4.97, -4.86) * sstep(z, 1.05, 1.35)
    rest = np.clip(1 - tail - leg - arm - head, 0, 1)
    side_l = (x < 0).astype(float)                                           # mesh x < 0 = "l"
    # tail: linear blend between segment pivots along z
    zc = np.array([TAIL_Z[k] for k in range(8)])
    for k in range(8):
        lo = zc[k + 1] if k < 7 else -99.0
        hi = zc[k - 1] if k > 0 else 99.0
        wk = np.clip(np.minimum((z - lo) / max(1e-6, zc[k] - lo) if k < 7 else 1.0, (hi - z) / max(1e-6, hi - zc[k]) if k > 0 else 1.0), 0, 1)
        W[:, BIDX[f"tail_{k + 1}"]] += tail * wk
    # legs
    shin = sstep(-y, -1.35, -0.95); foot = sstep(-y, -0.65, -0.38)
    for s, sl in (("l", side_l), ("r", 1 - side_l)):
        W[:, BIDX[f"thigh_{s}"]] += leg * sl * (1 - shin)
        W[:, BIDX[f"shin_{s}"]] += leg * sl * shin * (1 - foot)
        W[:, BIDX[f"foot_{s}"]] += leg * sl * shin * foot
        fore = sstep(-y, -3.55, -3.25)
        W[:, BIDX[f"arm_{s}_up"]] += arm * sl * (1 - fore)
        W[:, BIDX[f"arm_{s}_fore"]] += arm * sl * fore
    W[:, BIDX["head"]] += head - jaw
    W[:, BIDX["jaw"]] += jaw
    neck = rest * sstep(y, 4.05, 4.40)
    W[:, BIDX["neck"]] += neck
    torso = (rest - neck) * sstep(y, 2.65, 3.3)
    W[:, BIDX["torso"]] += torso
    W[:, BIDX["pelvis"]] += rest - neck - torso
    return W

def adjacency(mesh):
    """Vertex adjacency over *position-merged* vertices (UV seams split vertices; weights must not tear there)."""
    V = mesh.vertices
    key = np.round(V * 1e4).astype(np.int64)
    _, inv = np.unique(key, axis=0, return_inverse=True)
    inv = inv.reshape(-1)
    n = inv.max() + 1
    F = inv[mesh.faces]
    e = np.vstack([F[:, [0, 1]], F[:, [1, 2]], F[:, [2, 0]]])
    A = sp.coo_matrix((np.ones(len(e)), (e[:, 0], e[:, 1])), shape=(n, n)).tocsr()
    A = ((A + A.T) > 0).astype(float)
    return inv, A

def smooth(W, inv, A, iters, keep=0.35):
    n = A.shape[0]
    cnt = np.bincount(inv, minlength=n)[:, None]
    U = np.zeros((n, W.shape[1]))
    np.add.at(U, inv, W)
    U /= cnt
    deg = np.asarray(A.sum(1)).reshape(-1, 1)
    for _ in range(iters):
        U = keep * U + (1 - keep) * (A @ U) / np.maximum(deg, 1)
    W2 = U[inv]
    return W2 / np.maximum(W2.sum(1, keepdims=True), 1e-9)

Pm = body.vertices
W_body = raw_weights(Pm)
inv, A = adjacency(body)
W_body = smooth(W_body, inv, A, 10)

# keep the 4 strongest influences
def top4(W):
    idx = np.argsort(-W, axis=1)[:, :4]
    w = np.take_along_axis(W, idx, axis=1)
    w = w / np.maximum(w.sum(1, keepdims=True), 1e-9)
    return idx.astype(np.uint8), (w * 255 + 0.5).astype(np.uint8)

# ---------------------------------------------------------------- details (teeth/eyes/gums): glued to head or jaw
def detail_weights(g):
    c = g.vertices
    jaw = (c[:, 1] < 4.95) & (c[:, 2] > 1.25)
    W = np.zeros((len(c), NB)); W[jaw, BIDX["jaw"]] = 1; W[~jaw, BIDX["head"]] = 1
    return W

# ---------------------------------------------------------------- plates: 14 clusters (equal length along the spine, tail tip -> upper back)
NPL = 14
pc = plates.vertices
zb = np.linspace(pc[:, 2].min() - 1e-6, pc[:, 2].max() + 1e-6, 60)
path = []
for a, b in zip(zb[:-1], zb[1:]):
    m = (pc[:, 2] >= a) & (pc[:, 2] < b)
    if m.sum(): path.append((np.median(pc[m, 2]), np.median(pc[m, 1])))
path = np.array(path)
cum = np.concatenate([[0], np.cumsum(np.hypot(np.diff(path[:, 0]), np.diff(path[:, 1])))])
cluster = np.minimum(NPL - 1, (np.interp(pc[:, 2], path[:, 0], cum) / cum[-1] * NPL).astype(int))
tree = cKDTree(Pm)
_, nn = tree.query(pc)
W_plates = W_body[nn]
cl_pivot, cl_center = [], []
for i in range(NPL):
    m = cluster == i; Pi = pc[m]
    low = Pi[np.argsort(Pi[:, 1])[: max(5, len(Pi) // 8)]]
    cl_pivot.append(low.mean(0)); cl_center.append(Pi.mean(0))
print("plate vertices per cluster:", np.bincount(cluster, minlength=NPL).tolist())

# ---------------------------------------------------------------- textures (dedupe by content)
tex_ids, tex_files = {}, []
def tex_for(g, label):
    img = getattr(g.visual.material, "baseColorTexture", None)
    if img is None: return 0
    h = hashlib.md5(np.asarray(img.convert("RGB")).tobytes()).hexdigest()
    if h not in tex_ids:
        name = {0: "godzilla_body", 1: "godzilla_plates"}.get(len(tex_files), f"godzilla_tex{len(tex_files)}")
        img.convert("RGB").save(f"{RES}/textures/entity/{name}.png")
        tex_ids[h] = len(tex_files); tex_files.append(name)
    return tex_ids[h]

# ---------------------------------------------------------------- assemble + write
parts = [(body, W_body, None, False), (plates, W_plates, cluster, True)] + [(d, detail_weights(d), None, False) for d in details]
P_all, N_all, UV_all, BI_all, BW_all, CL_all, IDX, part_rows = [], [], [], [], [], [], [], []
voff = 0
for g, W, cl, is_plate in parts:
    tid = tex_for(g, "")
    bi, bw = top4(W)
    P_all.append(to_blocks(g.vertices)); N_all.append(to_blocks_n(g.vertex_normals))
    uv = g.visual.uv if g.visual.uv is not None else np.zeros((len(g.vertices), 2))
    UV_all.append(np.stack([uv[:, 0], 1.0 - uv[:, 1]], 1))
    BI_all.append(bi); BW_all.append(bw)
    CL_all.append(np.full(len(g.vertices), 255, np.uint8) if cl is None else cl.astype(np.uint8))
    f = g.faces + voff
    # the x/z flip is a 180-degree rotation, so winding is preserved
    part_rows.append((tid, len(IDX) * 3 if False else sum(len(x) for x in IDX) , len(f), 1 if is_plate else 0))
    IDX.append(f.reshape(-1).astype(np.uint32)); voff += len(g.vertices)
P_all = np.vstack(P_all).astype(np.float32); N_all = np.vstack(N_all).astype(np.float32); UV_all = np.vstack(UV_all).astype(np.float32)
BI_all = np.vstack(BI_all); BW_all = np.vstack(BW_all); CL_all = np.concatenate(CL_all); IDX_all = np.concatenate(IDX)
part_rows = []
off = 0
for (g, W, cl, is_plate), arr in zip(parts, IDX):
    part_rows.append((tex_for(g, ""), off, len(arr), 1 if is_plate else 0)); off += len(arr)

bone_pivots_b = to_blocks(np.array([b[2] for b in BONES], dtype=float))
with open(f"{RES}/mesh/godzilla.bin", "wb") as f:
    f.write(b"GZM1")
    f.write(struct.pack("<5i", len(P_all), len(IDX_all), NB, len(part_rows), NPL))
    for (name, parent, _), pv in zip(BONES, bone_pivots_b):
        nb = name.encode(); f.write(struct.pack("<B", len(nb))); f.write(nb)
        f.write(struct.pack("<i3f", -1 if parent is None else BIDX[parent], *pv))
    cp, cc = to_blocks(np.array(cl_pivot)), to_blocks(np.array(cl_center))
    for i in range(NPL): f.write(struct.pack("<6f", *cp[i], *cc[i]))
    for r in part_rows: f.write(struct.pack("<4i", *r))
    f.write(P_all.astype("<f4").tobytes()); f.write(N_all.astype("<f4").tobytes()); f.write(UV_all.astype("<f4").tobytes())
    f.write(BI_all.astype(np.uint8).tobytes()); f.write(BW_all.astype(np.uint8).tobytes()); f.write(CL_all.astype(np.uint8).tobytes())
    f.write(IDX_all.astype("<u4").tobytes())
size_mb = os.path.getsize(f"{RES}/mesh/godzilla.bin") / 1e6
print(f"mesh: {len(P_all)} vertices, {len(IDX_all) // 3} triangles, {NB} bones, {NPL} plate clusters, {size_mb:.2f} MB; textures {tex_files}")

MOUTH = to_blocks(np.array([[0.0, 4.93, 2.0]]))[0]
# ---------------------------------------------------------------- Java constants (bone indices)
names = ", ".join(f'"{b[0]}"' for b in BONES)
cons = "\n".join(f"    public static final int {b[0].upper()} = {i};" for i, b in enumerate(BONES))
os.makedirs(f"{ROOT}/src/main/java/com/example/godzilla/client/mesh", exist_ok=True)
with open(f"{ROOT}/src/main/java/com/example/godzilla/client/mesh/RigData.java", "w") as f:
    f.write(f"""package com.example.godzilla.client.mesh;

/** GENERATED by tools/glb_to_mesh.py: bone indices of mesh/godzilla.bin. */
public final class RigData {{
    private RigData() {{}}
    public static final int BONE_COUNT = {NB};
    public static final int PLATE_CLUSTERS = {NPL};
{cons}
    public static final int TAIL_COUNT = 8;
    public static final int TAIL_FIRST = TAIL_1;
    public static final int[] PARENT = {{{", ".join(str(-1 if b[1] is None else BIDX[b[1]]) for b in BONES)}}};
    /** Rest-pose joint positions, blocks, model space (front = -z), 3 floats per bone. */
    public static final float[] PIVOT = {{{", ".join(f"{v:.4f}F" for v in bone_pivots_b.reshape(-1))}}};
    /** Mouth tip in rest pose (blocks, model space, front = -z). */
    public static final float MOUTH_X = {MOUTH[0]:.4f}F, MOUTH_Y = {MOUTH[1]:.4f}F, MOUTH_Z = {MOUTH[2]:.4f}F;
}}
""")

# PlateProfile for the server side (particles/sounds positions + mouth offsets)
def arr(v): return ", ".join(f"{x:.3f}" for x in v)
cc_b = to_blocks(np.array(cl_center))
with open(f"{ROOT}/src/main/java/com/example/godzilla/entity/PlateProfile.java", "w") as f:
    f.write(f"""package com.example.godzilla.entity;

/**
 * GENERATED by tools/glb_to_mesh.py. Dorsal plate cluster centres (blocks, relative to the feet; index 0 = tail tip ... {NPL - 1} = upper back)
 * and the mouth offset, used by the server for sounds and the ray origin. The client draws from the mesh itself.
 */
public final class PlateProfile {{
    private PlateProfile() {{}}
    public static final double[] FORWARD = {{{arr(-cc_b[:, 2])}}};
    public static final double[] UP = {{{arr(cc_b[:, 1])}}};
    public static final double MOUTH_FORWARD = {-MOUTH[2]:.3f};
    public static final double MOUTH_UP = {MOUTH[1]:.3f};
    /** How far a plate pushes out (blocks, along +y) and how much it grows when extended. */
    public static final float PUSH_BLOCKS = 0.9F;
    public static final float PUSH_SCALE = 0.45F;
}}
""")

# ---------------------------------------------------------------- preview: skin test poses
if PREVIEW:
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.collections import PolyCollection
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    pivots = bone_pivots_b
    parent = [(-1 if p is None else BIDX[p]) for _, p, _ in BONES]

    def rot(ax, deg):
        a = np.radians(deg); c, s = np.cos(a), np.sin(a)
        if ax == 0: return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
        if ax == 1: return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
        return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])
    def mats(pose):
        M = [None] * NB
        for i in range(NB):
            r, t = pose.get(BONES[i][0], ((0, 0, 0), (0, 0, 0)))
            R = rot(2, r[2]) @ rot(1, r[1]) @ rot(0, r[0])        # Z * Y * X
            L = np.eye(4); L[:3, :3] = R
            L[:3, 3] = pivots[i] - R @ pivots[i] + np.array(t)     # rotate about the pivot, then translate
            M[i] = L if parent[i] < 0 else M[parent[i]] @ L
        return M
    def skin(pose):
        M = np.array(mats(pose))
        Pn = np.zeros_like(P_all)
        Ph = np.hstack([P_all, np.ones((len(P_all), 1), np.float32)])
        for k in range(4):
            Mk = M[BI_all[:, k]]; w = BW_all[:, k, None] / 255.0
            Pn += w * np.einsum("nij,nj->ni", Mk[:, :3, :], Ph)
        return Pn

    cols = []
    for g in [p[0] for p in parts]:
        vis = g.visual; img = getattr(vis.material, "baseColorTexture", None)
        a = np.asarray(img.convert("RGB"), dtype=np.float32) / 255.0 if img is not None else None
        uv = vis.uv
        if a is None or uv is None: cols.append(np.full((len(g.vertices), 3), 0.4)); continue
        h, w, _ = a.shape
        cols.append(a[((1 - np.mod(uv[:, 1], 1.0)) * (h - 1)).astype(int) * 0 + (np.mod(1 - uv[:, 1], 1.0) * (h - 1)).astype(int), (np.mod(uv[:, 0], 1.0) * (w - 1)).astype(int)])
    C_all = np.vstack(cols)

    def render(P, path, azim=38, elev=8, size=(9, 7)):
        a, e = np.radians(azim), np.radians(elev)
        x1 = P[:, 0] * np.cos(a) + P[:, 2] * np.sin(a); z1 = -P[:, 0] * np.sin(a) + P[:, 2] * np.cos(a)
        sx, sy, dz = -x1, P[:, 1] * np.cos(e) + z1 * np.sin(e), z1 * np.cos(e) - P[:, 1] * np.sin(e)
        F = IDX_all.reshape(-1, 3)
        tri = np.stack([sx[F], sy[F]], 2); depth = dz[F].mean(1)
        n = np.cross(P[F[:, 1]] - P[F[:, 0]], P[F[:, 2]] - P[F[:, 0]]); ln = np.linalg.norm(n, axis=1, keepdims=True) + 1e-9; n = n / ln
        nz = (-np.sin(a) * n[:, 0] + np.cos(a) * n[:, 2]) * np.cos(e) - n[:, 1] * np.sin(e)
        col = np.clip(C_all[F].mean(1) * (0.55 + 0.45 * np.abs(nz))[:, None] * 1.7, 0, 1)
        order = np.argsort(-depth)
        fig, ax = plt.subplots(figsize=size); fig.patch.set_facecolor("#c9cbd0"); ax.set_facecolor("#c9cbd0")
        ax.add_collection(PolyCollection(tri[order], facecolors=col[order], edgecolors="none")); ax.set_aspect("equal"); ax.axis("off")
        ax.set_xlim(sx.min() - 2, sx.max() + 2); ax.set_ylim(sy.min() - 2, sy.max() + 2)
        fig.savefig(path, dpi=85, bbox_inches="tight", facecolor=fig.get_facecolor()); plt.close(fig)

    def P_(rx=0, ry=0, rz=0, t=(0, 0, 0)): return ((rx, ry, rz), t)
    poses = {
        "rest": {},
        # test bends: left leg forward + knee, right arm up, jaw open, head up, tail swing, torso twist
        "test_bend": {"thigh_l": P_(rx=35), "shin_l": P_(rx=-45), "thigh_r": P_(rx=-25), "arm_r_up": P_(rx=-50), "arm_r_fore": P_(rx=-40),
                      "arm_l_up": P_(rx=25), "jaw": P_(rx=35), "head": P_(rx=-20), "torso": P_(ry=18, rx=-8),
                      **{f"tail_{k}": P_(ry=14) for k in range(1, 9)}},
    }
    # ---- mirror of GodzillaAnim.walk() so the gait can be checked here without running the game
    TAIL_FIRST = BIDX["tail_1"]
    def walk_pose(A, phi):
        pose = {}
        def add(bone, rx=0.0, ry=0.0, rz=0.0, t=(0, 0, 0)):
            r0, t0 = pose.get(bone, ((0, 0, 0), (0, 0, 0)))
            pose[bone] = ((r0[0] + rx, r0[1] + ry, r0[2] + rz), (t0[0] + t[0], t0[1] + t[1], t0[2] + t[2]))
        s_, c_, c2 = np.sin(phi), np.cos(phi), np.cos(2 * phi); dip = 0.5 + 0.5 * c2
        add("pelvis", 0, 5.5 * A * c_, -3.2 * A * s_, (1.2 * A * s_, -0.65 * A * dip, 0))
        add("torso", -3.0 * A - 1.6 * A * dip, -8.5 * A * c_, -4.5 * A * s_)
        add("neck", 0, 2 * A * c_, 3 * A * s_); add("head", 0, 2 * A * c_, 2.5 * A * s_)
        for side, sfx in ((0, "l"), (1, "r")):
            ph = phi + side * np.pi
            swing = np.cos(ph); lift = max(0.0, -np.sin(ph))
            thigh = 27 * A * swing; knee = -56 * A * lift ** 0.8
            add(f"thigh_{sfx}", thigh, t=(0, 0.5 * A * lift, 0)); add(f"shin_{sfx}", knee)
            add(f"foot_{sfx}", -(thigh + knee) * 0.9 + 10 * A * lift)
        add("arm_l_up", -13 * A * c_); add("arm_r_up", 13 * A * c_)
        add("arm_l_fore", 14 * A + 5 * A * s_); add("arm_r_fore", 14 * A - 5 * A * s_)
        for i in range(8):
            add(f"tail_{i + 1}", 1.2 * A * np.sin(2 * phi - 0.7 * i), -A * (2.8 + 0.9 * i) * np.sin(phi - 0.65 * i))
        return pose
    from PIL import Image as _I
    frames = []
    for k, ph in enumerate((0.0, np.pi / 2, np.pi, 3 * np.pi / 2)):
        render(skin(walk_pose(1.0, ph)), f"{ROOT}/tools/_w{k}.png", azim=90, elev=3, size=(6, 5))
        frames.append(_I.open(f"{ROOT}/tools/_w{k}.png").convert("RGB"))
    hh = max(f.height for f in frames); sheet = _I.new("RGB", (sum(f.width for f in frames), hh), (201, 203, 208)); xx = 0
    for f in frames: sheet.paste(f, (xx, 0)); xx += f.width
    sheet.save(f"{ROOT}/tools/preview_walk_cycle.png")
    for k in range(4): os.remove(f"{ROOT}/tools/_w{k}.png")
    for name, pose in poses.items():
        render(skin(pose), f"{ROOT}/tools/preview_{name}.png")
        if name == "test_bend": render(skin(pose), f"{ROOT}/tools/preview_{name}_side.png", azim=90, elev=4)
    print("previews written")

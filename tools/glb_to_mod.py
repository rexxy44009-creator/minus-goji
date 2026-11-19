#!/usr/bin/env python3
"""
Converts tools/glb/minus_one_godzilla.glb into the mod's GeckoLib model (voxel cubes), atlas texture, glowmask and
entity/PlateProfile.java.  Needs: numpy pillow scipy trimesh matplotlib.   python3 tools/glb_to_mod.py [--render]

Pipeline: scale to 94 ft -> sample the textured surface -> voxelise (half-block voxels) -> colour palette ->
split into bones (legs, arms, head, jaw, torso, 6 tail segments, 14 dorsal-plate clusters) -> greedy merge into boxes ->
pack a tiny atlas of palette-tinted scale tiles.   (Model credit: 'Minus One Godzilla' by CGreature on Sketchfab.)
"""
import json, math, os, random, sys
import numpy as np
from PIL import Image
from scipy.cluster.vq import kmeans2

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "glb"))
from loadglb import load_scene

RENDER = "--render" in sys.argv
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODID = "godzilla_mod"
RES = f"{ROOT}/src/main/resources/assets/{MODID}"
JAVA = f"{ROOT}/src/main/java/com/example/godzilla/entity"
GLB = f"{ROOT}/tools/glb/minus_one_godzilla.glb"
for d in (f"{RES}/geo", f"{RES}/animations", f"{RES}/textures/entity", JAVA):
    os.makedirs(d, exist_ok=True)

rng = np.random.default_rng(1954)
random.seed(1954)

# ------------------------------------------------------------------ scale: 94 ft = 28.65 blocks tall (head height)
BODY_H_MESH = 5.37                          # head top, excluding the dorsal spikes
TARGET_BLOCKS = 94 * 0.3048                 # 28.65 blocks (1 block = 1 m)
S = TARGET_BLOCKS * 16.0 / BODY_H_MESH      # model units (1/16 block) per mesh unit
Z0 = 0.4                                    # mesh z that becomes the entity origin (middle of the feet)
V = 8                                       # voxel size in units (half a block)
PALETTE_K = int(os.environ.get("PALETTE_K", "28"))
SMOOTH = int(os.environ.get("SMOOTH", "1"))

def to_model(p):  return np.stack([-p[:, 0] * S, p[:, 1] * S, -(p[:, 2] - Z0) * S], 1)   # mesh faces +z, model faces -z
def to_mesh(m):   return np.stack([-m[:, 0] / S, m[:, 1] / S, -m[:, 2] / S + Z0], 1)

# ------------------------------------------------------------------ sampling + voxelising
def sample_mesh(g, per_unit_area, seed):
    r = np.random.default_rng(seed)
    vis = g.visual
    img = getattr(vis.material, "baseColorTexture", None) or getattr(vis.material, "image", None)
    tex = np.asarray(img.convert("RGB"), dtype=np.float32) / 255.0 if img is not None else None
    F, Vt, area = g.faces, g.vertices, g.area_faces
    n = int(area.sum() * per_unit_area)
    fi = r.choice(len(F), size=n, p=area / area.sum())
    r1, r2 = r.random(n), r.random(n)
    s = np.sqrt(r1); b0, b1, b2 = 1 - s, s * (1 - r2), s * r2
    tri = Vt[F[fi]]
    pts = tri[:, 0] * b0[:, None] + tri[:, 1] * b1[:, None] + tri[:, 2] * b2[:, None]
    if tex is not None and vis.uv is not None:
        t = vis.uv[F[fi]]
        uvp = t[:, 0] * b0[:, None] + t[:, 1] * b1[:, None] + t[:, 2] * b2[:, None]
        h, w, _ = tex.shape
        u = np.mod(uvp[:, 0], 1.0); v = np.mod(1.0 - uvp[:, 1], 1.0)
        col = tex[(v * (h - 1)).astype(int), (u * (w - 1)).astype(int)]
    else:
        col = np.full((n, 3), 0.35, dtype=np.float32)
    return pts, col

def voxelize(g, size, seed):
    """Surface voxels of a mesh part at `size` model units. Returns int idx (N,3) and mean colour (N,3)."""
    pts, col = sample_mesh(g, 300.0 / ((size / S) ** 2), seed)
    m = to_model(pts)
    idx = np.floor(m / size).astype(np.int64)
    mn = idx.min(0); idx -= mn
    dims = idx.max(0) + 1
    flat = (idx[:, 0] * dims[1] + idx[:, 1]) * dims[2] + idx[:, 2]
    uniq, inv, cnt = np.unique(flat, return_inverse=True, return_counts=True)
    cs = np.stack([np.bincount(inv, weights=col[:, k], minlength=len(uniq)) for k in range(3)], 1) / cnt[:, None]
    ix = uniq // (dims[1] * dims[2]); rem = uniq % (dims[1] * dims[2])
    return np.stack([ix, rem // dims[2], rem % dims[2]], 1) + mn, cs

meshes = dict(load_scene(GLB))
body_vox, body_col = voxelize(meshes["Object_0"], V, 1)
plate_vox, plate_col = voxelize(meshes["Object_4"], V, 2)
detail_vox, detail_col = [], []
for nm in ("Object_1", "Object_2", "Object_3"):                           # teeth, gums, eyes: finer voxels
    v_, c_ = voxelize(meshes[nm], 4, 3)
    detail_vox.append(v_); detail_col.append(c_)
print(f"voxels: body {len(body_vox)}, plates {len(plate_vox)}, head details {sum(len(v) for v in detail_vox)}")

# ------------------------------------------------------------------ bone assignment (mesh space rules)
TAIL_EDGES = [-0.95, -2.30, -3.70, -5.00, -6.20, -7.00, -9.0]            # mesh z, tail_1 .. tail_6

def assign_body(vox):
    c = to_mesh((vox + 0.5) * V)
    x, y, z = c[:, 0], c[:, 1], c[:, 2]
    side = np.where(x < 0, "left", "right")      # mesh x<0 is model x>0 (mirrored); names only need to be consistent
    bone = np.full(len(vox), "", dtype=object)
    head = (y > 4.62) & (z > 0.75)
    jaw = head & (y < 4.95) & (z > 1.25)
    arm = (~head) & (np.abs(x + 0.04) > 0.82) & (y > 2.45) & (y < 4.50) & (z > -0.3)
    leg = (~head) & (~arm) & (y < 2.35) & (z > -1.55) & ~((np.abs(x) < 0.35) & (z < -0.35))
    tail = (~head) & (~arm) & (~leg) & ((z < -0.95) | ((np.abs(x) < 0.35) & (z < -0.3) & (y < 2.6)))
    rest = ~(head | arm | leg | tail)
    bone[head] = "head"; bone[jaw] = "jaw"
    bone[arm] = np.char.add("arm_", side[arm]); bone[leg] = np.char.add("leg_", side[leg])
    bone[rest & (y < 3.0)] = "body"; bone[rest & (y >= 3.0)] = "torso"
    for k in range(6):
        m = tail & (z <= TAIL_EDGES[k] if k else tail) & (z > TAIL_EDGES[k + 1] if k < 5 else True)
        if k == 0: m = tail & (z > TAIL_EDGES[1])
        elif k == 5: m = tail & (z <= TAIL_EDGES[5])
        else: m = tail & (z <= TAIL_EDGES[k]) & (z > TAIL_EDGES[k + 1])
        bone[m] = f"tail_{k + 1}"
    return bone

body_bone = assign_body(body_vox)
# tail voxels beyond the last edge (safety)
body_bone[body_bone == ""] = "body"

# ------------------------------------------------------------------ dorsal plate clusters: 14 equal-length bins, tail tip -> upper back
NPL = 14
pc = to_mesh((plate_vox + 0.5) * V)
order = np.argsort(pc[:, 2])
zbins = np.linspace(pc[:, 2].min() - 1e-6, pc[:, 2].max() + 1e-6, 60)
path = []
for a, b in zip(zbins[:-1], zbins[1:]):
    m = (pc[:, 2] >= a) & (pc[:, 2] < b)
    if m.sum(): path.append((np.median(pc[m, 2]), np.median(pc[m, 1])))
path = np.array(path)
seglen = np.hypot(np.diff(path[:, 0]), np.diff(path[:, 1]))
cum = np.concatenate([[0], np.cumsum(seglen)])
pidx = np.interp(pc[:, 2], path[:, 0], cum)
plate_cluster = np.minimum(NPL - 1, (pidx / cum[-1] * NPL).astype(int))
print("plate voxels per cluster:", np.bincount(plate_cluster, minlength=NPL).tolist())

# ------------------------------------------------------------------ palette
def smooth_colors(vox, col, passes):
    """Blend each voxel colour with its 26 neighbours so palette regions become big flat patches (fewer cubes)."""
    from scipy.spatial import cKDTree
    t = cKDTree(vox)
    for _ in range(passes):
        nb = t.query_ball_point(vox, r=1.8)
        col = np.array([col[n].mean(0) for n in nb])
    return col
if SMOOTH:
    body_col = smooth_colors(body_vox, body_col, SMOOTH)
    plate_col = smooth_colors(plate_vox, plate_col, SMOOTH)
all_cols = np.vstack([body_col, plate_col] + detail_col) * 1.12
all_cols = np.clip(all_cols, 0, 1)
centroids, labels = kmeans2(all_cols.astype(np.float64), PALETTE_K, minit="++", seed=7)
centroids = np.clip(centroids, 0, 1)
nb, npv = len(body_vox), len(plate_vox)
body_pal = labels[:nb]; plate_pal = labels[nb:nb + npv]
off = nb + npv
detail_pal = []
for v_ in detail_vox:
    detail_pal.append(labels[off:off + len(v_)]); off += len(v_)

# ------------------------------------------------------------------ cubes, merged greedily per (bone, palette id)
bones, BONE, SHAPES, GLOW_SHAPES = [], {}, [], []
def add_bone(name, parent, pivot):
    b = {"name": name, "pivot": [round(float(p), 2) for p in pivot], "cubes": []}
    if parent: b["parent"] = parent
    bones.append(b); BONE[name] = b

REQ = {}                      # (kind, colour id, a, b) -> placeholder region; resolved after packing
def req(kind, cid, a, b):
    k = (kind, int(cid), int(a), int(b))
    REQ.setdefault(k, None)
    return k

def add_box(bone, vmin, n, cid, v=V, glow=False, inflate=0.0, vsize=None):
    """vmin: voxel index (3,), n: (nx,ny,nz) voxel counts. Face UVs are placeholders resolved after atlas packing."""
    vs = vsize or v
    x0, y0, z0 = vmin[0] * vs, vmin[1] * vs, vmin[2] * vs
    nx, ny, nz = n
    c = {"origin": [float(x0), float(y0), float(z0)], "size": [float(nx * vs), float(ny * vs), float(nz * vs)]}
    if inflate: c["inflate"] = inflate
    kind = "glow" if glow else "tile"
    dims = {"north": (nx, ny), "south": (nx, ny), "east": (nz, ny), "west": (nz, ny), "up": (nx, nz), "down": (nx, nz)}
    c["uv"] = {f: req(kind, cid, a, b) for f, (a, b) in dims.items()}
    BONE[bone]["cubes"].append(c)
    rec = (( x0 + nx * vs / 2, y0 + ny * vs / 2, z0 + nz * vs / 2), (nx * vs, ny * vs, nz * vs), int(cid) if not glow else -1, bone)
    (GLOW_SHAPES if glow else SHAPES).append(rec)

def greedy(vset):
    used, boxes_ = set(), []
    for v in sorted(vset):
        if v in used: continue
        x, y, z = v
        nx = 1
        while (x + nx, y, z) in vset and (x + nx, y, z) not in used: nx += 1
        nz = 1
        while all(((x + i, y, z + nz) in vset and (x + i, y, z + nz) not in used) for i in range(nx)): nz += 1
        ny = 1
        while all(((x + i, y + ny, z + k) in vset and (x + i, y + ny, z + k) not in used) for i in range(nx) for k in range(nz)): ny += 1
        for i in range(nx):
            for j in range(ny):
                for k in range(nz): used.add((x + i, y + j, z + k))
        boxes_.append(((x, y, z), (nx, ny, nz)))
    return boxes_

# bone pivots (mesh -> model)
def P(x, y, z):
    return tuple(to_model(np.array([[x, y, z]]))[0])
def tail_y(zm):
    m = (body_bone != "") & np.char.startswith(body_bone.astype(str), "tail")
    c = to_mesh((body_vox[m] + 0.5) * V)
    s = (np.abs(c[:, 2] - zm) < 0.25)
    return float(np.median(c[s, 1])) if s.sum() else 0.9

add_bone("root", None, (0, 0, 0))
add_bone("body", "root", P(0, 2.5, 0.0))
add_bone("torso", "body", P(0, 3.0, 0.2))
add_bone("head", "torso", P(0, 4.55, 0.85))
add_bone("jaw", "head", P(0, 4.9, 1.25))
add_bone("mouth_glow", "head", P(0, 4.93, 1.6))
for side, xm in (("left", -1), ("right", 1)):           # mesh x<0 -> model x>0 ; keep names consistent with assign_body
    mx = 0.95 * (1 if side == "left" else -1) * -1
    pass
add_bone("arm_left", "torso", P(-0.95, 4.15, 0.8))
add_bone("arm_right", "torso", P(0.95, 4.15, 0.8))
add_bone("leg_left", "root", P(-0.6, 2.5, 0.3))
add_bone("leg_right", "root", P(0.6, 2.5, 0.3))
for k in range(6):
    zs_ = TAIL_EDGES[k] if k else -0.95
    add_bone(f"tail_{k + 1}", "body" if k == 0 else f"tail_{k}", P(0, tail_y(zs_), zs_))

groups = {}
for i, (v, b, cid) in enumerate(zip(body_vox, body_bone, body_pal)):
    groups.setdefault((b, int(cid)), set()).add(tuple(v))
body_box_count = 0
for (b, cid), vs_ in groups.items():
    for vmin, n in greedy(vs_):
        add_box(b, vmin, n, cid); body_box_count += 1

# fine head details (teeth, eyes, gums): jaw below the mouth line, else head
dgroups = {}
for v_, p_ in zip(detail_vox, detail_pal):
    c = to_mesh((v_ + 0.5) * 4)
    for vv, cid, cc in zip(v_, p_, c):
        bn = "jaw" if (cc[1] < 4.95 and cc[2] > 1.25) else "head"
        dgroups.setdefault((bn, int(cid)), set()).add(tuple(vv))
for (b, cid), vs_ in dgroups.items():
    for vmin, n in greedy(vs_):
        add_box(b, vmin, n, cid, vsize=4)

# plates: main spikes (coloured) + glow clones (solid blue, inflated, merged without colour)
PLATE_FWD, PLATE_UP, PLATE_PIV = [], [], []
for i in range(NPL):
    m = plate_cluster == i
    vox_i = plate_vox[m]; pal_i = plate_pal[m]
    cen = to_mesh((vox_i + 0.5) * V)
    low = np.argsort(cen[:, 1])[: max(3, len(cen) // 8)]
    piv = to_model(np.array([[cen[low, 0].mean(), cen[low, 1].mean(), cen[low, 2].mean()]]))[0]
    PLATE_PIV.append(piv)
    cm = to_model(cen.mean(0)[None, :])[0]
    PLATE_FWD.append(-cm[2] / 16.0); PLATE_UP.append(cm[1] / 16.0)
    add_bone(f"plate_{i:02d}", "torso" if cen[:, 1].mean() > 3.0 else "body", piv)
    add_bone(f"plate_glow_{i:02d}", "torso" if cen[:, 1].mean() > 3.0 else "body", piv)
    pg = {}
    for v, cid in zip(vox_i, pal_i):
        pg.setdefault(int(cid), set()).add(tuple(v))
    for cid, vs_ in pg.items():
        for vmin, n in greedy(vs_):
            add_box(f"plate_{i:02d}", vmin, n, cid)
    allv = set(map(tuple, vox_i))
    for vmin, n in greedy(allv):
        add_box(f"plate_glow_{i:02d}", vmin, n, 0, glow=True, inflate=1.0)
_main = sum(len(b["cubes"]) for b in bones if not b["name"].startswith("plate"))
_pl = sum(len(b["cubes"]) for b in bones if b["name"].startswith("plate_") and "glow" not in b["name"])
_gl = sum(len(b["cubes"]) for b in bones if b["name"].startswith("plate_glow"))
print(f"cubes: body+head {_main} | plates {_pl} | glow clones {_gl} | total {_main + _pl + _gl}")

# ------------------------------------------------------------------ atlas packing
def pattern(a, b, T=4):
    w, h = T * a, T * b
    yy, xx = np.mgrid[0:h, 0:w]
    row = yy // 3
    offx = np.where(row % 2 == 1, 2, 0)
    col = (xx + offx) // 4; u = (xx + offx) % 4; v = yy % 3
    hsh = ((row * 73856093) ^ (col * 19349663)) % 1000 / 1000.0
    m = 0.90 + 0.18 * hsh
    m = np.where((u == 0) | (v == 2), m * 0.80, m)
    m = np.where((u <= 1) & (v == 0), m * 1.12, m)
    return m

keys = [k for k in REQ if k[0] == "tile"]
sizes = {k: (min(4 * k[2], 64), min(4 * k[3], 64)) for k in keys}
tot = sum(w * h for w, h in sizes.values())
ATLAS = 512
while ATLAS * ATLAS < tot * 1.6 + 4096 and ATLAS < 2048: ATLAS *= 2
print(f"atlas: {len(keys)} tiles, {tot} texels -> {ATLAS}x{ATLAS}")
atlas = Image.new("RGBA", (ATLAS, ATLAS), (20, 20, 22, 255))
glowmask = Image.new("RGBA", (ATLAS, ATLAS), (0, 0, 0, 0))
# reserved glow swatch at (0,0) 8x8
for i in range(8):
    for j in range(8):
        r = math.hypot(i - 3.5, j - 3.5) / 4.5
        t_ = max(0.0, 1.0 - r)
        col = (int(70 + 185 * t_ * t_), int(175 + 80 * t_), 255, 255)
        atlas.putpixel((i, j), col); glowmask.putpixel((i, j), col)
REGION = {}
for k in req.__defaults__ or []: pass
x, y, rowh = 0, 8, 0
for k in sorted(keys, key=lambda k: -sizes[k][1]):
    w, h = sizes[k]
    if x + w > ATLAS: x, y, rowh = 0, y + rowh, 0
    if y + h > ATLAS: raise SystemExit("atlas overflow")
    kind, cid, a, b = k
    pat = pattern(min(a, 16), min(b, 16))
    pat = np.array(Image.fromarray((pat * 255).astype(np.uint8)).resize((w, h), Image.NEAREST)) / 255.0
    rgb = np.clip(centroids[cid][None, None, :] * pat[..., None], 0, 1)
    atlas.paste(Image.fromarray((rgb * 255).astype(np.uint8)).convert("RGBA"), (x, y))
    REGION[k] = (x, y, w, h)
    x += w; rowh = max(rowh, h)
atlas.save(f"{RES}/textures/entity/godzilla_minus_one.png")
glowmask.save(f"{RES}/textures/entity/godzilla_minus_one_glowmask.png")
for b in bones:
    for c in b["cubes"]:
        for f, k in list(c["uv"].items()):
            if k[0] == "glow":
                c["uv"][f] = {"uv": [0, 0], "uv_size": [8, 8]}
            else:
                x_, y_, w_, h_ = REGION[k]
                c["uv"][f] = {"uv": [x_, y_], "uv_size": [w_, h_]}

geo = {"format_version": "1.12.0", "minecraft:geometry": [{
    "description": {"identifier": "geometry.godzilla_minus_one", "texture_width": ATLAS, "texture_height": ATLAS,
                    "visible_bounds_width": 140, "visible_bounds_height": 70, "visible_bounds_offset": [0, 14, 20]},
    "bones": [{k: v for k, v in b.items() if not (k == "cubes" and not v)} for b in bones]}]}
with open(f"{RES}/geo/godzilla_minus_one.geo.json", "w") as f:
    json.dump(geo, f, separators=(",", ":"))

# ------------------------------------------------------------------ PlateProfile.java (+ scaled mouth)
mouth = to_model(np.array([[0.0, 4.93, 2.0]]))[0]
MOUTH_FWD, MOUTH_UP = -mouth[2] / 16.0, mouth[1] / 16.0
# plate push direction is +Y in model space; PLATE_PUSH is in model units
def arr(v): return ", ".join(f"{x:.3f}" for x in v)
with open(f"{JAVA}/PlateProfile.java", "w") as f:
    f.write(f"""package com.example.godzilla.entity;

/**
 * GENERATED by tools/glb_to_mod.py. Dorsal plate cluster centres (blocks, relative to the feet) for particles, the mouth
 * position, and how far a plate pushes out when it extends. Index 0 = tail tip ... {NPL - 1} = upper back.
 */
public final class PlateProfile {{
    private PlateProfile() {{}}
    public static final double[] FORWARD = {{{arr(PLATE_FWD)}}};
    public static final double[] UP = {{{arr(PLATE_UP)}}};
    public static final double MOUTH_FORWARD = {MOUTH_FWD:.3f};
    public static final double MOUTH_UP = {MOUTH_UP:.3f};
    /** Model units (1/16 block) a plate slides out along its own up axis when extended. */
    public static final float PUSH_UNITS = 36.0F;
    public static final float PUSH_SCALE = 0.30F;
}}
""")
print(f"mouth: forward {MOUTH_FWD:.2f} blocks, up {MOUTH_UP:.2f} blocks | model height {BODY_H_MESH * S / 16:.2f} blocks")

# ------------------------------------------------------------------ preview render
PUSH_U, PUSH_S = 36.0, 0.30
if RENDER:
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Polygon
    FACES = [((0, 1, 3, 2), "w"), ((4, 6, 7, 5), "e"), ((0, 4, 5, 1), "d"), ((2, 3, 7, 6), "u"), ((0, 2, 6, 4), "n"), ((1, 5, 7, 3), "s")]
    GLOWC = np.array([0.35, 0.8, 1.0])

    def render(path, azim, elev, state=None, bg="#c9cbd0", figsize=(13, 8)):
        """state: None (resting) or f(plate_index) -> (extension 0..1, lit bool) for the charge / inhale / fire preview."""
        a, e = math.radians(azim), math.radians(elev)
        def view(p):
            x1 = p[0] * math.cos(a) + p[2] * math.sin(a); z1 = -p[0] * math.sin(a) + p[2] * math.cos(a)
            return np.array([-x1, p[1] * math.cos(e) + z1 * math.sin(e), z1 * math.cos(e) - p[1] * math.sin(e)])
        def xf(c, sz, bone):
            if state is None or not bone.startswith("plate"): return np.array(c), np.array(sz)
            ext, _ = state(int(bone[-2:]))
            piv = PLATE_PIV[int(bone[-2:])]; sc = 1.0 + ext * PUSH_S
            return piv + (np.array(c) - piv) * sc + np.array([0, ext * PUSH_U, 0]), np.array(sz) * sc
        shapes = []
        for c, sz, cid, bone in SHAPES:
            c2, s2 = xf(c, sz, bone); shapes.append((c2, s2, centroids[cid], False))
        if state is not None:
            for c, sz, _, bone in GLOW_SHAPES:
                ext, lit = state(int(bone[-2:]))
                if lit:
                    c2, s2 = xf(c, sz, bone); shapes.append((c2, s2 + 2.0, GLOWC, True))
        polys = []
        for (cx, cy, cz), (w, h, d), col, glowing in shapes:
            pts = [view(np.array([cx + sx * w / 2, cy + sy * h / 2, cz + sz * d / 2])) for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)]
            body_c = sum(pts) / 8.0
            for f, nm in FACES:
                q = [pts[i] for i in f]
                n = np.cross(q[1] - q[0], q[2] - q[0]); ln = np.linalg.norm(n)
                if ln < 1e-9: continue
                n = n / ln; cen = sum(q) / 4.0
                if np.dot(n, cen - body_c) < 0: n = -n
                if n[2] > 0.0 and not glowing: continue
                L = np.array([0.45, 0.75, -0.55]); L /= np.linalg.norm(L)
                shade = 0.45 + 0.7 * max(0.0, float(np.dot(n, L)))
                c = np.clip(col * (1.0 if glowing else shade * 1.5), 0, 1)
                polys.append((cen[2], [(p[0], p[1]) for p in q], c, glowing))
        polys.sort(key=lambda t: -t[0])
        fig, ax = plt.subplots(figsize=figsize)
        fig.patch.set_facecolor(bg); ax.set_facecolor(bg)
        for _, xy, c, em in polys:
            ax.add_patch(Polygon(xy, closed=True, fc=c, ec=(0, 0, 0, 0.22) if not em else (0.8, 0.95, 1, 0.9), lw=0.15))
        ax.set_aspect("equal"); ax.axis("off")
        allp = np.array([p for _, xy, _, _ in polys for p in xy])
        ax.set_xlim(allp[:, 0].min() - 20, allp[:, 0].max() + 20); ax.set_ylim(allp[:, 1].min() - 20, allp[:, 1].max() + 20)
        fig.savefig(path, dpi=100, bbox_inches="tight", facecolor=fig.get_facecolor()); plt.close(fig)

    N_ = NPL
    render(f"{ROOT}/tools/preview_3d.png", 38, 10)
    render(f"{ROOT}/tools/preview_3d_side.png", 90, 4)
    render(f"{ROOT}/tools/preview_3d_front.png", 0, 6, figsize=(8, 9))
    # heat-ray sequence: (1) charging: plates pushing out tail->head  (2) all out  (3) inhale done: plates back, still glowing (fire)
    seqs = {"1_charging": lambda i: ((1.0 if i < N_ * 0.45 else (0.5 if i < N_ * 0.6 else 0.0)), i < N_ * 0.6),
            "2_all_out": lambda i: (1.0, True),
            "3_inhale_fire": lambda i: (0.0, True)}
    for k, st in seqs.items():
        render(f"{ROOT}/tools/seq_{k}.png", 38, 8, state=st, bg="#14161c", figsize=(9, 7))
    ims = [Image.open(f"{ROOT}/tools/seq_{k}.png").convert("RGB") for k in seqs]
    h = max(im.height for im in ims)
    sheet = Image.new("RGB", (sum(im.width for im in ims), h), (20, 22, 28))
    xo = 0
    for im in ims:
        sheet.paste(im, (xo, (h - im.height) // 2)); xo += im.width
    sheet.save(f"{ROOT}/tools/preview_heatray_sequence.png")
    for k in seqs: os.remove(f"{ROOT}/tools/seq_{k}.png")
    print("renders written")

import trimesh, numpy as np
from PIL import Image

def load_scene(path='/tmp/glb_in/source/minus_one_godzilla.glb'):
    sc = trimesh.load(path, force='scene')
    meshes = []
    for node in sc.graph.nodes_geometry:
        T, gname = sc.graph[node]
        g = sc.geometry[gname].copy()
        g.apply_transform(T)
        meshes.append((gname, g))
    return meshes

def vertex_colors(g):
    """Per-vertex RGB (0..1) sampled from the base-colour texture."""
    vis = g.visual
    mat = vis.material
    img = getattr(mat, 'baseColorTexture', None) or getattr(mat, 'image', None)
    uv = vis.uv
    if img is None or uv is None:
        return np.full((len(g.vertices), 3), 0.4)
    a = np.asarray(img.convert('RGB'), dtype=np.float32) / 255.0
    h, w, _ = a.shape
    u = np.mod(uv[:, 0], 1.0); v = np.mod(1.0 - uv[:, 1], 1.0)
    return a[(v * (h - 1)).astype(int), (u * (w - 1)).astype(int)]

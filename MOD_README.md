# Godzilla Minus One - boss mob (NeoForge 1.21.1, no other mods needed)

Entity ID `godzilla_mod:godzilla_minus_one`  ->  `/summon godzilla_mod:godzilla_minus_one`, or the spawn egg in the creative tab.
Build/install steps are in `BUILD_JAR.md`. **Never compiled or run by me**: expect a few small API fixes on the first build.

## What changed in this version
- **Smooth model.** No more cubes. The original GLB is rigged (24 bones, smooth skin weights) and drawn as one skinned mesh with
  its own textures (`client/mesh/GodzillaMesh`, `GodzillaRenderer`). Joints bend with no gaps; nothing separates when he moves or attacks.
- **Film-style walk** (`entity/GodzillaAnim.walk`): slow and heavy; hips shift over the planted foot, the body rocks side to side,
  shoulders counter-twist, head stays level, the 8-segment tail whips the opposite way in a travelling wave. The walk phase is synced
  from the server, so each footfall (sound, shake, dust, damage) lands exactly when a foot hits the ground.
- **All effects are custom** (no vanilla particles). Plate flames, arcs, charge orb, inhale streaks, the ray, the blast, stomp dust, radiation
  patches are drawn by our own renderers (`client/fx/*`) using generated textures (`textures/fx`). They are pure functions of time, so
  the server only spawns an invisible marker entity (`FxEntity`) and the client draws it.
- **New roars** (three variants, picked at random): `tools/gen_roar.py`. Original synthesis (not the Toho recording).

## Atomic breath (like your reference video)
1. **Roar.**  2. **Charge**: plates push out tail->head, each wrapped in jagged cyan energy flames + crackling arcs; a plasma orb grows at the mouth.
3. **Inhale**: air streaks pour into the mouth, plates snap back, body locks.  4. **Fire** (locked, no tracking): white-hot core in cyan plasma with twisting
streaks, a blinding flare at the mouth and impact, shock rings and molten spray where it hits the ground; terrain is carved, craters + radiation patches.
5. **Blast**: white screen flash, boiling fireball, expanding ground shockwave with dust wall, rolling mushroom cloud, flying debris and embers, thunder;
30 block crater, 95 block shockwave damage, trees/wood/glass stripped to 75 blocks (obeys `/gamerule mobGriefing`).

## Other attacks
Jaw bite, tail swipe (whole 34 block arc), **stomp** (foot slam with custom shockwave), stomping footsteps (12 dmg, camera shake). All in `GodzillaTuning.java`.

## Tools (all optional; outputs are already committed in `src/main/resources`)
- `python3 tools/glb_to_mesh.py [--preview]` - rig + export the mesh (put the GLB in `tools/glb/`). Needs numpy pillow scipy trimesh matplotlib.
- `python3 tools/gen_fx_textures.py` - effect textures.   `python3 tools/gen_roar.py [--spec]` - roars (needs numpy scipy soundfile).

## Where a first build is most likely to need fixes
Rendering API names in `client/` (Camera vectors, VertexConsumer methods, RenderType factories) and the event classes in `ScreenFlash`/`CameraShake`.
Visual tuning (flame size, beam width, blast scale, walk amplitudes) is all constants at the top of each effect function.
If a bone bends the wrong way, flip the sign in `GodzillaAnim` for that bone.

## Licence / credits
See `CREDITS.md`: the model is derived from a third-party Sketchfab model ("Sketchfab Standard" licence). Keep repo + jar private.

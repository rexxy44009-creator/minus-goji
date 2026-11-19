# Godzilla Minus One - boss mob (NeoForge 1.21.1 + GeckoLib 4)

Entity ID `godzilla_mod:godzilla_minus_one`  ->  `/summon godzilla_mod:godzilla_minus_one`, or the spawn egg in the creative tab.
Build/install steps are in `BUILD_JAR.md`. **Never compiled by me**: expect a few small GeckoLib/API fixes on the first build (section 7).

## 1. Size
Modelled and sized at **94 ft = 28.65 blocks tall** (head height, spikes add ~1.5 more) with a ~40 block tail. Hitbox 9 x 28.7.
All ranges, damage radii, craters and the beam are scaled to match (`GodzillaTuning.java`).

## 2. Attacks
| Attack | What happens |
|---|---|
| Walking | Slow, 1 foot plant per second: custom sound, camera shake, dust, 12 dmg + hop to anything under the foot. Blocks inside and just ahead of the body are flattened (never the ground, never bedrock, never fluids). |
| Jaw bite | Target in front. 60 dmg, launched skyward. |
| Tail swipe | Target behind/flank. The whole ~34 block tail arc, 55 dmg, launched. |
| **Stomp** | Target close in front (about half the time). He lifts the right foot and slams it down 8 blocks ahead: 5-power crater, dust rings, 14 block shockwave, 50 dmg + throw, big screen shake. |
| **Atomic breath** | Target 18-64 blocks away. Roar -> **charge** -> **inhale** -> **fire (locked)** -> vent. See below. |

### Atomic breath, stage by stage
1. **Roar** (3 s): turns to face you; the roar is `sounds/roar.ogg`.
2. **Charge** (4 s): the 14 dorsal plate groups **push out one by one starting at the tail**, each lighting blue with sparks and a chime.
3. **Inhale** (1.5 s): head rears back, chest arches, jaw opens, air streams into the mouth, and **all plates snap back to their resting place** (tail first).
4. **Fire** (5 s): **fully locked**: body, head and ray stay exactly where they were when the inhale began; nothing the target does changes it. (`BEAM_SWEEP_DEGREES` in `GodzillaTuning` can add a fixed sweep; 0 = locked.) The ray carves a 4.5-block-radius tunnel, burns and damages everything in it, drops craters and radiation clouds (Wither + Slowness).
5. **Blast + mushroom cloud**: 6 ticks before the ray ends, it erupts where the ray meets the ground:
   white flash, vanilla explosion (power 14), a **30 block radius crater** eaten out over ~10 ticks, a ground shockwave out to **95 blocks** (damage + throw), which also **strips trees, wood, glass, wool, leaves** out to 75 blocks, then a fire-and-smoke stem rising **~135 blocks** capped by a rolling **58 block radius** mushroom cap, with rumbling thunder. All block damage obeys `/gamerule mobGriefing`. Lower `BLAST_BLOCKS_PER_TICK` if a server stutters.
6. **Vent** (2 s): steam, plates cool tail -> head.

## 3. Model
- Built from the supplied `minus_one_godzilla.glb`: sampled with its own texture, voxelised at half-block size, split into parts (head, jaw, torso, body, 2 arms, 2 legs, 6 tail segments, 14 dorsal plate groups) and merged into ~7,000 cubes with a 28-colour palette taken from the original texture. Regenerate with `python3 tools/glb_to_mod.py --render` (put the .glb in `tools/glb/`; needs numpy, pillow, scipy, trimesh, matplotlib). `PALETTE_K=40` gives more colour detail at more cubes.
- Animations: `python3 tools/gen_animations.py` (idle, walk, roar, charge, inhale, fire, vent, bite, tail_swipe, stomp, death). Plates are animated in code (`GodzillaModel`, `PlateProfile.PUSH_UNITS`).
- If something is tilted the wrong way in game, tell me which bone; rotation signs in the animation file are the likely cause.

## 4. Sound
- `roar.ogg` is an **original synthesised roar** made by `tools/gen_roar.py` (rising screech, long falling growl, bass thump, cave reverb). It is *not* the Toho recording, which is copyrighted. Replace it with any .ogg you have rights to, same file name.
- Other sounds use pitched vanilla sounds as placeholders (`sounds.json`).

## 5. Tuning
Everything is in `GodzillaTuning.java`: health, damage, ranges, stage durations, beam size, blast size, block budgets.

## 6. Licence / credits
See `CREDITS.md`. The model data is derived from a third-party Sketchfab model whose file says "Sketchfab Standard" licence; keep the repo and the jar **private** unless the creator allows redistribution.

## 7. Where a first build is most likely to need fixes
- GeckoLib 4.8.x: `GodzillaModel` (`setCustomAnimations`, `getAnimationProcessor().getBone(..)`, `setPosY / setScaleX/Y/Z / setHidden`, `DataTickets.ENTITY_MODEL_DATA`) and `GodzillaRenderer#getDeathMaxRotation`.
- `MobEffects.MOVEMENT_SLOWDOWN` is the 1.21.1 name. Beam rendering uses `RenderType.lightning()` and `addVertex(...).setColor(...)`.

#!/usr/bin/env python3
"""Writes assets/godzilla_mod/animations/godzilla_minus_one.animation.json. Durations match GodzillaTuning (20 ticks = 1 s).
Rotation signs follow the Bedrock convention: +X pitch = front tips down (head/jaw open down). Positions are 1/16 block."""
import json, os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = f"{ROOT}/src/main/resources/assets/godzilla_mod/animations/godzilla_minus_one.animation.json"
SP = 1.4   # position scale (the model is ~1.4x bigger than the first version)
Z = [0, 0, 0]
def t(x): return str(float(x))
class Anim:
    def __init__(self, name, length, loop): self.name, self.length, self.loop, self.b = name, length, loop, {}
    def rot(self, bone, keys): self.b.setdefault(bone, {})["rotation"] = {t(k): v for k, v in keys}; return self
    def pos(self, bone, keys): self.b.setdefault(bone, {})["position"] = {t(k): [v[0] * SP, v[1] * SP, v[2] * SP] for k, v in keys}; return self
    def out(self): return {f"animation.godzilla_minus_one.{self.name}": {"loop": self.loop, "animation_length": self.length, "bones": self.b}}
def shake(start, end, step, a0, a1, axis=2):
    keys, k, i = [], start, 0
    while k <= end + 1e-6:
        a = a0 + (a1 - a0) * ((k - start) / max(1e-6, end - start)); v = [0, 0, 0]; v[axis] = a if i % 2 == 0 else -a
        keys.append((round(k, 3), v)); k += step; i += 1
    return keys
A = []

a = Anim("idle", 4.0, True)
a.rot("body", [(0, Z), (2, [1.0, 0, 0]), (4, Z)])
a.rot("torso", [(0, Z), (2, [-1.5, 0, 0]), (4, Z)])
a.rot("head", [(0, Z), (1, [0, 4, 0]), (2, Z), (3, [0, -4, 0]), (4, Z)])
a.rot("jaw", [(0, Z), (2, [3, 0, 0]), (4, Z)])
for i, amp in enumerate((2, 3, 4, 5, 6, 7), 1):
    s = 1 if i % 2 else -1
    a.rot(f"tail_{i}", [(0, Z), (1, [0, s * amp, 0]), (2, Z), (3, [0, -s * amp, 0]), (4, Z)])
a.rot("arm_left", [(0, Z), (2, [4, 0, 0]), (4, Z)]); a.rot("arm_right", [(0, Z), (2, [-4, 0, 0]), (4, Z)])
A.append(a)

a = Anim("walk", 2.0, True)     # a foot plants every 1.0 s (STEP_INTERVAL_TICKS = 20)
a.rot("leg_left", [(0, [-20, 0, 0]), (0.5, Z), (1, [20, 0, 0]), (1.5, Z), (2, [-20, 0, 0])])
a.rot("leg_right", [(0, [20, 0, 0]), (0.5, Z), (1, [-20, 0, 0]), (1.5, Z), (2, [20, 0, 0])])
a.pos("leg_left", [(0, Z), (1, Z), (1.5, [0, 8, 0]), (2, Z)]); a.pos("leg_right", [(0, Z), (0.5, [0, 8, 0]), (1, Z), (2, Z)])
a.pos("body", [(0, Z), (0.5, [0, -3, 0]), (1, Z), (1.5, [0, -3, 0]), (2, Z)])
a.rot("body", [(0, [0, 0, 1.5]), (1, [0, 0, -1.5]), (2, [0, 0, 1.5])])
a.rot("torso", [(0, [0, 3, 0]), (1, [0, -3, 0]), (2, [0, 3, 0])])
a.rot("head", [(0, [0, -3, 0]), (1, [0, 3, 0]), (2, [0, -3, 0])])
for i, amp in enumerate((4, 6, 8, 10, 12, 14), 1):
    s = -1 if i % 2 else 1
    a.rot(f"tail_{i}", [(0, [0, s * amp, 0]), (1, [0, -s * amp, 0]), (2, [0, s * amp, 0])])
a.rot("arm_left", [(0, [12, 0, 0]), (1, [-12, 0, 0]), (2, [12, 0, 0])]); a.rot("arm_right", [(0, [-12, 0, 0]), (1, [12, 0, 0]), (2, [-12, 0, 0])])
A.append(a)

a = Anim("roar", 2.5, False)    # ROAR_TICKS = 50
a.rot("torso", [(0, Z), (0.6, [-10, 0, 0]), (2.0, [-10, 0, 0]), (2.5, Z)])
a.rot("head", [(0, Z), (0.6, [-28, 0, 0]), (2.0, [-28, 0, 0]), (2.5, Z)])
a.rot("jaw", [(0, Z), (0.7, [42, 0, 0]), (2.0, [42, 0, 0]), (2.4, Z)])
a.rot("arm_left", [(0, Z), (0.6, [-25, 0, 20]), (2.0, [-25, 0, 20]), (2.5, Z)])
a.rot("arm_right", [(0, Z), (0.6, [-25, 0, -20]), (2.0, [-25, 0, -20]), (2.5, Z)])
a.rot("tail_1", [(0, Z), (0.6, [-4, 0, 0]), (2.0, [-4, 0, 0]), (2.5, Z)])
a.rot("body", [(0, Z)] + shake(0.7, 2.0, 0.1, 0.8, 0.8) + [(2.5, Z)])
A.append(a)

a = Anim("charge", 4.0, "hold_on_last_frame")   # CHARGE_TICKS = 80 (the plates extend one by one in code)
a.rot("torso", [(0, Z), (3.0, [-9, 0, 0]), (4.0, [-9, 0, 0])])
a.rot("head", [(0, Z), (3.0, [-22, 0, 0]), (4.0, [-22, 0, 0])])
a.rot("jaw", [(0, Z), (2.5, Z), (3.5, [28, 0, 0]), (4.0, [28, 0, 0])])
a.rot("arm_left", [(0, Z), (3.0, [-12, 0, 10]), (4.0, [-12, 0, 10])]); a.rot("arm_right", [(0, Z), (3.0, [-12, 0, -10]), (4.0, [-12, 0, -10])])
a.rot("body", [(0, Z)] + shake(0.5, 4.0, 0.125, 0.3, 1.2))
A.append(a)

a = Anim("inhale", 1.5, "hold_on_last_frame")   # INHALE_TICKS = 30: chest swells (torso arches back), head rears, jaw opens
a.rot("torso", [(0, [-9, 0, 0]), (1.2, [-20, 0, 0]), (1.5, [-20, 0, 0])])
a.rot("head", [(0, [-22, 0, 0]), (1.2, [-34, 0, 0]), (1.5, [-34, 0, 0])])
a.rot("jaw", [(0, [28, 0, 0]), (1.2, [36, 0, 0]), (1.5, [36, 0, 0])])
a.rot("arm_left", [(0, [-12, 0, 10]), (1.2, [-22, 0, 18]), (1.5, [-22, 0, 18])]); a.rot("arm_right", [(0, [-12, 0, -10]), (1.2, [-22, 0, -18]), (1.5, [-22, 0, -18])])
a.rot("body", [(0, Z), (1.2, [-3, 0, 0]), (1.5, [-3, 0, 0])])
A.append(a)

a = Anim("fire", 1.0, True)     # locked, braced stance
a.rot("torso", [(0, [-14, 0, 0]), (1, [-14, 0, 0])])
a.rot("head", [(0, [-12, 0, 0]), (1, [-12, 0, 0])])
a.rot("jaw", [(0, [50, 0, 0]), (0.5, [52, 0, 0]), (1, [50, 0, 0])])
a.rot("arm_left", [(0, [-14, 0, 16]), (1, [-14, 0, 16])]); a.rot("arm_right", [(0, [-14, 0, -16]), (1, [-14, 0, -16])])
a.rot("body", [(0, [-3, 0, 0.4]), (0.25, [-3, 0, -0.4]), (0.5, [-3, 0, 0.4]), (0.75, [-3, 0, -0.4]), (1, [-3, 0, 0.4])])
A.append(a)

a = Anim("vent", 2.0, "hold_on_last_frame")     # VENT_TICKS = 40
a.rot("torso", [(0, [-14, 0, 0]), (1.5, Z), (2, Z)]); a.rot("head", [(0, [-12, 0, 0]), (1.5, Z), (2, Z)])
a.rot("jaw", [(0, [50, 0, 0]), (1.2, Z), (2, Z)]); a.rot("body", [(0, [-3, 0, 0]), (1.5, Z), (2, Z)])
A.append(a)

a = Anim("bite", 1.5, "hold_on_last_frame")     # 30 ticks; jaws snap at 0.7 s = tick 14
a.rot("torso", [(0, Z), (0.6, [-9, 0, 0]), (0.7, [18, 0, 0]), (0.9, [18, 0, 0]), (1.5, Z)])
a.rot("head", [(0, Z), (0.6, [-14, 0, 0]), (0.7, [20, 0, 0]), (0.9, [20, 0, 0]), (1.5, Z)])
a.rot("jaw", [(0, Z), (0.55, [48, 0, 0]), (0.7, Z), (1.5, Z)])
a.pos("body", [(0, Z), (0.6, [0, 0, 4]), (0.7, [0, 0, -16]), (0.9, [0, 0, -16]), (1.5, Z)])
a.rot("arm_left", [(0, Z), (0.6, [-15, 0, 0]), (0.7, [20, 0, 0]), (1.5, Z)]); a.rot("arm_right", [(0, Z), (0.6, [-15, 0, 0]), (0.7, [20, 0, 0]), (1.5, Z)])
A.append(a)

a = Anim("tail_swipe", 2.2, "hold_on_last_frame")   # 44 ticks; tail connects at 1.1 s = tick 22
a.rot("body", [(0, Z), (1.0, [0, -40, 0]), (1.1, [0, -40, 0]), (1.35, [0, 60, 0]), (1.6, [0, 60, 0]), (2.2, Z)])
for i, (w0, w1) in enumerate(((20, -35), (25, -45), (25, -55), (25, -60), (20, -60), (15, -55)), 1):
    a.rot(f"tail_{i}", [(0, Z), (1.0, [0, w0, 0]), (1.1, [0, w0, 0]), (1.35, [0, w1, 0]), (1.6, [0, w1, 0]), (2.2, Z)])
a.rot("torso", [(0, Z), (1.0, [3, 0, 0]), (2.2, Z)]); a.rot("head", [(0, Z), (1.0, [0, -20, 0]), (1.35, [0, 30, 0]), (2.2, Z)])
A.append(a)

a = Anim("stomp", 1.4, "hold_on_last_frame")    # STOMP_ATTACK_TICKS = 28; foot lands at 0.7 s = tick 14
a.rot("leg_right", [(0, Z), (0.45, [-45, 0, 0]), (0.65, [-50, 0, 0]), (0.72, [-2, 0, 0]), (1.0, Z), (1.4, Z)])
a.pos("leg_right", [(0, Z), (0.45, [0, 34, 0]), (0.65, [0, 38, 0]), (0.72, [0, -3, 0]), (1.0, Z), (1.4, Z)])
a.rot("leg_left", [(0, Z), (0.45, [8, 0, 0]), (0.72, [4, 0, 0]), (1.4, Z)])
a.rot("torso", [(0, Z), (0.45, [-8, 0, 0]), (0.7, [12, 0, 0]), (1.0, [6, 0, 0]), (1.4, Z)])
a.rot("head", [(0, Z), (0.45, [-10, 0, 0]), (0.7, [14, 0, 0]), (1.4, Z)])
a.pos("body", [(0, Z), (0.45, [0, 6, 0]), (0.72, [0, -8, 0]), (1.0, [0, -2, 0]), (1.4, Z)])
a.rot("arm_left", [(0, Z), (0.45, [-22, 0, 8]), (0.72, [10, 0, 4]), (1.4, Z)]); a.rot("arm_right", [(0, Z), (0.45, [-22, 0, -8]), (0.72, [10, 0, -4]), (1.4, Z)])
a.rot("tail_1", [(0, Z), (0.45, [-5, 0, 0]), (0.72, [4, 0, 0]), (1.4, Z)])
A.append(a)

a = Anim("death", 5.0, "hold_on_last_frame")    # DEATH_TICKS = 100
a.rot("root", [(0, Z), (1.5, [0, 0, -6]), (3.0, [0, 0, -92]), (3.3, [0, 0, -86]), (3.6, [0, 0, -90]), (5, [0, 0, -90])])
a.rot("head", [(0, Z), (1.0, [-30, 0, 0]), (3.0, [10, 0, 0]), (5, [10, 0, 0])]); a.rot("jaw", [(0, Z), (1, [40, 0, 0]), (5, [40, 0, 0])])
a.rot("arm_left", [(0, Z), (2, [-20, 0, 15]), (5, [-20, 0, 15])]); a.rot("arm_right", [(0, Z), (2, [-20, 0, -15]), (5, [-20, 0, -15])])
A.append(a)

merged = {}
for x in A: merged.update(x.out())
json.dump({"format_version": "1.8.0", "animations": merged}, open(OUT, "w"), indent=1)
print("animations:", len(merged))

#!/usr/bin/env python3
"""
Synthesises an ORIGINAL monster roar in the classic style (rising screech -> long descending growl, raspy, harmonically
rich, sub-bass thump, cave reverb) and writes assets/godzilla_mod/sounds/roar.ogg.
This is NOT the Toho recording (that is copyrighted). Swap in any .ogg you have the rights to; same file name.
Needs: numpy scipy soundfile.
"""
import os
import numpy as np
import soundfile as sf
from scipy import signal

SR = 44100
T = 4.4
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = f"{ROOT}/src/main/resources/assets/godzilla_mod/sounds/roar.ogg"
rng = np.random.default_rng(1954)
n = int(SR * T)
t = np.arange(n) / SR

# ---- pitch contour: growl up into a screech, hold with wobble, long fall into a bass growl
def contour(t):
    f = np.empty_like(t)
    for i, x in enumerate(t):
        if x < 0.5:   f[i] = 90 + (340 - 90) * (x / 0.5) ** 1.6
        elif x < 1.7: f[i] = 340 - 30 * (x - 0.5) / 1.2
        else:         f[i] = 310 * np.exp(-(x - 1.7) / 1.25) + 62
    return f
f0 = contour(t)
f0 *= 1.0 + 0.035 * np.sin(2 * np.pi * 5.8 * t) + 0.02 * np.sin(2 * np.pi * 11.3 * t + 1.0)
jit = np.cumsum(rng.normal(0, 1, n)); jit = signal.lfilter(*signal.butter(2, 18 / (SR / 2)), jit)
f0 *= 1.0 + 0.012 * jit / (np.std(jit) + 1e-9)
phase = 2 * np.pi * np.cumsum(f0) / SR

# ---- harmonic-rich source (band-limited sawtooth-ish) + subharmonic
src = np.zeros(n)
for k in range(1, 70):
    amp = 1.0 / k ** 0.75
    mask = (f0 * k) < (SR / 2 - 1000)
    src += np.where(mask, amp * np.sin(k * phase + 0.3 * k), 0.0)
src += 0.55 * np.sin(0.5 * phase)                     # subharmonic gives the "rumble"
src /= np.max(np.abs(src))

# ---- roughness: fast amplitude modulation (the growl)
am = (1 - 0.5 * (0.5 + 0.5 * np.sin(2 * np.pi * 36 * t))) * (1 - 0.3 * (0.5 + 0.5 * np.sin(2 * np.pi * 17 * t + 0.7)))
src *= am

# ---- formants (vocal-tract-like resonances) that drift a little
def band(x, fc, q):
    b, a = signal.iirpeak(fc / (SR / 2), q)
    return signal.lfilter(b, a, x)
body = 0.45 * src + 0.9 * band(src, 620, 5) + 1.0 * band(src, 1500, 6) + 0.7 * band(src, 2900, 8) + 0.4 * band(src, 480, 4)

# ---- breathy noise layer (follows the loudness)
noise = rng.normal(0, 1, n)
bn, an = signal.butter(3, [900 / (SR / 2), 7000 / (SR / 2)], btype="band")
noise = signal.lfilter(bn, an, noise)

# ---- loudness envelope
env = np.minimum(1.0, t / 0.09) * np.where(t < 2.9, 1.0, np.exp(-(t - 2.9) / 0.55))
env *= 0.8 + 0.2 * np.minimum(1.0, t / 0.6)
y = env * (body / np.max(np.abs(body)) + 0.22 * noise / np.max(np.abs(noise)))

# ---- sub-bass thump at the start, then saturation
y += 0.9 * np.sin(2 * np.pi * 42 * t) * np.exp(-t / 0.35) * np.minimum(1.0, t / 0.01)
y = np.tanh(2.6 * y)

# ---- cave reverb
L = int(SR * 1.3)
ir = rng.normal(0, 1, L) * np.exp(-np.arange(L) / (SR * 0.32))
ir[0] = 1.0
wet = signal.fftconvolve(y, ir)[:n]
y = 0.78 * y + 0.22 * wet / (np.max(np.abs(wet)) + 1e-9) * np.max(np.abs(y))

# ---- gentle low-cut, normalise, fade out
hb, ha = signal.butter(2, 28 / (SR / 2), btype="high")
y = signal.lfilter(hb, ha, y)
y *= np.minimum(1.0, (T - t) / 0.25)
y = 0.89 * y / np.max(np.abs(y))

os.makedirs(os.path.dirname(OUT), exist_ok=True)
sf.write(OUT, y.astype(np.float32), SR, format="OGG", subtype="VORBIS")
print(f"wrote {OUT}  ({T}s, mono, {os.path.getsize(OUT) // 1024} KB, peak {np.max(np.abs(y)):.2f}, rms {np.sqrt(np.mean(y ** 2)):.3f})")

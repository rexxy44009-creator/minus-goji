#!/usr/bin/env python3
"""
Synthesises ORIGINAL monster roars (not the Toho recording, which is copyrighted) and writes three variants:
  assets/godzilla_mod/sounds/roar_1.ogg   bowed-string stick-slip growl (the technique the 1954 roar was famous for: rosined glove on a
                                          double-bass string, slowed down) with a rising scream and a long falling growl
  assets/godzilla_mod/sounds/roar_2.ogg   vocal-tract growl: jittery glottal pulses, period doubling, moving formants, breath
  assets/godzilla_mod/sounds/roar_3.ogg   a blend of both with extra rumble
sounds.json plays one at random each time. Delete the ones you dislike (and their sounds.json entries) or replace with your own.
Needs: numpy scipy soundfile.   Pass --spec to also write tools/roar_spectrograms.png
"""
import os, sys
import numpy as np
import soundfile as sf
from scipy import signal

SR = 44100
T = 4.8
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = f"{ROOT}/src/main/resources/assets/godzilla_mod/sounds"
os.makedirs(OUT, exist_ok=True)
n = int(SR * T)
t = np.arange(n) / SR

def smooth_noise(rate_hz, seed, length=n):
    r = np.random.default_rng(seed)
    k = int(length / SR * rate_hz) + 4
    pts = r.normal(0, 1, k)
    return np.interp(np.arange(length) / SR * rate_hz, np.arange(k), pts)

def lp(x, fc, order=2): return signal.lfilter(*signal.butter(order, fc / (SR / 2)), x)
def hp(x, fc, order=2): return signal.lfilter(*signal.butter(order, fc / (SR / 2), btype="high"), x)

# ---- the shape of the roar over time
def pitch_contour(base=1.0):
    """fundamental in Hz: low rumble -> fast rise into a scream -> hold with quiver -> long fall into a bass growl"""
    pts_t = [0.0, 0.25, 0.75, 1.5, 2.2, 3.2, 4.2, 4.8]
    pts_f = [46, 70, 150, 165, 125, 80, 52, 44]
    f = np.interp(t, pts_t, pts_f) * base
    f *= 1 + 0.03 * np.sin(2 * np.pi * 5.5 * t) * np.interp(t, [0, 0.8, 1.6, 4.8], [0, 0.2, 1, 0.5])
    f *= 1 + 0.035 * smooth_noise(14, 1)
    return f

def loudness():
    env = np.interp(t, [0, 0.08, 0.45, 1.6, 2.8, 3.8, 4.8], [0, 0.55, 1.0, 0.95, 0.8, 0.35, 0.0])
    return env * (1 + 0.08 * smooth_noise(9, 2))

# ---- variant 1: bowed-string stick-slip
def bowed_string():
    f0 = pitch_contour(1.0)
    vb = 0.55 * loudness() * (1 + 0.5 * smooth_noise(22, 3))              # bow speed (glove dragging, irregular)
    press = 3.0 + 4.0 * np.interp(t, [0, 0.5, 1.4, 4.8], [0.4, 1.0, 1.0, 0.6]) + 1.2 * smooth_noise(11, 4)
    L = int(SR / 28) + 16
    buf = np.zeros(L)
    out = np.zeros(n)
    pos, lpv = 0, 0.0
    for i in range(n):
        D = SR / f0[i]
        rp = pos - D
        i0 = int(np.floor(rp)); fr = rp - i0
        s = buf[i0 % L] * (1 - fr) + buf[(i0 + 1) % L] * fr
        lpv = 0.62 * s + 0.38 * lpv                                          # string/body damping (brightness control)
        vrel = vb[i] - lpv
        mu = np.sign(vrel) * (0.35 + 0.65 * np.exp(-abs(vrel) * press[i])) # stick-slip friction curve
        new = -0.994 * lpv + 0.30 * mu * min(1.0, vb[i] * 3.0)
        new = np.tanh(1.6 * new)                                             # string/bridge nonlinearity
        buf[pos % L] = new
        out[i] = new
        pos += 1
    out = hp(out, 30)
    return out / np.max(np.abs(out))

# ---- variant 2: vocal-tract growl
def vocal():
    f0 = pitch_contour(1.0)
    jit = 1 + 0.06 * smooth_noise(45, 5) + 0.02 * np.random.default_rng(6).normal(0, 1, n)   # rough, irregular pulses
    ph = np.cumsum(f0 * jit) / SR
    frac = ph % 1.0
    pulse = np.where(frac < 0.4, np.sin(np.pi * frac / 0.4) ** 2, 0.0) - 0.4 * np.where(frac >= 0.4, np.sin(np.pi * (frac - 0.4) / 0.6) ** 2, 0.0)
    cycle = np.floor(ph).astype(int)
    dbl = np.interp(t, [0, 0.4, 1.0, 2.2, 4.8], [0.1, 0.5, 0.3, 0.6, 0.9])   # period doubling grows in the low growl
    pulse *= 1 + dbl * np.where(cycle % 2 == 0, 0.5, -0.5)
    pulse *= 1 + 0.25 * np.random.default_rng(7).normal(0, 1, n) * 0.4        # shimmer
    breath = lp(hp(np.random.default_rng(8).normal(0, 1, n), 700), 6500) * 0.35
    src = pulse + breath * np.interp(t, [0, 0.5, 3.0, 4.8], [0.3, 0.7, 1.0, 1.2])
    # moving formants (vowel "ah" -> "oh" -> "uh"), filtered in short blocks with carried state
    F = {"f1": np.interp(t, [0, 0.7, 1.6, 3.0, 4.8], [450, 780, 700, 520, 420]),
         "f2": np.interp(t, [0, 0.7, 1.6, 3.0, 4.8], [900, 1350, 1200, 950, 800]),
         "f3": np.interp(t, [0, 4.8], [2500, 2200])}
    y = np.zeros(n); B = 256
    zi = {k: None for k in ("f1", "f2", "f3")}
    parts = np.zeros(n)
    for name, q, gain in (("f1", 4.5, 1.0), ("f2", 6.0, 0.8), ("f3", 8.0, 0.45)):
        acc = np.zeros(n); z = None
        for s0 in range(0, n, B):
            fc = float(np.clip(F[name][min(s0, n - 1)], 150, 8000))
            b, a = signal.iirpeak(fc / (SR / 2), q)
            if z is None: z = signal.lfilter_zi(b, a) * 0
            seg, z = signal.lfilter(b, a, src[s0:s0 + B], zi=z)
            acc[s0:s0 + B] = seg
        parts += gain * acc
    y = 0.35 * src + parts
    return y / np.max(np.abs(y))

def finish(y, name, rumble=0.0, seed=0):
    r = np.random.default_rng(seed)
    y = y * loudness()
    # sub-bass body + thump
    sub = np.sin(2 * np.pi * np.cumsum(np.interp(t, [0, 1.0, 4.8], [38, 48, 34])) / SR) * loudness()
    y = y + (0.35 + rumble) * sub + 0.9 * np.sin(2 * np.pi * 40 * t) * np.exp(-t / 0.28) * np.minimum(1, t / 0.008)
    y = np.tanh(2.4 * y / np.max(np.abs(y)))
    # big cave reverb
    L = int(SR * 1.6)
    ir = r.normal(0, 1, L) * np.exp(-np.arange(L) / (SR * 0.42)); ir = lp(ir, 5000); ir[0] = 1.0
    wet = signal.fftconvolve(y, ir)[:n]
    y = 0.74 * y + 0.26 * wet / (np.max(np.abs(wet)) + 1e-9) * np.max(np.abs(y))
    y = hp(y, 26)
    y *= np.minimum(1.0, (T - t) / 0.35)
    y = 0.9 * y / np.max(np.abs(y))
    sf.write(f"{OUT}/{name}.ogg", y.astype(np.float32), SR, format="OGG", subtype="VORBIS")
    return y

a = bowed_string(); b = vocal()
v1 = finish(a, "roar_1", 0.1, 11)
v2 = finish(b, "roar_2", 0.2, 12)
v3 = finish(0.65 * a + 0.75 * b, "roar_3", 0.3, 13)
for nm in ("roar_1", "roar_2", "roar_3"):
    print(nm, os.path.getsize(f"{OUT}/{nm}.ogg") // 1024, "KB")
if os.path.exists(f"{OUT}/roar.ogg"): os.remove(f"{OUT}/roar.ogg")

if "--spec" in sys.argv:
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axs = plt.subplots(3, 1, figsize=(11, 8))
    for ax, y, nm in zip(axs, (v1, v2, v3), ("roar_1 bowed string", "roar_2 vocal growl", "roar_3 blend")):
        f, tt, S = signal.spectrogram(y, SR, nperseg=2048, noverlap=1536)
        ax.pcolormesh(tt, f, 10 * np.log10(S + 1e-12), shading="auto", vmin=-90, vmax=-25); ax.set_ylim(0, 6000); ax.set_title(nm)
    fig.tight_layout(); fig.savefig(f"{ROOT}/tools/roar_spectrograms.png", dpi=75)

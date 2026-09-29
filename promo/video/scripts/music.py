#!/usr/bin/env python3
"""The launch video's score, synthesized to the cut from public/audio/cues.json (scripts/cues.ts writes it).

A minimal house groove at the cut's tempo: four-on-the-floor kick, clap, hats and an off-beat bass under one soft chord
a part, the harmony moving as the video's parts change. The open has no groove, just the icon's hit, a tick for the
wordmark and a swell into the green card, where the groove drops in; each part after lands on a crash, the lineup's
devices each land on a chord stab, and the end card on a last hit that rings out. No melody, no arpeggio: the chords
change only on the cuts. Mastered to -14 LUFS, true peak under -1 dBTP.

    python3 scripts/music.py public/audio/score.wav      (npm run music writes the cues first, then runs this)

It needs numpy, scipy and pedalboard: pip install -r scripts/requirements.txt.
"""

import json
import sys
from pathlib import Path

import numpy as np
from pedalboard import Compressor, HighpassFilter, Pedalboard, Reverb
from scipy import ndimage, signal
from scipy.io import wavfile

SR = 48_000
RNG = np.random.default_rng(221)
TARGET_LUFS = -14.0
CEILING_DBTP = -1.2


def midi(note: float) -> float:
    return 440.0 * 2 ** ((note - 69) / 12)


A1, C2, D2, F1, G1 = 33, 36, 38, 29, 31
# One chord a part, the notes of the pad (MIDI) and the bass's root: Am9, Am7 from the drop, Fmaj9, Dm9, Cmaj9, Fmaj9,
# G6/9, and Am9 again for the end, each voiced to keep the notes it shares with the last where they were.
CHORDS = {
    "intro": ([57, 60, 64, 67, 71], A1),
    "android": ([57, 60, 64, 67], A1),
    "start": ([57, 60, 64, 67], A1),
    "code": ([53, 57, 60, 64, 67], F1),
    "steer": ([50, 53, 57, 60, 64], D2),
    "ship": ([52, 55, 59, 62, 64], C2),
    "lineup": ([53, 57, 60, 64, 67], F1),
    "lift": ([55, 59, 62, 64, 69], G1),
    "end": ([57, 60, 64, 67, 71], A1),
}
# The lineup's three stabs, the Fmaj9 voiced higher as each device lands.
STABS = [[65, 69, 72, 76], [69, 72, 76, 79], [72, 76, 79, 81]]


def t_of(n: int) -> np.ndarray:
    return np.arange(n) / SR


def sos(kind: str, freq, order: int = 2):
    return signal.butter(order, freq, btype=kind, fs=SR, output="sos")


def filt(x: np.ndarray, kind: str, freq, order: int = 2) -> np.ndarray:
    return signal.sosfilt(sos(kind, freq, order), x)


def fade(x: np.ndarray, into: float = 0.002, out: float = 0.01) -> np.ndarray:
    n = len(x)
    a, b = min(n, int(into * SR)), min(n, int(out * SR))
    x = x.copy()
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[n - b :] *= np.linspace(1, 0, b)
    return x


def saw(freq: float, t: np.ndarray, top: float, phase: float = 0.0) -> np.ndarray:
    """A band-limited sawtooth: its harmonics summed up to [top] Hz, rolled off a little above half of it."""
    out = np.zeros_like(t)
    k = 1
    while k * freq < min(top, SR / 2 - 1000):
        out += np.sin(2 * np.pi * k * freq * t + phase * k) / k * np.exp(-k * freq / (0.55 * top))
        k += 1
    return out * 0.6


# ---- the kit ---------------------------------------------------------------------------------------------------------


def kick(punch: float = 1.0) -> np.ndarray:
    t = t_of(int(0.4 * SR))
    freq = 50 + 120 * np.exp(-t * 30) + 70 * np.exp(-t * 170)
    body = np.sin(2 * np.pi * np.cumsum(freq) / SR)
    amp = np.exp(-t * 9) * (1 - np.exp(-t * 2500))
    x = np.tanh(1.7 * body * amp * punch) / np.tanh(1.7)
    click = filt(RNG.standard_normal(len(t)) * np.exp(-t * 700), "highpass", 1500)
    return fade(x + 0.28 * click, 0.0005, 0.03)


def clap() -> np.ndarray:
    t = t_of(int(0.4 * SR))
    env = np.zeros_like(t)
    for d in (0.0, 0.009, 0.0175):
        env += (t >= d) * np.exp(-np.maximum(t - d, 0) * 300)
    env += 0.5 * (t >= 0.024) * np.exp(-np.maximum(t - 0.024, 0) * 15)
    x = filt(RNG.standard_normal(len(t)), "bandpass", (900, 5200)) * env
    return fade(x / np.max(np.abs(x)), 0.0005, 0.05)


HAT_PARTIALS = (205.3, 304.4, 369.6, 522.7, 540.0, 800.0)


def hat(decay: float, length: float) -> np.ndarray:
    t = t_of(int(length * SR))
    metal = sum(signal.square(2 * np.pi * f * 1.9 * t + RNG.uniform(0, 6)) for f in HAT_PARTIALS) / len(HAT_PARTIALS)
    x = 0.55 * metal + 0.45 * RNG.standard_normal(len(t))
    x = filt(filt(x, "highpass", 5000, 4), "lowpass", 15000)
    x *= np.exp(-t * decay) * (1 - np.exp(-t * 4000))
    return fade(x / np.max(np.abs(x)), 0.0003, 0.01)


def shaker() -> np.ndarray:
    t = t_of(int(0.09 * SR))
    x = filt(RNG.standard_normal(len(t)), "bandpass", (2800, 9000))
    x *= (1 - np.exp(-t * 400)) * np.exp(-t * 55)
    return fade(x / np.max(np.abs(x)), 0.001, 0.01)


def crash(length: float = 2.2) -> np.ndarray:
    t = t_of(int(length * SR))
    x = filt(RNG.standard_normal(len(t)), "highpass", 4800, 2)
    x = filt(x, "lowpass", 13000)
    x *= np.exp(-t * 2.4) * (1 - np.exp(-t * 800))
    return fade(x / np.max(np.abs(x)), 0.0005, 0.3)


def impact(length: float = 3.2) -> np.ndarray:
    t = t_of(int(length * SR))
    freq = 38 + 52 * np.exp(-t * 7)
    boom = np.sin(2 * np.pi * np.cumsum(freq) / SR) * np.exp(-t * 2.6) * (1 - np.exp(-t * 900))
    air = filt(RNG.standard_normal(len(t)), "lowpass", 2400) * np.exp(-t * 6)
    snap = filt(RNG.standard_normal(len(t)), "highpass", 2000) * np.exp(-t * 60)
    x = np.tanh(1.4 * boom) + 0.35 * air + 0.25 * snap
    return fade(x / np.max(np.abs(x)), 0.0005, 0.4)


def tick() -> np.ndarray:
    t = t_of(int(0.12 * SR))
    x = np.sin(2 * np.pi * 2350 * t) * np.exp(-t * 95) + 0.35 * np.sin(2 * np.pi * 4700 * t) * np.exp(-t * 160)
    x += 0.3 * filt(RNG.standard_normal(len(t)), "highpass", 5000) * np.exp(-t * 900)
    return fade(x / np.max(np.abs(x)), 0.0003, 0.02)


def tap() -> np.ndarray:
    t = t_of(int(0.06 * SR))
    x = np.sin(2 * np.pi * 1650 * t) * np.exp(-t * 230) + 0.5 * filt(RNG.standard_normal(len(t)), "highpass", 3500) * np.exp(-t * 700)
    return fade(x / np.max(np.abs(x)), 0.0003, 0.01)


def riser(length: float) -> np.ndarray:
    """Noise through a band-pass that sweeps up as it swells, its filter stepped a block at a time."""
    n = int(length * SR)
    noise = RNG.standard_normal(n)
    out = np.zeros(n)
    block = 256
    zi = None
    for i in range(0, n, block):
        p = i / n
        centre = 250 * (9000 / 250) ** p
        s = sos("bandpass", (centre / 1.6, min(centre * 1.6, SR / 2 - 100)))
        if zi is None:
            zi = signal.sosfilt_zi(s) * 0
        chunk, zi = signal.sosfilt(s, noise[i : i + block], zi=zi)
        out[i : i + block] = chunk
    t = t_of(n)
    out *= (t / length) ** 2.4
    return out / np.max(np.abs(out))


def swell(length: float) -> np.ndarray:
    """A crash played backwards: it gathers up to its end."""
    x = crash(length + 0.4)[::-1][-int(length * SR) :]
    return fade(x / np.max(np.abs(x)), 0.05, 0.004)


# ---- the tonal parts -------------------------------------------------------------------------------------------------


def bass(root: int, length: float) -> np.ndarray:
    t = t_of(int(length * SR))
    f = midi(root)
    sub = np.sin(2 * np.pi * f * t)
    grit = saw(2 * f, t, 2400)
    env = (1 - np.exp(-t * 500)) * np.exp(-t * 7)
    x = 0.6 * sub * env + 0.95 * grit * env * np.exp(-t * 5)
    return fade(np.tanh(1.3 * x), 0.001, 0.03)


def chord(notes: list[int], length: float, top: float, attack: float, release: float, seed: int) -> np.ndarray:
    """The pad: each note two detuned band-limited saws, one a side, fading in over [attack] and out over [release]."""
    rng = np.random.default_rng(seed)
    t = t_of(int(length * SR))
    left = np.zeros_like(t)
    right = np.zeros_like(t)
    for n in notes:
        f = midi(n)
        left += saw(f * 2 ** (-7 / 1200), t, top, rng.uniform(0, 6)) + 0.5 * saw(f * 2 ** (4 / 1200), t, top, rng.uniform(0, 6))
        right += saw(f * 2 ** (7 / 1200), t, top, rng.uniform(0, 6)) + 0.5 * saw(f * 2 ** (-4 / 1200), t, top, rng.uniform(0, 6))
    env = np.minimum(1, t / max(attack, 1e-3)) * np.clip((length - t) / release, 0, 1)
    # Its low notes are the bass's room, so the pad keeps above 200 Hz.
    return filt(np.stack([left, right]), "highpass", 200) * env / len(notes)


def stab(notes: list[int], seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    t = t_of(int(0.9 * SR))
    left = np.zeros_like(t)
    right = np.zeros_like(t)
    for n in notes:
        f = midi(n)
        left += saw(f * 2 ** (-6 / 1200), t, 3200, rng.uniform(0, 6))
        right += saw(f * 2 ** (6 / 1200), t, 3200, rng.uniform(0, 6))
    env = (1 - np.exp(-t * 900)) * np.exp(-t * 7.5)
    x = np.stack([left, right]) * env
    return np.stack([fade(c, 0.0005, 0.08) for c in x]) / np.max(np.abs(x))


# ---- the score -------------------------------------------------------------------------------------------------------


class Mix:
    def __init__(self, seconds: float):
        self.n = int(round(seconds * SR))
        self.dry = np.zeros((2, self.n))
        self.pumped = np.zeros((2, self.n))
        self.send = np.zeros((2, self.n))

    def add(self, at: float, x: np.ndarray, gain: float, pan: float = 0.0, verb: float = 0.0, pump: bool = False):
        if x.ndim == 1:
            x = np.stack([x * np.sqrt(0.5 * (1 - pan)), x * np.sqrt(0.5 * (1 + pan))]) * np.sqrt(2)
        i = int(round(at * SR))
        if i >= self.n:
            return
        x = x[:, : self.n - i] * gain
        (self.pumped if pump else self.dry)[:, i : i + x.shape[1]] += x
        if verb:
            self.send[:, i : i + x.shape[1]] += x * verb


def pump(mix: Mix, kicks: list[float], depth: float = 0.62, release: float = 0.16) -> np.ndarray:
    """The sidechain: what's pumped ducks under each kick and swells back before the next."""
    gain = np.ones(mix.n)
    t = t_of(int(release * 3 * SR))
    dip = 1 - depth * np.exp(-t / release * 2.2) * (1 - np.exp(-t * 900))
    for k in kicks:
        i = int(round(k * SR))
        j = min(mix.n, i + len(dip))
        gain[i:j] = np.minimum(gain[i:j], dip[: j - i])
    return gain


# Each voice's level in the mix, before mastering.
LEVEL = {
    "impact": 0.8,
    "tick": 0.4,
    "riser": 0.36,
    "swell": 0.4,
    "kick": 0.56,
    "clap": 0.9,
    "hat": 0.48,
    "sixteenths": 0.18,
    "open hat": 0.21,
    "shaker": 0.2,
    "bass": 0.45,
    "pickup": 0.22,
    "pad": 0.74,
    "crash": 0.4,
    "stab": 0.42,
    "tap": 0.09,
}
# How far each part's pad opens: the top of its harmonics, in Hz.
PAD_TOP = {"intro": 2300, "android": 2100, "start": 2400, "code": 2700, "steer": 2850, "ship": 3100, "lineup": 4000, "lift": 4400, "end": 3400}
# The shaker's sixteenths, the off-beat eighth leant on.
SHAKE = (0.45, 0.3, 0.8, 0.3)


def score(cues: dict) -> np.ndarray:
    fps, bpm = cues["fps"], cues["bpm"]
    at = cues["at"]
    beat = 60 / bpm
    sec = lambda frame: frame / fps  # noqa: E731
    length = cues["frames"] / fps
    mix = Mix(length)
    lv = LEVEL

    # The open: the icon's hit, the wordmark's tick, and the swell into the green card over a held chord.
    mix.add(sec(at["title"]), impact(), lv["impact"], verb=0.35)
    intro = chord(CHORDS["intro"][0], sec(at["android"] - at["title"]) + 0.05, PAD_TOP["intro"], 0.02, 0.06, 1)
    mix.add(sec(at["title"]), intro, lv["pad"] * 0.8, verb=0.4)
    mix.add(sec(at["wordmark"]), tick(), lv["tick"], pan=0.1, verb=0.5)
    rise_from = sec(at["wordmark"]) + beat * 0.5
    mix.add(rise_from, riser(sec(at["android"]) - rise_from), lv["riser"], verb=0.25)
    mix.add(sec(at["android"]) - beat * 1.5, swell(beat * 1.5), lv["swell"])

    # The groove, from the green card to the end card, a part at a time.
    parts = [
        ("android", at["android"], at["start"]),
        ("start", at["start"], at["code"]),
        ("code", at["code"], at["steer"]),
        ("steer", at["steer"], at["ship"]),
        ("ship", at["ship"], at["lineup"]),
        ("lineup", at["lineup"], at["lineup"] + 4 * fps * beat),
        ("lift", at["lineup"] + 4 * fps * beat, at["end"]),
    ]
    kicks: list[float] = []
    k_sound, c_sound, shake = kick(), clap(), shaker()
    closed, open_ = hat(95, 0.12), hat(13, 0.45)
    for seed, (name, start, until) in enumerate(parts, start=10):
        t0, t1 = sec(start), sec(until)
        notes, root = CHORDS[name]
        lineup = name in ("lineup", "lift")
        pad = chord(notes, t1 - t0 + 0.08, PAD_TOP[name], 0.03, 0.08, seed)
        mix.add(t0, pad, lv["pad"] * (1.2 if lineup else 1), verb=0.3, pump=True)
        if name != "lift":
            mix.add(t0, crash(), lv["crash"] * (1 if lineup or name == "android" else 0.65), verb=0.2)
        # The cut starts every part on a beat but not always on a bar, so beats count from the top of the video and
        # the clap keeps to the backbeat across the cuts.
        first, until_beat = int(round(t0 / beat)), int(round(t1 / beat))
        for g in range(first, until_beat):
            t = g * beat
            # A breath before the lineup and the end: the kick and clap drop out under a swell for the part's last beat.
            breath = g == until_beat - 1 and name in ("ship", "lift")
            if not breath:
                mix.add(t, k_sound, lv["kick"] * (1 if g == first else 0.95))
                kicks.append(t)
                if g % 2 == 1:
                    mix.add(t, c_sound, lv["clap"], pan=-0.05, verb=0.22)
            mix.add(t + beat / 2, closed, lv["hat"], pan=0.18)
            for q, velocity in enumerate(SHAKE):
                mix.add(t + beat * q / 4, shake, lv["shaker"] * velocity, pan=0.3)
            if name not in ("android", "start"):
                for q in (0.25, 0.75):
                    mix.add(t + beat * q, closed, lv["sixteenths"], pan=-0.22)
            if lineup or name == "ship":
                mix.add(t + beat / 2, open_, lv["open hat"] * (1 if lineup else 0.7), pan=0.12)
            mix.add(t + beat / 2, bass(root, beat * 0.42), lv["bass"], pump=True)
            if name != "android" and g % 2 == 1:
                mix.add(t + beat * 0.75, bass(root + 12, beat * 0.2), lv["pickup"], pump=True)
        if name in ("ship", "lift"):
            mix.add(t1 - beat, swell(beat), lv["swell"])

    # The lineup's devices, each landing on a stab.
    for i, frame in enumerate(cues["lands"]):
        mix.add(sec(frame), stab(STABS[i], 40 + i), lv["stab"], verb=0.45)

    # The end card: the last hit, and its chord ringing out to the end.
    mix.add(sec(at["end"]), impact(4.0), lv["impact"] * 0.95, verb=0.4)
    mix.add(sec(at["end"]), k_sound, lv["kick"])
    mix.add(sec(at["end"]), crash(3.5), lv["crash"], verb=0.3)
    ring = chord(CHORDS["end"][0], length - sec(at["end"]), PAD_TOP["end"], 0.01, 2.6, 7)
    mix.add(sec(at["end"]), ring, lv["pad"] * 1.2, verb=0.55)
    mix.add(sec(at["end"]), stab([57, 64, 67, 71, 76], 50), lv["stab"] * 0.8, verb=0.6)

    # The taps on screen, faintly.
    for frame in cues["taps"]:
        mix.add(sec(frame), tap(), lv["tap"], pan=0.1)

    gain = pump(mix, kicks)
    wet = Pedalboard([Reverb(room_size=0.62, damping=0.45, wet_level=1.0, dry_level=0.0, width=1.0)])(mix.send.astype(np.float32), SR)
    wet = filt(wet, "highpass", 250)
    return mix.dry + mix.pumped * gain + 0.5 * wet


# ---- mastering -------------------------------------------------------------------------------------------------------


def lufs(x: np.ndarray) -> float:
    """Integrated loudness as ITU-R BS.1770-4 has it, for 48 kHz: K-weighted, in 400 ms blocks, gated."""
    shelf = (np.array([1.53512485958697, -2.69169618940638, 1.19839281085285]), np.array([1.0, -1.69065929318241, 0.73248077421585]))
    rlb = (np.array([1.0, -2.0, 1.0]), np.array([1.0, -1.99004745483398, 0.99007225036621]))
    k = signal.lfilter(*rlb, signal.lfilter(*shelf, x, axis=1), axis=1)
    size, hop = int(0.4 * SR), int(0.1 * SR)
    z = np.array([np.mean(k[:, i : i + size] ** 2, axis=1).sum() for i in range(0, k.shape[1] - size + 1, hop)])
    loud = -0.691 + 10 * np.log10(np.maximum(z, 1e-12))
    z = z[loud > -70]
    rel = -0.691 + 10 * np.log10(np.mean(z)) - 10
    z = z[-0.691 + 10 * np.log10(z) > rel]
    return float(-0.691 + 10 * np.log10(np.mean(z)))


def peaks(x: np.ndarray) -> np.ndarray:
    """Each sample's true peak: the loudest of the channels' 4x-oversampled values from it to the next."""
    up = np.abs(signal.resample_poly(x, 4, 1, axis=1)).max(axis=0)
    return up[: 4 * x.shape[1]].reshape(-1, 4).max(axis=1)


def true_peak(x: np.ndarray) -> float:
    return float(20 * np.log10(np.max(peaks(x))))


def limit(x: np.ndarray, ceiling_db: float, lookahead: float = 0.002, release: float = 0.08) -> np.ndarray:
    """A look-ahead limiter on the true peak. The gain each peak needs is held from [lookahead] before it to as long
    after, released from there, then averaged over the look-ahead so the gain eases down; every sample the average
    takes in is held to the peak's gain, so the eased gain still meets it."""
    need = np.minimum(1.0, 10 ** (ceiling_db / 20) / np.maximum(peaks(x), 1e-9))
    n = int(lookahead * SR)
    held = ndimage.minimum_filter1d(need, size=2 * n + 1, mode="nearest")
    a = np.exp(-1 / (release * SR))
    gain = np.empty_like(held)
    g = 1.0
    for i, h in enumerate(held):
        g = h if h < g else a * g + (1 - a) * h
        gain[i] = g
    return x * ndimage.uniform_filter1d(gain, size=n + 1, mode="nearest")


def master(x: np.ndarray) -> np.ndarray:
    x = x * 10 ** ((-20 - lufs(x)) / 20)
    x = Pedalboard([HighpassFilter(26), Compressor(threshold_db=-18, ratio=2.2, attack_ms=14, release_ms=150)])(x.astype(np.float32), SR).astype(np.float64)
    gain = TARGET_LUFS - lufs(x)
    for _ in range(8):
        y = limit(x * 10 ** (gain / 20), CEILING_DBTP)
        miss = TARGET_LUFS - lufs(y)
        if abs(miss) < 0.05:
            break
        gain += miss
    peak = true_peak(y)
    if peak > CEILING_DBTP:
        y *= 10 ** ((CEILING_DBTP - peak) / 20)
    tail = int(0.02 * SR)
    y[:, -tail:] *= np.linspace(1, 0, tail)
    return y


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "public/audio/score.wav")
    cues = json.loads((Path(__file__).resolve().parent.parent / "public" / "audio" / "cues.json").read_text())
    x = master(score(cues))
    print(f"score: {x.shape[1] / SR:.2f}s, {lufs(x):.2f} LUFS, true peak {true_peak(x):.2f} dBTP")
    pcm = np.clip(x.T + RNG.triangular(-1, 0, 1, x.T.shape) / 32768, -1, 1)
    out.parent.mkdir(parents=True, exist_ok=True)
    wavfile.write(out, SR, (pcm * 32767).astype(np.int16))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Turn ordinary engine recordings into a DriveApex sample bank.

The player wants several seamless loops of the same engine, each labelled with
the rpm it was captured at. Recordings you can actually find are not that: they
are rev sweeps, or a clip of an engine pulling, at an rpm nobody wrote down.
This bridges the two.

    python3 tools/build_engine_bank.py sweep.mp3 --id sport_petrol --name "Petrol Sport"

What it does:

  1. Decodes whatever you give it and folds it to mono.
  2. Tracks the engine's firing frequency across the recording.
  3. Anchors that track to real rpm. The absolute rpm of a recording is
     unknowable without knowing the engine -- a four cylinder fires twice per
     revolution, a V12 six times -- so instead the quietest, slowest part of the
     recording is taken to be idle and the whole track is scaled so that lands
     at --idle-rpm. Every other slice then sits at the right ratio to it, and
     ratios are the only thing playback rate depends on.
  4. Picks the steadiest window near each rpm on the grid.
  5. Makes each window loop seamlessly, by construction rather than by luck:
     the loop is a whole number of firing periods long, and its tail is
     crossfaded onto its head so the seam cannot click.
  6. Writes the loops and the manifest the app reads.

Nothing here needs the recording to be clean, labelled, or made for this. It
needs one engine, revving, for a few seconds.
"""

import argparse
import json
import math
import os
import sys

import numpy as np
import soundfile as sf

# The grid from docs/SAMPLE_RECORDING_SPEC.md, trimmed to what one sweep can
# realistically cover.
DEFAULT_GRID = [900, 1400, 2000, 2800, 3600, 4400, 5200, 6000]

# Engine fundamentals live low. Above this is intake, road and wind, and
# tracking it instead of the engine is the usual way this goes wrong.
F_MIN, F_MAX = 20.0, 220.0


def load_mono(path):
    data, rate = sf.read(path, always_2d=True, dtype="float64")
    return data.mean(axis=1), rate


def track_fundamental(x, rate, hop_ms=25.0, win_ms=100.0):
    """Firing frequency per frame, by autocorrelation.

    Autocorrelation rather than a spectrum peak: an engine's loudest partial is
    rarely its fundamental, so a spectrum peak lands on some harmonic and the
    track jumps by whole multiples as the mix changes. Autocorrelation finds the
    period the whole stack shares.
    """
    hop = max(1, int(rate * hop_ms / 1000))
    win = max(hop * 2, int(rate * win_ms / 1000))
    lo = int(rate / F_MAX)
    hi = int(rate / F_MIN)

    freqs, powers = [], []
    for start in range(0, max(1, len(x) - win), hop):
        frame = x[start:start + win]
        if len(frame) < win:
            break
        frame = frame - frame.mean()
        power = float(np.sqrt(np.mean(frame ** 2)))
        if power < 1e-5:
            freqs.append(0.0)
            powers.append(power)
            continue
        # Unbiased autocorrelation via FFT.
        n = 1 << (2 * win - 1).bit_length()
        spectrum = np.fft.rfft(frame * np.hanning(win), n)
        acf = np.fft.irfft(spectrum * np.conj(spectrum), n)[:hi + 2]
        acf /= acf[0] + 1e-12
        band = acf[lo:hi]
        if len(band) == 0:
            freqs.append(0.0)
            powers.append(power)
            continue
        lag = lo + int(np.argmax(band))
        # Parabolic refinement: a lag rounded to whole samples is up to half a
        # sample out, which at these periods is a few rpm.
        if lo < lag < hi - 1:
            a, b, c = acf[lag - 1], acf[lag], acf[lag + 1]
            denom = a - 2 * b + c
            if abs(denom) > 1e-12:
                lag += 0.5 * (a - c) / denom
        freqs.append(rate / lag)
        powers.append(power)

    freqs = np.array(freqs)
    # Median-smooth the track before anyone uses it.
    #
    # A frame-by-frame estimate on real exhaust is noisy -- a steady V8 came out
    # as 49 48 45 54 43 46 39 55 46, jumping every frame around a flat 46Hz. That
    # noise was being read as the recording being unsteady, which condemned
    # material that was fine, and feeding it to the pitch flattener injected the
    # jitter straight into the audio. A median is the right filter here: it
    # removes the outliers without smearing a genuine sweep the way a mean does.
    smoothed = freqs.copy()
    k = 5
    for i in range(len(freqs)):
        lo = max(0, i - k // 2)
        hi = min(len(freqs), i + k // 2 + 1)
        window = freqs[lo:hi]
        window = window[window > 0]
        if window.size:
            smoothed[i] = float(np.median(window))
    return smoothed, np.array(powers), hop, win


def anchor_to_rpm(freqs, powers, idle_rpm):
    """Scale the frequency track so its quietest sustained part is idle."""
    # The gate here must reject silence and nothing else. Gating on a
    # percentile of power threw away the quietest frames -- which are the idle
    # this function exists to find -- and anchored the whole bank to a fast part
    # of the recording instead. Every rpm then came out wrong by that ratio, and
    # the top of the range fell outside the search band entirely.
    live = powers > max(1e-4, 0.05 * float(np.median(powers[powers > 0])))
    voiced = freqs[(freqs > 0) & live]
    if voiced.size == 0:
        raise SystemExit("no engine tone found -- is this a recording of an engine?")
    # The 5th percentile rather than the minimum: one bad frame should not set
    # the scale for the whole bank.
    idle_hz = float(np.percentile(voiced, 5))
    if idle_hz <= 0:
        raise SystemExit("could not find an idle in this recording")
    return idle_rpm / idle_hz


def harvest(freqs, powers, hop, window_frames, scale, want, separation=0.14,
            max_drift=0.08):
    """The steadiest windows the recording actually offers, wherever they land.

    Asking for a fixed rpm grid was the wrong question. A recording of a car
    being driven passes through most of the grid too fast to hold, so the grid
    rejected material that was perfectly steady a few hundred rpm to either
    side: 59 windows on the AMG clip sit under 5% drift, and the grid found two
    of them. Since every slice is labelled with its own measured rpm anyway, the
    grid was never needed -- only coverage is, and that is what `separation`
    enforces.
    """
    floor = max(1e-4, 0.05 * float(np.median(powers[powers > 0])))
    candidates = []
    for i in range(0, max(1, len(freqs) - window_frames)):
        seg = freqs[i:i + window_frames]
        pw = powers[i:i + window_frames]
        if np.any(seg <= 0) or float(np.mean(pw)) < floor:
            continue
        median = float(np.median(seg))
        drift = float(np.std(seg)) / median
        if drift > max_drift:
            continue
        candidates.append((drift, i * hop, median, median * scale))
    candidates.sort()

    kept = []
    for drift, start, hz, rpm in candidates:
        if all(abs(rpm - k[3]) / k[3] > separation for k in kept):
            kept.append((drift, start, hz, rpm))
        if len(kept) >= want:
            break
    kept.sort(key=lambda k: k[3])
    return kept


def flatten_pitch(x, rate, freqs, hop, start, length, target_hz):
    """Resample a slice at a varying rate so its pitch comes out constant.

    Recordings you can find are of a car being driven, not of an engine held at
    a steady rpm, so every window drifts: the best on a 21-second AMG clip still
    moves 5.6% within 0.3s, which loops as an audible warble.

    But the pitch at every instant is already known -- it is the track this tool
    computes to label the slice in the first place. Reading the input faster
    where it is flat and slower where it is sharp cancels the drift out, and a
    slice that was unusable becomes a steady one. This is what makes a bank
    possible from ordinary material rather than from a recording session.
    """
    out = np.empty(length)
    pos = float(start)
    for i in range(length):
        # Instantaneous frequency at this input position, between tracked frames.
        f = np.interp(pos / hop, np.arange(len(freqs)), freqs)
        if f <= 0:
            f = target_hz
        j = int(math.floor(pos))
        frac = pos - j
        a = x[max(0, j - 1)]
        b = x[min(len(x) - 1, j)]
        c = x[min(len(x) - 1, j + 1)]
        d = x[min(len(x) - 1, j + 2)]
        out[i] = 0.5 * ((2 * b) + (-a + c) * frac +
                        (2 * a - 5 * b + 4 * c - d) * frac ** 2 +
                        (-a + 3 * b - 3 * c + d) * frac ** 3)
        pos += target_hz / f
        if pos >= len(x) - 3:
            return out[:i + 1]
    return out


def make_loop(x, rate, hz, seconds=0.6, fade_ms=25.0):
    """A seamless loop: whole firing periods, with the seam crossfaded away."""
    period = rate / hz
    cycles = max(4, int(round(seconds * hz)))
    length = int(round(cycles * period))
    fade = min(int(rate * fade_ms / 1000), length // 4)
    if len(x) < length + fade:
        return None
    body = x[:length].copy()
    tail = x[length:length + fade]
    # Equal-power crossfade of the material just past the end onto the start, so
    # the loop meets itself instead of being cut at an arbitrary sample.
    t = np.linspace(0, 1, fade, endpoint=False)
    body[:fade] = body[:fade] * np.sin(t * np.pi / 2) + tail * np.cos(t * np.pi / 2)
    return body


def seam_step(loop):
    """Worst sample-to-sample step across the wrap, over the signal's own rms."""
    if loop is None or len(loop) < 4:
        return float("inf")
    wrapped = np.concatenate([loop, loop[:4]])
    steps = np.abs(np.diff(wrapped))
    rms = float(np.sqrt(np.mean(loop ** 2))) + 1e-12
    return float(np.max(steps[-6:]) / rms)


def build(paths, out_dir, bank_id, name, grid, idle_rpm, load, level, quiet):
    layers = []
    report = []
    for path in paths:
        x, rate = load_mono(path)
        freqs, powers, hop, win = track_fundamental(x, rate)
        scale = anchor_to_rpm(freqs, powers, idle_rpm)
        window_frames = max(4, int(0.45 * rate / hop))

        for drift, start, hz, rpm_f in harvest(freqs, powers, hop, window_frames,
                                               scale, len(grid)):
            measured = int(round(rpm_f))
            if any(abs(measured - l["rpm"]) < 100 for l in layers):
                continue
            # Flatten first, then slice: the loop is cut from material already at
            # one pitch, so its length in whole firing periods is exact.
            segment = flatten_pitch(x, rate, freqs, hop, start, int(1.2 * rate), hz)
            loop = make_loop(segment, rate, hz)
            if loop is None:
                report.append((measured, "too short to loop"))
                continue
            step = seam_step(loop)
            # Reject a loop whose seam is rough rather than shipping it and
            # hearing a tick once per pass. Measured good loops come in under
            # 0.10; 0.15 leaves room without letting an audible one through.
            if step > 0.15:
                report.append((measured, f"rejected, seam {step:.2f}"))
                continue
            peak = float(np.max(np.abs(loop))) + 1e-12
            loop = loop / peak * 0.85
            rel = f"{bank_id}/rpm{measured}_load{int(load)}.ogg"
            dest = os.path.join(out_dir, rel)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            sf.write(dest, loop, rate, format="OGG", subtype="VORBIS")
            layers.append({"file": rel, "rpm": measured, "load": float(load)})
            report.append((measured, f"drift {drift*100:.1f}%  {len(loop)/rate:.2f}s  seam {step:.2f}"))

    if len(layers) < 2:
        print("\n".join(f"  {r:5} rpm  {m}" for r, m in report), file=sys.stderr)
        raise SystemExit(
            "\nfewer than two usable rpm points -- the player needs at least two to "
            "crossfade between. Try a recording that revs further, or lower --idle-rpm."
        )

    manifest = {"id": bank_id, "name": name, "level": level, "layers": layers}
    manifest_path = os.path.join(out_dir, f"{bank_id}.json")
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)

    if not quiet:
        for rpm, message in report:
            print(f"  {rpm:5} rpm  {message}")
        print(f"\n{len(layers)} layers -> {manifest_path}")
    return manifest


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("recordings", nargs="+", help="any format: mp3, ogg, wav, flac")
    p.add_argument("--out", default="app/src/main/assets/enginebanks")
    p.add_argument("--id", required=True)
    p.add_argument("--name", default=None)
    p.add_argument("--idle-rpm", type=float, default=800.0,
                   help="what the slowest part of the recording should be called")
    p.add_argument("--load", type=float, default=1.0,
                   help="0 for a closed throttle recording, 1 for full load")
    # 0.8, not 0.9: two neighbouring layers are correlated, so an equal-power
    # crossfade between them peaks above either one alone. Measured at 1.02 on a
    # generated bank, which 0.9 would leave with almost no headroom.
    p.add_argument("--level", type=float, default=0.8)
    p.add_argument("--grid", type=int, nargs="*", default=DEFAULT_GRID)
    p.add_argument("--quiet", action="store_true")
    a = p.parse_args()
    build(a.recordings, a.out, a.id, a.name or a.id, a.grid,
          a.idle_rpm, a.load, a.level, a.quiet)


if __name__ == "__main__":
    main()

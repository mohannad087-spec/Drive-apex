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
    return np.array(freqs), np.array(powers), hop, win


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


def steadiest_window(freqs, powers, hop, target_hz, window_frames):
    """Where the recording sits closest to target_hz and moves least."""
    best, best_cost = None, None
    for i in range(0, max(1, len(freqs) - window_frames)):
        seg = freqs[i:i + window_frames]
        pw = powers[i:i + window_frames]
        if np.any(seg <= 0) or np.mean(pw) < 1e-4:
            continue
        # Distance from the target, plus how much it drifts while it is there.
        cost = abs(np.median(seg) - target_hz) / target_hz + 2.0 * np.std(seg) / target_hz
        if best_cost is None or cost < best_cost:
            best, best_cost = i * hop, cost
    return best, best_cost


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

        for rpm in grid:
            target_hz = rpm / scale
            if not (F_MIN <= target_hz <= F_MAX):
                continue
            start, cost = steadiest_window(freqs, powers, hop, target_hz, window_frames)
            if start is None or cost > 0.25:
                report.append((rpm, "not present in the recording"))
                continue
            segment = x[start:start + int(1.2 * rate)]
            hz = float(np.median(freqs[start // hop:start // hop + window_frames]))

            # Label the slice with the rpm actually measured in it, not with the
            # grid value that led us here. The grid is only a list of places to
            # look; a sweep never sits exactly on one, and calling a 2150 rpm
            # slice "2000" detunes it by 7% every time it is played.
            measured = int(round(hz * scale))
            if any(abs(measured - l["rpm"]) < 120 for l in layers):
                report.append((rpm, f"skipped, {measured} rpm already covered"))
                continue

            loop = make_loop(segment, rate, hz)
            if loop is None:
                report.append((rpm, "too short to loop"))
                continue
            step = seam_step(loop)
            peak = float(np.max(np.abs(loop))) + 1e-12
            loop = loop / peak * 0.85
            rel = f"{bank_id}/rpm{measured}_load{int(load)}.ogg"
            dest = os.path.join(out_dir, rel)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            sf.write(dest, loop, rate, format="OGG", subtype="VORBIS")
            layers.append({"file": rel, "rpm": measured, "load": float(load)})
            report.append((rpm, f"ok -> {measured} rpm  {len(loop)/rate:.2f}s  seam {step:.2f}"))

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

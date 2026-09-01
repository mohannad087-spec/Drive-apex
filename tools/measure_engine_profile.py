#!/usr/bin/env python3
"""Measure an engine's harmonic balance and body resonances from a recording.

This is where the numbers in the "measured" characters come from. It does not
produce audio: it produces the two things a synthesised voice needs and cannot
be guessed at -- how loud each order is relative to the fundamental, and where
the broad spectral peaks sit that make the sound have a body rather than a tone.

    python3 tools/measure_engine_profile.py clip.mp3 [clip2.mp3 ...]

Method, and why each part is what it is:

  1. The firing frequency is tracked by autocorrelation, median-smoothed. A
     spectrum peak lands on whichever harmonic happens to be loudest and jumps
     by whole multiples as the mix changes; autocorrelation finds the period the
     whole stack shares. The median matters as much: a frame-by-frame estimate
     on real exhaust jitters by 20% around a flat value, and reading that jitter
     as the recording being unsteady is what condemned material that was fine.

  2. Only windows that are steady and loud are measured. A window whose pitch
     is sweeping smears every harmonic across the bins either side of it, so the
     balance measured from it is the sweep's, not the engine's.

  3. Each order's level is the peak magnitude in a narrow band around it,
     divided by the fundamental's. Narrow, because the neighbouring order is
     only half an order away for the half-order family, and a wide band reads
     one as the other.

  4. Resonances are what is left of the average spectrum after the harmonic comb
     is taken out of it: a peak that stays in the same place while the engine
     revs is the body, and one that moves with the revs is a harmonic. The
     recording has to actually rev for this to separate the two, which is why a
     steady idle loop is the one kind of clip this cannot measure.

Everything is reported relative -- there is no absolute level here, because the
recording's own gain is unknown and irrelevant. The caller scales the order set
to whatever sum the app's existing characters use, so voices compare at equal
loudness.
"""

import argparse
import sys

import numpy as np
import soundfile as sf

F_MIN, F_MAX = 20.0, 220.0
ORDERS = [0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0]


def load_mono(path):
    data, rate = sf.read(path, always_2d=True, dtype="float64")
    return data.mean(axis=1), rate


def track_fundamental(x, rate, hop_ms=25.0, win_ms=100.0):
    hop = max(1, int(rate * hop_ms / 1000))
    win = max(hop * 2, int(rate * win_ms / 1000))
    lo, hi = int(rate / F_MAX), int(rate / F_MIN)
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
        n = 1 << (2 * win - 1).bit_length()
        spectrum = np.fft.rfft(frame * np.hanning(win), n)
        acf = np.fft.irfft(spectrum * np.conj(spectrum), n)[:hi + 2]
        acf /= acf[0] + 1e-12
        band = acf[lo:hi]
        lag = lo + int(np.argmax(band))
        if lo < lag < hi - 1:
            a, b, c = acf[lag - 1], acf[lag], acf[lag + 1]
            denom = a - 2 * b + c
            if abs(denom) > 1e-12:
                lag += 0.5 * (a - c) / denom
        freqs.append(rate / lag)
        powers.append(power)
    freqs = np.array(freqs)
    smoothed = freqs.copy()
    for i in range(len(freqs)):
        window = freqs[max(0, i - 2):i + 3]
        window = window[window > 0]
        if window.size:
            smoothed[i] = float(np.median(window))
    return smoothed, np.array(powers), hop, win


def steady_windows(freqs, powers, hop, win, max_drift=0.06, frames=8):
    """Every window that is steady enough and loud enough to measure."""
    live = powers[powers > 0]
    floor = max(1e-4, 0.25 * float(np.median(live))) if live.size else 1e-4
    out = []
    for i in range(0, max(1, len(freqs) - frames)):
        seg, pw = freqs[i:i + frames], powers[i:i + frames]
        if np.any(seg <= 0) or float(np.mean(pw)) < floor:
            continue
        median = float(np.median(seg))
        if float(np.std(seg)) / median > max_drift:
            continue
        out.append((i * hop, i * hop + frames * hop + win, median))
    return out


def order_levels(x, rate, windows):
    """Median level of each order across every steady window, fundamental = 1."""
    rows = []
    for start, end, f0 in windows:
        frame = x[start:min(end, len(x))]
        if len(frame) < rate // 20:
            continue
        frame = (frame - frame.mean()) * np.hanning(len(frame))
        n = 1 << (len(frame) * 4 - 1).bit_length()
        mag = np.abs(np.fft.rfft(frame, n))
        binhz = rate / n
        levels = []
        for order in ORDERS:
            centre = order * f0
            # +/-4%: wide enough to catch the peak through the window's own
            # drift, narrow enough that the half-order next door is 12x further
            # away than the band is wide.
            lo = int((centre * 0.96) / binhz)
            hi = int((centre * 1.04) / binhz) + 1
            if hi >= len(mag) or lo >= hi:
                levels.append(0.0)
                continue
            levels.append(float(mag[lo:hi].max()))
        if levels[1] <= 0:
            continue
        rows.append([v / levels[1] for v in levels])
    if not rows:
        return None, 0
    return np.median(np.array(rows), axis=0), len(rows)


def resonances(x, rate, windows, count=4):
    """Fixed spectral peaks: the ones that do not move as the engine revs.

    The average spectrum of a revving engine has the harmonics smeared out by
    the sweep itself, while anything belonging to the body stays put and
    therefore survives the average. Dividing by a broad smoothing of that same
    average leaves only what stands proud of the general slope.
    """
    n = 1 << 15
    acc = np.zeros(n // 2 + 1)
    used = 0
    for start, end, _ in windows:
        frame = x[start:min(end, len(x))]
        if len(frame) < n:
            frame = np.pad(frame, (0, n - len(frame)))
        frame = (frame[:n] - frame[:n].mean()) * np.hanning(n)
        acc += np.abs(np.fft.rfft(frame, n))
        used += 1
    if used == 0:
        return []
    avg = acc / used
    binhz = rate / n
    # Smooth in log frequency: a fixed-width smoother is far too broad down low
    # and far too narrow up high, and the peaks worth having are spread across
    # both ends.
    trend = np.copy(avg)
    for i in range(1, len(avg)):
        half = max(2, int(i * 0.18))
        trend[i] = avg[max(1, i - half):i + half + 1].mean()
    ratio = avg / (trend + 1e-12)
    # A mild smoothing of the ratio itself, again in log frequency. Without it
    # every peak's half-power width is one bin wide -- the curve is spiky enough
    # to fall through half its height immediately -- and the q that comes out is
    # in the hundreds, which is a whistle, not a body.
    smooth = np.copy(ratio)
    for i in range(1, len(ratio)):
        half = max(1, int(i * 0.05))
        smooth[i] = ratio[max(1, i - half):i + half + 1].mean()
    ratio = smooth

    lo_bin = int(150 / binhz)
    hi_bin = int(4000 / binhz)
    peaks = []
    for i in range(lo_bin + 1, hi_bin - 1):
        if ratio[i] > ratio[i - 1] and ratio[i] >= ratio[i + 1] and ratio[i] > 1.15:
            peaks.append((float(ratio[i]), i * binhz, i))
    peaks.sort(reverse=True)

    kept = []
    for prominence, hz, i in peaks:
        # One peak per third of an octave; two peaks 20Hz apart are one bump
        # measured twice, and stacking them in the renderer just makes it loud.
        if any(abs(np.log2(hz / k[1])) < 0.33 for k in kept):
            continue
        half = ratio[i] / np.sqrt(2)
        left = i
        while left > lo_bin and ratio[left] > half:
            left -= 1
        right = i
        while right < hi_bin and ratio[right] > half:
            right += 1
        width = max(binhz, (right - left) * binhz)
        # Bounded, because a peak that runs into the edge of the search band
        # reports a width the recording never showed. Below 0.7 the filter is
        # so broad it is a tilt rather than a resonance; above 4 it rings.
        kept.append((prominence, hz, min(4.0, max(0.7, hz / width))))
        if len(kept) >= count:
            break
    kept.sort(key=lambda k: k[1])
    return kept


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--sum", type=float, default=1.09,
                    help="scale the order set so its gains add up to this")
    args = ap.parse_args()

    for path in args.files:
        x, rate = load_mono(path)
        freqs, powers, hop, win = track_fundamental(x, rate)
        windows = steady_windows(freqs, powers, hop, win)
        print(f"\n=== {path.split('/')[-1]}")
        print(f"    {len(x)/rate:.1f}s at {rate}Hz, {len(windows)} steady windows")
        if not windows:
            print("    nothing steady enough to measure")
            continue
        hz = [w[2] for w in windows]
        print(f"    fundamental {min(hz):.1f}-{max(hz):.1f}Hz "
              f"(ratio {max(hz)/min(hz):.2f}x)")
        levels, used = order_levels(x, rate, windows)
        if levels is None:
            print("    no window gave a usable fundamental")
            continue
        print(f"    orders (relative, from {used} windows):")
        print("      " + "  ".join(f"{o}:{v:.2f}" for o, v in zip(ORDERS, levels)))
        scaled = levels * (args.sum / levels.sum())
        print(f"    scaled to sum {args.sum}:")
        print("      " + "  ".join(f"{o}:{v:.3f}" for o, v in zip(ORDERS, scaled)))
        for prominence, hz_, q in resonances(x, rate, windows):
            print(f"    resonance {hz_:7.0f}Hz  q {q:4.2f}  prominence {prominence:.2f}")


if __name__ == "__main__":
    sys.exit(main())

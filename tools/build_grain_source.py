#!/usr/bin/env python3
"""Turn a rev recording into a grain source the app can play at any rpm.

This is the material side of the granular voice. The player does not repitch a
loop; it reads short grains out of a real recording at the place where the
engine was at the rpm being asked for. For that it needs two things: the audio,
and a map saying which rpm the engine was at, at every point in it.

    python3 tools/build_grain_source.py rev.mp3 --id corvette --name "Corvette V8"

What matters about the input, and why:

  * A **ramp**, not a loop. Ten seconds or more of the engine climbing (and,
    ideally, a second file of it coming back down). A steady idle loop maps to
    one rpm and can only ever play that one rpm back.
  * One engine, no music, no voices. Everything in the file is played.

What comes out is an Ogg Vorbis file and a JSON map. Nothing here needs the
recording to be labelled, clean, or made for this purpose.
"""

import argparse
import json
import math
import os
import sys

import numpy as np
import soundfile as sf

F_MIN, F_MAX = 20.0, 220.0
HOP_MS = 20.0
WIN_MS = 90.0
TARGET_RATE = 44100


def load_mono(path):
    """Whatever the file is, as mono float at its own rate.

    libsndfile covers wav, flac, ogg and mp3; it does not cover the AAC in an
    .m4a, which is what most recordings found in the wild turn out to be. PyAV
    covers the rest and is only imported when it is needed, so the common path
    keeps no extra dependency.
    """
    try:
        data, rate = sf.read(path, always_2d=True, dtype="float64")
        return data.mean(axis=1), rate
    except Exception:
        import av
        container = av.open(path)
        stream = container.streams.audio[0]
        rate = stream.codec_context.sample_rate
        resampler = av.audio.resampler.AudioResampler(
            format="fltp", layout="mono", rate=rate
        )
        chunks = []
        for frame in container.decode(stream):
            for out in resampler.resample(frame):
                chunks.append(out.to_ndarray()[0].copy())
        if not chunks:
            raise SystemExit(f"nothing decodable in {path}")
        return np.concatenate(chunks).astype(np.float64), rate


def track_fundamental(x, rate):
    """Firing frequency per frame, by autocorrelation, median smoothed.

    Autocorrelation rather than a spectrum peak: an engine's loudest partial is
    rarely its fundamental, so a spectrum peak lands on some harmonic and jumps
    by whole multiples as the mix changes. The median afterwards matters as
    much -- a frame-by-frame estimate on real exhaust jitters about 20% around a
    flat value, and that jitter would be written straight into the rpm map.
    """
    hop = max(1, int(rate * HOP_MS / 1000))
    win = max(hop * 2, int(rate * WIN_MS / 1000))
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
        lag = lo + int(np.argmax(acf[lo:hi]))
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
        window = freqs[max(0, i - 3):i + 4]
        window = window[window > 0]
        if window.size:
            smoothed[i] = float(np.median(window))
    return smoothed, np.array(powers), hop


def unfold_octaves(freqs, passes=3, radius=10):
    """Pull frames that locked onto the wrong harmonic back onto the track.

    Autocorrelation on a big lopey V8 sometimes locks onto twice or two thirds
    of the firing rate and stays there for a stretch -- the loudest partial wins
    and the median filter cannot outvote a sustained error. Those frames end up
    in the wrong bucket, so the player fetches audio from a place where the
    engine was at a different rpm than the map claims and the rate correction
    cannot undo it: measured on a 1970 Charger, the voice came out 27% sharp at
    3600 rpm and 31% at 6500.

    A rev is smooth, which is the fact this uses. Each frame is compared against
    the median of its neighbours and replaced by whichever simple ratio of
    itself sits closest to that trend. Iterated, because fixing a frame improves
    the reference for the next pass.
    """
    ratios = np.array([1.0, 0.5, 2.0, 2.0 / 3.0, 1.5, 1.0 / 3.0, 3.0])
    out = freqs.astype(float).copy()
    for _ in range(passes):
        reference = np.zeros_like(out)
        for i in range(len(out)):
            window = out[max(0, i - radius):i + radius + 1]
            window = window[window > 0]
            reference[i] = np.median(window) if window.size else 0.0
        fixed = out.copy()
        for i in range(len(out)):
            if out[i] <= 0 or reference[i] <= 0:
                continue
            candidates = out[i] * ratios
            error = np.abs(np.log(candidates / reference[i]))
            fixed[i] = candidates[int(np.argmin(error))]
        if np.allclose(fixed, out):
            break
        out = fixed
    return out


def fill_gaps(freqs, powers):
    """Carry the last good reading across silence rather than writing a zero.

    A zero in the map is a hole the player would have to skip, and the quiet
    moments in a rev recording are usually a gearshift -- where the engine is
    still turning at very nearly the rpm either side of it.
    """
    live = powers > max(1e-4, 0.05 * float(np.median(powers[powers > 0])))
    out = freqs.copy()
    last = 0.0
    for i in range(len(out)):
        if out[i] > 0 and live[i]:
            last = out[i]
        elif last > 0:
            out[i] = last
    # And backwards, for a file that opens on silence.
    nxt = 0.0
    for i in range(len(out) - 1, -1, -1):
        if out[i] > 0:
            nxt = out[i]
        elif nxt > 0:
            out[i] = nxt
    return out


def span(freqs):
    """The band of firing frequency the recording actually covers.

    Deliberately percentiles rather than min and max: one bad frame at either
    end would otherwise stretch the whole mapping around it.
    """
    voiced = freqs[freqs > 0]
    if voiced.size == 0:
        raise SystemExit("no engine tone found -- is this a recording of an engine?")
    return float(np.percentile(voiced, 3)), float(np.percentile(voiced, 97))


def resample(x, src, dst):
    if src == dst:
        return x
    n = int(len(x) * dst / src)
    position = np.arange(n) * (src / dst)
    left = np.floor(position).astype(int)
    frac = position - left
    right = np.clip(left + 1, 0, len(x) - 1)
    left = np.clip(left, 0, len(x) - 1)
    return x[left] * (1 - frac) + x[right] * frac


def pedal_lanes(x, rate, freqs, hop):
    """Does this recording get brighter when the engine is pulling?

    The player can follow the throttle by reading the stretches where the
    frequency was climbing when the pedal is down and the stretches where it was
    falling when the pedal is up. That only helps if climbing actually sounds
    harder in this recording, and it does not always: an F-Type crackles on the
    overrun, so its falling stretches are the brighter ones, and a player using
    them for the pedal would answer a stab of throttle by going quieter and
    duller.

    So it is measured rather than assumed. Rising and falling frames are
    compared by spectral centroid, and the lanes are only enabled when rising
    comes out brighter by a clear margin.
    """
    win = 2048
    rising, falling = [], []
    for i in range(3, len(freqs) - 3):
        if freqs[i] <= 0 or freqs[i - 3] <= 0 or freqs[i + 3] <= 0:
            continue
        start = i * hop
        if start + win >= len(x):
            break
        slope = (freqs[i + 3] - freqs[i - 3]) / (6 * freqs[i])
        if abs(slope) <= 0.004:
            continue
        frame = x[start:start + win] * np.hanning(win)
        spectrum = np.abs(np.fft.rfft(frame)) ** 2
        f = np.fft.rfftfreq(win, 1 / rate)
        total = spectrum.sum()
        if total <= 0:
            continue
        centroid = float((spectrum * f).sum() / total)
        (rising if slope > 0 else falling).append(centroid)
    if len(rising) < 20 or len(falling) < 20:
        return False, 0.0, 0.0
    up, down = float(np.median(rising)), float(np.median(falling))
    # A clear margin, not a coin toss: 8% of centroid is audible as the engine
    # hardening up, and anything less is not worth splitting the material over.
    return up > down * 1.08, up, down


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("--id", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--load", choices=["on", "off"], default="on",
                    help="on for an acceleration ramp, off for overrun")
    ap.add_argument("--skip", type=float, default=0.0, help="seconds to drop from the front")
    ap.add_argument("--credit", default="", help="who recorded this, shown in the app")
    ap.add_argument("--credit-url", default="", help="where the recording came from")
    ap.add_argument("--out", default="app/src/main/assets/grains")
    # Vorbis quality. Raised from 0.5 after the rasp report: engine noise is
    # broadband and unforgiving, and 66kbps of it grits audibly. 0.7 costs about
    # 30KB per source, which is nothing against the size of the APK.
    ap.add_argument("--quality", type=float, default=0.7)
    args = ap.parse_args()

    x, rate = load_mono(args.input)
    if args.skip > 0:
        x = x[int(args.skip * rate):]
    peak = float(np.max(np.abs(x))) or 1.0
    x = x / peak * 0.97

    freqs, powers, hop = track_fundamental(x, rate)
    freqs = unfold_octaves(freqs)
    freqs = fill_gaps(freqs, powers)
    low, high = span(freqs)
    lanes, up_hz, down_hz = pedal_lanes(x, rate, freqs, hop)

    # The audio goes out at the engine's own rate so the player never resamples.
    out_audio = resample(x, rate, TARGET_RATE)
    hop_ms = HOP_MS

    os.makedirs(args.out, exist_ok=True)
    audio_path = os.path.join(args.out, f"{args.id}.ogg")
    with sf.SoundFile(audio_path, "w", samplerate=TARGET_RATE, channels=1,
                      format="OGG", subtype="VORBIS",
                      compression_level=1.0 - args.quality) as handle:
        handle.write(out_audio.astype(np.float32))

    # What is written down is the firing frequency, not an rpm.
    #
    # The absolute rpm of a recording is unknowable without knowing the engine
    # -- a four cylinder fires twice per revolution, a V12 six times -- and
    # guessing it was the wrong question anyway. What the player needs is where
    # in the file the engine was at each point of its own rev range, so the band
    # this recording covers is mapped onto the band the voice needs. Inside that
    # band nothing is repitched at all, which is the whole reason for playing
    # grains instead of stretching a loop.
    manifest = {
        "id": args.id,
        "name": args.name,
        "load": 1.0 if args.load == "on" else 0.0,
        "sampleRate": TARGET_RATE,
        "hopMs": hop_ms,
        "pedalLanes": lanes,
        "credit": args.credit,
        "creditUrl": args.credit_url,
        "lowHz": round(low, 2),
        "highHz": round(high, 2),
        "hz": [round(float(v), 2) for v in freqs],
        # Loudness per hop, so the player can refuse to take grains from the
        # quiet parts. The frequency map is carried across gaps to stay
        # continuous, which means the tail of a recording -- the silence after
        # the rev -- inherits the frequency of the loudest moment in it. Without
        # this the top of the rev range reads grains out of that silence, which
        # measured as the voice all but disappearing above 6500 rpm.
        "rms": [round(float(v), 5) for v in powers[:len(freqs)]],
    }
    json_path = os.path.join(args.out, f"{args.id}.json")
    with open(json_path, "w") as handle:
        json.dump(manifest, handle)

    size = os.path.getsize(audio_path)
    coverage = high / max(low, 1e-6)
    print(f"{args.id}: {len(out_audio)/TARGET_RATE:.1f}s  firing {low:.1f}-{high:.1f}Hz"
          f"  covers {coverage:.2f}x of a rev range  {size/1024:.0f}KB  {len(freqs)} map points")
    print(f"  pedal lanes {'on' if lanes else 'off'}: pulling {up_hz:.0f}Hz, "
          f"coasting {down_hz:.0f}Hz")
    if coverage < 2.5:
        print("  NOTE: a gear spans about 3x. Under that, the top of each gear is")
        print("  stretched. A ten-second pull from idle to redline fixes it.")
    print(f"  {audio_path}")
    print(f"  {json_path}")


if __name__ == "__main__":
    sys.exit(main())

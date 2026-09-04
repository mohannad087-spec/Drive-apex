# Grain sources

A granular voice is built from a **rev**, not a loop: twenty-odd seconds of one
engine sweeping through its range, plus a map of the firing frequency through
it. The player then reads grains from wherever in that recording the engine was
at the rpm being asked for, and nothing is repitched while the request falls
inside the band the recording covers.

    python3 tools/build_grain_source.py rev.m4a --id cadillac_ctsv \
        --name "Cadillac CTS-V" --credit "Matt Moran" --credit-url "https://..."

The tool prints how much of a rev range the recording covers. A gear spans
about 3x; less than that and the top of every gear is stretched. It reads
anything libsndfile handles, and falls back to PyAV for the AAC in an .m4a,
which is what most recordings found in the wild turn out to be.

## What a good source looks like

Measured against thirty real 24-second revs, the number that decides whether a
granular voice works is the range the recording covers, and the spread is wide:

    5.71x  Aston Martin Vantage        3.89x  Mercedes-AMG GT
    5.32x  Dodge Viper V10             3.19x  Dodge Hellcat
    4.69x  Cadillac CTS-V              1.33x  Shelby GT350

Anything from about 3x up is enough for a gear. Under that the top of every
gear is stretched.

## Nothing ships here yet, and the reason is licensing rather than sound

Three voices were built and measured from a public collection of real recordings
-- Cadillac CTS-V, Mercedes-AMG GT, Dodge Viper -- and they are good: the AMG
adds 0.028 of amplitude ripple over its own recording, against the 0.026 the
withdrawn Corvette added over a recording that was already rough.

They are not committed, because they are other people's recordings. The
collection credits each clip to whoever captured it and uses them
non-commercially; this APK goes out on a public GitHub release, which is
redistribution.

What unblocks it is one recording that is free to redistribute. Either:

  * a CC0 clip -- the Internet Archive's `car-engines` collection is CC0 1.0 and
    this environment's network policy blocks reaching it from here, so it has to
    be downloaded and handed over; or
  * ten seconds of a real pull from idle to the redline, recorded on a phone,
    and ten of it coming back down off the throttle.

Either becomes a voice in one command.

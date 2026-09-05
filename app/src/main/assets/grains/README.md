# Grain sources

A granular voice is built from a **rev**, not a loop: twenty-odd seconds of one
engine sweeping through its range, plus a map of the firing frequency through
it. The player then reads grains from wherever in that recording the engine was
at the rpm being asked for, and nothing is repitched while the request falls
inside the band the recording covers.

    python3 tools/build_grain_source.py rev.mp3 --id camaro_69 \
        --name "Camaro SS 1969" --credit "..." --credit-url "https://..."

The tool reads anything libsndfile handles and falls back to PyAV for the AAC in
an .m4a. It prints how much of a rev range the recording covers; a gear spans
about 3x, and under that the top of every gear is stretched.

## What ships, and how it was chosen

The source is the Internet Archive's `car-engines` collection, CC0 1.0:
https://archive.org/details/car-engines — 71 recordings, all of them free to
redistribute, which is why these can be in the APK at all.

Every one of the 71 was measured, then the shortlist was built and each built
voice was played through a port of the shipping renderer and measured again.
Two numbers decided it:

  * **slope and r** — a slow ramp from 900 to 6500 rpm, with the output's own
    pitch tracked back. Slope 1.00 means the note rises exactly as asked; r is
    how tightly it followed.
  * **ripple against the recording's own** — the amplitude modulation the player
    adds. This is the rasp, and the recording's own figure is the floor.

| id             | car                        | covers | slope | r     | ripple | source |
|----------------|----------------------------|--------|-------|-------|--------|--------|
| `huracan_v10`  | Lamborghini Huracán, V10   | 4.20x  | 1.02  | 0.893 | 0.228  | 0.394  |
| `camaro_69`    | Chevrolet Camaro SS 1969   | 3.09x  | 0.89  | 0.963 | 0.136  | 0.148  |
| `ghibli`       | Maserati Ghibli            | 5.40x  | 0.88  | 0.832 | 0.198  | 0.223  |
| `boss302_69`   | Ford Mustang Boss 302 1969 | 4.35x  | 0.97  | 0.655 | 0.222  | 0.180  |
| `charger_70`   | Dodge Charger 1970         | 5.77x  | 0.92  | 0.677 | 0.273  | 0.209  |
| `jaguar_ftype` | Jaguar F-Type S            | 6.14x  | 0.77  | 0.733 | 0.172  | 0.160  |
| `vantage`      | Aston Martin Vantage       | 3.87x  | 0.80  | 0.617 | 0.133  | 0.124  |

Three of them — the Huracán, the Camaro and the Ghibli — come out *smoother*
than the recording they are made from, because a grain player reading steadily
through a clean stretch does not reproduce whatever unsteadiness the microphone
caught elsewhere in the clip.

## What did not ship, and why

Seven more were built and dropped, all on measurement rather than taste:

    mercedes_slr  slope 0.36   c63_amg     slope 0.26   clk_gtr   slope 0.52
    stingray_67   slope 0.63   chevelle_70 slope 0.71
    s2000         ripple 0.405 against its own 0.259
    hellcat       ripple 0.378 against its own 0.159

A slope well under 1 means the note stops rising with the rpm near the ends of
the range; ripple far above the source means the player is adding a rasp the
engine never made. The S2000 tracked better than anything else built (slope
1.00, r 0.996) and was still dropped: the rasp is the thing that got complained
about, and it more than doubled it.

## Level, and what the pedal does

Two things the player does to every one of these before it reaches the speaker.

**They are level-matched.** Only their peaks happened to agree — each was
normalised to about 0.97 by whoever recorded it — while their loudness spans
0.189 to 0.272 rms, which is audible as one voice being bigger than the next.
The player brings them all to 0.17 rms over the parts above the loudness gate.
That also stopped the 1970 Charger clipping: through the player at SPORT's level
scale it peaked at 1.03, and now the worst of the seven peaks at 0.93.

**The throttle changes the timbre, not only the level.** A synthesised voice
rebalances every partial against load on every sample, which is what makes an
engine open up; this path used the pedal for a gain and nothing else, so it
sounded like a recording being turned up, because that is what it was.

The recordings cannot supply the difference themselves. All seven are free revs
in neutral, so the engine is not pulling against anything on the way up either.
The builder measures it and says so:

    charger_70   pedal lanes off: pulling 149Hz, coasting 148Hz
    boss302_69   pedal lanes off: pulling 612Hz, coasting 604Hz
    camaro_69    pedal lanes off: pulling 303Hz, coasting 379Hz
    ghibli       pedal lanes off: pulling 446Hz, coasting 422Hz
    huracan_v10  pedal lanes off: pulling 404Hz, coasting 433Hz
    jaguar_ftype pedal lanes ON:  pulling 366Hz, coasting 310Hz
    vantage      pedal lanes off: pulling 456Hz, coasting 492Hz

Only the F-Type clears the bar, and only because it crackles on the overrun.
Where a recording does clear it the player reads the climbing stretches under
throttle and the falling ones off it; everywhere else the load is put back by
shaping what is played — a split that follows the fourth order of the note
rather than sitting at a fixed frequency, and 10.2dB of swing above it between
a closed throttle and an open one.

Measured across the seven voices at seven rpm each, with the load the app
actually feeds it: the spectral centroid moves a median of +2.2dB from closed
throttle to open, and in 48 of the 49 cases it moves the right way. The
exception is the F-Type at 1800rpm, by 0.26dB, and it is the one recording whose
overrun really is the brighter of the two.

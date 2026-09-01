# Where these recordings came from

`amg_v8` was built by `tools/build_engine_bank.py` from two Mercedes CLS 63 S
AMG clips supplied by the project owner. The loops here are slices of those
clips, pitch-flattened and made seamless -- they are the original audio, not a
synthesis of it.

Worth being clear about, once: imitating how a car sounds is not restricted, and
the `measured_petrol` and `mercedesV12` characters do exactly that -- they carry
numbers measured off recordings and ship no audio at all. Redistributing someone
else's actual recording inside an app is a different thing, and that is what
this directory does. For a personal build it is the owner's call; before any
wider release, replace these with recordings that are owned or licensed. The
tool rebuilds a bank from new sources in one command, so the swap costs nothing
but the files.

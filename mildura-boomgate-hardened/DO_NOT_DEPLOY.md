# DO NOT DEPLOY THIS TREE TO THE LIVE PI

This directory is an **offline reference rewrite** assembled from repo
snapshots and tarballs. It is useful for review and for drafting surgical
patches.

It is **not** a drop-in replacement for `/home/rjlcommercial/boomgate` on the
live Mildura Pi.

## Why

The live Pi already runs a more advanced `app.py` than the snapshots used
here (see `scripts/patch_mildura_pulse_force_off.py` in the parent repo).
Replacing the live tree with this package can regress gate / hold / Wiegand
behaviour.

## Next real move

1. SSH read-only audit of the live Pi
2. Diff live sources against `docs/MILDURA_PI_FULL_AUDIT.md`
3. Apply tiny targeted patches only

# DriveApex 0.2.38 Update-Channel Smoke Test

This document exists only to trigger the next signed release pipeline after the 0.2.37 baseline.

Expected validation:

- Installed baseline: `0.2.37` / `37`
- Target release: `0.2.38` / `38`
- Publish `DriveApex.apk` and `DriveApex-update.json`
- Verify the version-pinned APK URL
- On the device, `CHECK FOR UPDATE` must report `0.2.38 (38)` as available
- Download and SHA-256 verification must complete before Android installation is launched

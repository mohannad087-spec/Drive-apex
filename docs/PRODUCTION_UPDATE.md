# DriveApex Production In-App Updates

## Target behavior

After the first production APK is manually installed on the vehicle, future `main` pushes publish a signed `DriveApex.apk` to the GitHub `latest` release. DriveApex checks that release from inside the app, compares `versionCode`, downloads the APK, and hands it to Android's package installer.

## One-time account setup

Android application updates require the same signing identity across versions. The release keystore must therefore be kept outside the repository and supplied to GitHub Actions as encrypted repository secrets.

Configure these four GitHub Actions secrets in the repository settings:

- `DRIVEAPEX_KEYSTORE_BASE64`
- `DRIVEAPEX_KEYSTORE_PASSWORD`
- `DRIVEAPEX_KEY_ALIAS`
- `DRIVEAPEX_KEY_PASSWORD`

Do not commit the `.jks` file or any password to the repository.

## First production installation

1. Run `Publish DriveApex Latest` after the four secrets are configured.
2. Confirm the workflow builds `app-release.apk` and publishes it as `DriveApex.apk` on the GitHub `latest` release.
3. Install that signed production APK manually on the vehicle once.
4. On Android 8+, allow DriveApex to install packages from this source when prompted.
5. From this point onward, use `CHECK FOR UPDATE` inside DriveApex.

## Every future code change

`git push main` -> `Publish DriveApex Latest` -> signed APK -> `latest` release -> in-app update check -> download -> Android installer.

The APK version is derived from the GitHub Actions run number, so version codes increase automatically for published runs.

## Current safety behavior

- If no latest release exists, manual `CHECK FOR UPDATE` reports that the update channel is not ready.
- If the release has no `DriveApex.apk`, the app does not attempt installation.
- If GitHub is unavailable, the app reports the failure for a manual check.
- Android remains the authority for package installation and signing compatibility.

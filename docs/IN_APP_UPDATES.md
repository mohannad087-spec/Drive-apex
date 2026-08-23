# DriveApex In-App Updates

DriveApex checks the public GitHub `latest` release when the app starts and from **CHECK FOR UPDATE**.

## Distribution contract

The release asset must be named:

`DriveApex.apk`

The release body must contain:

`versionCode: <integer>`

The updater downloads the newer APK and hands it to Android's package installer.

## Critical signing requirement

Every update APK must be signed with the **same signing certificate** as the installed DriveApex APK. Do not use GitHub-host-generated debug signing for production updates because ephemeral runners can produce different signing keys.

The release workflow should use a persistent private keystore stored in GitHub Actions Secrets. Never commit the keystore or passwords to the repository.

Recommended secrets:

- `DRIVEAPEX_KEYSTORE_BASE64`
- `DRIVEAPEX_KEYSTORE_PASSWORD`
- `DRIVEAPEX_KEY_ALIAS`
- `DRIVEAPEX_KEY_PASSWORD`

## Android installation policy

The app requests `REQUEST_INSTALL_PACKAGES`. Android may still require the user to allow DriveApex to install unknown apps. A fully silent update is only appropriate when the vehicle is managed in a way that grants the installer/device-owner privileges; the normal Android flow can require user action.

## Update flow

`GitHub latest release -> version check -> download APK -> FileProvider -> Android Package Installer -> upgrade existing DriveApex`

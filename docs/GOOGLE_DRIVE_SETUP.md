# Google Drive setup for Android releases

Chompass uses Google's official Android `AuthorizationClient` for Drive app-data sync. This is a device-side authorization flow: it does not require the Google Drive app, an HTTPS domain, a backend, or a client secret embedded in the APK.

## One-time Google Cloud setup

1. In the [Google Cloud Console](https://console.cloud.google.com/), create or select a project.
2. Enable the **Google Drive API**.
3. Configure the OAuth consent screen with the app name **Chompass** and a support email. For personal testing, add the Google account that will use the app as a test user if Google presents that option.
4. Create an **Android** OAuth client with package name `app.chompass` and the SHA-1 fingerprint of the release signing certificate.

The signed-release workflow prints the SHA-1 and SHA-256 fingerprints in its job log under **Print release certificate fingerprints**. Use the SHA-1 value for the Android client; fingerprints are not passwords or OAuth tokens.

The app does not need a `google-services.json` file for this flow. Google Play services matches the Android OAuth client using the installed package name and signing certificate.

## What the user sees

In Chompass, open Settings → Sync → Connect Google Drive. Android shows Google's account/consent UI. Approve the limited Drive app-data permission. The backup is stored in Drive's hidden `appDataFolder`, so it will not appear as a normal file in My Drive.

If the consent UI is unavailable or the release certificate is not registered, Chompass leaves the connection disabled and shows an authorization error instead of claiming that Drive is connected.

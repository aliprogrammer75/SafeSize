# Unified SafeSize workspace

- Native Compose app retains the existing Room database, dashboard, calculator and journal. New Online Workspace route opens the deployed SafeSize HTTPS app in an Android Custom Tab. Browser fallback is available.
- One web chart engine; web updates appear without rebuilding Android. Sign in to the same WordPress account and use workspace sync.
- Browser notifications are separate from native Android FCM: permission and device registration are required. No FCM configured.
- Native Room journal and web paper journal are distinct; no silent migration, deletion or upload of financial records.
- Build: JDK 17, Gradle 8.6, Android SDK 34. CI produces a debug artifact only. Production APK must use the existing application's original signing identity to install as an update; do not uninstall the current app just to install a test build.
- Acceptance: build, check chart/replay/risk calculator, authenticate, sync edits between two devices, test closed-tab notifications, disconnect/reconnect, rotate and resume, verify existing Room data.

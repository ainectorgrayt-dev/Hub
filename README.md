# Wi-Fi Finder and Hotspot (Android)
Open this folder in Android Studio (Koala or newer), let Gradle sync, plug in a phone with USB debugging, press Run.
Requires Android 10 or newer. Scans nearby networks, connects with a password you enter, then opens Hotspot settings to share it.
Android blocks apps from switching the main hotspot on, so that last step is done in system settings.

## Get an APK without a computer
1. Create a free account at github.com and make a new repository.
2. Upload every file from this folder (including the hidden .github folder) to it.
3. Open the Actions tab, run "Build APK", wait about 5 minutes.
4. Open the finished run, download "wifi-hotspot-apk", unzip it and tap app-debug.apk to install (allow "install unknown apps" when asked).

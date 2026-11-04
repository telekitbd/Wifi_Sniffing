# WiFi Radar

Rooted Android app that lists every device on the WiFi you are connected to, with name, IP and MAC address.

## Requirements
- Rooted phone (Magisk or KernelSU), Android 8.0+
- Connected to the WiFi you want to scan

## Build the APK
1. Push this project to a GitHub repo
2. Open **Actions → Build APK** (runs on every push)
3. Download `WifiRadar-debug` from the run's **Artifacts**, unzip, install `app-debug.apk`

## Use
1. Open the app, allow root when asked
2. Tap **Scan network** (5-10 seconds)
3. Tap **Copy MAC** on a card to copy its MAC to the clipboard
4. Long-press a card to copy name + IP + MAC
5. **Copy all** (top) copies the whole device list

## Device type detection
Each device is labelled Phone / Computer / Router / Printer / TV. Detection uses the device name, open ports, ping TTL and the MAC vendor (offline list in `res/raw/oui.txt`). A trailing `?` (e.g. `Phone?`) means a best guess. Devices with a Private MAC have no usable vendor, so they rely on name/ports.

## Frames lost
Every scan sends 10 test pings to each device and shows `Frames lost: 2/10 (20%)` and the average ping. Green = 0% lost, yellow = up to 10%, red = more. A busy device (downloading/streaming) or a weak signal usually shows more lost frames and higher ping. This is a hint, not an exact measure of internet use.

## Internet status
The top card shows **Internet: WORKING** (green) or **NO INTERNET** (red) for the whole WiFi, tested by pinging public servers through the WiFi interface. Each device card shows **ONLINE** (green) or **NO REPLY** (red, connected but not answering). Android cannot tell whether one single device has internet; if the whole WiFi has no internet, every device on it has none.

## Notes
- "Unknown" name: the device does not share its name on the network
- "Private MAC": the phone uses a random MAC for this WiFi
- Your own MAC is hidden by Android
- Only scan networks you own or have permission to scan

## Change package name
Edit `namespace` and `applicationId` in `app/build.gradle.kts`, rename the folders under `app/src/main/java/`, and update the `package` line at the top of each `.kt` file.

## Credits & Community
Stay connected and get new updates by joining our social media and community groups.

- Facebook Group: https://facebook.com/share/g/1JbRQgSuZz/
- Messenger Group (MAX): https://m.me/j/Abado-eMQJTWGYcV
- Telegram Group: https://t.me/+mU6PXP7hXR9hN2M1
- Proof / Verification: https://t.me/nayemroot
- YouTube Channel: https://youtube.com/@bengleraptor
- TikTok: https://tiktok.com/@naimbinharun

Thank you for being with us. Your support and cooperation mean a lot to us.

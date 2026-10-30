# Notif Relay

Forward notifications from chosen Android apps to your Mac. Nothing else.

The phone and Mac never talk to each other directly. Both make plain outbound HTTPS
connections to an [ntfy](https://ntfy.sh) server, so it works on networks that isolate
devices (university/hotel Wi-Fi), on mobile data, or with the two devices in different places.

```
Android NotificationListener ──AES-256-GCM──▶ POST ntfy.sh/<topic> ──stream──▶ Mac menu bar app ──▶ macOS notification
```

- **Private:** each message is encrypted on the phone with a 256-bit key that only your
  Mac and phone share (it travels in the pairing QR code). ntfy only stores ciphertext,
  under a random, unguessable topic name.
- **Missed messages:** ntfy caches messages for 12 hours. When the Mac wakes up or
  reconnects, it fetches whatever it missed. Messages older than 30 minutes go to the
  menu's Recent list without popping a banner.
- **Phone side has no persistent connection.** Android keeps the notification listener
  alive by itself. Each notification is a single HTTPS POST; if you're offline it's queued
  and retried (WorkManager) once the network is back.

## Limits

The free public ntfy.sh server allows **250 messages per day per IP**. That's fine if you
only sync a few apps. If you hit it, either pay for ntfy (Supporter tier, 2,500/day) or
[self-host ntfy](https://docs.ntfy.sh/install/) and set the server under
*Pair Phone… → Advanced* on the Mac, then rescan on the phone.

## Mac

Requires macOS 15+ and Xcode command-line tools.

```bash
mac/build.sh --install
```

Builds `Notif Relay.app`, copies it to `/Applications` and launches it. A bell appears in the
menu bar. On first launch the pairing window opens; later it's under **Pair Phone…**.
Allow notifications when macOS asks. Turn on **Launch at Login** from the menu.

## Android

Open `android/` in Android Studio and press Run, or from the command line (with the
Android SDK installed):

```bash
cd android && ./gradlew installRelease
```

Then on the phone:

1. **Grant** notification access (opens system Settings; enable Notif Relay).
2. **Scan** the QR code from the Mac's pairing window.
3. Toggle the apps you want synced.
4. **Send test notification** to check the whole path.

Some phones (Samsung, Xiaomi, OnePlus) kill background apps aggressively. If notifications
stop after a while, set Notif Relay's battery usage to *Unrestricted* in app settings.

## Layout

```
design/              icon sources (SVG): glyph.svg is the master; mac-icon.svg and
                     menubar.svg render to mac/Resources, Android uses vector drawables
mac/Sources/NotifRelay/
  App.swift          menu bar UI, pairing window with QR code
  AppModel.swift     ntfy subscription, reconnect on wake / network change
  Pairing.swift      topic + key generation, AES-GCM, payload format
  Notifier.swift     posts macOS notifications
android/app/src/main/java/dev/notifrelay/
  RelayListener.kt   NotificationListenerService: filter by app, extract text, de-dupe
  Relay.kt           encrypt + POST, WorkManager retry
  Store.kt           pairing + selected apps (SharedPreferences)
  MainActivity.kt    setup + app picker UI
```

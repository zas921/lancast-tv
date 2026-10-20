# LanCast TV

[中文说明](README.md) · [Download test APKs](https://github.com/zas921/lancast-tv/releases) · [MIT license](LICENSE)

A lightweight, native Java media receiver for Android 5.1+ televisions and set-top boxes. Install the APK on the television; compatible Android and iPhone video apps can discover it through standard DLNA and control media playback. No companion phone APK is required.

**Version 0.1.1 is a test release.** It has not been tested on the intended Damai television or verified against specific commercial video apps. This project does not implement AirPlay, Miracast, Google Cast, Lelink, DRM authorization, or screen mirroring. An app using a proprietary casting protocol may not discover this receiver.

## Features

- SSDP discovery and UPnP MediaRenderer v1 services: AVTransport, RenderingControl and ConnectionManager.
- Video / music playback, photo display, playback position, pause, stop, volume and mute.
- GENA state notifications, including retries after rejected callbacks and delivery of changes that occur while a previous notification is in flight.
- Remote-control-friendly TV interface, receiver naming, pairing code, QR code and cache cleanup.
- A local browser interface for sending photos, videos and music from either phone platform.
- No third-party Android runtime dependencies or analytics service.

## Use

Download an APK from [Releases](https://github.com/zas921/lancast-tv/releases), install it on the television and launch the receiver. Connect both devices to the same router. In a video app that supports standard DLNA, choose TV / Cast and select the displayed receiver name.

For local files, open the address or scan the QR shown on the television using the phone browser. Pair with the six-digit code, then send media. Files are uploaded to the television before playback. Each file and the total cache are limited to 1 GB; the library holds up to 40 items. Supported photo types are JPEG, PNG and WebP. Actual audio / video decoding depends on the television; H.264 + AAC in MP4 is a useful baseline.

The paired browser interface and standard DLNA are separate control paths. DLNA allows devices on the local network to control playback without a browser pairing code. Use a trusted LAN and do not expose the receiver port to the Internet. Online media is requested from the source server; browser uploads do not pass through a cloud service.

## Build

Install JDK 17, Android SDK Platform 35, Build Tools 34.0.0, and PowerShell 5.1 on Windows or PowerShell 7. Set `JAVA_HOME` and `ANDROID_HOME` / `ANDROID_SDK_ROOT`, then run:

```powershell
./build.ps1
```

The standalone SDK builder creates `dist/LanCast-TV-0.1.1.apk` and its checksum. It preserves the local development signing key in `build/lancast-debug.keystore`. Keep that key private and backed up if you need installable updates. CI APKs may use a different development key on each run. This is a sideloading test package targeting API 28, not a store-ready release.

Android Studio / Gradle configuration is also included; the standalone SDK builder is the primary verified build path.

## Test and contribute

```powershell
./test.ps1
./test.ps1 -Multicast
npm ci --ignore-scripts
npx playwright install chromium
npm test
python -m pip install -r requirements-test.txt
python tests/verify-qr.py
```

`-Multicast` requires a working LAN and is opt-in. Browser smoke tests use a simulated TV API and do not exercise Android decoding. QR verification compares every module against an independent implementation. See [the Chinese README](README.md#验证记录), [contribution guidelines](CONTRIBUTING.md), and [security reporting](SECURITY.md) for details.

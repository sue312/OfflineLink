# OfflineLink

OfflineLink is an Android peer-to-peer communication app for short-range offline scenarios. It lets nearby phones discover each other, connect without cellular data or internet access, and exchange messages, images, locations, voice clips, and live call audio.

## Highlights

- Offline peer discovery using Bluetooth scanning and advertising.
- Direct peer connection using Bluetooth L2CAP/RFCOMM transport.
- App-only device filtering, so search results show OfflineLink peers instead of every nearby Bluetooth device.
- Encrypted session framing for payload transfer.
- Live RSSI signal display after connection.
- Adaptive call audio path designed to favor quality on strong links and intelligibility on weak links.
- Lyra codec assets and native libraries included for experimental low-bitrate voice work.

## Use Cases

- Outdoor no-network communication.
- Field work where cellular coverage is unavailable or unreliable.
- Short-range team coordination between Android devices.
- Offline demos and testing of peer-to-peer communication behavior.

## Technical Overview

The app replaces Google Play Services Nearby Connections with a Bluetooth-first stack:

- BLE advertising and scanning are used for discovery.
- Bluetooth L2CAP CoC is preferred where supported.
- RFCOMM is retained as a compatibility path.
- A stable app-level signal id is included in BLE service data so connected peers can keep updating RSSI even when Android rotates BLE addresses.
- Payloads are framed and encrypted before transport delivery.

## Build

Prerequisites:

- Android Studio or Android SDK command-line tools.
- JDK compatible with the Gradle Android plugin.
- Android device with Bluetooth support for real-world testing.

Build a debug APK:

```powershell
.\gradlew.bat :app:assembleDebug
```

Run unit tests and build:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Device Testing

1. Install the APK on two Android phones.
2. Grant Bluetooth, nearby device, location, notification, and microphone permissions when requested.
3. On both phones, enable Visible.
4. Tap Search on one phone.
5. Select the discovered OfflineLink peer and accept the request on the other phone.
6. Confirm that the connected panel shows peer name and signal strength in dBm.

## Project Structure

```text
app/src/main/java/com/example/offlinelink/audio       Call audio, codecs, jitter buffer
app/src/main/java/com/example/offlinelink/chat        Chat, connection, and call state stores
app/src/main/java/com/example/offlinelink/crypto      Secure transport framing
app/src/main/java/com/example/offlinelink/transport   Bluetooth transport and protocol framing
app/src/main/java/com/example/offlinelink/ui          Compose UI and screen managers
app/src/main/assets/lyra                              Lyra model assets
app/src/main/jniLibs                                  Native Lyra libraries
```

## Current Status

This branch is focused on Bluetooth-only offline communication. Wi-Fi Direct and SoftAP transport experiments are not part of the current active path.

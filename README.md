<div align="center">
  <img width="96" alt="ic_launcher-playstore" src="assets/logo.png?v=2" />

# AutoCompressorEdge

A personal fork of [Compressor Edge](https://github.com/JoshAtticus/CompressorEdge), adding automatic detection of camera recordings so they can be compressed right after you shoot them — with no need to open the app.

<br>

![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
</div>

> [!IMPORTANT]
> This is an unofficial fork for personal use. For the original app, its full feature set, credits, support links and translations, see the upstream project: **[JoshAtticus/CompressorEdge](https://github.com/JoshAtticus/CompressorEdge)**.

---

## What this fork adds on top of Compressor Edge

### 📹 Camera recording detection
A background job watches for new videos recorded with the system camera app (no persistent service, battery-friendly — it wakes up only on a MediaStore change). As soon as a new recording is detected, you get a notification showing its size and asking if you'd like to compress it.

- Tapping the notification opens the app with the video already imported, ready to configure and compress as usual.
- Tapping one of the **Alta / Media / Bassa** (High / Medium / Low) quick-action buttons instead compresses the video **entirely in the background**, using that quality preset — no need to open the app at all.

### 🔔 Smarter background notifications
- The background compression notification shows a live progress bar while it runs.
- When it finishes, it shows a summary: original size → compressed size and the percentage saved, then automatically dismisses itself after 10 minutes.
- Output files default to `<original name>_<codec>.mp4` (e.g. `VID_2026-01-01_H265.mp4`).

### 🗑️ Automatic cleanup
When a video is compressed through the camera-detection flow, the result is saved back into the same folder as the original recording (typically `DCIM/Camera`), and the original file is deleted automatically once the compressed copy is safely saved. This requires granting the "All files access" permission, which the app will prompt for when you enable the feature in Settings → General → *Detect camera recordings*.

### 🇮🇹 Italian localization
Added a complete Italian translation (`values-it`), covering every string in the app, including the new camera-detection feature.

---

## Everything else

All other features, requirements, credits, support links, translations and the star history are maintained upstream and apply unchanged to this fork — see **[JoshAtticus/CompressorEdge](https://github.com/JoshAtticus/CompressorEdge)**.

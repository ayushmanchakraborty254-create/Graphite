<div align="center">
  <img src="app_logo_512.png" alt="Graphite Logo" width="130" height="130" style="border-radius: 28px;" />
  <h1>Graphite — The Best Open-Source Note-Taking App</h1>
  <p><strong>A fluid, privacy-first handwriting, stylus precision, PDF annotation, and digital notebook app for Android.</strong></p>

  <p>
    <a href="https://github.com/ayushmanchakraborty254-create/Graphite/releases/latest"><img src="https://img.shields.io/badge/Download-APK-orange?style=for-the-badge&logo=android" alt="Download APK" /></a>
    <img src="https://img.shields.io/badge/Platform-Android-green?style=for-the-badge&logo=android" alt="Android" />
    <img src="https://img.shields.io/badge/Language-Kotlin-purple?style=for-the-badge&logo=kotlin" alt="Kotlin" />
    <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-blue?style=for-the-badge" alt="Jetpack Compose" />
    <img src="https://img.shields.io/badge/License-MIT-lightgrey?style=for-the-badge" alt="MIT License" />
  </p>
</div>

---

## ⚡ What is Graphite?

**Graphite** is an open-source, next-generation note-taking and digital journaling application built specifically for Android tablets, foldables, and smartphones. Designed for students, researchers, developers, and creative thinkers, Graphite combines hardware stylus accuracy with local-first privacy, AI voice transcription, and cross-device sync.

Whether you're annotating 100-page academic lecture PDFs, writing handwritten mathematical equations with an active stylus pen, or recording lecture audio alongside rich text boxes, Graphite offers an ultra-fluid paper-like experience with zero latency.

---

## ✨ Key Features

* ✍️ **Ultra-Low-Latency Inking & Stylus Support**: Sub-millisecond stroke response with pressure sensitivity, tilt support, and palm rejection for Samsung S-Pen, active styluses, and capacitive pens.
* 📄 **Native PDF Markup & Document Annotation**: Import PDF, DOCX, and PPTX files directly into notebooks. Highlight, scribble, and annotate seamlessly without destroying document layout.
* 🎙️ **Smart Voice-to-Text & Live Dictation**: Rapid speech recognition powered by on-device dictation and Gemini 2.5 Flash / Whisper AI, with automatic sentence capitalization and speech sync.
* 🔤 **Dynamic Rich Text Boxes**: Add movable, resizable text boxes with custom typography (Sans, Serif, Monospace, Cursive), font colors, underline, bold, and italics anywhere on the canvas.
* 🎨 **Curated Notebook Covers & Templates**: 60+ curated cover designs, Unsplash online collections, auto-cropping gallery covers (3:4 ratio), and custom paper templates (Ruled, Grid, Dotted, Blank).
* 🔒 **Local-First & Cloud-Enabled**: Your notes stay on your device. Optionally back up notebooks to Google Drive or synchronize seamlessly across devices via Supabase.
* 📐 **Cross-Device Proportional Scaling**: Canonical canvas scaling ensures your notes, markings, and handwritten strokes look identical on compact phones and large 14-inch tablets.

---

## 📥 Download and Install

Download the latest release APK directly to your Android device:

👉 **[Download Latest Graphite APK](https://github.com/ayushmanchakraborty254-create/Graphite/releases/latest/download/app-release.apk)**

1. Download the `.apk` file above on your Android tablet or phone.
2. Tap the file in your notification bar or file manager.
3. Allow "Install unknown apps" if prompted, and enjoy Graphite!

---

## 🛠️ Building from Source

To compile and run Graphite locally:

```bash
# 1. Clone the repository
git clone https://github.com/ayushmanchakraborty254-create/Graphite.git
cd Graphite

# 2. Build the debug APK
./gradlew assembleDebug

# 3. Install directly to a connected Android tablet or emulator
./gradlew installDebug
```

---

## 🛡️ Privacy & Permissions

Graphite operates with a strict **local-first privacy architecture**:
* All handwriting, drawings, and notebook pages are stored locally on your device.
* Google Drive integration uses the minimal `drive.file` scope, strictly accessing only the backup files created by Graphite.
* For more information, please read our [Privacy Policy](PRIVACY.md).

---

## 📄 License

Graphite is proudly released under the **[MIT License](LICENSE)**. Contributions, pull requests, and feature suggestions are welcome!

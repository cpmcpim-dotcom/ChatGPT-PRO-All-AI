# ChatGPT PRO All AI — Android starter project

A no-subscription, no-ad Android AI client with a hybrid provider design.

## Included
- Compose Android app with Chat, Tools, Files and Settings screens
- Provider selector: OpenAI, DeepSeek, Gemini and local Ollama
- OpenAI-compatible chat completion calls for OpenAI and DeepSeek
- Gemini `generateContent` integration
- Local Ollama OpenAI-compatible endpoint support
- API keys encrypted on-device with Android Keystore (AES-GCM)
- Android speech-to-text intent and text-to-speech replies
- Basic text/CSV file import and send-to-AI workflow
- Web search shortcut that opens a browser
- No ads, no account system, no subscription paywall

## Important cost and access note
The APK itself can be free and contain no subscription. This does **not** make commercial hosted AI inference free. OpenAI, DeepSeek and Gemini access requires their own API keys and is subject to each provider's pricing, quotas and terms. ChatGPT Plus/Pro subscriptions do not include API credits and cannot be embedded or transferred into this app. For genuinely no-per-call-cost use, run a compatible local model with Ollama on your own computer/server and connect the phone over the same trusted network. Local model performance depends on your hardware.

## Build with Android Studio
1. Install Android Studio (current stable) and Android SDK Platform 35.
2. Open this folder (`ChatGPT_PRO_All_AI`) as an existing Gradle project.
3. Allow Android Studio to install the requested Gradle and SDK components and sync.
4. Use JDK 17 for Gradle (Android Studio's bundled JBR 17 is suitable for AGP 8.7.x).
5. Connect an Android phone with USB debugging enabled, or create an emulator.
6. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
7. The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

Command line after Gradle is installed/configured:

```bash
gradle assembleDebug
```

For a release APK, create a signing key in Android Studio (**Build → Generate Signed Bundle / APK**) and keep the keystore private. Never commit API keys or signing credentials.

## Configure providers
Open the app → Settings → enter the relevant API key → Save settings. Then return to Chat and select that provider. Use provider model names accepted by the provider account. Ollama's URL must be reachable from the Android device; `10.0.2.2` is the Android emulator alias for the host machine, not a universal phone address.

## Current limitations / roadmap
This is a functional starter app, not a clone of the proprietary ChatGPT Android app. It does not unlock ChatGPT Pro, private OpenAI models, or server-side ChatGPT tools. PDF/DOCX extraction, true OCR, image generation, image uploads/vision, browsing with citations, cloud sync, chat persistence/export, multi-user admin, and robust local on-device inference need additional implementation and/or provider-specific services. The Files screen currently previews text-like files; binary PDF/DOCX extraction is not implemented. Web search opens a browser rather than returning search results inside the chat.

## Security
- API keys are encrypted using an AES-GCM key stored in Android Keystore.
- Local Ollama HTTP is enabled for LAN development. Use a trusted network; do not expose an unauthenticated Ollama endpoint to the public internet.
- For production, route provider calls through a secured backend if you need centralized key management, abuse controls or shared users. Do not ship a developer-owned secret API key inside the APK.

## Build from an Android phone (Termux-assisted)

**Important:** Termux on an ARM64 Android phone is not a reliably supported host for the full Android Gradle toolchain. Android build tools and AGP host binaries can be x86_64/glibc-specific. The project includes a GitHub Actions workflow so you can use Termux to prepare/upload the source and let a Linux x86_64 runner compile the APK. This does not require a PC.

1. Install Termux from F-Droid or the official Termux GitHub releases. Run:

   ```bash
   pkg update && pkg upgrade -y
   pkg install git gh unzip -y
   termux-setup-storage
   ```

2. Extract `ChatGPT_PRO_All_AI_Source.zip` into a folder. If it is in Downloads:

   ```bash
   cd ~/storage/downloads
   unzip ChatGPT_PRO_All_AI_Source.zip
   cd ChatGPT_PRO_All_AI
   ```

3. Create a new empty repository on GitHub, then authenticate in Termux:

   ```bash
   gh auth login
   git init
   git add .
   git commit -m "Initial ChatGPT PRO All AI Android project"
   git branch -M main
   git remote add origin https://github.com/YOUR_GITHUB_USERNAME/YOUR_REPOSITORY.git
   git push -u origin main
   ```

   Replace the two uppercase placeholders with your own GitHub username and repository name.

4. On GitHub, open the repository → **Actions** → **Build Android APK** → **Run workflow** (or wait for the first push build to finish).
5. Open the completed workflow run → **Artifacts** → download `ChatGPT-PRO-All-AI-debug-apk`. Extract it on your phone and install `app-debug.apk`. If Android blocks installation, allow your file manager/browser to install unknown apps.

The workflow builds a **debug APK**. It is suitable for testing; a public release should use a privately managed signing key. Never put provider API keys in the source repository.

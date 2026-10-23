# AzCode (Android)

[简体中文](zh-CN.md) | [日本語](ja.md) | [한국어](ko.md) | [Français](fr.md) | [Deutsch](de.md) | [Русский](ru.md) | [Español](es.md)

AzCode is an on-device automation assistant for Android. Describe a task in natural language and the built-in agent loop reads the screen, taps controls, swipes and runs commands through the accessibility service until the task is done. All data stays on your phone, with no cloud sync.

- App name: **AzCode**
- GUI form label: **magic**
- CLI form label: **Listen**
- Current version: **1.9.15** (versionCode 70)
- Requirements: Android 8.0+ (minSdk 26), targetSdk 34

AzCode runs standalone, or exposes a capability bridge bound only to `127.0.0.1:8848` so the Listen CLI or other clients can reuse the same device capabilities over `adb forward`.

## Features

| Capability | Description |
| --- | --- |
| Natural language tasks | Chat input, multi-step agent planning, live tool call display |
| Screen control | Read nodes, tap by text or coordinates, swipe, system navigation, Unicode text input |
| App launching | Launch by package or app name with shell and accessibility fallbacks |
| Command execution | Termux, built-in shell, Shizuku and Root channels selected by privilege mode |
| Multi-model support | Multiple provider accounts, OpenAI-compatible, Anthropic and Gemini protocols |
| Thinking depth | Off, fast, standard and deep levels with per-model parameter compatibility |
| Sessions | Separate sessions, history drawer, auto titles, context continuation and compaction |
| Attachments | Images, audio, PDF, Office, text and code |
| Voice input | Switchable offline system recognition and online Xunfei recognition |
| Floating assistant | Default digital assistant entry, transparent glow, in-page questions and keyboard input |
| Long-term memory | Sensitive tokens, habits, methods and local personalization |
| Skills and plugins | Built-in skills, custom skills, GitHub SKILL.md install, popular plugin scan |
| GitHub | Read and write files, browse branches and commits, manage issues and pull requests |
| Image generation | Multi-provider text-to-image with inline preview and save |
| Office documents | Generate docx / xlsx / pptx via GenOffice MCP and preview them |
| Input method | Built-in AzCode IME as a fallback for Unicode text writing |
| Capability bridge | Loopback HTTP API and the Listen CLI |
| Logs | Crash and runtime logs on disk, viewable, copyable and clearable in Settings |

## Quick start

### Install

Download an APK from the `android-v*` GitHub Releases, or grab the `azcode-magic-apk` artifact from GitHub Actions. Debug and release builds share the same in-repo signing key, so they can be installed over each other.

### First-time setup

1. Open Settings > Device capabilities > Accessibility and enable **AzCode Screen Control** in system settings.
2. Go to Settings > Model management, add and enable a provider, and enter an API Key. Base URL and model can use preset templates.
3. Return to the main screen and confirm the active model in the model picker.
4. Type a task and send it, for example "Open Settings and find the Android version".

### Permissions

| Permission | Purpose |
| --- | --- |
| Accessibility service | Read screen, tap, swipe, system navigation, text writing |
| Microphone | Voice input and floating assistant |
| Notifications | Task completion and question notifications |
| Display over other apps | Floating assistant glow |
| Camera | Photo attachments |
| Termux RUN_COMMAND | Run full Linux commands through Termux |
| Shizuku | Run system-level commands as adb |

### Build from source

You need JDK 17 and the Android SDK. The repository does not include `gradle-wrapper.jar`, so use the system `gradle`:

```bash
gradle assembleDebug
gradle assembleRelease
```

After you push code, `.github/workflows/android.yml` builds Debug and Release APKs on GitHub Actions. Pushing an `android-v*` tag creates a GitHub Release.

## Device capabilities and command execution

| Mode | Channel | Identity | Requirement | Timeout |
| --- | --- | --- | --- | --- |
| NORMAL + Termux | Termux `RUN_COMMAND` | Termux uid | Termux installed, RUN_COMMAND granted, `allow-external-apps=true` | 90 s |
| NORMAL + built-in shell | `/system/bin/sh -c` | App uid | None | 60 s |
| SHIZUKU | Shizuku binder | adb uid 2000 | Shizuku running and authorized | 60 s |
| ROOT | `su -c` | uid 0 | Device rooted | 60 s |

NORMAL mode tries Termux first and falls back to the built-in shell. Accessing other apps' private directories or changing system settings requires Shizuku or Root.

## Local data and storage

AzCode uses no SQLite, Room or server database. Its data layer is app-private `SharedPreferences` plus JSON files:

| Path | Content |
| --- | --- |
| `SharedPreferences` | Model providers, session cursor, skills, command memory, GitHub / GenOffice config, speech and privilege mode |
| `files/sessions/<id>.json` | One session's chat history and agent context |
| `files/memory.json` | Long-term memory entries |
| `files/logs/YYYY-MM-DD.log` | Daily rotating logs, up to 10 files, 512 KB per file |
| `files/genoffice/` | Generated Office documents |
| `cache/az_images/`, `cache/captures/` | Image cache and temporary photo files |

Sessions are written via temp file plus rename. Agent context keeps at most 40 messages, with older content folded into a summary. Uninstalling the app deletes all local data.

## Capability bridge and the Listen CLI

The bridge listens only on `127.0.0.1:8848`, is not reachable from the internet and has no authentication. Remote access requires `adb forward`.

| Method | Path | Description |
| --- | --- | --- |
| GET | `/health` | Accessibility, Shizuku, Root and privilege mode status |
| GET | `/screen` | Visible screen nodes, up to 200 |
| POST | `/tap` | `{"x":123,"y":456}` or `{"text":"OK"}` |
| POST | `/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"duration":300}` |
| POST | `/global` | `{"action":"back"}`, supports back / home / recents / notifications |
| POST | `/shell` | `{"cmd":"pm list packages"}`, run under the current privilege mode |
| POST | `/notify` | `{"title":"..","body":".."}` |
| POST | `/agent` | `{"task":"..","session":"optional id"}`, returns `answer` and `events` |

With a phone connected over adb:

```bash
curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/main/tools/install.sh | bash
listen run "Open Settings and find the Android version"
listen screen
listen tap --text "OK"
```

## Privacy and security

- Model API keys, GitHub tokens, GenOffice tokens and Xunfei credentials are stored only in app-private storage; the app does not read environment variables.
- Local personalization snapshots are generated on-device and never injected into cloud conversations.
- The bridge binds to loopback only; remote access relies on adb forwarding.
- Online Xunfei recognition sends audio to the Xunfei open platform; built-in system recognition prefers on-device offline capability.
- Shizuku and Root can run system-level commands and mistakes can damage the device or data. Use them only on devices you own or are explicitly authorized to operate.

## Known limitations

- Screen reading and UI control depend on the accessibility service, which some vendors restrict in the background.
- System-level shell access depends on Shizuku or Root; NORMAL mode is limited by the app sandbox.
- Audio and image input depend on the selected model's multimodal capability.
- Image generation depends on provider APIs, which differ in supported sizes and counts.
- There is no cloud sync; data is removed with the app.

## Links

- Full documentation: root `README.md`
- Product overview: `PRODUCT.md`
- Design system: `DESIGN.md`
- CLI tools: `tools/listen` and `tools/install.sh`

## License

MIT. The accessibility and Shizuku execution modules are adapted from [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android) (MIT). Shizuku API comes from [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API).

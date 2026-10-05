# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

rawGram: a developer-oriented fork of Telegram Android (base 12.10.5, package `dev.rawgram.app`, `.beta` for debug).
GPLv3 (see `CREDITS.md`; code/ideas ported from Nagram/NekoX/exteraGram/AyuGram). `RAWGRAM.md` is the user-facing overview.

API credentials are never committed: `RAWGRAM_API_ID` / `RAWGRAM_API_HASH` come from `local.properties` (git-ignored) or
environment variables.

## Branches

- `rawgram` — the public branch (pushed to `origin`).
- `rawgram-plugins` — the exteraGram plugin engine (Chaquopy + aliuhook/LSPlant, CMake 3.31.6, Java 17, Python 3.11 + Cython
  on the host). Lives in a separate clone at `../telegram-plugs`; **never push it to the public repo**. Bring changes over
  with `git fetch origin rawgram && git merge origin/rawgram` there. The settings hub (`RawgramSettingsActivity.buildRows`)
  has the «Плагины» category only on that branch.

## Build and install (Windows)

There is no `gradlew.bat`; run the wrapper jar directly with JDK 21 (JDK 25 breaks `buildSrc`):

```powershell
$env:JAVA_HOME='C:\Users\aleks\.jdks\jbr-21.0.11'
& "$env:JAVA_HOME\bin\java.exe" -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :TMessagesProj_App:assembleAfatDebug --console=plain
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r TMessagesProj_App\build\outputs\apk\afat\debug\app.apk
```

- Preferred: `..\rawgram-builds\build.ps1` (same build + install, and archives every successful APK as
  `buildNNN_<date>_<commit>[-dirty].apk` with a `.patch`/`.new-files.zip` for uncommitted builds; `-List` shows the
  archive, `-Install N` rolls back to build N, `-NoInstall`, `-Repo ..\telegram-plugs -Tag plugins`).
- Compile-only check (Java errors, no APK): `:TMessagesProj:compileDebugJavaWithJavac`.
- Local, **never committed** edits that keep builds fast: `abiFilters "arm64-v8a"` in `TMessagesProj/build.gradle`
  (`externalNativeBuild.cmake`) and in the `afat` flavor of `TMessagesProj_App/build.gradle`. Stage commits with
  `git add TMessagesProj/src ...` and leave these two files out.
- A normal Java-only change builds in ~4–6 min; switching branches (or running the plugins build in parallel) invalidates the
  native `tmessages.49` build and costs 15–25 min. Avoid concurrent builds in both clones.
- There is no unit-test suite in use; verification is done on a device.

## Device testing conventions

- Test in Saved Messages (⋮ → Избранное) with the inline query `@HowYourBot :`; never send messages to other chats.
- Launch with `adb shell am start` (not `monkey` — it re-enables auto-rotate on the phone). Don't launch the app while the
  user is using it; just install and say so.
- HyperOS kills background processes; push delivery relies on the keep-alive service (`RawPushDiag`), not FCM.

## Architecture

All rawGram code is in `TMessagesProj/src/main/java/org/telegram/rawgram/`. Upstream Telegram files only get small hooks,
marked `rawGram` in comments, so upstream merges stay manageable. Keep it that way: logic in rawgram classes, one-line calls
in Telegram classes.

- **Hook facades**: `RawChatHooks` owns everything rawGram adds to `ChatActivity` behind a `Host` interface that
  ChatActivity implements (inline tray, reroll host, inline-result raw viewer, «Подробности» submenu, bot-button sheet,
  keyboard/emoji-panel folding, chat UI actions via `ui()` → `RawChatUiActions`). `RawMentionsButtons` does the same for
  `MentionsContainerView`. Other screens call static helpers (`RawProfileRaw`, `RawStickerSetRaw`, `RawDialogRaw`,
  `RawIdLookup`, `RawPaste`, `RawIcons`, ...).
- **Raw data viewing**: `TLDumper` (reflection dump of TL objects → JSON / flat fields; `fieldsOf` is shared),
  `RawObjectSheet` (tabs, JSON / Поля / «Дерево (beta)» via `RawTreeView`, actions), `RawSyntax` (highlighting).
  Most "Raw" entry points open a `RawObjectSheet`.
- **Config**: each area has its own SharedPreferences-backed static config with cached values —
  `RawgramConfig` ("rawgram": feature switches, dev tools, delays), `RawUiConfig` ("rawgram_ui"), `RawChatUiConfig`
  ("rawgram_chat_ui"), `RawClassicUi` ("rawgram_classic"), `RawMotion` ("rawgram_motion"). Defaults reproduce stock Telegram
  unless the feature is a rawGram addition.
- **Settings UI**: `RawgramSettingsActivity` is a hub → `RawUiSettingsActivity` (Внешний вид, live previews built from real
  Telegram views), `RawChatUiSettingsActivity` (Чаты, chat preview), `RawDevSettingsActivity` (Инструменты разработчика).
  Options that need a restart call `RawUiSettingsActivity.showRestartNotice`.
- **Diagnostics**: `RawRequestLog` hooks `ConnectionsManager.sendRequestInternal` (cheap flag check when off);
  `RawCrashLog` + `RawExitReports` + `RawTombstone` record Java crashes and import native crashes/ANRs from
  `ApplicationExitInfo` (tombstone protobuf decoded); `RawReportExport` shares/forwards/saves reports.
- **Motion**: `RawMotion` gates all rawGram-only animations (switch + system animator setting + power saver); it must not
  change stock Telegram animations.
- **Inline tooling**: `RawInlineResultViewer`, `RawReroll*` (reroll until a pattern matches), `RawInlineStash` (per-account
  parked results), `RawAbsorb` (gooey park animation), `RawSend` (send chooser).

## Porting from Nagram

A Nagram checkout lives at `../nagram` (GPLv3, credited in `CREDITS.md`). Before inventing a UI feature, grep it there
(`NaConfig.kt` / `NekoConfig.java` for the switch, then the hook site in the same upstream file) and port its behaviour
and look (strings, icons such as the `*_solar` drawables, popup style), adapted to rawGram's hook style: the logic in a
`rawgram` class, one-line calls in the Telegram file, the switch in the matching `Raw*Config`.

## Repo quirks

- Line endings: the index is LF; `core.autocrlf=true` here, so "LF will be replaced by CRLF" warnings are harmless.
  `TMessagesProj/src/main/assets/**` and `res/raw/**` are `-text` in `.gitattributes` (theme files break with CRLF).
- When committing from PowerShell, write the message to a file and use `git commit -F` (quoting breaks `-m`); avoid
  `Set-Content`/`Out-File` defaults that add a BOM.
- minSdk is 21: avoid API 26+ conveniences like `ThreadLocal.withInitial` without a version check.

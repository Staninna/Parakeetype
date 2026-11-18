# Parakeetype: droid fork

A personal fork of theScrabi/Parakeetype, maintained as a droid app: modified the Android way and
shipped as signed GitHub releases. The full conventions and workflows (change, sync, upstream PR, build,
release) live in the droid skill: load it with `/droid` before any branch, sync, signing or release
work. The skill comes from the claude.ai account; when it isn't available, the ground rules below
are enough to work safely.

**Read `AGENTS.md` before touching code.** It is upstream's guide to this app (architecture, build,
tests, privacy constraints) and wins over these rules on everything except branches, releases and
signing keys.

## This repo

- `origin` is the fork (Staninna/Parakeetype), `upstream` is the original (theScrabi/Parakeetype).
- `my-main` holds all fork work and is the GitHub default branch. `main` mirrors `upstream/main`.
- Install mode: replace. The fork keeps upstream's application ID (`org.schabi.parakeetype`).
- Release build: `:app:assembleRelease`, JDK 21. `.github/workflows/release.yml` is the only release
  path; any other release workflow `AGENTS.md` or the docs mention belongs to upstream.

## Ground rules

- **Commit to `my-main`**, directly or through a short-lived branch merged into it. Upstream reaches
  `my-main` by merge. `my-main` is published, so its history only grows: merge, never rebase.
- **`main` only fast-forwards from `upstream/main`.** If it has diverged, stop and ask.
- **A push to `my-main` is a release.** CI tags it `v<versionName>-fork.<n>` and publishes signed
  APKs to users. Push only when the user asks and the work is verified. Pushes that only touch the
  `paths-ignore` list in `release.yml` (docs, `*.md`) or carry `[skip ci]` don't release.
- **Cloud sessions** (no `~/code/android-apps/release-keys` folder) have no signing keys, but CI
  signs releases with the secrets on GitHub, so they work like the laptop: commit on `my-main` and
  push it when the work is verified (the user allows this, for subagents too). If the environment
  has no Android SDK, install one in `/opt/android-sdk` with the command-line tools from
  `dl.google.com` and build there; if that's impossible, say so and don't push. If the commit email
  isn't a noreply address, stop and ask.
- **Verify before committing:** build the debug variant and run the unit tests. Before pushing, also
  build the minified release task above: R8 problems only show up there.
- **Tags:** only `-fork.<n>` tags, so they never clash with upstream's. Pushed tags stay where they
  are; a failed release is fixed by pushing a fix to `my-main`.
- **Signing keys are hands-off.** The key lives outside the repo; only the droid scripts
  (`signing-key.sh`, `build-signed.sh`) touch it. Never read, print or create keystores (`*.jks`,
  `*.keystore`, `keystore.properties`) or anything in the release-keys folder.
- **Commit identity:** the GitHub noreply address in the repo's local git config. Before committing,
  confirm `git config --local user.email` ends in `@users.noreply.github.com`.
- **Small, focused changes** in the app's own style and architecture.

## Fork additions

`AGENTS.md` stays exactly as upstream has it; the fork's own code is described here. Keep fork
code in fork-owned files and leave only one-line hooks in upstream files, so syncs stay clean.

| Where | What |
|---|---|
| `bubble/` | Optional floating dictation bubble over other keyboards (accessibility service). `BubbleAccessibilityService` decides when to show it and runs a segmented `RecognitionSession`; `FieldFinder` finds the keyboard and the focused field (structure and focus only, never field text); `BubbleWindow` is the overlay window (docking, keyboard push-up, drag); `TranscriptInserter` inserts via `ACTION_SET_TEXT` with a clipboard + `ACTION_PASTE` fallback; `TextSplice` and `BubblePlacement` hold the pure logic (unit-tested); `BubbleSetupItem` is the home-screen row. |
| `res/values*/strings_bubble.xml` | All fork strings, in all 25 locales. Never add fork strings to upstream's `strings.xml`. |
| `ime/KeepScreenOn.kt` | Keeps the screen on while the keyboard dictates (hook in `ParakeetypeInputMethodService`). The voice-input sheet does the same in `VoiceInputActivity`. |
| `RecognitionSession.Listener.onEndpointed`, `KeyboardViewModel.onEndOfSpeechDetected` | A `HapticFeedbackConstants.CONFIRM` tick when end-of-speech detection stops instant mode or the voice-input sheet on its own. |

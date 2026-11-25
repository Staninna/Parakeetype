#!/usr/bin/env bash
# End-to-end test of voice note transcription on a connected phone, without WhatsApp.
#
# Speaks a sentence with ffmpeg's flite voice, encodes it as Ogg Opus (what WhatsApp sends),
# pushes it into Parakeetype's test folder and posts a WhatsApp-style "🎤 Voice message (0:05)"
# notification from adb's shell. Parakeetype treats that like a WhatsApp voice note
# (notes/VoiceNotes.kt TEST_PACKAGE) and should post the transcription as a notification.
#
# Needs: adb with one phone connected (or ANDROID_SERIAL set to pick one), ffmpeg with libflite
# and libopus, and Parakeetype set up on the phone (home screen row says "Voice note
# transcription on").
#
# Usage: devtools/voice-note-test.sh ["sentence to speak"]
set -euo pipefail

text="${1:-Hello, this is a test voice note. Please call me back tomorrow at nine.}"
pkg=org.schabi.parakeetype
folder="/sdcard/Android/media/$pkg/Test Voice Notes/test"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
name="PTT-$(date +%Y%m%d)-TEST$(date +%H%M%S).opus"

# flite's text option can't hold ':' or quotes; keep the sentence plain.
spoken="$(printf '%s' "$text" | tr -d ":'\"\\\\")"
ffmpeg -hide_banner -loglevel error -f lavfi -i "flite=text='$spoken'" \
    -ac 1 -ar 16000 -c:a libopus -b:a 24k -f ogg "$work/$name"
seconds="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$work/$name" | cut -d. -f1)"
length="$((seconds / 60)):$(printf '%02d' $((seconds % 60)))"

adb shell "mkdir -p '$folder'"
adb push "$work/$name" "$folder/$name" >/dev/null
adb shell "touch '$folder/$name'"

since="$(adb shell date +'%m-%d\ %H:%M:%S.000')"
echo "Posting a test voice message ($length): $spoken"
adb shell "cmd notification post -S messaging --conversation 'Voice note test' \
    --message 'Tester:🎤 Voice message ($length)' voice-note-test 'Voice message'" >/dev/null

# The listener logs one line when it is done, either way.
result="$(timeout 90 adb logcat -v brief -T "$since" -s VoiceNoteListener:* \
    | grep -m1 -E 'Transcribed|Skipped|failed|off|unavailable|No all-files' || true)"
echo "Listener: ${result:-nothing within 90 s (is notification access on?)}"

key="$(adb shell cmd notification list | grep "|$pkg|" | grep -v parakeetype_inference | tail -1 || true)"
if [[ -n "$key" ]]; then
    echo "Notification text:"
    adb shell cmd notification get "'$key'" | grep -m1 -E 'android\.text=|android.bigText=' || true
fi

adb shell "rm -f '$folder/$name'"
[[ "$result" == *Transcribed* ]]

#!/usr/bin/env bash
# Word error rates of the voice note pipeline on bad-mic recordings, on this computer.
#
# Speaks test sentences with ffmpeg's flite voices, degrades them the way poor phone mics
# and messengers do (noise, muffling, echo, clipping, low level, low bitrate), encodes them as
# Ogg Opus like WhatsApp and decodes them back to 48 kHz PCM like the phone's decoder. Then
# runs VoiceNoteBenchTest, which prints the word error rate of each pipeline per condition.
#
# Needs: ffmpeg with libflite and libopus, and the model (devtools/fetch-test-model.sh).
#
# Usage: devtools/voice-note-bench.sh [output dir]
set -euo pipefail

out="${1:-$(mktemp -d)}"
mkdir -p "$out"
: > "$out/refs.tsv"

sentences=(
  "Hey, I just left the office and I will be home a bit later than planned tonight."
  "Could you pick up some bread and milk from the shop on your way back?"
  "The meeting with the landlord got moved to Thursday afternoon, so keep that free."
  "I called the garage and they said the car will not be ready before the weekend."
  "Do not forget that your sister is coming over for dinner on Sunday evening."
  "Okay so here is the plan for the trip. We leave early in the morning and drive straight to the coast. Your brother will meet us at the hotel around lunch time. After that we can walk along the beach, and in the evening we have a table at the fish restaurant near the harbour. Let me know if that works for you, and do not forget to bring a warm jacket because it can get cold at night."
)
voices=(slt awb rms)

# name#filter applied to the 48 kHz speech#opus bitrate
conditions=(
  "clean#anull#24k"
  "lowbitrate#anull#6k"
  "noisy#NOISE0.04#16k"
  "verynoisy#NOISE0.1#16k"
  "muffled#lowpass=f=1200,lowpass=f=1200#16k"
  "quiet#volume=-35dB#16k"
  "echo#aecho=0.8:0.7:60|130|210:0.5|0.35|0.25#16k"
  "clipped#volume=20dB,asoftclip=type=hard#16k"
  "cheapmic#highpass=f=400,lowpass=f=2800,aecho=0.8:0.6:50|110:0.4|0.3,NOISE0.03#10k"
)

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for s in "${!sentences[@]}"; do
  text="${sentences[$s]}"
  spoken="$(printf '%s' "$text" | tr -d ":'\"\\\\")"
  for voice in "${voices[@]}"; do
    ffmpeg -hide_banner -loglevel error -y -f lavfi -i "flite=text='$spoken':voice=$voice" \
      -af "aresample=48000,apad=pad_dur=0.4,adelay=300:all=1" -ac 1 "$work/speech.wav"
    for c in "${conditions[@]}"; do
      IFS='#' read -r name filter bitrate <<<"$c"
      if [[ "$filter" == *NOISE* ]]; then
        level="${filter##*NOISE}"
        pre="${filter%NOISE*}"; pre="${pre%,}"; [[ -z "$pre" ]] && pre=anull
        graph="[0]$pre[s];anoisesrc=c=pink:a=$level:r=48000[n];[s][n]amix=inputs=2:duration=first:normalize=0"
      else
        graph="[0]$filter"
      fi
      base="${name}_${voice}_s$s"
      ffmpeg -hide_banner -loglevel error -y -i "$work/speech.wav" -filter_complex "$graph" \
        -ac 1 -ar 48000 -c:a libopus -b:a "$bitrate" -application voip -f ogg "$work/$base.opus"
      ffmpeg -hide_banner -loglevel error -y -i "$work/$base.opus" -f s16le -ac 1 -ar 48000 "$out/$base.pcm"
      printf '%s\t%s\n' "$base.pcm" "$text" >> "$out/refs.tsv"
    done
  done
done
echo "Bench set: $(wc -l < "$out/refs.tsv") recordings in $out"

cd "$(dirname "$0")/.."
PARAKEETYPE_VOICE_BENCH_DIR="$out" ./gradlew :app:testDebugUnitTest \
  --tests 'org.schabi.parakeetype.notes.VoiceNoteBenchTest' --rerun -i \
  | grep -E '^\[|^condition|^[a-z]+ +[0-9-]|^ALL' || true

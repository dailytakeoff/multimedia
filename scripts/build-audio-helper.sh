#!/usr/bin/env bash
# Builds the small Windows audio helper (captures the sound of one program) from native/mmaudio.cpp.
# The result is put into the mod's resources; it is NOT stored in the repository.
# Requires MinGW-w64 (Ubuntu: apt install g++-mingw-w64-x86-64). The build is reproducible (no timestamp).
set -euo pipefail
cd "$(dirname "$0")/.."
out=src/client/resources/multimedia/mmaudio.exe
mkdir -p "$(dirname "$out")"
x86_64-w64-mingw32-g++ -O2 -s -static -Wl,--no-insert-timestamp \
  native/mmaudio.cpp -o "$out" -lole32 -luuid -lwinmm
sha256sum "$out"
# Remote-control helper (replays mouse/keyboard events from allowed viewers), see native/mminput.cpp.
input=src/client/resources/multimedia/mminput.exe
x86_64-w64-mingw32-g++ -O2 -s -static -Wl,--no-insert-timestamp \
  native/mminput.cpp -o "$input" -ldxgi -luuid
sha256sum "$input"

# R8 rules of ToolMap. Every library ships its own consumer rules (MapLibre, WebRTC, Room, Hilt, kotlinx.serialization,
# CameraX, OkHttp), so nothing is kept here by default. R8 runs on the benchmark build (ADR-057; P15.1, 2026-10-16:
# the map, the settings, the maps panel and the measure scenarios ran on the emulator without any rule added here) and
# on the release only with -Ptoolmap.minify=true. Add the rule a crash report asks for here, with the stack trace that
# justified it.

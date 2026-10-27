#!/usr/bin/env node
// Live view of a test of the position sharing (docs/specs/live-test.md §3). Reads the logcat of the emulator: the
// logs of the phones that the collector receives (tag TMRemote) and the emulator's own (TMEvent). Everything goes to
// tools/livetest/out/live-<date>.log; the screen (or the Monitor of Claude Code) gets one line per thing worth a look:
// notable events, a position that stops coming, a log that falls behind, and a summary every SUMMARY_S seconds.
//   node tools/livetest/live.mjs            GAP_S=60 SUMMARY_S=120 ANDROID_SERIAL=emulator-5554
import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { fmtFields, hhmmss, isNotable, makeDedupe, parseEvent, parseRemote } from "./parse.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = process.env.OUT_DIR || path.join(here, "out");
fs.mkdirSync(outDir, { recursive: true });
const stamp = new Date().toISOString().replace(/[:T]/g, "-").substring(0, 19);
const raw = fs.createWriteStream(path.join(outDir, `live-${stamp}.log`), { flags: "a" });
const GAP_MS = Number(process.env.GAP_S || 60) * 1000;
const SUMMARY_MS = Number(process.env.SUMMARY_S || 120) * 1000;
const LOG_LATE_MS = 90_000;

function adbPath() {
  const exe = process.platform === "win32" ? "adb.exe" : "adb";
  for (const sdk of [process.env.ANDROID_HOME, process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, "Android", "Sdk")]) {
    if (sdk && fs.existsSync(path.join(sdk, "platform-tools", exe))) return path.join(sdk, "platform-tools", exe);
  }
  return "adb";
}

/** What is known of each member (by hex id; "emu" is the emulator itself). */
const members = new Map();
function member(id) {
  let m = members.get(id);
  if (!m) {
    m = { id, name: id, lastLineAt: 0, lastPosInAt: 0, posIn: [], gapShown: false, lateShown: false, snap: {}, gnss: {}, relay: {} };
    members.set(id, m);
  }
  return m;
}
/** The emulator's own id in the group: its lines come as "emu", never as a member. */
let selfHex = null;
const label = (id) => (id === "emu" ? "ÉMU" : member(id).name === id ? id : `${member(id).name} (${id.substring(0, 6)})`);
const once = makeDedupe();

function say(text) {
  process.stdout.write(`${hhmmss(Date.now())} ${text}\n`);
}

function onEvent(e) {
  const now = Date.now();
  const m = member(e.member);
  m.lastLineAt = now;
  m.lateShown = false;
  if (e.marker !== undefined) {
    say(`${label(e.member)} # ${e.marker}`);
    return;
  }
  const f = e.f;
  if (e.member === "emu" && (e.name === "share.node.start" || e.name === "relay.start") && f.me) selfHex = f.me;
  // Names of the members, from what the emulator sees of the links.
  if (e.member === "emu" && (e.name === "mesh.link.open" || e.name === "relay.peer.open") && f.peer && f.name) member(f.peer).name = f.name;
  if (e.member === "emu" && e.name === "share.peer" && f.member && f.name) member(f.member).name = f.name;
  if (e.member === "emu" && e.name === "mesh.pos.in" && f.member) {
    const p = member(f.member);
    if (p.gapShown) say(`✓ ${label(f.member)} : positions reprises après ${Math.round((now - p.lastPosInAt) / 1000)} s`);
    p.gapShown = false;
    p.lastPosInAt = now;
    p.posIn.push(now);
    p.lastPos = f;
  }
  // A fix held back on purpose (moved less than the minimal distance) is not a gap.
  if (e.name === "share.fix") m.lastFix = { at: now, sent: f.send === "true" };
  if (e.name === "snap.sys") m.snap = f;
  if (e.name === "gnss.status") m.gnss = f;
  if (e.name === "snap.relay") m.relay = f;
  if (e.name === "debug.status") m.debug = f;
  if (isNotable(e)) say(`${label(e.member)} ${e.time} ${e.name} ${fmtFields(f)}`);
}

function summary() {
  const now = Date.now();
  const parts = [];
  for (const m of members.values()) {
    if (m.id === "emu" || m.id === selfHex) continue;
    m.posIn = m.posIn.filter((t) => now - t < 60_000);
    const pos = m.lastPosInAt ? `${Math.round((now - m.lastPosInAt) / 1000)} s` : "jamais";
    const s = m.snap;
    parts.push(
      `${label(m.id)} : dernière position ${pos}, ${m.posIn.length}/min` +
        (m.lastPos ? ` (acc ${m.lastPos.acc ?? "?"} m, âge ${m.lastPos.ageS ?? "?"} s)` : "") +
        (s.bat ? `, batterie ${s.bat} %` : "") +
        (s.screen ? `, écran ${s.screen === "true" ? "allumé" : "éteint"}` : "") +
        (s.doze === "true" ? ", doze" : "") +
        (m.gnss.used ? `, sat ${m.gnss.used}/${m.gnss.seen}` : "") +
        (m.relay.stream ? `, flux ${m.relay.stream}` : "") +
        (m.lastLineAt ? `, journal reçu il y a ${Math.round((now - m.lastLineAt) / 1000)} s` : ", journal jamais reçu"),
    );
  }
  say(`RÉSUMÉ ${parts.length ? parts.join(" | ") : "aucun téléphone vu"}`);
}

function watchdog() {
  const now = Date.now();
  for (const m of members.values()) {
    if (m.id === "emu" || m.id === selfHex) continue;
    const held = m.lastFix && !m.lastFix.sent && now - m.lastFix.at < GAP_MS;
    if (m.lastPosInAt && !m.gapShown && !held && now - m.lastPosInAt > GAP_MS) {
      m.gapShown = true;
      say(`⚠ ${label(m.id)} : aucune position reçue depuis ${Math.round((now - m.lastPosInAt) / 1000)} s`);
    }
    if (m.lastLineAt && !m.lateShown && now - m.lastLineAt > LOG_LATE_MS) {
      m.lateShown = true;
      say(`⚠ ${label(m.id)} : plus de journal reçu depuis ${Math.round((now - m.lastLineAt) / 1000)} s`);
    }
  }
}

const LOGCAT = /^\s*(\d+\.\d+)\s+\d+\s+\d+\s+([VDIWEF])\s+(\S+?)\s*:\s(.*)$/;
let buffer = "";
function onLogcat(chunk) {
  buffer += chunk;
  let nl;
  while ((nl = buffer.indexOf("\n")) >= 0) {
    const line = buffer.substring(0, nl).replace(/\r$/, "");
    buffer = buffer.substring(nl + 1);
    const m = LOGCAT.exec(line);
    if (!m) continue;
    const [, , level, tag, text] = m;
    if (tag === "TMRemote") {
      raw.write(text + "\n");
      // A line sent again (collector restarted, payload lost) is shown once.
      const e = once(parseRemote(text));
      if (e) onEvent(e);
    } else if (tag === "TMEvent") {
      raw.write("emu " + text + "\n");
      const e = parseEvent(text, "emu");
      if (e) onEvent(e);
    } else if (level === "E" || level === "F" || tag === "TMDebug") {
      raw.write(`logcat ${tag} ${text}\n`);
      if (tag === "TMDebug" || tag === "AndroidRuntime") say(`ÉMU logcat ${tag}: ${text}`);
    }
  }
}

function start() {
  const args = ["logcat", "-v", "epoch", "-T", "1", "TMRemote:I", "TMEvent:I", "TMDebug:V", "AndroidRuntime:E", "*:S"];
  const adb = spawn(adbPath(), args, { stdio: ["ignore", "pipe", "inherit"] });
  adb.stdout.setEncoding("utf8");
  adb.stdout.on("data", onLogcat);
  adb.on("exit", (code) => {
    say(`logcat arrêté (code ${code}) : reprise dans 5 s`);
    setTimeout(start, 5000);
  });
}

say(`Suivi en direct : écarts > ${GAP_MS / 1000} s, résumé toutes les ${SUMMARY_MS / 1000} s, journal brut ${raw.path}`);
start();
setInterval(watchdog, 5_000);
setInterval(summary, SUMMARY_MS);

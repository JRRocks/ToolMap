#!/usr/bin/env node
// Report of a live test (docs/specs/live-test.md §4), from the logs copied by pull.sh (or the raw log of live.mjs):
//   node tools/livetest/analyze.mjs tools/livetest/out/<date>/        [--from HH:MM] [--to HH:MM] [--gap 60]
// Sections: timeline, positions sent and received (cadence, gaps with what happened meanwhile, delay end to end,
// losses), server, GPS, battery and resources.
import fs from "node:fs";
import path from "node:path";
import { fmtFields, fmtQ, hhmmss, isNotable, makeDedupe, parseEvent, parseRemote, quantiles } from "./parse.mjs";

const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : def;
};
const target = args.find((a, i) => !a.startsWith("--") && !(i > 0 && args[i - 1].startsWith("--")));
if (!target) {
  console.error("usage : node analyze.mjs <dossier de pull.sh | live-*.log> [--from HH:MM] [--to HH:MM] [--gap 60]");
  process.exit(2);
}
const GAP_MS = Number(opt("--gap", 60)) * 1000;

// ---- reading
const events = [];
const once = makeDedupe();
// The server's address stays out of the report (it may be pasted into the journal).
const redact = (line) => line.replace(/\r$/, "").replace(/host \\?"[^"\\]*\\?"/g, "host <serveur>");
function readFile(file, kind) {
  for (const line of fs.readFileSync(file, "utf8").split("\n")) {
    if (!line) continue;
    const e = kind === "remote" ? once(parseRemote(redact(line))) : parseEvent(redact(line), "emu");
    if (e) events.push(e);
  }
}
if (fs.statSync(target).isDirectory()) {
  for (const f of ["remote.previous.log", "remote.log"]) if (fs.existsSync(path.join(target, f))) readFile(path.join(target, f), "remote");
  for (const f of ["previous.log", "current.log"]) if (fs.existsSync(path.join(target, f))) readFile(path.join(target, f), "local");
} else {
  // live-*.log: every line starts with the member, "emu" for the emulator.
  readFile(target, "remote");
}

const dayOf = (ms) => new Date(ms).toDateString();
function bound(hhmm) {
  if (!hhmm) return null;
  const any = events.find((e) => e.t);
  if (!any) return null;
  const d = new Date(any.t);
  const [h, m] = hhmm.split(":").map(Number);
  d.setHours(h, m, 0, 0);
  return d.getTime();
}
const from = bound(opt("--from")) ?? -Infinity;
const to = bound(opt("--to")) ?? Infinity;
const timed = events.filter((e) => e.t && e.t >= from && e.t <= to).sort((a, b) => a.t - b.t);
const byMember = new Map();
for (const e of timed) {
  if (!byMember.has(e.member)) byMember.set(e.member, []);
  byMember.get(e.member).push(e);
}
const names = new Map([["emu", "émulateur"]]);
for (const e of timed) {
  if (e.member === "emu" && (e.name === "mesh.link.open" || e.name === "relay.peer.open") && e.f.peer && e.f.name) names.set(e.f.peer, e.f.name);
  if (e.member === "emu" && e.name === "share.peer" && e.f.member && e.f.name) names.set(e.f.member, e.f.name);
}
const nameOf = (id) => (names.has(id) ? `${names.get(id)} (${id})` : id);
const out = [];
const h = (title) => out.push("", `## ${title}`);
const p = (text) => out.push(text);
const sec = (ms) => Math.round(ms / 100) / 10;

// ---- overview
h("Vue d'ensemble");
if (!timed.length) {
  p("Aucun événement dans la période.");
  console.log(out.join("\n"));
  process.exit(0);
}
p(`Période : ${hhmmss(timed[0].t)} → ${hhmmss(timed[timed.length - 1].t)} (${dayOf(timed[0].t)}), ${timed.length} événements.`);
for (const [id, list] of byMember) {
  p(`- ${nameOf(id)} : ${list.length} lignes, de ${hhmmss(list[0].t)} à ${hhmmss(list[list.length - 1].t)}`);
}
const markers = events.filter((e) => e.marker !== undefined);
if (markers.length) p(`Marques du collecteur : ${markers.map((e) => `${e.member} ${e.marker}`).join(" ; ")}`);

// ---- timeline
h("Chronologie (marques de test et événements notables)");
for (const e of timed) {
  if (!isNotable(e) || e.name === "ui.screen") continue;
  p(`${e.time.substring(0, 8)} ${e.member === "emu" ? "ÉMU " : names.get(e.member) ?? e.member} ${e.name} ${fmtFields(e.f)}`);
}

// ---- positions
h("Positions");
const emu = byMember.get("emu") ?? [];
for (const [id, list] of byMember) {
  if (id === "emu") continue;
  p(`### ${nameOf(id)} (envoi)`);
  const fixes = list.filter((e) => e.name === "share.fix");
  const sent = fixes.filter((e) => e.f.send === "true");
  p(`Fixes GPS reçus par le partage : ${fixes.length}, envoyés : ${sent.length} ; précision ${fmtQ(quantiles(fixes.map((e) => +e.f.acc).filter(Number.isFinite)), " m")} ; âge ${fmtQ(quantiles(fixes.map((e) => +e.f.ageMs / 1000).filter(Number.isFinite)), " s")}`);
  const outs = list.filter((e) => e.name === "mesh.pos.out");
  const gapsOut = outs.slice(1).map((e, i) => e.t - outs[i].t);
  p(`Positions émises (mesh.pos.out) : ${outs.length} ; intervalle ${fmtQ(quantiles(gapsOut.map((g) => g / 1000)), " s")}`);
  reportGaps(outs, list, "d'émission", list.at(-1).t);

  p(`### ${nameOf(id)} (réception par l'émulateur)`);
  const ins = emu.filter((e) => e.name === "mesh.pos.in" && e.f.member === id);
  const gapsIn = ins.slice(1).map((e, i) => e.t - ins[i].t);
  p(`Positions reçues : ${ins.length} ; intervalle ${fmtQ(quantiles(gapsIn.map((g) => g / 1000)), " s")} ; âge à l'arrivée ${fmtQ(quantiles(ins.map((e) => +e.f.ageS).filter(Number.isFinite)), " s")} ; via ${[...new Set(ins.map((e) => e.f.via))].join(", ")}`);
  reportGaps(ins, [...list, ...emu].sort((a, b) => a.t - b.t), "de réception", emu.at(-1)?.t ?? 0);
  // End to end: the same frame (fid) sent by the phone and received by the emulator. Clocks of two devices: the
  // relay.clock lines give each one's offset to the server.
  const sentAt = new Map(outs.map((e) => [e.f.fid, e.t]));
  const delays = ins.filter((e) => sentAt.has(e.f.fid)).map((e) => (e.t - sentAt.get(e.f.fid)) / 1000);
  const received = new Set(ins.map((e) => e.f.fid));
  const lost = outs.filter((e) => e.t < (ins.at(-1)?.t ?? 0) && !received.has(e.f.fid));
  p(`Délai émission → réception (même trame) : ${fmtQ(quantiles(delays), " s")} ; trames émises jamais reçues : ${lost.length}/${outs.length}`);
  const offsets = (who) => (byMember.get(who) ?? []).filter((e) => e.name === "relay.clock").map((e) => +e.f.offsetMs);
  const oPhone = quantiles(offsets(id));
  const oEmu = quantiles(offsets("emu"));
  if (oPhone && oEmu) p(`Écart d'horloge au serveur : téléphone ${Math.round(oPhone.med)} ms, émulateur ${Math.round(oEmu.med)} ms (à retrancher des délais).`);
}

// The gaps between two positions, and the silence after the last one when the log goes on ([end]: its last line).
function reportGaps(series, context, what, end) {
  const gaps = [];
  for (let i = 1; i < series.length; i++) if (series[i].t - series[i - 1].t > GAP_MS) gaps.push([series[i - 1].t, series[i].t]);
  const last = series.at(-1)?.t;
  const tail = last !== undefined && end - last > GAP_MS;
  if (tail) gaps.push([last, end]);
  if (!gaps.length) return p(`Aucun trou ${what} > ${GAP_MS / 1000} s.`);
  p(`Trous ${what} > ${GAP_MS / 1000} s : ${gaps.length}`);
  for (const [a, b] of gaps) {
    const open = tail && a === last;
    p(`- ${hhmmss(a)} → ${open ? `plus rien jusqu'à la fin du journal, ${hhmmss(b)}` : hhmmss(b)} (${sec(b - a)} s) ; pendant ce temps :`);
    const during = context.filter((e) => e.t > a && e.t < b && isNotable(e) && e.name !== "ui.screen");
    for (const e of during.slice(0, 25)) p(`    ${e.time.substring(0, 8)} ${e.member === "emu" ? "ÉMU" : names.get(e.member) ?? e.member} ${e.name} ${fmtFields(e.f)}`);
    if (during.length > 25) p(`    … ${during.length - 25} autres`);
  }
}

// ---- server
h("Serveur (Firebase)");
for (const [id, list] of byMember) {
  const writes = list.filter((e) => e.name === "relay.write");
  const byCh = {};
  for (const e of writes) (byCh[e.f.type ? `${e.f.what}/${e.f.type}` : e.f.what] ??= []).push(+e.f.ms);
  const fails = list.filter((e) => e.name === "relay.write.fail");
  const ends = list.filter((e) => e.name === "relay.stream.end");
  // A stream that was up is a cut; one that ends at once is a failed attempt to reconnect, counted by reason.
  const cuts = ends.filter((e) => Number(e.f.upS) > 0);
  const failedRetries = {};
  for (const e of ends) if (!(Number(e.f.upS) > 0)) failedRetries[e.f.why ?? e.f.error ?? "?"] = (failedRetries[e.f.why ?? e.f.error ?? "?"] ?? 0) + 1;
  const replaced = list.filter((e) => e.name === "relay.p.replaced");
  const http = list.filter((e) => e.name === "fb.http" && e.f.code && !e.f.code.startsWith("2"));
  p(`- ${nameOf(id)} : écritures ${Object.entries(byCh).map(([ch, v]) => `${ch} ${fmtQ(quantiles(v), " ms")}`).join(" ; ") || "—"}`);
  p(`  échecs d'écriture ${fails.length}${fails.length ? ` (${[...new Set(fails.map((e) => e.f.why ?? e.f.error))].join(", ")})` : ""} ; flux coupé ${cuts.length} fois${cuts.length ? ` (${cuts.map((e) => `${e.time.substring(0, 8)} après ${e.f.upS} s : ${e.f.why ?? e.f.error ?? "?"}`).join(" ; ")})` : ""}`);
  if (Object.keys(failedRetries).length) p(`  reconnexions échouées : ${Object.entries(failedRetries).map(([why, n]) => `${n} × ${why}`).join(" ; ")}`);
  if (replaced.length) {
    const kinds = {};
    for (const e of replaced) kinds[`${e.f.old}→${e.f.new}`] = (kinds[`${e.f.old}→${e.f.new}`] ?? 0) + 1;
    p(`  trames du canal de position remplacées avant écriture : ${replaced.length} (${Object.entries(kinds).map(([k, v]) => `${k} ×${v}`).join(", ")})`);
  }
  if (http.length) p(`  réponses HTTP en erreur : ${http.map((e) => `${e.f.method} ${e.f.code}`).join(", ")}`);
}

// ---- GPS
h("GPS");
for (const [id, list] of byMember) {
  const fixes = list.filter((e) => e.name === "gps.fix");
  const status = list.filter((e) => e.name === "gnss.status");
  const first = list.filter((e) => e.name === "gnss.firstfix");
  if (!fixes.length && !status.length) continue;
  p(`- ${nameOf(id)} : fixes ${fmtQ(quantiles(fixes.slice(1).map((e, i) => (e.t - fixes[i].t) / 1000)), " s")} entre deux ; précision ${fmtQ(quantiles(fixes.map((e) => +e.f.acc).filter(Number.isFinite)), " m")}`);
  if (status.length) p(`  satellites utilisés ${fmtQ(quantiles(status.map((e) => +e.f.used)))} ; C/N0 ${fmtQ(quantiles(status.map((e) => +e.f.cn0).filter(Number.isFinite)), " dB-Hz")}`);
  if (first.length) p(`  premier fix : ${first.map((e) => `${e.time.substring(0, 8)} en ${sec(+e.f.ttffMs)} s`).join(", ")}`);
}

// ---- battery and resources
h("Batterie et ressources (instantanés snap.sys)");
for (const [id, list] of byMember) {
  const snaps = list.filter((e) => e.name === "snap.sys");
  if (snaps.length < 2) continue;
  const a = snaps[0].f;
  const b = snaps[snaps.length - 1].f;
  const hours = (snaps[snaps.length - 1].t - snaps[0].t) / 3_600_000;
  const per = (k) => (+b[k] - +a[k]) / hours;
  const current = quantiles(snaps.map((e) => +e.f.currentUa).filter(Number.isFinite));
  const screenOff = snaps.filter((e) => e.f.screen === "false").length;
  const doze = snaps.filter((e) => e.f.doze === "true").length;
  p(`- ${nameOf(id)} sur ${Math.round(hours * 60)} min : batterie ${a.bat} % → ${b.bat} %` +
    (a.chargeUah && b.chargeUah ? `, ${Math.round(-per("chargeUah") / 1000)} mAh/h` : "") +
    (current ? `, courant médian ${Math.round(current.med / 1000)} mA` : "") +
    `, CPU ${Math.round((per("cpuMs") / 3_600_000) * 1000) / 10} %` +
    `, données ${Math.round(per("rxB") / 1024)} Kio/h reçues, ${Math.round(per("txB") / 1024)} Kio/h envoyées` +
    `, écran éteint ${Math.round((100 * screenOff) / snaps.length)} %, doze ${Math.round((100 * doze) / snaps.length)} %` +
    `, tas max ${Math.max(...snaps.map((e) => +e.f.heapKb || 0))} Kio, fils max ${Math.max(...snaps.map((e) => +e.f.threads || 0))}`);
}

console.log(out.join("\n").trim());

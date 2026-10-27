// Reading of the event log lines (docs/specs/event-log.md §2), shared by live.mjs and analyze.mjs.
// A line: `<seq> <epoch ms> <HH:mm:ss.SSS> <name> k=v k="v w"`; in remote.log it starts with the member (hex id).

/** The fields after the name, unquoted (same rules as EventLine.fieldsOf in :core). */
export function fieldsOf(text) {
  const out = {};
  let i = 0;
  while (i < text.length) {
    while (i < text.length && text[i] === " ") i++;
    const eq = text.indexOf("=", i);
    if (eq < 0) break;
    const key = text.substring(i, eq);
    i = eq + 1;
    let value = "";
    if (text[i] === '"') {
      i++;
      while (i < text.length && text[i] !== '"') {
        if (text[i] === "\\" && i + 1 < text.length) {
          i++;
          value += text[i] === "n" ? "\n" : text[i];
        } else {
          value += text[i];
        }
        i++;
      }
      i++;
    } else {
      while (i < text.length && text[i] !== " ") value += text[i++];
    }
    out[key] = value;
  }
  return out;
}

/** One event line, or a `#` marker of the collector; `null` for anything else. */
export function parseEvent(line, member = "emu") {
  if (line.startsWith("#")) return { member, marker: line.substring(1).trim(), t: null };
  const m = /^(\d+) (\d+) (\S+) (\S+) ?(.*)$/.exec(line);
  if (!m) return null;
  return { member, seq: Number(m[1]), t: Number(m[2]), time: m[3], name: m[4], f: fieldsOf(m[5]), raw: line };
}

/** A line of remote.log: `<member> <event line>`. */
export function parseRemote(line) {
  const sp = line.indexOf(" ");
  if (sp <= 0) return null;
  return parseEvent(line.substring(sp + 1), line.substring(0, sp));
}

/**
 * A filter that lets each member's line through once: a phone sends lines again when the collector did not
 * acknowledge them (lost payload, collector restarted). A `# log epoch=…` marker of a new log starts the count again.
 */
export function makeDedupe() {
  const at = new Map();
  return (e) => {
    if (!e) return null;
    const s = at.get(e.member);
    if (e.marker !== undefined) {
      const epoch = /^log epoch=(\S+)/.exec(e.marker)?.[1];
      if (epoch && s?.epoch !== epoch) at.set(e.member, { epoch, seq: 0 });
      return e;
    }
    if (!s) {
      at.set(e.member, { epoch: null, seq: e.seq });
      return e;
    }
    if (e.seq <= s.seq) return null;
    s.seq = e.seq;
    return e;
  };
}

/** The events worth a look at once, in the live view and the timeline of the report. */
export const NOTABLE = new Set([
  "app.start", "app.start.reason", "app.exit", "app.crash", "app.foreground", "app.background", "app.trim", "app.lowmem",
  "ui.screen", "app.intent",
  "share.load", "share.group.create", "share.group.join", "share.group.leave", "share.sharing", "share.problem",
  "share.peer", "share.gps.error", "share.reconnect", "share.settings", "share.node.start", "share.excluded",
  "share.service.start", "share.service.refused", "share.service.finish", "share.service.destroy",
  "share.net.available", "share.net.lost",
  "gps.provider", "gps.request", "gps.refused", "gps.stop", "gnss.firstfix", "gnss.refused",
  "net.available", "net.lost", "net.blocked",
  "relay.start", "relay.close", "relay.stream.open", "relay.stream.end", "relay.retry", "relay.reconnect.now",
  "relay.write.fail", "relay.p.replaced", "relay.state", "relay.peer.open", "relay.peer.close", "relay.peer.gone",
  "relay.ban", "fb.auth.lost", "fb.auth.fail",
  "mesh.link.open", "mesh.link.closed", "mesh.link.lost", "mesh.frame.bad", "mesh.send.fail",
  "rtc.link.open", "rtc.link.close",
  "test.mark", "debug.start", "debug.test", "debug.collector", "debug.test.set", "debug.error",
]);

/** System events: every `sys.*` but the battery level, which the snapshots follow. */
export function isNotable(e) {
  if (!e) return false;
  if (e.marker !== undefined) return true;
  if (NOTABLE.has(e.name)) return true;
  return e.name.startsWith("sys.") && e.name !== "sys.battery";
}

export function fmtFields(f, skip = []) {
  return Object.entries(f)
    .filter(([k]) => !skip.includes(k))
    .map(([k, v]) => (/[\s"=]/.test(v) || v === "" ? `${k}="${v}"` : `${k}=${v}`))
    .join(" ");
}

export function quantiles(values) {
  if (!values.length) return null;
  const s = [...values].sort((a, b) => a - b);
  const q = (p) => s[Math.min(s.length - 1, Math.floor(p * (s.length - 1) + 0.5))];
  return { n: s.length, min: s[0], med: q(0.5), p90: q(0.9), max: s[s.length - 1], mean: s.reduce((a, b) => a + b, 0) / s.length };
}

export function fmtQ(q, unit = "") {
  if (!q) return "—";
  const r = (x) => (Math.abs(x) >= 100 ? Math.round(x) : Math.round(x * 10) / 10);
  return `n=${q.n} méd=${r(q.med)}${unit} p90=${r(q.p90)}${unit} max=${r(q.max)}${unit}`;
}

export function hhmmss(ms) {
  return new Date(ms).toLocaleTimeString("fr-FR", { hour12: false });
}

// Pure functions of the problem import (ADR-116, docs/specs/problems.md): validation of the reports left on the
// coordination server by the app, grouping by fingerprint, and the text of the GitHub issues and comments.
// Reports come from anonymous writers: everything is validated and rendered as inert Markdown.

export const MARKER = /<!-- toolmap-problem fp=([0-9a-f]{16}) max=([0-9.]+) -->/;
export const LABEL = 'rapport-auto';

export const KINDS = {
  ERROR: { label: 'erreur inattendue', tag: 'erreur-inattendue' },
  CRASH: { label: 'plantage', tag: 'plantage' },
  NATIVE_CRASH: { label: 'plantage natif', tag: 'plantage-natif' },
  ANR: { label: 'blocage (ANR)', tag: 'anr' },
};

const ID = /^([0-9a-f]{16})-[0-9a-z]{10}$/;
const WHERE = /^[a-z][a-z0-9_]*(\.[a-z0-9_]+)*$/;
const VERSION = /^\d{1,3}\.\d{1,3}\.\d{1,5}( \(\d{1,9}\))?$/;
const COMMIT = /^[0-9a-f]{7,40}$|^local$|^$/;

const str = (v, max) => typeof v === 'string' && v.length <= max;
const int = (v, min, max) => Number.isInteger(v) && v >= min && v <= max;

/** The report of one server entry, or null when it is not one the app could have written. */
export function parseEntry(id, entry) {
  const m = ID.exec(id);
  if (!m || !entry || typeof entry !== 'object' || entry.v !== 1 || !str(entry.j, 16000)) return null;
  let r;
  try {
    r = JSON.parse(entry.j);
  } catch {
    return null;
  }
  if (!r || typeof r !== 'object' || r.v !== 1 || r.fp !== m[1]) return null;
  if (!Object.hasOwn(KINDS, r.kind) || !str(r.where, 80) || !(WHERE.test(r.where) || ['native', 'anr', 'uncaught'].includes(r.where))) return null;
  if (!str(r.error, 200) || !str(r.message, 300) || !str(r.stack, 10000) || !str(r.thread, 300)) return null;
  const d = r.device;
  if (!d || typeof d !== 'object') return null;
  if (!str(d.app, 40) || !VERSION.test(d.app) || !str(d.commit, 40) || !COMMIT.test(d.commit)) return null;
  if (!str(d.android, 20) || !int(d.api, 1, 100) || !str(d.device, 80) || !str(d.abi, 20)) return null;
  if (!int(r.count, 1, 1e9) || !int(r.firstMs, 0, 1e14) || !int(r.lastMs, 0, 1e14)) return null;
  const context = {};
  if (r.context && typeof r.context === 'object') {
    for (const [k, v] of Object.entries(r.context).slice(0, 12)) {
      if (str(k, 32) && /^[A-Za-z0-9_.]+$/.test(k) && str(v, 32)) context[k] = v;
    }
  }
  return {
    id,
    fp: r.fp,
    kind: r.kind,
    where: r.where,
    error: r.error,
    message: r.message,
    stack: r.stack,
    thread: r.thread,
    device: { app: d.app, commit: d.commit, android: d.android, api: d.api, device: d.device, abi: d.abi },
    context,
    count: r.count,
    firstMs: r.firstMs,
    lastMs: r.lastMs,
  };
}

/** "0.4.210 (210)" → [0, 4, 210]. */
export function versionOf(app) {
  return app.split(' ')[0].split('.').map((n) => Number(n));
}

export function compareVersions(a, b) {
  const x = versionOf(a);
  const y = versionOf(b);
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] - y[i];
  return 0;
}

/**
 * Whether [version] may move an issue (reopen it, raise its marker): at most [ceiling], the newest version the CI
 * published (`android-<version>` releases, read by import-reports.mjs), since no real build is above it. Anyone who has
 * the app can write a report: a forged "999.0.0" must not reopen an issue nor raise its marker, or every real report of
 * the bug would be dropped once it is closed again (P36 review). Without a ceiling (releases unreadable), none may.
 */
export function trusted(version, ceiling) {
  return Boolean(ceiling) && compareVersions(version, ceiling) <= 0;
}

/** The highest version of the group [g] that may move an issue, `0.0.0` when none may. */
export function trustedMax(g, ceiling) {
  return g.versions.filter((v) => trusted(v, ceiling)).at(-1) ?? '0.0.0';
}

const SEVERITY = { ERROR: 0, ANR: 1, CRASH: 2, NATIVE_CRASH: 2 };

/** The reports of one fingerprint as one: summed counts, time span, and the report of the newest build as sample. */
export function group(reports) {
  const byFp = new Map();
  for (const r of reports) {
    if (!byFp.has(r.fp)) byFp.set(r.fp, []);
    byFp.get(r.fp).push(r);
  }
  return [...byFp.entries()].map(([fp, all]) => {
    const sample = all.reduce((a, b) => {
      const v = compareVersions(b.device.app, a.device.app);
      if (v !== 0) return v > 0 ? b : a;
      return SEVERITY[b.kind] > SEVERITY[a.kind] ? b : a;
    });
    return {
      fp,
      ids: all.map((r) => r.id),
      sample,
      count: all.reduce((n, r) => n + r.count, 0),
      firstMs: Math.min(...all.map((r) => r.firstMs)),
      lastMs: Math.max(...all.map((r) => r.lastMs)),
      versions: [...new Set(all.map((r) => r.device.app.split(' ')[0]))].sort(compareVersions),
      devices: [...new Set(all.map((r) => `${r.device.device} · Android ${r.device.android}`))],
      maxVersion: all.map((r) => r.device.app.split(' ')[0]).sort(compareVersions).at(-1),
    };
  });
}

/** Text that cannot ping, link or break out of a code span. */
export function inline(text) {
  const t = String(text).replace(/[`\r\n]/g, ' ').replace(/@/g, '@​').trim();
  return t ? '`' + t + '`' : '—';
}

/** Plain text outside code, without Markdown effects. */
export function plain(text) {
  return String(text)
    .replace(/[\r\n]+/g, ' ')
    .replace(/[\\`*_{}[\]()#+!|<>~]/g, (c) => '\\' + c)
    .replace(/@/g, '@​');
}

export function block(text) {
  return String(text).replace(/~{3,}/g, '~~').replace(/@/g, '@​');
}

export function utc(ms) {
  return new Date(ms).toISOString().slice(0, 16).replace('T', ' ');
}

const simpleName = (cls) => cls.split('.').at(-1);

export function title(g) {
  const s = g.sample;
  const what = s.kind === 'NATIVE_CRASH' || s.kind === 'ANR' ? s.error : simpleName(s.error);
  const t = `[auto] ${KINDS[s.kind].label} : ${what} (${s.where})`;
  return plain(t.length > 120 ? t.slice(0, 119) + '…' : t).replace(/\\/g, '');
}

export function body(g, ceiling) {
  const s = g.sample;
  const d = s.device;
  const ctx = Object.entries(s.context);
  const rows = [
    ['Type', KINDS[s.kind].label],
    ['Où', inline(s.where)],
    ['Erreur', inline(s.error)],
    ['Message', inline(s.message)],
    // Free text from an anonymous writer goes in code spans: no link, no mention, no Markdown.
    ['Version', `${inline(d.app)} (${inline(d.commit || '?')})`],
    ['Android', `${inline(d.android)} (API ${d.api})`],
    ['Appareil', `${inline(d.device)} (${inline(d.abi)})`],
    ['Fil', inline(s.thread)],
    ['Vu', occurrences(g)],
  ];
  if (ctx.length) rows.push(['Contexte', ctx.map(([k, v]) => inline(`${k}=${v}`)).join(', ')]);
  return [
    `<!-- toolmap-problem fp=${g.fp} max=${trustedMax(g, ceiling)} -->`,
    "Rapport envoyé automatiquement par l'application (ADR-116, `docs/specs/problems.md`). Il ne contient aucune donnée personnelle : types d'erreur, pile d'appels, messages nettoyés, version et modèle du téléphone.",
    '',
    '| | |',
    '|---|---|',
    ...rows.map(([k, v]) => `| ${k} | ${v} |`),
    '',
    "<details open><summary>Pile d'appels</summary>",
    '',
    '~~~~text',
    block(s.stack.trimEnd()),
    '~~~~',
    '</details>',
    '',
    `Empreinte : \`${g.fp}\` (même bogue, même empreinte : les nouveaux cas s'ajoutent ici en commentaire).`,
  ].join('\n');
}

export function occurrences(g) {
  const n = g.count === 1 ? '1 fois' : `${g.count} fois`;
  const span = g.firstMs === g.lastMs ? `le ${utc(g.firstMs)}` : `du ${utc(g.firstMs)} au ${utc(g.lastMs)}`;
  return `${n}, ${span} (UTC)`;
}

export function comment(g, reopened, ceiling) {
  const head = reopened ? `**Réapparu en ${plain(trustedMax(g, ceiling))}** (ticket rouvert).` : 'Vu de nouveau.';
  return [
    head,
    '',
    `- ${occurrences(g)}`,
    `- Versions : ${g.versions.map(inline).join(', ')}`,
    `- Appareils : ${g.devices.map(inline).join(' ; ')}`,
    `- Exemple : ${inline(g.sample.error)} ${inline(g.sample.message)}`,
  ].join('\n');
}

/** The new marker of an issue body once [g] has been seen: the highest trusted version met is kept. */
export function updatedBody(issueBody, g, ceiling) {
  const m = MARKER.exec(issueBody || '');
  if (!m) return null;
  const max = trustedMax(g, ceiling);
  if (compareVersions(max, m[2]) <= 0) return null;
  return issueBody.replace(MARKER, `<!-- toolmap-problem fp=${g.fp} max=${max} -->`);
}

/** What to do with a group, given its issue (or none). */
export function decide(g, issue, ceiling) {
  if (!issue) return 'create';
  if (issue.state === 'open') return 'comment';
  const m = MARKER.exec(issue.body || '');
  if (m && compareVersions(trustedMax(g, ceiling), m[2]) > 0) return 'reopen';
  return 'drop';
}

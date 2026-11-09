// Turns the problem reports left by the app on the coordination server into GitHub issues (ADR-116,
// docs/specs/problems.md). Run every hour by .github/workflows/problems.yml; no dependency beyond Node 20.
//
// Environment: FIREBASE_DB_URL, FIREBASE_API_KEY, REPORTS_PASSWORD (the reader account reports@toolmap.invalid),
// GH_TOKEN, GITHUB_REPOSITORY. Without the Firebase values or the password, it says so and stops (exit 0).
// DRY_RUN=1 prints what it would do and changes nothing.
import {
  LABEL,
  KINDS,
  MARKER,
  body,
  comment,
  compareVersions,
  decide,
  group,
  parseEntry,
  title,
  updatedBody,
} from './reports.mjs';

const READER = 'reports@toolmap.invalid';
const MAX_NEW_ISSUES = 10;
const MAX_COMMENTS = 20;
const MAX_READ = 200;
const GITHUB = 'https://api.github.com';

const env = process.env;
const dry = env.DRY_RUN === '1';

function warn(text) {
  console.log(`::warning::${text}`);
}

async function json(response, what) {
  if (!response.ok) throw new Error(`${what}: HTTP ${response.status} ${(await response.text()).slice(0, 200)}`);
  return response.status === 204 ? null : response.json();
}

async function signIn() {
  const url = `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${encodeURIComponent(env.FIREBASE_API_KEY)}`;
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: READER, password: env.REPORTS_PASSWORD, returnSecureToken: true }),
  });
  return (await json(r, 'sign-in')).idToken;
}

function db(path, token) {
  const base = env.FIREBASE_DB_URL.replace(/\/+$/, '');
  return `${base}/${path}.json?auth=${encodeURIComponent(token)}`;
}

async function gh(method, path, payload) {
  const r = await fetch(`${GITHUB}${path}`, {
    method,
    headers: {
      authorization: `Bearer ${env.GH_TOKEN}`,
      accept: 'application/vnd.github+json',
      'x-github-api-version': '2022-11-28',
      ...(payload ? { 'content-type': 'application/json' } : {}),
    },
    body: payload ? JSON.stringify(payload) : undefined,
  });
  return json(r, `${method} ${path}`);
}

async function existingIssues(repo) {
  const byFp = new Map();
  for (let page = 1; page <= 20; page++) {
    const issues = await gh('GET', `/repos/${repo}/issues?labels=${LABEL}&state=all&per_page=100&page=${page}`);
    for (const issue of issues) {
      const m = MARKER.exec(issue.body || '');
      if (m && !issue.pull_request && !byFp.has(m[1])) byFp.set(m[1], issue);
    }
    if (issues.length < 100) break;
  }
  return byFp;
}

/**
 * The newest version the CI published (highest `android-<version>` release): no real build is above it, so a report
 * above it cannot reopen an issue (reports.mjs, trusted). Unreadable: null, and no report reopens anything this run.
 */
async function newestRelease(repo) {
  try {
    const releases = await gh('GET', `/repos/${repo}/releases?per_page=100`);
    const versions = releases.map((r) => /^android-(\d+\.\d+\.\d+)$/.exec(r.tag_name)?.[1]).filter(Boolean);
    return versions.sort(compareVersions).at(-1) ?? null;
  } catch (e) {
    warn(`releases not readable (${e.message}): no report may reopen an issue this run.`);
    return null;
  }
}

async function ensureLabels(repo) {
  const wanted = [
    [LABEL, '5319e7', "Ticket ouvert par l'application (ADR-116)"],
    ...Object.values(KINDS).map((k) => [k.tag, 'b60205', `Rapport automatique : ${k.label}`]),
  ];
  for (const [name, color, description] of wanted) {
    const r = await fetch(`${GITHUB}/repos/${repo}/labels/${encodeURIComponent(name)}`, {
      headers: { authorization: `Bearer ${env.GH_TOKEN}`, accept: 'application/vnd.github+json' },
    });
    if (r.status === 404 && !dry) await gh('POST', `/repos/${repo}/labels`, { name, color, description });
  }
}

async function main() {
  if (!env.FIREBASE_DB_URL || !env.FIREBASE_API_KEY || !env.REPORTS_PASSWORD) {
    warn('TOOLMAP_FIREBASE_DB_URL, TOOLMAP_FIREBASE_API_KEY or TOOLMAP_REPORTS_PASSWORD missing: no report read (docs/specs/problems.md § 5).');
    return;
  }
  const repo = env.GITHUB_REPOSITORY;
  const token = await signIn();
  // At most MAX_READ reports a run, oldest keys first: a flood of fake reports cannot make one run download it all
  // (the rest waits for the next runs, which delete what they handled).
  const page = `${db('reports', token)}&orderBy=${encodeURIComponent('"$key"')}&limitToFirst=${MAX_READ}`;
  const entries = (await json(await fetch(page), 'read reports')) || {};
  const ids = Object.keys(entries);
  const reports = [];
  const invalid = [];
  for (const id of ids) {
    const r = parseEntry(id, entries[id]);
    if (r) reports.push(r);
    else invalid.push(id);
  }
  console.log(`${ids.length} reports on the server: ${reports.length} valid, ${invalid.length} refused.`);

  await ensureLabels(repo);
  const issues = await existingIssues(repo);
  const ceiling = await newestRelease(repo);
  console.log(`Newest published version: ${ceiling ?? 'unknown'}.`);
  const done = [...invalid];
  let created = 0;
  let commented = 0;
  for (const g of group(reports).sort((a, b) => b.count - a.count)) {
    const issue = issues.get(g.fp);
    const action = decide(g, issue, ceiling);
    if (action === 'create') {
      if (created >= MAX_NEW_ISSUES) continue; // Kept on the server for the next run.
      const labels = [LABEL, KINDS[g.sample.kind].tag];
      console.log(`create ${g.fp}: ${title(g)}`);
      if (!dry) await gh('POST', `/repos/${repo}/issues`, { title: title(g), body: body(g, ceiling), labels });
      created++;
    } else if (action === 'comment' || action === 'reopen') {
      if (commented >= MAX_COMMENTS) continue;
      console.log(`${action} #${issue.number} (${g.fp}): ${g.count} more`);
      if (!dry) {
        await gh('POST', `/repos/${repo}/issues/${issue.number}/comments`, { body: comment(g, action === 'reopen', ceiling) });
        const next = updatedBody(issue.body, g, ceiling);
        const patch = {};
        if (next) patch.body = next;
        if (action === 'reopen') patch.state = 'open';
        if (Object.keys(patch).length) await gh('PATCH', `/repos/${repo}/issues/${issue.number}`, patch);
      }
      commented++;
    } else {
      console.log(`drop ${g.fp}: issue #${issue.number} closed, no newer published version (${g.maxVersion})`);
    }
    done.push(...g.ids);
  }

  // The handled reports leave the server, a hundred at a time.
  for (let i = 0; i < done.length && !dry; i += 100) {
    const patch = Object.fromEntries(done.slice(i, i + 100).map((id) => [id, null]));
    await json(await fetch(db('reports', token), { method: 'PATCH', body: JSON.stringify(patch) }), 'delete reports');
  }
  console.log(`${created} issues created, ${commented} updated, ${done.length} reports removed${dry ? ' (dry run)' : ''}.`);
}

main().catch((e) => {
  // The message may carry the server address: only its first line, without query strings.
  console.log(`::error::${String(e.message).split('\n')[0].replace(/\?[^\s]*/g, '?…')}`);
  process.exit(1);
});

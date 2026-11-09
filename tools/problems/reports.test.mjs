// node --test tools/problems/  (run by the CI)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MARKER, block, body, comment, decide, group, inline, parseEntry, title, trusted, updatedBody } from './reports.mjs';

const FP = '0123456789abcdef';

function report(over = {}) {
  return {
    v: 1,
    fp: FP,
    kind: 'ERROR',
    where: 'tiles.decode',
    error: 'java.lang.IllegalStateException',
    message: 'bad tile <n>',
    stack: 'java.lang.IllegalStateException: bad tile <n>\n\tat fr.toolmap.data.tiles.X.y(X.kt:12)\n',
    thread: 'main',
    device: { app: '0.4.210', commit: 'abc1234', android: '15', api: 35, device: 'Google Pixel 7', abi: 'arm64-v8a' },
    context: { zoom: '12' },
    count: 2,
    firstMs: 1_762_600_000_000,
    lastMs: 1_762_600_600_000,
    ...over,
  };
}

const entry = (r) => ({ v: 1, j: JSON.stringify(r), t: 1_762_600_700_000 });

test('a report written by the app is read back', () => {
  const r = parseEntry(`${FP}-abcdefghij`, entry(report()));
  assert.equal(r.fp, FP);
  assert.equal(r.device.api, 35);
  assert.deepEqual(r.context, { zoom: '12' });
});

test('anything else is refused', () => {
  const id = `${FP}-abcdefghij`;
  assert.equal(parseEntry('nope', entry(report())), null, 'bad id');
  assert.equal(parseEntry(`fedcba9876543210-abcdefghij`, entry(report())), null, 'fingerprint not the id');
  assert.equal(parseEntry(id, { v: 1, j: '{' }), null, 'not JSON');
  assert.equal(parseEntry(id, entry(report({ kind: 'SPAM' }))), null, 'unknown kind');
  assert.equal(parseEntry(id, entry(report({ where: 'Tiles decode' }))), null, 'bad place');
  assert.equal(parseEntry(id, entry(report({ count: 0 }))), null, 'bad count');
  assert.equal(parseEntry(id, entry(report({ device: { ...report().device, app: 'x' } }))), null, 'bad version');
  const extra = parseEntry(id, entry(report({ context: { 'a b': 'x', ok: '1', long: 'y'.repeat(40) } })));
  assert.deepEqual(extra.context, { ok: '1' }, 'context values filtered');
});

test('reports of one fingerprint are one issue, sampled from the newest build', () => {
  const a = parseEntry(`${FP}-aaaaaaaaaa`, entry(report({ count: 2 })));
  const b = parseEntry(`${FP}-bbbbbbbbbb`, entry(report({ count: 3, kind: 'CRASH', device: { ...report().device, app: '0.4.211' } })));
  const [g] = group([a, b]);
  assert.equal(g.count, 5);
  assert.equal(g.maxVersion, '0.4.211');
  assert.equal(g.sample.kind, 'CRASH');
  assert.deepEqual(g.ids.sort(), [`${FP}-aaaaaaaaaa`, `${FP}-bbbbbbbbbb`]);
});

test('issue text is inert: no mention, no fence break, marker kept', () => {
  const r = parseEntry(`${FP}-abcdefghij`, entry(report({ message: 'hi @someone `x`', stack: 'a\n~~~~\n@x' })));
  const [g] = group([r]);
  const text = body(g);
  assert.match(text, MARKER);
  assert.ok(!text.includes('@someone'));
  assert.ok(!text.includes('\n~~~~\n@'));
  assert.ok(title(g).startsWith('[auto] erreur inattendue : IllegalStateException (tiles.decode)'));
  assert.equal(inline('a`b'), '`a b`');
  assert.ok(!block('~~~~').includes('~~~'));
  assert.ok(comment(g, false).startsWith('Vu de nouveau.'));
  const linked = parseEntry(`${FP}-abcdefghij`, entry(report({ device: { ...report().device, device: 'https://evil.example' } })));
  assert.match(body(group([linked])[0]), /`https:\/\/evil\.example`/, 'free text is a code span, not a link');
});

const marker = (max) => `<!-- toolmap-problem fp=${FP} max=${max} -->`;
// The newest android-<version> release: no real build is above it.
const CEILING = '0.4.211';

test('what to do with an issue', () => {
  const [g] = group([parseEntry(`${FP}-abcdefghij`, entry(report()))]);
  assert.equal(decide(g, undefined, CEILING), 'create');
  assert.equal(decide(g, { state: 'open', body: marker('0.4.200') }, CEILING), 'comment');
  assert.equal(decide(g, { state: 'closed', body: marker('0.4.210') }, CEILING), 'drop');
  assert.equal(decide(g, { state: 'closed', body: marker('0.4.209') }, CEILING), 'reopen');
  assert.equal(updatedBody(marker('0.4.209'), g, CEILING), marker('0.4.210'));
  assert.equal(updatedBody(marker('0.4.211'), g, CEILING), null);
  assert.ok(comment(g, true, CEILING).startsWith('**Réapparu en 0.4.210**'));
});

test('a version no build has cannot reopen an issue nor hide later reports', () => {
  const forged = (id, app) => parseEntry(`${FP}-${id}`, entry(report({ device: { ...report().device, app } })));
  const [fake] = group([forged('ffffffffff', '999.0.0')]);
  assert.equal(trusted('999.0.0', CEILING), false);
  assert.equal(trusted('0.4.211', CEILING), true);
  assert.equal(trusted('0.4.200', null), false, 'releases unreadable: nothing is trusted');
  assert.equal(decide(fake, { state: 'closed', body: marker('0.4.210') }, CEILING), 'drop');
  assert.equal(updatedBody(marker('0.4.200'), fake, CEILING), null, 'marker not raised by an open issue either');
  assert.match(body(fake, CEILING), /max=0\.0\.0 -->/, 'a new issue keeps no forged marker');
  // A real report next to a forged one moves the issue up to the real version only.
  const [mixed] = group([forged('ffffffffff', '999.0.0'), forged('aaaaaaaaaa', '0.4.211')]);
  assert.equal(decide(mixed, { state: 'closed', body: marker('0.4.210') }, CEILING), 'reopen');
  assert.equal(updatedBody(marker('0.4.210'), mixed, CEILING), marker('0.4.211'));
  // Without a ceiling, a closed issue stays closed this run.
  assert.equal(decide(mixed, { state: 'closed', body: marker('0.4.210') }, null), 'drop');
});

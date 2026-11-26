import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';
import { openAlerts, parseAlert } from './alerts.js';
import { parseQuote } from './bitunix.js';
import { sendAlert, smsConfig } from './melipayamak.js';
import { alertServer } from './server.js';

const rule = (overrides = {}) => ({
  id: randomUUID(), symbol: 'BTCUSDT', priceType: 'LAST_PRICE', condition: 'ABOVE',
  price: '100', ...overrides
});
const tick = (price, timestamp, priceType = 'LAST_PRICE') =>
  ({ symbol: 'BTCUSDT', priceType, price, timestamp });
const now = 100_000;
const fakeEnv = {
  SMS_MODE: 'live', MELIPAYAMAK_USERNAME: 'fixture', MELIPAYAMAK_API_KEY: 'fake-api-key',
  MELIPAYAMAK_FROM: '50001234', SMS_TO: '+989000000000'
};

test('one event uses the exact comparison and the requested Last or Mark market', t => {
  const store = openAlerts(':memory:');
  t.after(store.close);
  const target = rule({ price: '100000.0000000000000002' });
  assert.equal(store.create(target, 'dry-run', now).code, 201);
  assert.equal(store.create(target, 'dry-run', now).code, 200);
  assert.equal(store.create({ ...target, price: '5' }, 'dry-run', now).code, 409);
  store.observe(tick('999999', now - 1), now);
  store.observe(tick(target.price, now + 1, 'MARK_PRICE'), now + 1);
  store.observe(tick('100000.0000000000000001', now + 2), now + 2);
  store.observe(tick('999999', now + 1), now + 2);
  assert.equal(store.claim(), undefined);
  store.observe(tick(target.price, now + 3), now + 3);
  store.observe(tick('999999', now + 4), now + 4);
  assert.equal(store.claim().id, target.id);
  assert.equal(store.claim(), undefined);
  store.finish(target.id, { status: 'simulated' });
  assert.equal(store.list()[0].status, 'simulated');
});

test('cancelled and uncertain submissions cannot be dispatched again after restart', t => {
  const dir = mkdtempSync(join(tmpdir(), 'safesize-alerts-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const file = join(dir, 'alerts.sqlite');
  const first = openAlerts(file);
  const target = rule({ condition: 'BELOW' }), stopped = rule();
  first.create(target, 'live', now);
  first.create(stopped, 'dry-run', now);
  first.observe(tick('200', now + 1), now + 1);
  assert.equal(first.cancel(stopped.id).data.status, 'cancelled');
  first.observe(tick('80', now + 2), now + 2);
  assert.equal(first.claim().id, target.id);
  first.close();
  const next = openAlerts(file);
  t.after(next.close);
  assert.equal(next.list().find(row => row.id === target.id).status, 'uncertain');
  assert.equal(next.claim(), undefined);
});

test('Bitunix source mapping handles live Last and Mark independently', () => {
  assert.deepEqual(parseQuote({ ch: 'ticker', symbol: 'BTCUSDT', ts: now, data: { s: 'BTCUSDT', la: '41000' } }),
    tick('41000', now));
  assert.deepEqual(parseQuote({ ch: 'price', symbol: 'BTCUSDT', ts: now, data: { mp: '40000' } }),
    tick('40000', now, 'MARK_PRICE'));
  assert.equal(parseQuote({ ch: 'ticker', symbol: 'BTCUSDT', data: { s: 'ETHUSDT', la: '5' } }), null);
  assert.equal(parseAlert(randomUUID(), { ...rule(), price: '1e20' }), null);
});

test('Melipayamak direct and template request shape and provider acceptance', async () => {
  const live = { ...rule(), mode: 'live', triggeredPrice: '101' };
  const providerId = '123456789012345678';
  const accepted = new Response(JSON.stringify({ RetStatus: 1, Value: providerId }));
  const direct = await sendAlert(smsConfig(fakeEnv), live, async (url, params) => {
    assert.match(url, /\/SendSMS\/SendSMS$/);
    assert.equal(params.method, 'POST');
    assert.equal(params.redirect, 'error');
    assert.equal(params.body.get('password'), fakeEnv.MELIPAYAMAK_API_KEY);
    assert.equal(params.body.get('from'), fakeEnv.MELIPAYAMAK_FROM);
    assert.equal(params.body.get('to'), '09000000000');
    assert.match(params.body.get('text'), /BTCUSDT Last >= 100/);
    return accepted.clone();
  });
  assert.deepEqual(direct, { status: 'accepted', providerId });
  const pattern = smsConfig({ ...fakeEnv, MELIPAYAMAK_FROM: '', MELIPAYAMAK_BODY_ID: '321' });
  const templated = await sendAlert(pattern, { ...live, priceType: 'MARK_PRICE' }, async (url, params) => {
    assert.match(url, /\/BaseServiceNumber$/);
    assert.equal(params.body.get('bodyId'), '321');
    assert.equal(params.body.get('text'), 'BTCUSDT;Mark;100;101');
    assert(!params.body.has('from'));
    return accepted.clone();
  });
  assert.deepEqual(templated, direct);
  assert.deepEqual(await sendAlert(pattern, live, () => Promise.reject()), {
    status: 'uncertain', result: 'provider_response_unavailable'
  });
  assert.deepEqual(await sendAlert(pattern, live,
    () => Promise.resolve(Response.json({ RetStatus: 1, Value: '35' }))),
    { status: 'failed', result: 'provider_1_35' });
  assert.deepEqual(await sendAlert(pattern, { ...live, mode: 'dry-run' }, () => {
    assert.fail('dry-run must never contact the provider');
  }), { status: 'simulated' });
});

test('authenticated HTTP can register, list and cancel without a running Android client', async t => {
  const token = 'a'.repeat(64);
  const store = openAlerts(':memory:');
  let attempts = 0;
  const app = alertServer({ token, mode: 'dry-run', store, market: () => true,
    send: async () => { attempts++; return { status: 'simulated' }; } });
  t.after(async () => { await app.stop(); store.close(); });
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  const url = 'http://127.0.0.1:' + app.server.address().port + '/v1/alerts';
  const auth = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };
  assert.equal((await fetch(url)).status, 401);
  const target = rule();
  const body = JSON.stringify(target);
  const endpoint = url + '/' + target.id;
  const request = { method: 'PUT', headers: auth, body };
  assert.equal((await fetch(endpoint, request)).status, 201);
  assert.equal((await fetch(endpoint, request)).status, 200);
  const stopped = rule({ condition: 'BELOW' });
  await fetch(url + '/' + stopped.id, { ...request, body: JSON.stringify(stopped) });
  assert.equal((await fetch(url + '/' + stopped.id, { method: 'DELETE', headers: auth })).status, 200);
  store.observe(tick('105', Date.now() + 1));
  store.observe(tick('105', Date.now() + 1));
  for (let i = 0; !attempts && i < 20; i++) await delay(50);
  const list = await (await fetch(url, { headers: auth })).json();
  assert.equal(list.service, 'safesize-sms-alerts');
  assert.equal(list.alerts.find(row => row.id === target.id).status, 'simulated');
  assert.equal(list.alerts.find(row => row.id === stopped.id).status, 'cancelled');
  assert.equal(attempts, 1);
});

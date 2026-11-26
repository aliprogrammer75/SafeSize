import { createHash, timingSafeEqual } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { idPattern, openAlerts, parseAlert } from './alerts.js';
import { startBitunix } from './bitunix.js';
import { sendAlert, smsConfig } from './melipayamak.js';

const digest = value => createHash('sha256').update(value).digest();
const service = 'safesize-sms-alerts';

function reply(response, status, value) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
}

async function readAlert(request) {
  if (!/^application\/json(?:;|$)/i.test(request.headers['content-type'] ?? '')) return null;
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 4096) return null;
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks)); } catch { return null; }
}

export function alertServer({ token, mode, store, market, send }) {
  const expected = digest('Bearer ' + token);
  let sending = null;
  let stopping = false;
  function pump() {
    if (stopping || sending) return;
    const alert = store.claim();
    if (!alert) return;
    sending = Promise.resolve()
      .then(() => send(alert))
      .catch(() => ({ status: 'uncertain', result: 'delivery_interrupted' }))
      .then(outcome => store.finish(alert.id, outcome))
      .catch(() => console.error('Unable to persist SMS result'))
      .finally(() => { sending = null; });
  }
  const timer = setInterval(pump, 500);
  const server = createServer(async (request, response) => {
    try {
      const path = request.url;
      if (request.method === 'GET' && path === '/health') {
        return reply(response, 200, { ok: true });
      }
      if (!timingSafeEqual(digest(request.headers.authorization ?? ''), expected)) {
        return reply(response, 401, { error: 'unauthorized' });
      }
      if (request.method === 'GET' && path === '/v1/alerts') {
        return reply(response, 200, { service, mode, streamConnected: market(), alerts: store.list() });
      }
      const id = path?.startsWith('/v1/alerts/') ? path.slice(11) : '';
      if (!idPattern.test(id)) return reply(response, 404, { error: 'not_found' });
      let result;
      if (request.method === 'PUT') {
        const rule = parseAlert(id, await readAlert(request));
        if (!rule) return reply(response, 400, { error: 'invalid_alert' });
        result = store.create(rule, mode);
      } else if (request.method === 'DELETE') {
        result = store.cancel(id);
      } else return reply(response, 405, { error: 'method_not_allowed' });
      reply(response, result.code, result.data ? { alert: result.data } : { error: result.error });
    } catch {
      reply(response, 500, { error: 'server_error' });
    }
  });
  server.requestTimeout = 15_000;
  return {
    server,
    async stop() {
      stopping = true;
      clearInterval(timer);
      await new Promise(resolve => server.close(resolve));
      await sending;
    }
  };
}

function main(env) {
  const token = env.ALERT_API_TOKEN;
  if (!/^[a-f0-9]{64}$/.test(token ?? '')) throw new Error('Set ALERT_API_TOKEN to a random 64-character hex token');
  const config = smsConfig(env);
  const port = Number(env.PORT || 8080);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid PORT');
  const file = resolve(env.DB_PATH || './data/alerts.sqlite');
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
  const store = openAlerts(file);
  const feed = startBitunix(store.markets, store.observe);
  const app = alertServer({ token, mode: config.mode, store, market: feed.live, send: alert => sendAlert(config, alert) });
  app.server.listen(port, env.HOST || '127.0.0.1', () => {
    console.log(`SafeSize SMS server listening on ${port} (${config.mode})`);
  });
  async function stop() {
    feed.stop();
    await app.stop();
    store.close();
  }
  process.once('SIGTERM', stop);
  process.once('SIGINT', stop);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { main(process.env); } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

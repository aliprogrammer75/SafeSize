import { DatabaseSync } from 'node:sqlite';

const symbolPattern = /^[A-Z0-9]{2,20}USDT$/;
export const idPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const pricePattern = /^\d{1,20}(?:\.\d{1,16})?$/;
const pendingStates = "('armed','queued','sending')";
const modeValues = ['dry-run', 'live'];

// Convert decimal strings to a common fixed scale instead of rounding prices through doubles.
function decimal(text) {
  if (typeof text !== 'string' || !pricePattern.test(text)) return null;
  const [whole, fraction = ''] = text.split('.');
  const value = BigInt(whole + fraction.padEnd(16, '0'));
  return value > 0n ? value : null;
}

export function parseAlert(id, payload) {
  if (!idPattern.test(id) || !payload || typeof payload !== 'object' || Array.isArray(payload)) return null;
  const { symbol, priceType, condition, price } = payload;
  if (typeof symbol !== 'string' || !symbolPattern.test(symbol) || !decimal(price) ||
      !['LAST_PRICE', 'MARK_PRICE'].includes(priceType) || !['ABOVE', 'BELOW'].includes(condition)) return null;
  return { id, symbol, priceType, condition, price };
}

export function openAlerts(path) {
  const db = new DatabaseSync(path);
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA busy_timeout = 3000;
    CREATE TABLE IF NOT EXISTS alerts (
      id TEXT PRIMARY KEY, symbol TEXT NOT NULL, price_type TEXT NOT NULL,
      condition TEXT NOT NULL, price TEXT NOT NULL, mode TEXT NOT NULL,
      status TEXT NOT NULL, created_at INTEGER NOT NULL, triggered_at INTEGER,
      triggered_price TEXT, provider_id TEXT, result TEXT
    ) STRICT;
    CREATE INDEX IF NOT EXISTS alerts_by_status_market ON alerts(status, symbol, price_type);
    UPDATE alerts SET status='uncertain', result='worker_interrupted'
      WHERE status='sending';
  `);
  const columns = 'id, symbol, price_type AS priceType, condition, price, mode, status, ' +
    'created_at AS createdAt, triggered_at AS triggeredAt, triggered_price AS triggeredPrice, ' +
    'provider_id AS providerId, result';
  const byId = db.prepare(`SELECT ${columns} FROM alerts WHERE id=?`);
  const list = db.prepare(`SELECT ${columns} FROM alerts ORDER BY status IN ${pendingStates} DESC, created_at DESC LIMIT 100`);
  const active = db.prepare(`SELECT id FROM alerts WHERE status IN ${pendingStates} LIMIT 30`);
  const insert = db.prepare(`INSERT INTO alerts(id,symbol,price_type,condition,price,mode,status,created_at)
    VALUES(?,?,?,?,?,?,'armed',?)`);
  const latestByMarket = new Map();
  const markets = db.prepare("SELECT DISTINCT symbol, price_type AS priceType FROM alerts WHERE status='armed'");
  const matches = db.prepare("SELECT id,price,condition FROM alerts WHERE status='armed' AND symbol=? AND price_type=? AND created_at<=?");
  const enqueue = db.prepare("UPDATE alerts SET status='queued', triggered_at=?, triggered_price=? WHERE id=? AND status='armed'");
  const claim = db.prepare(`UPDATE alerts SET status='sending'
    WHERE id=(SELECT id FROM alerts WHERE status='queued' ORDER BY triggered_at LIMIT 1)
    RETURNING ${columns}`);
  const finish = db.prepare("UPDATE alerts SET status=?,provider_id=?,result=? WHERE id=? AND status='sending'");
  const cancel = db.prepare("UPDATE alerts SET status='cancelled' WHERE id=? AND status IN ('armed','queued')");

  return {
    list: () => list.all(),
    markets: () => markets.all(),
    create(rule, mode, now = Date.now()) {
      if (!modeValues.includes(mode)) throw new Error('invalid_alert_mode');
      const previous = byId.get(rule.id);
      if (previous) {
        const same = ['symbol', 'priceType', 'condition', 'price'].every(key => previous[key] === rule[key]);
        return same ? { code: 200, data: previous } : { code: 409, error: 'id_conflict' };
      }
      if (active.all().length >= 30) return { code: 409, error: 'active_limit' };
      insert.run(rule.id, rule.symbol, rule.priceType, rule.condition, rule.price, mode, now);
      return { code: 201, data: byId.get(rule.id) };
    },
    cancel(id) {
      const item = byId.get(id);
      if (!item) return { code: 404, error: 'not_found' };
      if (item.status === 'cancelled' || cancel.run(id).changes) return { code: 200, data: byId.get(id) };
      return { code: 409, error: 'already_processed' };
    },
    observe({ symbol, priceType, price, timestamp }, now = Date.now()) {
      const value = decimal(price);
      if (!value || !Number.isSafeInteger(timestamp) || timestamp < now - 20_000 || timestamp > now + 5_000) return;
      const key = symbol + '/' + priceType;
      if (timestamp < (latestByMarket.get(key) ?? 0)) return;
      const armed = matches.all(symbol, priceType, timestamp);
      if (!armed.length) {
        latestByMarket.delete(key);
        return;
      }
      latestByMarket.set(key, timestamp);
      for (const rule of armed) {
        const threshold = decimal(rule.price);
        if (rule.condition === 'ABOVE' ? value >= threshold : value <= threshold) {
          enqueue.run(timestamp, price, rule.id);
        }
      }
    },
    claim: () => claim.get(),
    finish(id, outcome) { finish.run(outcome.status, outcome.providerId ?? null, outcome.result ?? null, id); },
    close: () => db.close()
  };
}

const channelFor = { LAST_PRICE: 'ticker', MARK_PRICE: 'price' };
const channels = { ticker: ['LAST_PRICE', 'la'], price: ['MARK_PRICE', 'mp'] };

export function parseQuote(message) {
  const mapping = channels[message?.ch];
  if (!Array.isArray(mapping) || typeof message.symbol !== 'string' ||
      (message.data?.s && message.data.s !== message.symbol)) return null;
  const [priceType, field] = mapping;
  const price = message.data?.[field];
  if (typeof price !== 'string') return null;
  return { symbol: message.symbol, priceType, price, timestamp: Number(message.ts) };
}

export function startBitunix(markets, observe, { connect = url => new WebSocket(url), fetchQuotes = fetch } = {}) {
  let socket = null;
  let stopped = false;
  let seen = 0;
  let heartbeatAt = 0;
  let retryAt = 0;
  let delay = 1000;
  let receivedQuote = false;
  let syncing = false;
  const subscribed = new Map();
  const marketKey = ({ symbol, priceType }) => symbol + '/' + channelFor[priceType];

  function clearConnection(connection) {
    if (socket !== connection) return;
    socket = null;
    retryAt = Date.now() + delay;
    delay = Math.min(delay * 2, 30_000);
    connection.close();
  }

  function open() {
    const connection = connect('wss://fapi.bitunix.com/public/');
    socket = connection;
    seen = Date.now();
    heartbeatAt = 0;
    receivedQuote = false;
    subscribed.clear();
    connection.addEventListener('message', event => {
      if (socket !== connection || stopped) return;
      let message;
      try { message = JSON.parse(event.data); } catch { return; }
      seen = Date.now();
      const quote = parseQuote(message);
      if (quote && subscribed.has(marketKey(quote))) {
        receivedQuote = true;
        delay = 1000;
        observe(quote);
      }
    });
    connection.addEventListener('close', () => clearConnection(connection));
    connection.addEventListener('error', () => clearConnection(connection));
  }

  function tick() {
    const wanted = new Map(markets().map(item => [marketKey(item), { symbol: item.symbol, ch: channelFor[item.priceType] }]));
    const now = Date.now();
    if (!wanted.size) {
      if (socket) { const old = socket; socket = null; old.close(); }
      retryAt = 0;
      delay = 1000;
      return;
    }
    if (!socket && now >= retryAt) open();
    if (!socket) return;
    if (now - seen > 45_000) return clearConnection(socket);
    if (socket.readyState !== 1) return;
    const added = [...wanted].filter(([key]) => !subscribed.has(key));
    const removed = [...subscribed].filter(([key]) => !wanted.has(key));
    if (added.length) socket.send(JSON.stringify({ op: 'subscribe', args: added.map(([, arg]) => arg) }));
    if (removed.length) socket.send(JSON.stringify({ op: 'unsubscribe', args: removed.map(([, arg]) => arg) }));
    for (const [key, arg] of added) subscribed.set(key, arg);
    for (const [key] of removed) subscribed.delete(key);
    if (now - heartbeatAt >= 15_000) {
      socket.send(JSON.stringify({ op: 'ping', ping: Math.floor(now / 1000) }));
      heartbeatAt = now;
    }
  }

  // REST also seeds fresh prices when a symbol is quiet or the socket is reconnecting.
  async function snapshot() {
    const requested = markets();
    if (syncing || !requested.length) return;
    syncing = true;
    const receivedAt = Date.now();
    try {
      const symbols = [...new Set(requested.map(market => market.symbol))].join(',');
      const response = await fetchQuotes('https://fapi.bitunix.com/api/v1/futures/market/tickers?symbols=' + symbols,
        { signal: AbortSignal.timeout(8000), redirect: 'error' });
      if (!response.ok) return;
      const body = await response.json();
      if (stopped || body.code !== 0 || !Array.isArray(body.data)) return;
      const bySymbol = new Map(body.data.map(row => [row.symbol, row]));
      for (const { symbol, priceType } of requested) {
        const row = bySymbol.get(symbol);
        const price = priceType === 'MARK_PRICE' ? row?.markPrice : row?.lastPrice;
        if (typeof price === 'string') observe({ symbol, priceType, price, timestamp: receivedAt });
      }
    } catch {
      // The next public snapshot or stream update will be used.
    } finally { syncing = false; }
  }

  const subscriptionTimer = setInterval(tick, 1000);
  const snapshotTimer = setInterval(() => { void snapshot(); }, 15000);
  tick();
  void snapshot();
  return {
    live: () => Boolean(socket?.readyState === 1 && receivedQuote),
    stop() {
      stopped = true;
      clearInterval(subscriptionTimer);
      clearInterval(snapshotTimer);
      if (socket) { const old = socket; socket = null; old.close(); }
    }
  };
}

const providerUrl = 'https://rest.payamak-panel.com/api/SendSMS/';

export function smsConfig(env) {
  const mode = env.SMS_MODE || 'dry-run';
  if (mode === 'dry-run') return { mode };
  if (mode !== 'live') throw new Error('SMS_MODE must be dry-run or live');
  const username = env.MELIPAYAMAK_USERNAME?.trim();
  const apiKey = env.MELIPAYAMAK_API_KEY?.trim();
  const from = env.MELIPAYAMAK_FROM?.trim();
  const bodyId = env.MELIPAYAMAK_BODY_ID?.trim();
  const to = env.SMS_TO?.trim().replace(/^(?:\+98|0098|98)(9\d{9})$/, '0$1');
  if (!username || !apiKey || !/^09\d{9}$/.test(to ?? '')) {
    throw new Error('Live SMS needs MELIPAYAMAK_USERNAME, MELIPAYAMAK_API_KEY and an Iranian SMS_TO');
  }
  if (bodyId ? !/^[1-9]\d{0,9}$/.test(bodyId) || Number(bodyId) > 2147483647 : !/^\d{3,25}$/.test(from ?? '')) {
    throw new Error('Live SMS needs a valid MELIPAYAMAK_BODY_ID or MELIPAYAMAK_FROM');
  }
  return { mode, username, apiKey, from, bodyId, to };
}

export async function sendAlert(config, alert, request = fetch) {
  // Store the requested mode at registration so switching to live never sends old test alerts.
  if (alert.mode === 'dry-run') return { status: 'simulated' };
  if (config.mode !== 'live') return { status: 'failed', result: 'live_mode_disabled' };
  const condition = alert.condition === 'ABOVE' ? '>=' : '<=';
  const source = alert.priceType === 'MARK_PRICE' ? 'Mark' : 'Last';
  const params = new URLSearchParams({ username: config.username, password: config.apiKey, to: config.to });
  let method;
  if (config.bodyId) {
    method = 'BaseServiceNumber';
    params.set('bodyId', config.bodyId);
    params.set('text', [alert.symbol, source, alert.price, alert.triggeredPrice].join(';'));
  } else {
    method = 'SendSMS';
    params.set('from', config.from);
    params.set('isFlash', 'false');
    params.set('text', `SafeSize: ${alert.symbol} ${source} ${condition} ${alert.price}\nقیمت هنگام هشدار: ${alert.triggeredPrice}`);
  }
  try {
    const reply = await request(providerUrl + method, {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(12_000), body: params
    });
    if (!reply.ok) {
      return { status: 'uncertain', result: 'provider_http_' + reply.status };
    }
    const body = await reply.json();
    const value = String(body?.Value ?? '');
    const code = String(body?.RetStatus ?? '');
    if (code === '1' && /^[1-9]\d{15,29}$/.test(value)) {
      return { status: 'accepted', providerId: value };
    }
    if (/^-?\d{1,4}$/.test(code)) {
      return { status: 'failed', result: 'provider_' + code + (/^-?\d{1,4}$/.test(value) ? '_' + value : '') };
    }
    return { status: 'uncertain', result: 'invalid_provider_response' };
  } catch {
    // A timeout can happen after acceptance. Keep it visible for review and do not submit twice.
    return { status: 'uncertain', result: 'provider_response_unavailable' };
  }
}

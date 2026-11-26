'use strict';

// KLineChart indexes real bars. Interpolate between them and use the exchange
// interval beyond either edge, without inserting synthetic candles.
function drawingTimeline(bars, interval) {
 const last = bars.length - 1;
 const unit = {m: 60000, h: 3600000, d: 86400000, w: 604800000};
 const duration = Number(interval.slice(0, -1)) * unit[interval.slice(-1)];
 function boundary(base, count) {
  if (interval !== '1M') return base + count * duration;
  const date = new Date(base);
  date.setUTCMonth(date.getUTCMonth() + count, 1);
  return +date;
 }
 function outsideTime(base, offset) {
  const lower = Math.floor(offset);
  const from = boundary(base, lower);
  return from + (boundary(base, lower + 1) - from) * (offset - lower);
 }
 function outsideIndex(base, time) {
  if (interval !== '1M') return (time - base) / duration;
  const date = new Date(time), origin = new Date(base);
  const month = (date.getUTCFullYear() - origin.getUTCFullYear()) * 12 +
   date.getUTCMonth() - origin.getUTCMonth();
  const from = boundary(base, month);
  return month + (time - from) / (boundary(base, month + 1) - from);
 }
 return {
  time(index) {
   if (index < 0) return outsideTime(bars[0].timestamp, index);
   if (index >= last) return outsideTime(bars[last].timestamp, index - last);
   const floor = Math.floor(index), from = bars[floor].timestamp;
   return from + (bars[floor + 1].timestamp - from) * (index - floor);
  },
  index(timestamp) {
   if (timestamp < bars[0].timestamp) return outsideIndex(bars[0].timestamp, timestamp);
   if (timestamp >= bars[last].timestamp) return last + outsideIndex(bars[last].timestamp, timestamp);
   let lo = 0, hi = last;
   while (hi - lo > 1) {
    const mid = (hi + lo) >> 1;
    if (bars[mid].timestamp <= timestamp) lo = mid;
    else hi = mid;
   }
   return lo + (timestamp - bars[lo].timestamp) / (bars[hi].timestamp - bars[lo].timestamp);
  }
 };
}

function drawingGeometry(d, p, width, height, format, barCount, priceY) {
 const lines = [], fills = [], labels = [];
 const [a, b, c] = p;
 const cfg = d.config;
 const xy = (x, y) => ({x, y});
 const line = (from, to, color = cfg.color, size = cfg.width, style = cfg.style) =>
  lines.push({from, to, color, size, style});
 const fill = (points, color = cfg.background, opacity = cfg.opacity) =>
  fills.push({points, color, opacity});
 const label = (x, y, text, color = cfg.color, align = 'left', size = 11) =>
  labels.push({x, y, text, color, align, size});
 const rect = (x1, y1, x2, y2) => [xy(x1,y1), xy(x2,y1), xy(x2,y2), xy(x1,y2)];
 const border = vertices => vertices.forEach((v, i) => line(v, vertices[(i + 1) % vertices.length]));
 function extend(from, to, left, right) {
  const dx = to.x - from.x, dy = to.y - from.y;
  if (Math.abs(dx) < .01) {
   if (left && right) return [xy(from.x, 0), xy(from.x, height)];
   if (right) return [from, xy(from.x, dy < 0 ? 0 : height)];
   return [from, to];
  }
  const y = x => from.y + (x - from.x) / dx * dy;
  const start = dx > 0 ? 0 : width, end = dx > 0 ? width : 0;
  return [left ? xy(start, y(start)) : from, right ? xy(end, y(end)) : to];
 }
 function fibLabel(from, to, level, value, color) {
  const parts = [];
  if (cfg.showPercent) parts.push(cfg.labelFormat === 'number' ? String(level) : Number((level * 100).toFixed(4)) + '%');
  if (cfg.showPrice) parts.push(format(value));
  if (!parts.length) return;
  const left = Math.min(from.x, to.x), right = Math.max(from.x, to.x);
  let x = cfg.labels === 'right' ? right - 5 : cfg.labels === 'center' ? (left + right) / 2 : left + 5;
  x = Math.max(4, Math.min(width - 4, x));
  const y = Math.abs(to.x - from.x) > .01 ?
   from.y + (x - from.x) / (to.x - from.x) * (to.y - from.y) : from.y;
  label(x,y+(cfg.labelOffset==='below'?(cfg.labelSize||11)+3:-4),parts.join('  ·  '),color,cfg.labels,cfg.labelSize);
 }
 if (d.name === 'horizontalStraightLine') {
  line(xy(0, a.y), xy(width, a.y)); label(width - 6, a.y - 4, format(d.points[0].value), cfg.color, 'right');
 } else if (d.name === 'horizontalRay') {
  line(a, xy(width, a.y)); label(width - 6, a.y - 4, format(d.points[0].value), cfg.color, 'right');
 } else if (d.name === 'verticalStraightLine') {
  line(xy(a.x, 0), xy(a.x, height));
 } else if (d.name === 'straightLine') {
  line(...extend(a, b, true, true));
 } else if (d.name === 'rayLine') {
  line(...extend(a, b, false, true));
 } else if (d.name === 'segment') {
  line(a, b);
 } else if (d.name === 'rectangleZone' || d.name === 'priceRange' || d.name === 'measureMove') {
  const box = rect(a.x, a.y, b.x, b.y);
  fill(box); border(box);
  if (d.name !== 'rectangleZone') {
   const diff = d.points[1].value - d.points[0].value;
   const pct = d.points[0].value ? (diff / d.points[0].value * 100).toFixed(2) + '%' : '—';
   const bars = Math.abs(barCount(d.points[0].timestamp, d.points[1].timestamp));
   const elapsed = Math.abs(d.points[1].timestamp - d.points[0].timestamp);
   const duration = elapsed >= 86400000 ? (elapsed / 86400000).toFixed(1) + 'd' :
    elapsed >= 3600000 ? (elapsed / 3600000).toFixed(1) + 'h' : Math.round(elapsed / 60000) + 'm';
   const price = format(diff) + '  (' + pct + ')';
   label((a.x + b.x) / 2, Math.min(a.y, b.y) - 7,
    d.name === 'priceRange' ? price : price + '  ·  ' + bars + ' bars  ·  ' + duration, cfg.color, 'center');
  }
 } else if (d.name === 'longPosition' || d.name === 'shortPosition') {
  const left = Math.min(a.x, b.x), right = Math.max(a.x, b.x);
  const target = d.points[1].value, stop = d.points[2].value, entry = d.points[0].value;
  const risk = Math.abs(entry - stop), reward = Math.abs(target - entry);
  const valid = d.name === 'longPosition' ? target > entry && stop < entry : target < entry && stop > entry;
  const profitColor = cfg.targetColor, stopColor = cfg.riskColor;
  fill(rect(left, a.y, right, b.y), profitColor, cfg.opacity);
  fill(rect(left, a.y, right, c.y), stopColor, cfg.opacity);
  line(xy(left, b.y), xy(right, b.y), profitColor);
  line(xy(left, c.y), xy(right, c.y), stopColor);
  line(xy(left, a.y), xy(right, a.y));
  label(left + 5, b.y - 5, 'TP ' + format(target), profitColor);
  label(left + 5, c.y - 5, 'SL ' + format(stop), stopColor);
  label(left + 5, a.y - 5, 'R:R ' + (valid && risk > 0 ? (reward / risk).toFixed(2) : '—'));
 } else {
  const dx = b.x - a.x;
  const anchor = c ? Math.abs(dx) > .01 ? (c.x - a.x) / dx : 0 : 0;
  const fromPrice = d.points[0].value, toPrice = d.points[1].value;
  const baseOffset = c ? d.points[2].value - (fromPrice + anchor * (toPrice - fromPrice)) : 0;
  const channel = (v) => {
   const offset = c ? baseOffset * v : 0;
   const value = c ? (fromPrice + toPrice) / 2 + offset : toPrice + (fromPrice - toPrice) * v;
   const left = c ? xy(a.x, priceY(fromPrice + offset)) : xy(Math.min(a.x,b.x), priceY(value));
   const right = c ? xy(b.x, priceY(toPrice + offset)) : xy(Math.max(a.x,b.x), left.y);
   return {value, ends: extend(left, right, dx < 0 && c ? cfg.extendRight : cfg.extendLeft,
    dx < 0 && c ? cfg.extendLeft : cfg.extendRight)};
  };
  const levels = d.name === 'fibonacciLine' || d.name === 'fibChannel' ? cfg.fib :
   (d.name === 'priceChannelLine' ? [0, .5, 1] : [0, 1]).map(v => ({v, on: true}));
  if(cfg.trend&&(d.name==='fibonacciLine'||d.name==='fibChannel'))line(a,b,cfg.color,cfg.width,'dashed');
  const enabled = levels.filter(l => l.on).sort((l, r) => l.v - r.v);
  let previous = null;
  for (const level of enabled) {
   const {value, ends} = channel(cfg.reverse ? 1 - level.v : level.v);
   if (previous) fill([previous[0], previous[1], ends[1], ends[0]]);
   const color = level.color || cfg.color;
   line(...ends, color, level.width || cfg.width, level.style || cfg.style);
   if (d.name === 'fibonacciLine' || d.name === 'fibChannel') fibLabel(...ends, level.v, value, color);
   previous = ends;
  }
 }
 return {lines, fills, labels};
}

function drawingDistance(point, from, to) {
 const dx = to.x - from.x, dy = to.y - from.y, length = dx * dx + dy * dy;
 const t = length ? Math.max(0, Math.min(1, ((point.x - from.x) * dx + (point.y - from.y) * dy) / length)) : 0;
 return Math.hypot(point.x - from.x - t * dx, point.y - from.y - t * dy);
}

function drawingContains(point, polygon) {
 let inside = false;
 for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
  const a = polygon[i], b = polygon[j];
  if ((a.y > point.y) !== (b.y > point.y) &&
      point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside;
 }
 return inside;
}

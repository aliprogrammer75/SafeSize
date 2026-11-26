'use strict';

const layer = $('drawing-layer');
const surface = $('chartwrap');
const context = layer.getContext('2d');
const numberOfPoints = {
 horizontalStraightLine: 1, horizontalRay: 1, verticalStraightLine: 1, segment: 2,
 rayLine: 2, straightLine: 2, priceChannelLine: 3, parallelStraightLine: 3,
 fibonacciLine: 2, fibChannel: 3, rectangleZone: 2, priceRange: 2, measureMove: 2,
 longPosition: 3, shortPosition: 3
};
const defaultFib = [0, .236, .382, .5, .618, .66, .705, .786, 1, 1.618].map(v => ({v, on: true}));
const defaults = {
 color: '#8b9efa', width: 2, style: 'solid', snap: 'normal', fib: defaultFib,
 reverse: false, extendLeft: false, extendRight: false, showPrice: true, showPercent: true,
 background:'#8b9efa', opacity:.13, labels:'left', labelFormat:'percent', labelOffset:'above', labelSize:11, trend:false, targetColor:'#16a394', riskColor:'#e24b67'
};
let placement = null, gesture = null, frame = 0, pricePrecision = 8;
let timelineInterval = '15m', drawingKey = '';
let serial = 0;
let magnet = read('magnet', null);
const snapFor = cfg => magnet ?? cfg.snap;
const pointers = new Map();
let pinch = null, pinchActive = false, blockNativeUntil = 0, blank = null, holdTimer = 0;
let undoStack = [], redoStack = [];

function config(value) {
 return {...defaults, ...value, fib: Array.isArray(value?.fib) ? value.fib : defaultFib};
}
function metadata(id) { return drawings.find(d => d.id === id); }
function formatValue(value) {
 return value.toLocaleString('en-US', {maximumFractionDigits: pricePrecision, useGrouping: false});
}
function saveDraw() { save(drawingKey, drawings); }
function newDrawingId() { return crypto.randomUUID?.() || Date.now() + '-' + (++serial); }
function toggleLayoutCursor() {
 surface.classList.toggle('drawing-active', !!placement);
 $('toolbar').classList.toggle('placing', !!placement);
}
function selection(id) {
 selected = id;
 const d = metadata(id);
 $('selection').hidden = !d;
 $('selname').textContent = d ? toolLabel(d.name) : '';
 $('sellock').textContent = d?.lock ? '🔓' : '🔒';
 $('sellock').setAttribute('aria-label', d?.lock ? 'باز کردن قفل' : 'قفل ابزار');
 redrawLayer();
}
function historyButtons() {
 $('undo').disabled = !undoStack.length;
 $('redo').disabled = !redoStack.length;
}
function record(previous) {
 undoStack.push(previous);
 if (undoStack.length > 60) undoStack.shift();
 redoStack = [];
 saveDraw();
 historyButtons();
 redrawLayer();
}
function editDrawings(next, id = selected) {
 const previous = drawings;
 drawings = next;
 selection(id);
 record(previous);
}
function changeDrawing(d, changes) {
 editDrawings(drawings.map(item => item.id === d.id ? {...item, ...changes} : item), d.id);
}
function deleteDrawing(id) {
 if (metadata(id)) editDrawings(drawings.filter(d => d.id !== id), selected === id ? null : selected);
}
function historyChange(from, to) {
 if (!from.length) return;
 cancelTouch();
 to.push(drawings);
 drawings = from.pop();
 selection(null);
 saveDraw();
 historyButtons();
}
function restoreDrawings(symbol, type) {
 drawingKey = 'draw:v2:' + symbol + '|' + type;
 const stored = read(drawingKey, null);
 const valid = raw => raw && Object.prototype.hasOwnProperty.call(numberOfPoints,raw.name) &&
  raw.points?.length === numberOfPoints[raw.name] &&
  raw.points.every(p => Number.isFinite(p.timestamp) && Number.isFinite(p.value));
 if (Array.isArray(stored)) {
  drawings = stored.filter(valid).map(d => ({...d, config:config(d.config)}));
 } else {
  // Earlier builds kept independent copies in each interval.
  const known = new Set();
  drawings = [];
  for (let i = 0; i < localStorage.length; i++) {
   const key = localStorage.key(i);
   if (!key.startsWith('draw:' + symbol + '|') || !key.endsWith('|' + type)) continue;
   const list = read(key, []);
   if (!Array.isArray(list)) continue;
   for (const o of list.filter(valid)) {
    const fingerprint = JSON.stringify([o.name, o.pane || 'main', o.points, o.config, o.lock, o.visible]);
    if (known.has(fingerprint)) continue;
    known.add(fingerprint);
    const legacy = o.name === 'fibonacciLine' ? {...o.config, reverse:true} : o.config;
    drawings.push({...o, id:newDrawingId(), pane:o.pane || 'main', config:config(legacy)});
   }
  }
  saveDraw();
 }
 historyButtons();
 redrawLayer();
}
function panes() {
 const root = surface.getBoundingClientRect();
 return [['main', 'candle_pane'], ...Object.entries(indicators)
  .filter(([, v]) => v.pane !== 'candle_pane' && !v.hidden).map(([k, v]) => [k, v.pane])]
  .map(([key, id]) => {
   const dom = chart?.getDom(id, 'main');
   if (!dom) return null;
   const rect = dom.getBoundingClientRect();
   return {key, id, rect, left:rect.left - root.left, top:rect.top - root.top};
  }).filter(p => p && p.rect.width > 0 && p.rect.height > 0);
}
function atPane(x, y) {
 return panes().find(({rect:r}) => x >= r.left && x <= r.right && y >= r.top && y <= r.bottom);
}
function mapper(pane) {
 const data = chart.getDataList();
 if (!data.length) return null;
 const {id, rect} = pane;
 const finder = {paneId:id, absolute:false};
 const space = chart.getBarSpace();
 const reference = Math.max(0, Math.min(data.length - 1, chart.getVisibleRange().from));
 const origin = chart.convertToPixel({dataIndex:reference}, finder).x - reference * space;
 const indexAt = x => (x - origin) / space;
 const timeline = drawingTimeline(data, timelineInterval);
 const projectValue = value => chart.convertToPixel({value}, finder).y;
 function point(x, y, snap = 'normal') {
  let index = indexAt(x);
  let value = chart.convertFromPixel({y}, finder).value;
  const nearest = Math.round(index), bar = data[nearest];
  if (pane.key === 'main' && bar && snap !== 'normal') {
   const target = [bar.open, bar.high, bar.low, bar.close].reduce((a,b) =>
    Math.abs(projectValue(a)-y) <= Math.abs(projectValue(b)-y) ? a : b);
   if (snap === 'strong_magnet' || (Math.abs(projectValue(target) - y) <= 12 && Math.abs(index-nearest)*space <= 10)) {
    value = target;
    index = nearest;
   }
  }
  const timestamp = Math.round(timeline.time(index));
  return Number.isFinite(timestamp) && Number.isFinite(value) ? {timestamp, value} : null;
 }
 return {
  point, atClient:(x,y,snap) => point(x - rect.left,y - rect.top,snap),
  xy:p => ({x:origin + timeline.index(p.timestamp) * space, y:projectValue(p.value)}),
  valueY:projectValue, barsBetween:(a,b) => Math.round(timeline.index(b)-timeline.index(a))
 };
}
function isPosition(name) { return name === 'longPosition' || name === 'shortPosition'; }
function geometry(d, pane, map) {
 const points = d.points.map(map.xy);
 if (isPosition(d.name)) points[2].x = points[1].x;
 return {points, ...drawingGeometry(d, points, pane.rect.width, pane.rect.height,
  formatValue, map.barsBetween, map.valueY)};
}
function polygon(ctx, points) {
 ctx.beginPath(); ctx.moveTo(points[0].x, points[0].y);
 for (const p of points.slice(1)) ctx.lineTo(p.x,p.y);
 ctx.closePath();
}
function renderGeometry(d, g, handles) {
 for (const {points,color,opacity} of g.fills) {
  if (!opacity) continue;
  context.globalAlpha = opacity; context.fillStyle = color;
  polygon(context,points); context.fill(); context.globalAlpha = 1;
 }
 for (const {from,to,color,size,style} of g.lines) {
  context.strokeStyle = color; context.lineWidth = size;
  context.setLineDash(style === 'dashed' ? [7,5] : style === 'dotted' ? [2,4] : []);
  context.beginPath(); context.moveTo(from.x,from.y); context.lineTo(to.x,to.y); context.stroke();
 }
 context.setLineDash([]);
 context.font = '11px system-ui,sans-serif'; context.textAlign = 'left';
 for (const {x,y,text,color,align,size} of g.labels) {
  context.font = (size||11)+'px system-ui,sans-serif';
  context.fillStyle = color; context.textAlign = align; context.fillText(text,x,y);
 }
 if (!handles) return;
 context.fillStyle = document.body.classList.contains('light') ? '#fff' : '#101825';
 context.strokeStyle = d.config.color; context.lineWidth = 2;
 for (const p of g.points) {
  context.beginPath(); context.arc(p.x,p.y,5,0,Math.PI*2); context.fill(); context.stroke();
 }
}
function draftDrawing() {
 if ((!placement?.pane && gesture?.kind !== 'place') || !placement || (placement.level || placement.alert)) return null;
 const points = [...placement.points];
 if (gesture?.kind === 'place' && gesture.point) {
  if (isPosition(placement.name)) return {...placement, pane:gesture.pane.key, points:positionPoints(placement.name,gesture.point,gesture.pane)};
  points.push(gesture.point);
 }
 if (!points.length) return null;
 while (points.length < numberOfPoints[placement.name]) points.push(points[points.length - 1]);
 return {...placement, pane:placement.pane || gesture.pane.key, points};
}
const tradeColors={
 entry:'#639aff', fill:'#639aff', tp:'#2bbfad', sl:'#fa728c', pending:'#fbbf54',
 'pending-tp':'#2bbfad','pending-sl':'#fa728c', alert:'#c895f7'
};
function renderTradeLevels(pane,map){
 const w=pane.rect.width,h=pane.rect.height;
 const trades=read('hideTradeLevels',false)?[]:accountLevels;
 const levels=[...trades,...chartAlerts.map(l=>({...l,kind:'alert'}))];
 const tags=[];
 for(const l of levels){
  const y=map.valueY(l.price),color=tradeColors[l.kind];
  if(!Number.isFinite(y)||y<4||y>h-4||!color)continue;
  if(l.kind==='fill'){
   if(l.timestamp>0){
    const x=map.xy({timestamp:l.timestamp,value:l.price}).x;
    if(x<0||x>w)continue;
    context.fillStyle=color;
    polygon(context,[{x,y:y-6},{x:x+6,y},{x,y:y+6},{x:x-6,y}]);context.fill();
    tags.push({y,x:Math.min(w-10,x),color,label:l.label});
   }
   continue;
  }
  context.strokeStyle=color;context.lineWidth=1;
  context.setLineDash(l.kind==='entry'?[]:l.kind.startsWith('pending')?[2,4]:[6,4]);
  context.globalAlpha=.76;
  context.beginPath();context.moveTo(0,y);context.lineTo(w,y);context.stroke();
  context.globalAlpha=1;
  tags.push({y,x:w-5,color,label:l.label+'  '+formatValue(l.price)});
 }
 context.setLineDash([]);context.font='11px system-ui,sans-serif';context.textAlign='right';
 const spacing=17;
 const visible=tags.sort((a,b)=>a.y-b.y).slice(0,Math.floor((h-8)/spacing));
 let upper=0;
 for(const tag of visible){tag.y=Math.max(tag.y,upper+spacing);upper=tag.y;}
 let lower=h+2;
 for(const tag of visible.slice().reverse()){tag.y=Math.min(tag.y,lower-spacing);lower=tag.y;}
 for(const {y,x,color,label} of visible){
  const width=context.measureText(label).width;
  const to=Math.max(width+10,x);
  context.fillStyle=document.body.classList.contains('light')?'#fff':'#101825';
  context.fillRect(to-width-6,y-13,width+10,16);
  context.fillStyle=color;context.fillText(label,to,y-1);
 }
}
function renderDrawings() {
 frame = 0;
 const w = surface.clientWidth, h = surface.clientHeight, ratio = window.devicePixelRatio || 1;
 const width = Math.round(w*ratio), height = Math.round(h*ratio);
 if (layer.width !== width) layer.width = width;
 if (layer.height !== height) layer.height = height;
 context.setTransform(ratio,0,0,ratio,0,0);
 context.clearRect(0,0,w,h);
 if (!initialized) return;
 const draft = draftDrawing();
 for (const pane of panes()) {
  const items = drawings.filter(d => d.visible && visibleAtInterval(d) && d.pane === pane.key).map(d => d.id === gesture?.draft?.id ? gesture.draft : d);
  if (draft?.pane === pane.key) items.push(draft);
  const marketLayer=pane.key==='main'&&(chartAlerts.length||accountLevels.length);
  if (!items.length&&!marketLayer) continue;
  const map = mapper(pane);
  if (!map) continue;
  context.save(); context.translate(pane.left,pane.top);
  context.beginPath(); context.rect(0,0,pane.rect.width,pane.rect.height); context.clip();
  if(marketLayer)renderTradeLevels(pane,map);
  for (const d of items) renderGeometry(d,geometry(d,pane,map),d.id === selected || d === draft);
  context.restore();
 }
}
function redrawLayer() {
 if (!frame) frame = requestAnimationFrame(renderDrawings);
}
function hitDrawing(pane, x, y) {
 const map = mapper(pane);
 if (!map) return null;
 const point = {x:x - pane.rect.left, y:y - pane.rect.top};
 const candidates = drawings.filter(d => d.visible && visibleAtInterval(d) && d.pane === pane.key).reverse();
 const active = candidates.find(d => d.id === selected);
 if (active) {
  const pixels = geometry(active,pane,map).points;
  const handle = pixels.map((v,i) => ({i, distance:Math.hypot(point.x-v.x,point.y-v.y)}))
   .filter(v => v.distance <= 17).sort((a,b) => a.distance-b.distance)[0];
  if (handle) return {drawing:active, handle:handle.i};
 }
 const figures = candidates.map(d => ({drawing:d, shape:geometry(d,pane,map)}));
 for (const {drawing,shape} of figures) {
  if (shape.lines.some(l => drawingDistance(point,l.from,l.to) <= Math.max(10,l.size+4))) return {drawing, handle:-1};
 }
 for (const {drawing,shape} of figures) {
  if (shape.fills.some(f => drawingContains(point,f.points))) return {drawing, handle:-1};
 }
 return null;
}
function cancelTouch() {
 if (gesture) finishGesture(null, false);
 placement = null;
 $('guide').hidden = true;
 $('cancel').hidden = true;
 toggleLayoutCursor();
 refreshQuick();
 redrawLayer();
}
function startPlacement(name) {
 if (!initialized) { say('ابتدا تاریخچه نمودار را دریافت کنید'); return; }
 cancelTouch();
 selection(null);
 placement = {name, pane:null, points:[], config:config(read('tool:'+name,{...defaults,intervals:[timelineInterval]}))};
 say(isPosition(name) ?
  'قیمت ورود را انتخاب کنید و سپس دستگیره‌های سود و ضرر را تنظیم کنید' : 'برای ثبت نقطه، روی نمودار لمس کنید و انگشت را بردارید');
 $('cancel').hidden = false;
 toggleLayoutCursor();
 refreshQuick();
 closeSheet();
}
function showGuide(pane, map, point) {
 const guide = $('guide');
 const xy = map.xy(point);
 const x = pane.left + xy.x;
 const guideLabel = $('guide-label');
 guideLabel.style.marginLeft = (Math.max(89,Math.min(surface.clientWidth-89,x)) - x) + 'px';
 guide.classList.toggle('label-below',pane.top+xy.y<52);
 guide.hidden = false;
 guide.style.left = pane.left + xy.x + 'px';
 guide.style.top = pane.top + xy.y + 'px';
 guideLabel.textContent = formatValue(point.value) + '  ·  ' +
  new Date(point.timestamp).toISOString().slice(0,16).replace('T',' ') + ' UTC';
}
function updateGesture(e) {
 const g = gesture;
 if (g.kind === 'cursor') {
  const {rect:r,id} = g.pane, map = mapper(g.pane);
  const x = Math.max(0,Math.min(r.width,e.clientX-r.left));
  const y = Math.max(0,Math.min(r.height,e.clientY-r.top));
  chart.executeAction('onCrosshairChange',{x,y,paneId:id});
  const point = map?.point(x,y);
  if (point) {g.cursorPoint=point;showGuide(g.pane,map,point)}
  return;
 }
 if (g.kind !== 'place' && g.kind !== 'edit') { moveAxis(g,e);return; }
 const map = mapper(g.pane);
 if (!map) return;
 if (g.kind === 'place') {
  const r = g.pane.rect;
  g.point = map.atClient(Math.max(r.left,Math.min(r.right,e.clientX)),
   Math.max(r.top,Math.min(r.bottom,e.clientY)), snapFor(placement.config));
  if (g.point) showGuide(g.pane,map,g.point);
 } else {
  const dx = e.clientX-g.x, dy = e.clientY-g.y;
  if (!g.moved && Math.hypot(dx,dy) < 4) return;
  g.moved = true;
  const movedPoint = (v,i) => {
   if (g.handle >= 0 && g.handle !== i) return v;
   const start = g.original[i];
   const snap = g.handle >= 0 ? snapFor(g.drawing.config) : 'normal';
   const next = map.point(start.x+dx,start.y+dy,snap);
   if (next && g.handle >= 0) showGuide(g.pane,map,next);
   if (next && isPosition(g.drawing.name) && g.handle === 2) next.timestamp = v.timestamp;
   return next || v;
  };
  g.draft = {...g.drawing, points:g.drawing.points.map(movedPoint)};
 }
 redrawLayer();
}
function positionPoints(name, point, pane) {
 const map = mapper(pane);
 const xy = map.xy(point);
 const direction = name === 'longPosition' ? 1 : -1;
 const x = xy.x + Math.min(100,pane.rect.width*.3);
 const distance = Math.min(42,pane.rect.height*.2);
 return [point, map.point(x,xy.y-distance*direction), map.point(x,xy.y+distance*direction)];
}
function finishGesture(e, commit) {
 const g = gesture;
 if (!g) return;
 if (commit && e) updateGesture(e);
 gesture = null;
 $('guide').hidden = true;
 if (g.kind === 'cursor') {
  if(commit&&g.cursorPoint&&g.pane.key==='main')lastPickedPrice=g.cursorPoint.value;
  return;
 }
 if (g.kind !== 'place' && g.kind !== 'edit') { finishAxis(g,commit);redrawLayer();return; }
 $('guide').hidden = true;
 if (commit && g.kind === 'place' && g.point) {
  placement.pane = g.pane.key;
  if (isPosition(placement.name)) placement.points = positionPoints(placement.name,g.point,g.pane);
  else placement.points.push(g.point);
  if (placement.alert) {
   const delivery=placement.alert;
   cancelTouch();alertMenu(g.point.value,delivery);
  } else if (placement.level) {
   if (window.ChartNative) ChartNative.select(placement.level,String(g.point.value));
   cancelTouch();
   say('سطح به ماشین‌حساب منتقل شد');
  } else if (placement.points.length === numberOfPoints[placement.name]) {
   const d = {...placement, id:newDrawingId(), lock:false, visible:true};
   cancelTouch();
   editDrawings([...drawings,d],d.id);
   say('برای ویرایش، دستگیره یا بدنه ابزار را بکشید');
  } else {
   say('نقطه ' + (placement.points.length+1) + ' از ' + numberOfPoints[placement.name]);
  }
 } else if (commit && g.draft) {
  changeDrawing(g.drawing,{points:g.draft.points});
 }
 redrawLayer();
}
function inside(dom,x,y) {
 if (!dom) return false;
 const r = dom.getBoundingClientRect();
 return x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
}
let previousAxisTap = null;
function priceAxis(pane) {
 // KLineChart 9.8 does not expose manual price ranges on its public Chart API.
 return chart.getDrawPaneById(pane.id).getAxisComponent();
}
function axisGesture(e) {
 if (e.pointerType !== 'touch') return null;
 const {clientX:x,clientY:y} = e;
 if (inside(chart.getDom('x_axis_pane','main'),x,y)) {
  const pane = panes()[0];
  return {kind:'timeScale',pane,x,y,space:chart.getBarSpace()};
 }
 const pane = panes().find(p => inside(chart.getDom(p.id,'yAxis'),x,y));
 if (!pane) return null;
 const axis = priceAxis(pane);
 return {kind:'priceScale',pane,x,y,axis,range:axis.getRange()};
}
function startGesture(e, g) {
 gesture = {...g,id:e.pointerId};
 e.preventDefault();
 pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
 surface.setPointerCapture(e.pointerId);
}
function moveAxis(g,e) {
 const dx = e.clientX-g.x, dy = e.clientY-g.y;
 if (!g.moved && Math.hypot(dx,dy) < 4) return;
 g.moved = true;
 if (g.kind === 'timeScale') {
  const scale = Math.exp(Math.max(-2,Math.min(2,-dx/165)));
  chart.zoomAtCoordinate(g.space*scale/chart.getBarSpace(),{x:g.x-g.pane.rect.left,y:0});
 } else {
  const {range,axis} = g;
  const delta = g.kind === 'pricePan' ? range.range*dy/g.pane.rect.height : 0;
  const scale = g.kind === 'priceScale' ? Math.exp(Math.max(-2,Math.min(2,dy/140))) : 1;
  const midpoint = (range.from+range.to)/2+delta;
  const span = range.range*scale;
  const from = midpoint-span/2, to = midpoint+span/2;
  if (!Number.isFinite(from) || !Number.isFinite(to) || span <= 0) return;
  const realFrom = axis.convertToRealValue(from),realTo = axis.convertToRealValue(to);
  axis.setRange({from,to,range:span,realFrom,realTo,realRange:realTo-realFrom});
  chart.adjustPaneViewport(false,true,true,true);
 }
 redrawLayer();
}
function finishAxis(g,commit) {
 if (!commit || g.moved || g.kind !== 'priceScale') return;
 const time = performance.now();
 if (previousAxisTap?.pane === g.pane.id && time - previousAxisTap.time < 325) {
  g.axis.setAutoCalcTickFlag(true);
  chart.adjustPaneViewport(false,true,true,true);
  previousAxisTap = null;
 } else previousAxisTap = {pane:g.pane.id,time};
}
function drawingPointerDown(e) {
 if (!initialized || e.button !== 0) return;
 const targetInPlot = e.target.closest('#chart') || e.target === surface;
 if (!targetInPlot && !gesture && !pinchActive) return;
 const pane = atPane(e.clientX,e.clientY);
 if (gesture || pinchActive) {
  if (e.pointerType !== 'touch') return;
  e.preventDefault();
  pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
  surface.setPointerCapture(e.pointerId);
  if (gesture) finishGesture(null,false);
  pinchActive = true; pinch = null;
  return;
 }
 if (e.pointerType === 'touch' && !e.isPrimary) {
  clearTimeout(holdTimer);blank = null;return;
 }
 if (!pane) {
  const axis = axisGesture(e);
  if (axis) startGesture(e,axis);
  return;
 }
 const hit = !placement && hitDrawing(pane,e.clientX,e.clientY);
 if (!placement && !hit) {
  blank = {id:e.pointerId,x:e.clientX,y:e.clientY,pane,time:performance.now()};
  if(e.pointerType === 'touch'){
   clearTimeout(holdTimer);
   holdTimer = setTimeout(()=>{
    if(blank?.id!==e.pointerId)return;
    blank=null;
    startGesture(e,{kind:'cursor',pane});
    updateGesture(e);
   },430);
  }
  return;
 }
 blank = null;
 if (placement && (((placement.level || placement.alert) && pane.key !== 'main') || (placement.pane && placement.pane !== pane.key))) {
  say('نقطه را در همان پنل انتخاب کنید'); return;
 }
 if (hit) {
  selection(hit.drawing.id);
  if (hit.drawing.lock) return;
  const map = mapper(pane);
  startGesture(e,{kind:'edit', pane, ...hit, x:e.clientX, y:e.clientY,
   original:geometry(hit.drawing,pane,map).points});
 } else startGesture(e,{kind:'place',pane});
 updateGesture(e);
}
function drawingPointerMove(e) {
 if (blank?.id === e.pointerId) {
  const {x,y,pane,time} = blank;
  const dx = Math.abs(e.clientX-x), dy = Math.abs(e.clientY-y);
  if (Math.hypot(dx,dy) >= 7) {
   clearTimeout(holdTimer);blank = null;
   if (e.pointerType === 'touch' && dy > dx*1.6 && performance.now()-time < 310) {
    const axis = priceAxis(pane);
    startGesture(e,{kind:'pricePan',pane,x,y,axis,range:axis.getRange()});
   }
  }
 }
 if (!pointers.has(e.pointerId)) { if (e.buttons) redrawLayer(); return; }
 pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
 if (pinchActive) {
  const [a,b] = pointers.values();
  if (!b) return;
  const next = {distance:Math.hypot(a.x-b.x,a.y-b.y), x:(a.x+b.x)/2};
  const p = atPane(next.x,(a.y+b.y)/2);
  if (p && pinch?.distance > 0 && next.distance > 0) {
   chart.zoomAtCoordinate(next.distance/pinch.distance,{x:next.x-p.rect.left,y:0});
   chart.scrollByDistance(next.x-pinch.x);
   redrawLayer();
  }
  pinch = next;
 } else if (gesture?.id === e.pointerId) updateGesture(e);
}
function drawingPointerEnd(e, commit) {
 if (blank?.id === e.pointerId) {
  clearTimeout(holdTimer);
  if (commit && performance.now()-blank.time<350 && Math.hypot(e.clientX-blank.x,e.clientY-blank.y) < 7) selection(null);
  blank = null;
 }
 if (!pointers.delete(e.pointerId)) return;
 blockNativeUntil = performance.now() + 80;
 if (gesture?.id === e.pointerId) finishGesture(e,commit);
 if (!pointers.size) { pinch = null; pinchActive = false; }
}
surface.addEventListener('pointerdown',drawingPointerDown,true);
surface.addEventListener('pointermove',drawingPointerMove,true);
surface.addEventListener('pointerup',e=>drawingPointerEnd(e,true),true);
surface.addEventListener('pointercancel',e=>drawingPointerEnd(e,false),true);
surface.addEventListener('lostpointercapture',e=>drawingPointerEnd(e,false),true);
for (const type of ['touchstart','touchmove','touchend','touchcancel','mousedown','mousemove','mouseup']) {
 surface.addEventListener(type,e => {
  if ((gesture || pinchActive || performance.now() < blockNativeUntil) && e.target.closest('#chart')) {
   if (e.cancelable) e.preventDefault();
   e.stopPropagation();
  } else if (type === 'touchmove') redrawLayer();
 },{capture:true,passive:false});
}
$('undo').onclick = () => historyChange(undoStack,redoStack);
$('redo').onclick = () => historyChange(redoStack,undoStack);
$('cancel').onclick = () => { cancelTouch(); say('ترسیم لغو شد'); };
$('sellock').onclick = () => { const d = metadata(selected); if (d) changeDrawing(d,{lock:!d.lock}); };
document.addEventListener('keydown', e => {
 if (e.target.matches('input,select,textarea')) return;
 if (e.key === 'Escape') { cancelTouch(); closeSheet(); selection(null); }
 if (e.key === 'Delete' && selected) deleteDrawing(selected);
 if ((e.ctrlKey || e.metaKey) && ['z','y'].includes(e.key.toLowerCase())) {
  e.preventDefault();
  (e.shiftKey || e.key.toLowerCase() === 'y' ? $('redo') : $('undo')).click();
 }
});

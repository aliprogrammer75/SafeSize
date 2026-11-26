'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/chart/drawing-geometry.js'), 'utf8');
const {drawingTimeline, drawingGeometry, drawingDistance, drawingContains} = vm.runInNewContext(
 source + '\n({drawingTimeline, drawingGeometry, drawingDistance, drawingContains})'
);
const base = Date.UTC(2026, 0, 1);
const hour = 3600000;
const bar = timestamp => ({timestamp});

test('a future timestamp keeps its fractional position when a candle arrives', () => {
 const candles = [bar(base), bar(base + hour)];
 const before = drawingTimeline(candles, '1h');
 const anchor = before.time(2.25);
 assert.equal(anchor, base + 2.25 * hour);
 const after = drawingTimeline([...candles, bar(base + 2 * hour)], '1h');
 assert.equal(after.index(anchor), 2.25);
 assert.equal(after.time(2.25), anchor);
});

test('prepending history and changing interval keep the anchor timestamp', () => {
 const anchor = base + 1.75 * hour;
 const expanded = drawingTimeline([bar(base - hour), bar(base), bar(base + hour)], '1h');
 assert.equal(expanded.index(anchor), 2.75);
 const quarterHour = drawingTimeline([bar(base), bar(base + hour / 4)], '15m');
 assert.equal(quarterHour.index(anchor), 7);
 assert.equal(quarterHour.time(7), anchor);
});

test('calendar months and missing candles round trip without synthetic candles', () => {
 const timeline = drawingTimeline([bar(Date.UTC(2024, 0, 1)), bar(Date.UTC(2024, 1, 1))], '1M');
 assert.equal(timeline.time(2), Date.UTC(2024, 2, 1));
 assert.equal(timeline.time(-1), Date.UTC(2023, 11, 1));
 assert.equal(timeline.index(Date.UTC(2024, 1, 15, 12)), 1.5);
 const gap = drawingTimeline([bar(base), bar(base + 4 * hour)], '1h');
 assert.equal(gap.time(.75), base + 3 * hour);
 assert.equal(gap.index(base + 3 * hour), .75);
});

const style = {color:'#101010', width:2, style:'solid', background:'#101010', opacity:.15};
const p = (x,y) => ({x,y});
const value = value => ({value, timestamp:base});
const format = n => String(n);
const priceY = y => 150-y;

test('future Fibonacci levels use their own styles and price anchors', () => {
 const d = {name:'fibonacciLine', points:[value(100),value(120)], config:{
  ...style, fib:[{v:.705,on:true,color:'#ff0000',width:3,style:'dotted'},{v:.66,on:false}],
  extendRight:true, reverse:true, showPrice:true, showPercent:true, labels:'right'
 }};
 const g = drawingGeometry(d,[p(30,50),p(250,30)],300,180,format,()=>0,priceY);
 assert.equal(g.lines.length,1);
 assert.equal(g.lines[0].to.x,300);
 assert(Math.abs(g.lines[0].to.y - 35.9) < 1e-9);
 assert.equal(g.lines[0].color,'#ff0000');
 assert.equal(g.lines[0].style,'dotted');
 assert.equal(g.lines[0].size,3);
 assert.match(g.labels[0].text,/70\.5%.*114\.1/);
});

test('drawings expose consistent geometry for handle and body hit tests', () => {
 const g = drawingGeometry({name:'rectangleZone',config:style},[p(20,40),p(140,110)],200,200,format,()=>0,priceY);
 assert(drawingContains(p(100,80),g.fills[0].points));
 assert(!drawingContains(p(145,80),g.fills[0].points));
 assert.equal(drawingDistance(p(80,43),g.lines[0].from,g.lines[0].to),3);
});

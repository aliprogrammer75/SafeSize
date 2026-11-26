'use strict';
const $=id=>document.getElementById(id), panel=$('panel'), info=$('notice');
let chart=null, series='', indicators={}, drawings=[], selected=null, pendingOlder=false, pendingCallback=null, more=true, levelValues=['','',''], initialized=false, currentStyle='candle_solid';
const overlayTools=[['horizontalStraightLine','خط افقی'],['verticalStraightLine','خط عمودی'],['segment','خط روند'],['rayLine','نیم‌خط'],['straightLine','خط ممتد'],['priceChannelLine','کانال قیمت'],['parallelStraightLine','خطوط موازی'],['fibonacciLine','فیبوناچی'],['rectangleZone','محدوده مستطیلی'],['measureMove','اندازه‌گیری']];
const indicatorNames=['MA','EMA','SMA','BOLL','SAR','BBI','VOL','MACD','KDJ','RSI','OBV','CCI','DMI','WR','BIAS','BRAR','VR','MTM','EMV','CR','PSY','AO','DMA','ROC','TRIX','PVT','AVP'];
const mainIndicators=new Set(['MA','EMA','SMA','BOLL','SAR','BBI','AVP']);
function say(t){info.textContent=t}
function btn(label,action,active=false){const el=document.createElement('button');el.textContent=label;if(active)el.classList.add('active');el.onclick=()=>{try{action()}catch(_){say('عملیات نمودار ناموفق بود')}};panel.append(el);return el}
function menu(draw){panel.replaceChildren();panel.hidden=false;draw()}
function read(k,fallback){try{return JSON.parse(localStorage.getItem(k)||'null')??fallback}catch(_){return fallback}}
function save(k,v){try{localStorage.setItem(k,JSON.stringify(v))}catch(_){say('ذخیره تنظیمات ممکن نبود')}}
function saveDraw(){save('draw:'+series,drawings.map(id=>chart.getOverlayById(id)).filter(Boolean).map(o=>({name:o.name,points:o.points.map(p=>({timestamp:p.timestamp,value:p.value}))})).filter(o=>o.points.length&&o.points.every(p=>Number.isFinite(p.timestamp)&&Number.isFinite(p.value))))}
function drawTool(name,points){
 const opts={name,groupId:'user-draw',mode:'normal',onDrawEnd:()=>{saveDraw();say('ترسیم ذخیره شد');return false},onPressedMoveEnd:()=>{saveDraw();return false},onSelected:e=>{selected=e.overlay.id;return false},onDeselected:()=>{selected=null;return false}};
 if(points)opts.points=points;
 const id=chart.createOverlay(opts,'candle_pane');
 if(typeof id==='string'){drawings.push(id);return id}
}
function indicator(name,params){
 if(indicators[name]){chart.removeIndicator(indicators[name].pane,name);delete indicators[name]}
 const options=Array.isArray(params)&&params.length?{name,calcParams:params}:name;
 const pane=chart.createIndicator(options,true,mainIndicators.has(name)?{id:'candle_pane'}:{height:100,minHeight:45,dragEnabled:true});
 if(pane)indicators[name]={pane,params:params||[]};
}
function saveInd(){save('indicators',Object.entries(indicators).map(([name,v])=>({name,params:v.params})))}
function indicatorMenu(){
 menu(()=>{
 for(const name of indicatorNames)btn((indicators[name]?'✓ ':'')+name,()=>{if(indicators[name]){chart.removeIndicator(indicators[name].pane,name);delete indicators[name]}else indicator(name,[]);saveInd();indicatorMenu()},!!indicators[name]);
 for(const [name,v] of Object.entries(indicators)){
  const label=document.createElement('label');label.textContent=name+' · پارامترها (جدا با کاما)';
  const field=document.createElement('input');field.dir='ltr';field.placeholder='مثلاً 14 یا 12,26,9';
  let cur=v.params;
  if(!cur.length){const obj=chart.getIndicatorByPaneId(v.pane,name);cur=Array.isArray(obj?.calcParams)?obj.calcParams:[]}
  field.value=cur.join(',');label.append(field);panel.append(label);
  btn('اعمال '+name,()=>{const values=field.value.split(',').map(x=>Number(x.trim()));if(values.length>12||values.some(x=>!Number.isFinite(x)||x<=0||x>10000)){say('پارامتر نامعتبر');return}indicator(name,values);saveInd();indicatorMenu()});
 }
 btn('بستن',()=>panel.hidden=true);
 })
}
function registerCustom(){
 klinecharts.registerOverlay({name:'rectangleZone',totalStep:3,needDefaultPointFigure:true,needDefaultXAxisFigure:true,needDefaultYAxisFigure:true,createPointFigures:({coordinates:c})=>c.length<2?[]:[{type:'rect',attrs:{x:Math.min(c[0].x,c[1].x),y:Math.min(c[0].y,c[1].y),width:Math.abs(c[1].x-c[0].x),height:Math.abs(c[1].y-c[0].y)},styles:{style:'stroke_fill',color:'rgba(165,180,252,0.16)',borderColor:'#a5b4fc',borderSize:1}}]});
 klinecharts.registerOverlay({name:'measureMove',totalStep:3,needDefaultPointFigure:true,needDefaultYAxisFigure:true,createPointFigures:({overlay,coordinates:c})=>{
 if(c.length<2)return[];const a=overlay.points[0]?.value,b=overlay.points[1]?.value;if(!Number.isFinite(a)||!Number.isFinite(b)||a===0)return[];
 return[{type:'line',attrs:{coordinates:c}},{type:'text',attrs:{x:(c[0].x+c[1].x)/2,y:(c[0].y+c[1].y)/2,text:((b-a)/a*100).toFixed(2)+'% | '+(b-a).toPrecision(5)},styles:{color:'#fff',backgroundColor:'#30365b'}}];
 }});
}
function updateLevels(){
 if(!chart||!initialized)return;chart.removeOverlay({groupId:'trade-level'});
 ['#a5b4fc','#fb7185','#2dd4bf'].forEach((color,i)=>{const value=Number(levelValues[i]);if(value>0&&Number.isFinite(value))chart.createOverlay({name:'horizontalStraightLine',groupId:'trade-level',lock:true,points:[{value}],styles:{line:{color,size:1,style:'dashed'}}},'candle_pane')});
}
function chooseLevel(kind){
 chart.removeOverlay({groupId:'pick-level'});
 chart.createOverlay({name:'horizontalStraightLine',groupId:'pick-level',onDrawEnd:e=>{
  const val=e.overlay.points[0]?.value;if(Number.isFinite(val)&&val>0){ChartNative.select(kind,String(val));say('سطح انتخاب شد؛ به ماشین حساب منتقل شد')}
  setTimeout(()=>chart.removeOverlay({groupId:'pick-level'}),0);return false
 }},'candle_pane');panel.hidden=true;say('سطح را روی نمودار انتخاب کنید');
}
function olderRequest(cb){
 if(pendingOlder||!more)return false;const data=chart.getDataList();if(!data.length)return false;
 pendingOlder=true;pendingCallback=cb||null;ChartNative.older(String(data[0].timestamp));say('در حال دریافت تاریخچه قدیمی‌تر…');return true
}
window.SafeChart={
 init(key,precision){
  if(chart)return;series=key;registerCustom();chart=klinecharts.init('chart',{styles:'dark',timezone:'Etc/UTC',locale:'en-US'});
  if(!chart){say('موتور نمودار آماده نشد');return}
  chart.setPriceVolumePrecision(precision,6);chart.setOffsetRightDistance(55);
  chart.setStyles({candle:{bar:{upColor:'#2dd4bf',downColor:'#fb7185',noChangeColor:'#94a3b8',upBorderColor:'#2dd4bf',downBorderColor:'#fb7185',upWickColor:'#2dd4bf',downWickColor:'#fb7185'}}});
  for(const v of read('indicators',[{name:'VOL',params:[]}]))if(indicatorNames.includes(v.name))try{indicator(v.name,v.params)}catch(_){}
  currentStyle=read('chartStyle','candle_solid');chart.setStyles({candle:{type:currentStyle}});
  chart.setLoadDataCallback(({type,data,callback})=>{if(data && data.timestamp===chart.getDataList()[0]?.timestamp){if(!olderRequest(callback))callback([],more)}else callback([],false)});
  new ResizeObserver(()=>chart.resize()).observe($('chart'));say('آماده دریافت داده بیت‌یونیکس')
 },
 reset(data){if(!chart||!data.length)return;more=data.length===200;chart.applyNewData(data,more);initialized=true;for(const o of read('draw:'+series,[]))if(overlayTools.some(x=>x[0]===o.name)&&o.points?.length)try{drawTool(o.name,o.points)}catch(_){}updateLevels();say('تاریخچه بارگذاری شد')},
 sync(data){if(!chart||!data.length)return;const prev=chart.getDataList();if(!prev.length){this.reset(data);return}const last=prev[prev.length-1].timestamp;const map=new Map(prev.map(x=>[x.timestamp,x]));for(const b of data)map.set(b.timestamp,b);const all=[...map.values()].sort((a,b)=>a.timestamp-b.timestamp);
  const earlier=data.some(b=>{const old=prev.find(p=>p.timestamp===b.timestamp);return b.timestamp<last&&(!old||['open','high','low','close','volume'].some(k=>old[k]!==b[k]))});if(earlier){const visible=chart.getVisibleRange(),anchor=prev[Math.min(prev.length-1,Math.max(0,visible?.to||0))]?.timestamp;chart.applyNewData(all,more);if(anchor&&visible.to<prev.length-3)chart.scrollToTimestamp(anchor)}else for(const b of data)if(b.timestamp>=last)chart.updateData(b);
 },
 tick(b){if(!chart||!initialized||!b)return;const d=chart.getDataList();if(d.length&&b.timestamp>=d[d.length-1].timestamp)chart.updateData(b)},
 older(data,hasMore){if(!chart)return;more=hasMore;const cb=pendingCallback;pendingCallback=null;pendingOlder=false;if(cb)cb(data,hasMore);else if(data.length)chart.applyMoreData(data,hasMore);say(data.length?'تاریخچه افزوده شد':'تاریخچه بیشتری دریافت نشد')},
 olderFailed(){const cb=pendingCallback;pendingCallback=null;pendingOlder=false;if(cb)cb([],true);say('دریافت تاریخچه ناموفق بود؛ دوباره امتحان کنید')},
 levels(e,s,t){levelValues=[e,s,t];updateLevels()}
};
$('ind').onclick=()=>{if(chart)indicatorMenu()};
$('draw').onclick=()=>{if(!chart)return;menu(()=>{for(const [name,label] of overlayTools)btn(label,()=>{drawTool(name);panel.hidden=true;say('نقاط ترسیم را روی نمودار انتخاب کنید')});btn('حذف انتخاب',()=>{if(selected){chart.removeOverlay(selected);drawings=drawings.filter(x=>x!==selected);selected=null;saveDraw()}});btn('حذف آخرین',()=>{const id=drawings.pop();if(id){chart.removeOverlay(id);saveDraw()}});btn('پاک کردن ترسیم‌ها',()=>{menu(()=>{btn('تأیید حذف همه ترسیم‌ها',()=>{chart.removeOverlay({groupId:'user-draw'});drawings=[];saveDraw();panel.hidden=true});btn('انصراف',()=>panel.hidden=true)})});btn('بستن',()=>panel.hidden=true)})};
$('style').onclick=()=>{if(!chart)return;menu(()=>{for(const [id,title] of [['candle_solid','کندل'],['candle_stroke','کندل توخالی'],['candle_up_stroke','صعودی توخالی'],['candle_down_stroke','نزولی توخالی'],['ohlc','OHLC'],['area','خطی']])btn(title,()=>{chart.setStyles({candle:{type:id}});currentStyle=id;save('chartStyle',id);panel.hidden=true},id===currentStyle);btn('بستن',()=>panel.hidden=true)})};
$('levels').onclick=()=>{if(!chart)return;menu(()=>{btn('قیمت ورود',()=>chooseLevel('entry'));btn('حد ضرر',()=>chooseLevel('sl'));btn('حد سود',()=>chooseLevel('tp'));btn('بستن',()=>panel.hidden=true)})};
$('more').onclick=()=>{if(chart&&!olderRequest(null))say('تاریخچه دیگری در دسترس نیست یا در حال دریافت است')};
$('live').onclick=()=>chart?.scrollToRealTime();
if(window.klinecharts&&window.ChartNative)ChartNative.ready();else say('فایل موتور نمودار در APK موجود نیست');

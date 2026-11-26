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

const defaultFib=[0,0.236,0.382,0.5,0.618,0.705,0.786,1,1.618].map(v=>({v,on:true}));
const defaults={color:'#a5b4fc',width:2,style:'solid',snap:'normal',fib:defaultFib};
const required={horizontalStraightLine:1,verticalStraightLine:1,segment:2,rayLine:2,straightLine:2,priceChannelLine:3,parallelStraightLine:3,fibonacciLine:2,rectangleZone:2,measureMove:2};
let placement=null,drag=null,moveId=null,previewId=null;
const pad=$('touchpad'), guide=$('guide');
function config(v){return {...defaults,...v,fib:Array.isArray(v?.fib)?v.fib:defaultFib}}
function stylesFor(c){return {line:{color:c.color,size:c.width,style:c.style,dashedValue:[6,4]},point:{color:c.color,radius:5,activeRadius:8},text:{color:c.color}}}
function paneKey(p){return p==='candle_pane'?'main':Object.keys(indicators).find(k=>indicators[k].pane===p)||'main'}
function paneId(k){return k==='main'?'candle_pane':indicators[k]?.pane}
function metadata(id){return drawings.find(d=>d.id===id)}
function saveDraw(){save('draw:'+series,drawings.map(d=>{const o=chart.getOverlayById(d.id);return o?{name:d.name,pane:d.pane,config:d.config,lock:d.lock,visible:d.visible,points:o.points.map(p=>({timestamp:p.timestamp,value:p.value}))}:null}).filter(o=>o&&o.points.length&&o.points.every(p=>Number.isFinite(p.timestamp)&&Number.isFinite(p.value))))}
function selection(id){selected=id;const d=metadata(id);$('selection').hidden=!d;$('selname').textContent=d?overlayTools.find(x=>x[0]===d.name)?.[1]||d.name:''}
function createDrawing(name,points,pane='main',settings={},lock=false,visible=true,temporary=false){
 const c=config(settings);const dest=paneId(pane);if(!dest)return null;
 const opts={name:name==='fibonacciLine'?'customFib':name,groupId:temporary?'preview-draw':'user-draw',mode:c.snap,modeSensitivity:12,lock:temporary||lock,visible,extendData:c,styles:stylesFor(c),points,
 onClick:e=>{if(!temporary)selection(e.overlay.id);return false},onSelected:e=>{if(!temporary)selection(e.overlay.id);return false},
 onPressedMoveEnd:e=>{if(!temporary){selection(e.overlay.id);saveDraw()}return false}};
 const id=chart.createOverlay(opts,dest);
 if(typeof id!=='string')return null;
 if(!temporary)drawings.push({id,name,pane,config:c,lock,visible});
 return id;
}
function deleteDrawing(id){chart.removeOverlay({id});drawings=drawings.filter(d=>d.id!==id);if(selected===id)selection(null);saveDraw()}
function redraw(d,changes){
 const o=chart.getOverlayById(d.id);if(!o)return;
 const points=o.points.map(p=>({timestamp:p.timestamp,value:p.value}));
 const id=createDrawing(d.name,changes.points||points,changes.pane||d.pane,changes.config||d.config,changes.lock??d.lock,changes.visible??d.visible);
 if(id){chart.removeOverlay({id:d.id});drawings=drawings.filter(v=>v.id!==d.id);selection(id);saveDraw()}
}
function makeField(label,value,type='text'){
 const l=document.createElement('label');l.textContent=label;const i=document.createElement('input');i.type=type;i.value=value;i.dir='ltr';l.append(i);panel.append(l);return i;
}
function choice(label,values,value){
 const l=document.createElement('label');l.textContent=label;const i=document.createElement('select');for(const [v,t] of values){const o=document.createElement('option');o.value=v;o.textContent=t;i.append(o)}i.value=value;l.append(i);panel.append(l);return i
}
function settingsMenu(d,name){
 const c=config(d?.config||read('tool:'+name,defaults));
 menu(()=>{
  const color=makeField('رنگ',c.color,'color'),width=makeField('ضخامت (۱ تا ۶)',String(c.width),'number');
  const style=choice('نوع خط',[['solid','پیوسته'],['dashed','خط‌چین']],c.style);
  const snap=choice('اتصال به کندل',[['normal','آزاد'],['weak_magnet','نزدیک کندل'],['strong_magnet','چسبیدن به OHLC']],c.snap);
  let fib=c.fib.map(x=>({...x}));
  const rows=document.createElement('div');panel.append(rows);
  function renderFib(){rows.replaceChildren();fib.forEach((x,j)=>{const row=document.createElement('div');row.className='fibrow';const chk=document.createElement('input');chk.type='checkbox';chk.checked=x.on;chk.onchange=()=>x.on=chk.checked;const v=document.createElement('input');v.type='number';v.step='any';v.value=x.v;v.oninput=()=>x.v=v.value;const del=document.createElement('button');del.textContent='حذف';del.onclick=()=>{fib.splice(j,1);renderFib()};row.append(chk,v,del);rows.append(row)})}
  if(name==='fibonacciLine'){renderFib();btn('افزودن سطح',()=>{if(fib.length<40){fib.push({v:'',on:true});renderFib()}});btn('سطوح پیش‌فرض',()=>{fib=defaultFib.map(x=>({...x}));renderFib()})}
  btn('اعمال و ذخیره',()=>{
   const w=Number(width.value),levels=fib.map(x=>({v:Number(x.v),on:x.on}));if(!Number.isFinite(w)||w<1||w>6||fib.some(x=>String(x.v).trim()==='')||levels.some(x=>!Number.isFinite(x.v)||Math.abs(x.v)>100)){say('تنظیمات نامعتبر');return}
   const newC={color:color.value,width:w,style:style.value,snap:snap.value,fib:levels};save('tool:'+name,newC);
   if(d)redraw(d,{config:newC});panel.hidden=true;say('تنظیمات ذخیره شد');
  });
  btn('بازگشت',()=>d?objectMenu():drawMenu())
 })
}
function objectMenu(){
 menu(()=>{
 for(const d of drawings){const label=overlayTools.find(x=>x[0]===d.name)?.[1]||d.name;btn(label+(d.lock?' • قفل':'')+(d.visible?'':' • مخفی'),()=>{selection(d.id);objectActions(d)},selected===d.id)}
 if(!drawings.length)say('ابزاری روی نمودار وجود ندارد');
 btn('بستن',()=>panel.hidden=true)
 })
}
function objectActions(d){menu(()=>{
 btn('تنظیمات',()=>settingsMenu(d,d.name));
 btn('جابجایی',()=>startMove(d.id));
 btn(d.lock?'باز کردن قفل':'قفل',()=>{redraw(d,{lock:!d.lock});panel.hidden=true});
 btn(d.visible?'مخفی کردن':'نمایش',()=>{redraw(d,{visible:!d.visible});panel.hidden=true});
 btn('حذف همین ابزار',()=>{deleteDrawing(d.id);panel.hidden=true});
 btn('فهرست ابزارها',objectMenu)
})}
function drawMenu(){menu(()=>{
 for(const [name,label] of overlayTools){btn(label,()=>startPlacement(name));btn('⚙ '+label,()=>settingsMenu(null,name))}
 btn('ابزارهای روی چارت',objectMenu);
 btn('پاک کردن همه',()=>menu(()=>{btn('تأیید حذف همه',()=>{chart.removeOverlay({groupId:'user-draw'});drawings=[];selection(null);saveDraw();panel.hidden=true});btn('انصراف',drawMenu)}));
 btn('بستن',()=>panel.hidden=true)
})}
function currentPanes(){return [...new Set(['main',...Object.keys(indicators).filter(k=>indicators[k].pane!=='candle_pane')])].map(k=>({k,p:paneId(k)})).filter(x=>x.p&&chart.getDom(x.p,'main'))}
function hitPane(x,y){
 for(const q of currentPanes()){const r=chart.getDom(q.p,'main').getBoundingClientRect();if(x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom)return {...q,r}}
 return null
}
function pointAt(x,y,pane,c){
 const p=paneId(pane),dom=p&&chart.getDom(p,'main');if(!dom)return null;const r=dom.getBoundingClientRect();
 const px=Math.max(0,Math.min(r.width-1,x-r.left)),py=Math.max(0,Math.min(r.height-1,y-r.top));
 const v=chart.convertFromPixel({x:px,y:py},{paneId:p,absolute:false});
 const data=chart.getDataList(),bar=data[v.dataIndex];const t=Number(v.timestamp??bar?.timestamp),value=Number(v.value);
 if(!Number.isFinite(t)||!Number.isFinite(value)||!bar)return null;
 let val=value;
 if(pane==='main'&&c.snap!=='normal'){
  const levels=[bar.open,bar.high,bar.low,bar.close];const nearest=levels.reduce((a,b)=>Math.abs(a-value)<Math.abs(b-value)?a:b);
  const np=chart.convertToPixel({timestamp:t,value:nearest},{paneId:p,absolute:false});
  if(c.snap==='strong_magnet'||Math.abs(np.y-py)<=12)val=nearest
 }
 return {timestamp:t,value:val}
}
function clearPreview(){if(previewId){chart.removeOverlay({id:previewId});previewId=null}}
function cancelTouch(){clearPreview();placement=null;moveId=null;drag=null;pad.hidden=true;guide.hidden=true;$('cancel').hidden=true;chart?.setScrollEnabled(true);chart?.setZoomEnabled(true)}
function startPlacement(name){
 if(!initialized)return;say('انگشت را روی نقطه بگذارید، بکشید و رها کنید');cancelTouch();placement={name,pane:null,points:[],config:config(read('tool:'+name,defaults))};pad.hidden=false;$('cancel').hidden=false;panel.hidden=true
}
function startMove(id){
 const d=metadata(id);if(!d||d.lock||!d.visible){say('ابتدا ابزار را نمایش دهید و از قفل خارج کنید');return}
 cancelTouch();moveId=id;pad.hidden=false;$('cancel').hidden=false;panel.hidden=true;say('انگشت را روی نمودار بگذارید و ابزار را به محل جدید بکشید')
}
function guideAt(e){const r=pad.getBoundingClientRect();guide.hidden=false;guide.style.left=(e.clientX-r.left)+'px';guide.style.top=(e.clientY-r.top)+'px'}
function previewAt(e){
 if(!drag)return;guideAt(e);
 if(placement){
  const p=pointAt(e.clientX,e.clientY,placement.pane,placement.config);if(!p)return;drag.point=p;const pts=[...placement.points,p];while(pts.length<required[placement.name])pts.push(p);
  clearPreview();previewId=createDrawing(placement.name,pts,placement.pane,placement.config,true,true,true)
 }else if(moveId){
  const d=metadata(moveId);if(!d)return;const q=hitPane(e.clientX,e.clientY);if(!q)return;
  const dx=e.clientX-drag.x,dy=e.clientY-drag.y;const points=drag.pixels.map(p=>pointAt(p.x+dx,p.y+dy,q.k,d.config));
  if(points.some(p=>!p))return;drag.points=points;drag.pane=q.k;clearPreview();previewId=createDrawing(d.name,points,q.k,d.config,true,true,true)
 }
}
pad.addEventListener('pointerdown',e=>{
 if(!e.isPrimary||drag)return;const q=hitPane(e.clientX,e.clientY);if(!q){say('روی بخش کندل یا اندیکاتور لمس کنید');return}
 if(placement){
  if(placement.level&&q.k!=='main'){say('سطح معامله فقط روی پنل قیمت');return}if(placement.pane&&placement.pane!==q.k){say('نقاط ابزار باید در یک پنل باشند');return}
  placement.pane=q.k;drag={id:e.pointerId};
 }else if(moveId){
  const d=metadata(moveId),o=d&&chart.getOverlayById(d.id),p=d&&paneId(d.pane);if(!o||!p)return;
  const r=chart.getDom(p,'main').getBoundingClientRect();const pts=chart.convertToPixel(o.points.map(v=>({timestamp:v.timestamp,value:v.value})),{paneId:p,absolute:false});
  if(!Array.isArray(pts)||pts.some(v=>!Number.isFinite(v.x)||!Number.isFinite(v.y)))return;
  drag={id:e.pointerId,x:e.clientX,y:e.clientY,pixels:pts.map(v=>({x:v.x+r.left,y:v.y+r.top}))};
 }else return;
 e.preventDefault();pad.setPointerCapture(e.pointerId);previewAt(e)
});
let raf=0,lastEvent=null;
pad.addEventListener('pointermove',e=>{if(drag?.id!==e.pointerId)return;e.preventDefault();lastEvent=e;if(!raf)raf=requestAnimationFrame(()=>{raf=0;if(lastEvent)previewAt(lastEvent)})});
pad.addEventListener('pointerup',e=>{
 if(drag?.id!==e.pointerId)return;e.preventDefault();if(raf){cancelAnimationFrame(raf);raf=0}lastEvent=null;previewAt(e);const d=drag;drag=null;guide.hidden=true;clearPreview();
 if(placement&&d.point){
  placement.points.push(d.point);
  if(placement.points.length>=required[placement.name]){
   const v=placement;if(v.level){ChartNative.select(v.level,String(v.points[0].value));cancelTouch();say('سطح به ماشین حساب منتقل شد');return}const id=createDrawing(v.name,v.points,v.pane,v.config);cancelTouch();if(id){selection(id);saveDraw();say('ابزار ثبت شد؛ برای جابه‌جایی آن را لمس کنید')}
  }else say('نقطه '+(placement.points.length+1)+' از '+required[placement.name]+' را انتخاب کنید')
 }else if(moveId&&d.points){const obj=metadata(moveId);if(obj)redraw(obj,{points:d.points,pane:d.pane});cancelTouch();say('ابزار جابه‌جا شد')}
});
pad.addEventListener('pointercancel',()=>{if(raf)cancelAnimationFrame(raf);raf=0;lastEvent=null;drag=null;guide.hidden=true;clearPreview();say('لمس لغو شد؛ دوباره تلاش کنید')});
$('cancel').onclick=()=>{cancelTouch();say('حالت ترسیم لغو شد')};
$('selsettings').onclick=()=>{const d=metadata(selected);if(d)settingsMenu(d,d.name)};
$('selmove').onclick=()=>{if(selected)startMove(selected)};
$('seldel').onclick=()=>{if(selected)deleteDrawing(selected)};
$('selmore').onclick=()=>{const d=metadata(selected);if(d)objectActions(d)};
function indicator(name,params,opts={}){
 const key=opts.key||name;
 if(indicators[key]){chart.removeIndicator(indicators[key].pane,indicators[key].engine);delete indicators[key]}
 let engine=name;
 const source=opts.source||'close', color=opts.color||'#fbbf24', width=Number(opts.width)||2;
 if(name==='EMA'||name==='MA'||name==='SMA'){
  engine='SS_'+name+'_'+key.replace(/[^a-zA-Z0-9_]/g,'');
  klinecharts.registerIndicator({name:engine,shortName:name+' '+(params[0]||20),series:'price',calcParams:[params[0]||20],figures:[{key:'v',title:name+': ',type:'line'}],
    calc:(list,ind)=>{const n=Math.max(1,Math.floor(ind.calcParams[0]));let ema=null,sum=0;const a=2/(n+1);return list.map((b,i)=>{const v=b[source];sum+=v;if(i>=n)sum-=list[i-n][source];if(i<n-1)return {};if(name==='EMA'){ema=ema===null?sum/n:ema+(v-ema)*a;return{v:ema}}return{v:sum/n}})}});
 }
 const options={name:engine};if(params.length)options.calcParams=params;
 if(engine!==name||opts.color)options.styles={lines:[{color,size:width,style:'solid'}]};
 const pane=chart.createIndicator(options,true,mainIndicators.has(name)?{id:'candle_pane'}:{height:100,minHeight:45,dragEnabled:true});
 if(pane)indicators[key]={pane,engine,name,params,source,color,width,key}
}
function saveInd(){save('indicators',Object.values(indicators).map(({pane,engine,...v})=>v))}
function removeInd(key){
 const v=indicators[key];if(!v)return;
 if(v.pane!=='candle_pane'){for(const d of [...drawings])if(d.pane===key)deleteDrawing(d.id)}
 chart.removeIndicator(v.pane,v.engine);delete indicators[key];saveInd();
}
function indicatorMenu(){
 menu(()=>{
 for(const name of indicatorNames)btn('＋ '+name,()=>{if(['EMA','MA','SMA'].includes(name))indicator(name,[20],{key:name+'_'+Date.now()});else if(!indicators[name])indicator(name,[],{});saveInd();indicatorMenu()});
 for(const [key,v] of Object.entries(indicators)){
  const label=document.createElement('div');label.className='section';label.textContent=v.name+' '+v.params.join(',');panel.append(label);
  let cur=v.params;if(!cur.length){const obj=chart.getIndicatorByPaneId(v.pane,v.engine);cur=Array.isArray(obj?.calcParams)?obj.calcParams:[]}
  const field=makeField('دوره / پارامترها (با کاما)',cur.join(','));
  const source=['EMA','MA','SMA'].includes(v.name)?choice('منبع قیمت',[['close','Close'],['open','Open'],['high','High'],['low','Low']],v.source):null;
  const color=makeField('رنگ خط',v.color,'color'),width=makeField('ضخامت',String(v.width),'number');
  btn('اعمال '+v.name,()=>{const values=field.value.split(',').map(x=>Number(x.trim())),w=Number(width.value);if(values.length>12||values.some(x=>!Number.isFinite(x)||x<=0||x>10000)||!Number.isFinite(w)||w<1||w>6){say('پارامتر نامعتبر');return}
   if(v.pane!=='candle_pane'){
     chart.overrideIndicator({name:v.engine,calcParams:values,styles:{lines:[{color:color.value,size:w,style:'solid'}]}},v.pane);
     Object.assign(v,{params:values,color:color.value,width:w})
   }else indicator(v.name,values,{...v,key,source:source?.value||v.source,color:color.value,width:w});
   saveInd();indicatorMenu()});
  btn('حذف '+v.name,()=>{if(drawings.some(d=>d.pane===key)){menu(()=>{btn('تأیید حذف اندیکاتور و ترسیم‌های پنل',()=>{removeInd(key);indicatorMenu()});btn('انصراف',indicatorMenu)})}else{removeInd(key);indicatorMenu()}});
 }
 btn('بستن',()=>panel.hidden=true);
 })
}
function templatesMenu(){menu(()=>{
 const name=makeField('نام قالب','');
 btn('ذخیره قالب فعلی',()=>{const n=name.value.trim().slice(0,60);if(!n)return;const t=read('templates',{});t[n]={indicators:Object.values(indicators).map(({pane,engine,...v})=>v),style:currentStyle};save('templates',t);templatesMenu()});
 for(const [n,t] of Object.entries(read('templates',{}))){
  btn('بارگذاری '+n,()=>{if(drawings.some(d=>d.pane!=='main')){say('پیش از تغییر قالب، ترسیم‌های پنل اندیکاتور را حذف یا منتقل کنید');return}for(const k of Object.keys(indicators))removeInd(k);for(const v of t.indicators||[])if(indicatorNames.includes(v.name))indicator(v.name,v.params||[],v);currentStyle=t.style||'candle_solid';chart.setStyles({candle:{type:currentStyle}});save('chartStyle',currentStyle);saveInd();panel.hidden=true});
  btn('حذف قالب '+n,()=>{const all=read('templates',{});delete all[n];save('templates',all);templatesMenu()})
 }
 btn('بستن',()=>panel.hidden=true)
})}
function registerCustom(){
 klinecharts.registerOverlay({name:'customFib',totalStep:3,needDefaultPointFigure:true,needDefaultXAxisFigure:true,needDefaultYAxisFigure:true,createPointFigures:({overlay,coordinates:c,bounding})=>{
 if(c.length<2)return[];const opts=config(overlay.extendData),a=overlay.points[0]?.value,b=overlay.points[1]?.value;if(!Number.isFinite(a)||!Number.isFinite(b))return[];
 return opts.fib.filter(v=>v.on&&Number.isFinite(v.v)).flatMap(v=>{const y=c[0].y+(c[1].y-c[0].y)*v.v;const price=a+(b-a)*v.v;return [{type:'line',attrs:{coordinates:[{x:Math.min(c[0].x,c[1].x),y},{x:Math.max(c[0].x,c[1].x),y}]}},{type:'text',attrs:{x:Math.min(c[0].x,c[1].x)+4,y:y-4,text:v.v+' · '+Number(price.toPrecision(9))},styles:{color:opts.color,backgroundColor:'transparent'}}]})
 }});

 klinecharts.registerOverlay({name:'rectangleZone',totalStep:3,needDefaultPointFigure:true,needDefaultXAxisFigure:true,needDefaultYAxisFigure:true,createPointFigures:({overlay,coordinates:c})=>c.length<2?[]:[{type:'rect',attrs:{x:Math.min(c[0].x,c[1].x),y:Math.min(c[0].y,c[1].y),width:Math.abs(c[1].x-c[0].x),height:Math.abs(c[1].y-c[0].y)},styles:{style:'stroke_fill',color:'rgba(165,180,252,0.16)',borderColor:config(overlay.extendData).color,borderSize:config(overlay.extendData).width}}]});
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
 startPlacement('horizontalStraightLine');if(placement){placement.level=kind;say('سطح را روی کندل لمس و رها کنید')}
}
function olderRequest(cb){
 if(pendingOlder||!more)return false;const data=chart.getDataList();if(!data.length)return false;
 pendingOlder=true;pendingCallback=cb||null;ChartNative.older(String(data[0].timestamp));say('در حال دریافت تاریخچه قدیمی‌تر…');return true
}
function fitChart(){
 const root=document.documentElement,body=document.body,toolbar=$('toolbar'),wrap=$('chartwrap');
 const h=Math.max(0,window.innerHeight||root.clientHeight),w=Math.max(0,window.innerWidth||root.clientWidth);
 if(!h||!w)return;
 body.style.height=h+'px';body.style.width=w+'px';
 const top=Math.ceil(toolbar.getBoundingClientRect().height);
 const available=Math.max(1,h-top);
 wrap.style.top=top+'px';wrap.style.height=available+'px';wrap.style.bottom='auto';
 $('chart').style.height=available+'px';$('chart').style.width=w+'px';
 if(chart)chart.resize()
}
let fitFrame=0;
function scheduleFit(){if(fitFrame)return;fitFrame=requestAnimationFrame(()=>{fitFrame=0;fitChart()})}
window.addEventListener('resize',scheduleFit);
window.visualViewport?.addEventListener('resize',scheduleFit);
window.SafeChart={
 fit:fitChart,
 init(key,precision){
  if(chart)return;series=key;registerCustom();chart=klinecharts.init('chart',{styles:'dark',timezone:'Etc/UTC',locale:'en-US'});
  if(!chart){say('موتور نمودار آماده نشد');return}
  chart.setPriceVolumePrecision(precision,6);chart.setOffsetRightDistance(55);
  chart.setStyles({candle:{bar:{upColor:'#2dd4bf',downColor:'#fb7185',noChangeColor:'#94a3b8',upBorderColor:'#2dd4bf',downBorderColor:'#fb7185',upWickColor:'#2dd4bf',downWickColor:'#fb7185'}}});
  for(const v of read('indicators',[{name:'VOL',params:[]}]))if(indicatorNames.includes(v.name))try{indicator(v.name,v.params||[],v)}catch(_){}
  currentStyle=read('chartStyle','candle_solid');chart.setStyles({candle:{type:currentStyle}});
  chart.setLoadDataCallback(({type,data,callback})=>{if(data && data.timestamp===chart.getDataList()[0]?.timestamp){if(!olderRequest(callback))callback([],more)}else callback([],false)});
  new ResizeObserver(scheduleFit).observe($('toolbar'));fitChart();setTimeout(fitChart,100);setTimeout(fitChart,500);say('آماده دریافت داده بیت‌یونیکس')
 },
 reset(data){if(!chart||!data.length)return;more=data.length===200;chart.applyNewData(data,more);initialized=true;for(const o of read('draw:'+series,[]))if(overlayTools.some(x=>x[0]===o.name)&&o.points?.length)try{createDrawing(o.name,o.points,o.pane||'main',o.config||{},!!o.lock,o.visible!==false)}catch(_){}updateLevels();fitChart();say('تاریخچه بارگذاری شد')},
 sync(data){if(!chart||!data.length)return;const prev=chart.getDataList();if(!prev.length){this.reset(data);return}const last=prev[prev.length-1].timestamp;const map=new Map(prev.map(x=>[x.timestamp,x]));for(const b of data)map.set(b.timestamp,b);const all=[...map.values()].sort((a,b)=>a.timestamp-b.timestamp);
  const earlier=data.some(b=>{const old=prev.find(p=>p.timestamp===b.timestamp);return b.timestamp<last&&(!old||['open','high','low','close','volume'].some(k=>old[k]!==b[k]))});if(earlier){const visible=chart.getVisibleRange(),anchor=prev[Math.min(prev.length-1,Math.max(0,visible?.to||0))]?.timestamp;chart.applyNewData(all,more);if(anchor&&visible.to<prev.length-3)chart.scrollToTimestamp(anchor)}else for(const b of data)if(b.timestamp>=last)chart.updateData(b);
 },
 tick(b){if(!chart||!initialized||!b)return;const d=chart.getDataList();if(d.length&&b.timestamp>=d[d.length-1].timestamp)chart.updateData(b)},
 older(data,hasMore){if(!chart)return;more=hasMore;const cb=pendingCallback;pendingCallback=null;pendingOlder=false;if(cb)cb(data,hasMore);else if(data.length)chart.applyMoreData(data,hasMore);say(data.length?'تاریخچه افزوده شد':'تاریخچه بیشتری دریافت نشد')},
 olderFailed(){const cb=pendingCallback;pendingCallback=null;pendingOlder=false;if(cb)cb([],true);say('دریافت تاریخچه ناموفق بود؛ دوباره امتحان کنید')},
 levels(e,s,t){levelValues=[e,s,t];updateLevels()}
};
$('ind').onclick=()=>{if(chart)indicatorMenu()};
$('draw').onclick=()=>{if(chart)drawMenu()};
$('objects').onclick=()=>{if(chart)objectMenu()};
$('templates').onclick=()=>{if(chart)templatesMenu()};
$('style').onclick=()=>{if(!chart)return;menu(()=>{for(const [id,title] of [['candle_solid','کندل'],['candle_stroke','کندل توخالی'],['candle_up_stroke','صعودی توخالی'],['candle_down_stroke','نزولی توخالی'],['ohlc','OHLC'],['area','خطی']])btn(title,()=>{chart.setStyles({candle:{type:id}});currentStyle=id;save('chartStyle',id);panel.hidden=true},id===currentStyle);btn('بستن',()=>panel.hidden=true)})};
$('levels').onclick=()=>{if(!chart)return;menu(()=>{btn('قیمت ورود',()=>chooseLevel('entry'));btn('حد ضرر',()=>chooseLevel('sl'));btn('حد سود',()=>chooseLevel('tp'));btn('بستن',()=>panel.hidden=true)})};
$('more').onclick=()=>{if(chart&&!olderRequest(null))say('تاریخچه دیگری در دسترس نیست یا در حال دریافت است')};
$('live').onclick=()=>chart?.scrollToRealTime();
if(window.klinecharts&&window.ChartNative)ChartNative.ready();else say('فایل موتور نمودار در APK موجود نیست');

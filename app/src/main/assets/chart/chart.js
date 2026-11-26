'use strict';
const $=id=>document.getElementById(id), panel=$('panel'), info=$('notice');
let chart=null, indicators={}, drawings=[], selected=null, pendingOlder=false, pendingCallback=null, more=true, levelValues=['','',''], initialized=false, currentStyle='candle_solid';
const overlayTools=[
 ['segment','خط روند','trendline'],['horizontalStraightLine','خط افقی','horizontal line'],
 ['horizontalRay','نیم‌خط افقی','horizontal ray'],['verticalStraightLine','خط عمودی','vertical line'],
 ['rayLine','نیم‌خط','ray'],['straightLine','خط ممتد','extended line'],
 ['priceChannelLine','کانال قیمت','price channel'],['parallelStraightLine','کانال موازی','parallel channel'],
 ['fibonacciLine','فیبوناچی اصلاحی','fib fibonacci retracement'],['fibChannel','کانال فیبوناچی','fib fibonacci channel'],
 ['rectangleZone','مستطیل','rectangle'],['priceRange','محدوده قیمت','price range'],['measureMove','اندازه‌گیری قیمت و زمان','measure price date time'],
 ['longPosition','موقعیت لانگ','long position'],['shortPosition','موقعیت شورت','short position']
];
const toolLabel=name=>overlayTools.find(x=>x[0]===name)?.[1] || name;
const indicatorNames=['EMA','SMA','RSI','MACD','BOLL','ATR','VOL','MA','SAR','BBI','KDJ','OBV','CCI','DMI','WR','BIAS','BRAR','VR','MTM','EMV','CR','PSY','AO','DMA','ROC','TRIX','PVT','AVP'];
const mainIndicators=new Set(['MA','EMA','SMA','BOLL','SAR','BBI','AVP']);
const indicatorLabel=name=>({BOLL:'Bollinger Bands',VOL:'Volume'})[name]||name;
let levelsEnabled=true;
function say(t){info.textContent=t}
function btn(label,action,active=false){const el=document.createElement('button');el.textContent=label;if(active)el.classList.add('active');el.onclick=()=>{try{action()}catch(_){say('عملیات نمودار ناموفق بود')}};panel.append(el);return el}
function menu(draw,title='تنظیمات'){panel.replaceChildren();$('sheettitle').textContent=title;panel.hidden=false;$('sheet').hidden=false;$('backdrop').hidden=false;draw();panel.scrollTop=0}
function closeSheet(){panel.hidden=true;$('sheet').hidden=true;$('backdrop').hidden=true}
$('closepanel').onclick=closeSheet;$('backdrop').onclick=closeSheet;
function read(k,fallback){try{return JSON.parse(localStorage.getItem(k)||'null')??fallback}catch(_){return fallback}}
function save(k,v){try{localStorage.setItem(k,JSON.stringify(v))}catch(_){say('ذخیره تنظیمات ممکن نبود')}}

function makeField(label,value,type='text'){
 const l=document.createElement('label');l.textContent=label;const i=document.createElement('input');i.type=type;i.value=value;i.dir='ltr';l.append(i);panel.append(l);return i;
}
function choice(label,values,value){
 const l=document.createElement('label');l.textContent=label;const i=document.createElement('select');i.setAttribute('aria-label',label);for(const [v,t] of values){const o=document.createElement('option');o.value=v;o.textContent=t;i.append(o)}i.value=value;l.append(i);panel.append(l);return i
}
const lineStyles=[['solid','پیوسته'],['dashed','خط‌چین'],['dotted','نقطه‌چین']];
function checkField(label,value,parent=panel) {
 const row=document.createElement('label');row.className='check-field';
 const input=document.createElement('input');input.type='checkbox';input.checked=!!value;
 row.append(input,label);parent.append(row);return input;
}
function settingsMenu(d,name) {
 const c=config(d?.config || read('tool:'+name,defaults));
 const isFib=name==='fibonacciLine'||name==='fibChannel';
 const position=isPosition(name);
 const hasFill=position||isFib||['rectangleZone','priceRange','measureMove','priceChannelLine','parallelStraightLine'].includes(name);
 menu(()=>{
  const color=makeField('رنگ خط',c.color,'color');
  const width=makeField('ضخامت خط (۱ تا ۶)',c.width,'number');
  const style=choice('نوع خط',lineStyles,c.style);
  const background=hasFill&&!position ? makeField('رنگ پس‌زمینه',c.background,'color') : null;
  const targetColor=position ? makeField('رنگ هدف',c.targetColor,'color') : null;
  const riskColor=position ? makeField('رنگ حد ضرر',c.riskColor,'color') : null;
  const opacity=hasFill ? makeField('شفافیت رنگ (%)',Math.round(c.opacity*100),'number') : null;
  let fib=c.fib.map(x=>({...x}));
  let reverse,left,right,prices,percents,labels;
  if(isFib) {
   reverse=checkField('معکوس',c.reverse);
   left=checkField('امتداد به چپ',c.extendLeft);
   right=checkField('امتداد به راست',c.extendRight);
   prices=checkField('نمایش قیمت',c.showPrice);
   percents=checkField('نمایش درصد',c.showPercent);
   labels=choice('جای برچسب‌ها',[['left','چپ'],['center','وسط'],['right','راست']],c.labels);
   const rows=document.createElement('div');
   rows.className='fib-levels';
   panel.append(rows);
   function renderFib(){
    rows.replaceChildren();
    const headings=document.createElement('div');
    headings.className='fibrow fib-head';
    for(const text of ['فعال','سطح','رنگ','ضخامت','خط','']) {
     const heading=document.createElement('span');heading.textContent=text;headings.append(heading);
    }
    rows.append(headings);
    fib.forEach((level,i)=>{
     const row=document.createElement('div');row.className='fibrow';
     const input=(type,label,value,onChange)=>{
      const field=document.createElement('input');field.type=type;field.setAttribute('aria-label',label+' '+(i+1));
      if(type==='checkbox')field.checked=value;else field.value=value;
      field.oninput=()=>onChange(type==='checkbox'?field.checked:field.value);row.append(field);return field;
     };
     input('checkbox','فعال',level.on,v=>level.on=v);
     input('number','سطح',level.v,v=>level.v=v).step='any';
     input('color','رنگ سطح',level.color||color.value,v=>level.color=v);
     input('number','ضخامت سطح',level.width||'',v=>level.width=v).placeholder='—';
     const line=document.createElement('select');line.setAttribute('aria-label','خط سطح '+(i+1));
     for(const [v,t] of [['','عمومی'],...lineStyles]) line.add(new Option(t,v));
     line.value=level.style||'';line.onchange=()=>level.style=line.value;row.append(line);
     const del=document.createElement('button');del.textContent='×';del.setAttribute('aria-label','حذف سطح '+(i+1));
     del.onclick=()=>{fib.splice(i,1);renderFib()};row.append(del);rows.append(row);
    });
   }
   renderFib();
   btn('افزودن سطح',()=>{if(fib.length<40){fib.push({v:'',on:true});renderFib();rows.lastElementChild.querySelector('input[type=number]').focus()}});
   btn('بازنشانی سطوح',()=>{fib=defaultFib.map(x=>({...x}));renderFib()});
  }
  const makeDefault=d ? checkField('ذخیره به‌عنوان پیش‌فرض',false) : null;
  btn(d?'اعمال تغییرات':'ذخیره پیش‌فرض',()=>{
   const w=Number(width.value), alpha=opacity ? Number(opacity.value)/100 : c.opacity;
   const badLevel=isFib&&fib.some(l=>String(l.v).trim()===''||!Number.isFinite(Number(l.v))||Math.abs(Number(l.v))>100||
    (l.width && (!Number.isFinite(Number(l.width))||Number(l.width)<1||Number(l.width)>6)));
   if(!Number.isFinite(w)||w<1||w>6||!Number.isFinite(alpha)||alpha<0||alpha>1||badLevel){
    say('ضخامت، شفافیت یا سطح واردشده معتبر نیست');return;
   }
   const next={...c,color:color.value,width:w,style:style.value,background:background?.value||c.background,opacity:alpha};
   if(position) Object.assign(next,{targetColor:targetColor.value,riskColor:riskColor.value});
   if(isFib) Object.assign(next,{fib:fib.map(l=>{
    const level={...l,v:Number(l.v)};
    if(l.width)level.width=Number(l.width);else delete level.width;
    return level;
   }),
    reverse:reverse.checked,extendLeft:left.checked,extendRight:right.checked,
    showPrice:prices.checked,showPercent:percents.checked,labels:labels.value});
   if(!d||makeDefault.checked) save('tool:'+name,next);
   if(d) changeDrawing(d,{config:next});
   closeSheet();say('تنظیمات ذخیره شد');
  });
  btn('بازگشت',d?closeSheet:drawMenu);
 },toolLabel(name));
}
function objectMenu() {
 menu(()=>{
  for(const d of drawings) btn(toolLabel(d.name)+(d.lock?' · قفل':'')+(d.visible?'':' · مخفی'),
   ()=>{selection(d.id);objectActions(d)},selected===d.id);
  if(!drawings.length) panel.append(document.createTextNode('هنوز ترسیمی روی نمودار نیست'));
 },'ترسیم‌ها');
}
function objectActions(d) {
 menu(()=>{
  btn('تنظیمات',()=>settingsMenu(d,d.name));
  btn(d.lock?'باز کردن قفل':'قفل کردن',()=>{changeDrawing(d,{lock:!d.lock});closeSheet()});
  btn(d.visible?'مخفی کردن':'نمایش',()=>{changeDrawing(d,{visible:!d.visible});closeSheet()});
  btn('تکثیر',()=>{
   const offset=chart.getBarSpace()*2;
   const pane=panes().find(p=>p.key===d.pane);
   const map=pane&&mapper(pane);
   const points=map ? d.points.map(p=>{const {x,y}=map.xy(p);return map.point(x+offset,y+10)||p}) : d.points;
   const copy={...d,points,id:newDrawingId(),lock:false,visible:true};
   editDrawings([...drawings,copy],copy.id);closeSheet();
  });
  btn('حذف',()=>{deleteDrawing(d.id);closeSheet()});
  btn('فهرست ترسیم‌ها',objectMenu);
 },toolLabel(d.name));
}
const toolGroups=[
 {name:'خطوط و روند',ids:['segment','rayLine','horizontalStraightLine','horizontalRay','verticalStraightLine','straightLine']},
 {name:'کانال‌ها و فیبوناچی',ids:['priceChannelLine','parallelStraightLine','fibonacciLine','fibChannel']},
 {name:'محدوده و اندازه‌گیری',ids:['rectangleZone','priceRange','measureMove']},
 {name:'موقعیت معامله',ids:['longPosition','shortPosition']}
];
const iconPaths={horizontalRay:'M5 16h25M5 14v4',fibChannel:'M2 27L25 4M5 30L29 6M13 31L31 13',priceRange:'M4 5h23v22H4zM16 8v16m-3-13 3-3 3 3m-6 10 3 3 3-3',longPosition:'M4 4h24v24H4zM4 19h24M16 16V7m-3 3 3-3 3 3',shortPosition:'M4 4h24v24H4zM4 13h24M16 16v9m-3-3 3 3 3-3',segment:'M4 25L26 5 M4 25a2 2 0 1 0 0 .1 M26 5a2 2 0 1 0 0 .1',rayLine:'M3 26L28 3M3 26h3',horizontalStraightLine:'M2 16H30M16 13v6',verticalStraightLine:'M16 2V30M13 16h6',straightLine:'M2 29L30 3',priceChannelLine:'M2 24L24 3L30 10L8 31Z',parallelStraightLine:'M2 21L22 2M6 26L26 7M10 31L30 12',fibonacciLine:'M3 5h25M3 12h25M3 19h25M3 26h25M9 3v4M24 24v4',rectangleZone:'M4 7h24v18H4zM4 7l2 2M28 25l-2-2',measureMove:'M3 27V5H29M3 27L29 5'};
let favorites=new Set(read('favorites',['segment','rayLine','horizontalStraightLine','fibonacciLine','priceChannelLine']).filter(n=>overlayTools.some(x=>x[0]===n)));
let favoriteBar=!!read('favoriteBar',false);
let toolTab='favorites';
function toolIcon(name){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 32 32');const p=document.createElementNS(svg.namespaceURI,'path');p.setAttribute('d',iconPaths[name]||'M2 16h28');svg.append(p);return svg}
function refreshQuick(){
 const q=$('quick');q.replaceChildren();q.hidden=!favoriteBar||!favorites.size||!!placement;
 for(const [name,label] of overlayTools.filter(x=>favorites.has(x[0]))){const el=document.createElement('button');el.title=label;el.setAttribute('aria-label',label);el.append(toolIcon(name));el.onclick=()=>startPlacement(name);q.append(el)}
}
function drawMenu(){menu(()=>{
 const search=document.createElement('input');search.type='search';search.className='search';search.placeholder='جستجوی ابزار';panel.append(search);
 const tabs=document.createElement('div');tabs.className='tabs';panel.append(tabs);
 const content=document.createElement('div');panel.append(content);
 const tabOptions=[['favorites','علاقه‌مندی‌ها'],['all','همه ابزارها'],...toolGroups.map((g,i)=>[String(i),g.name])];
 function render(){
  for(const t of tabs.children)t.classList.toggle('active',t.dataset.key===toolTab);
  content.replaceChildren();const query=search.value.trim().toLowerCase();let count=0;
  toolGroups.forEach((g,i)=>{
   if(toolTab!=='all'&&toolTab!=='favorites'&&toolTab!==String(i)&&!query)return;
   const tools=overlayTools.filter(([n,l,keyword])=>g.ids.includes(n)&&(!query?(toolTab!=='favorites'||favorites.has(n)):(n.toLowerCase().includes(query)||l.includes(query)||keyword.includes(query))));
   if(!tools.length)return;count+=tools.length;
   const title=document.createElement('div');title.className='category';title.textContent=g.name;content.append(title);
   const grid=document.createElement('div');grid.className='tiles';content.append(grid);
   for(const [name,label] of tools){
    const tile=document.createElement('div');tile.className='tile';grid.append(tile);
    const pick=document.createElement('button');pick.className='tilepick';pick.append(toolIcon(name));const txt=document.createElement('span');txt.textContent=label;pick.append(txt);pick.onclick=()=>startPlacement(name);tile.append(pick);
    const star=document.createElement('button');star.className='star'+(favorites.has(name)?' on':'');star.textContent=favorites.has(name)?'★':'☆';star.setAttribute('aria-label','علاقه‌مندی '+label);star.onclick=()=>{if(favorites.has(name))favorites.delete(name);else favorites.add(name);save('favorites',[...favorites]);refreshQuick();render()};tile.append(star);
    const cfg=document.createElement('button');cfg.className='tileconfig';cfg.textContent='⚙';cfg.setAttribute('aria-label','تنظیمات '+label);cfg.onclick=()=>settingsMenu(null,name);tile.append(cfg)
   }
  });
  if(!count){const empty=document.createElement('div');empty.className='empty';empty.textContent='ابزاری پیدا نشد؛ همه ابزارها را ببینید.';content.append(empty)}
 }
 for(const [key,title] of tabOptions){const t=document.createElement('button');t.textContent=title;t.dataset.key=key;t.onclick=()=>{toolTab=key;search.value='';render()};tabs.append(t)}
 search.oninput=render;render();
 const toggle=document.createElement('label');toggle.className='toggleline';toggle.textContent='نمایش علاقه‌مندی‌ها روی چارت';const input=document.createElement('input');input.type='checkbox';input.checked=favoriteBar;input.onchange=()=>{favoriteBar=input.checked;save('favoriteBar',favoriteBar);refreshQuick()};toggle.append(input);panel.append(toggle);
 btn('مدیریت ترسیم‌های موجود',objectMenu);
},'ابزارهای ترسیم')}
$('selsettings').onclick=()=>{const d=metadata(selected);if(d)settingsMenu(d,d.name)};
$('seldel').onclick=()=>{if(selected)deleteDrawing(selected)};
$('selmore').onclick=()=>{const d=metadata(selected);if(d)objectActions(d)};
const customIndicators = new Set(['EMA','MA','SMA','ATR']);
function calculateIndicator(name, list, n, source) {
 let average = null, sum = 0;
 const alpha = name === 'ATR' ? 1/n : 2/(n+1);
 return list.map((bar,index) => {
  const close = list[index-1]?.close;
  const v = name === 'ATR' ? Math.max(bar.high-bar.low,
   close === undefined ? 0 : Math.abs(bar.high-close),
   close === undefined ? 0 : Math.abs(bar.low-close)) : bar[source];
  if (index < n) sum += v;
  if (index < n-1) return {};
  if (name === 'EMA' || name === 'ATR') {
   average = average === null ? sum/n : average+(v-average)*alpha;
  } else {
   if(index >= n) sum += v - list[index-n][source];
   average = sum/n;
  }
  return {v:average};
 });
}
let indicatorLayout = '';
function layoutIndicatorPanes(){
 const secondary=Object.values(indicators).filter(v=>v.pane&&v.pane!=='candle_pane');
 const height=$('chartwrap').clientHeight;
 const key=height+'|'+secondary.map(v=>v.pane).join('|');
 if(key===indicatorLayout||height<=0)return;
 indicatorLayout=key;
 if(!secondary.length)return;
 const size=Math.max(22,Math.min(82,Math.floor((height-25)*.42/secondary.length)));
 for(const v of secondary)chart.setPaneOptions({id:v.pane,height:size,minHeight:Math.min(38,size)});
}
function indicator(name,params,opts={}) {
 const key=opts.key||name, old=indicators[key];
 const custom=customIndicators.has(name);
 const source=opts.source||'close',color=opts.color||'#fbbf24',width=Number(opts.width)||2,hidden=!!opts.hidden;
 const styled=opts.styled ?? !!opts.color;
 const engine=custom ? 'SS_'+name+'_'+key.replace(/[^a-zA-Z0-9_]/g,'') : name;
 if(custom&&!old) klinecharts.registerIndicator({
  name:engine,shortName:name,series:mainIndicators.has(name)?'price':'normal',
  calcParams:[name==='ATR'?14:20],figures:[{key:'v',title:name+': ',type:'line'}],
  calc:(list,ind)=>calculateIndicator(name,list,Math.max(1,Math.floor(ind.calcParams[0])),ind.extendData?.source||'close')
 });
 const options={name:engine,visible:!hidden,extendData:{source}};
 if(params.length) options.calcParams=params;
 if(custom||styled) {
  const lineStyle=shade=>({color:shade,size:width,style:'solid',dashedValue:[4,4],smooth:false});
  const lines=[lineStyle(color)];
  if(name==='MACD') lines.push(lineStyle('#799afa'));
  if(name==='BOLL') lines.push(lineStyle('#799afa'),lineStyle('#ad82ea'));
  options.styles={lines};
  if(name==='VOL') options.styles.bars=[{upColor:color,downColor:'#e24b67',noChangeColor:'#94a3b8'}];
 }
 const isMain=mainIndicators.has(name);
 let pane=old?.pane;
 let values=params;
 if(hidden&&!isMain){
  if(pane)chart.removeIndicator(pane,engine);
  pane=null;
 } else {
  if(pane)chart.overrideIndicator(options,pane);
  else pane=chart.createIndicator(options,true,isMain?{id:'candle_pane'}:{height:82,minHeight:38,dragEnabled:true});
  if(!pane)return;
  if(!values.length)values=chart.getIndicatorByPaneId(pane,engine)?.calcParams||[];
 }
 indicators[key]={pane,engine,name,params:values,source,color,width,key,hidden,styled};
 layoutIndicatorPanes();redrawLayer();refreshLegend();
}
function saveInd(){save('indicators',Object.values(indicators).map(({pane,engine,...v})=>v))}
function removeInd(key) {
 const v=indicators[key];
 if(!v)return;
 if(v.pane)chart.removeIndicator(v.pane,v.engine);
 delete indicators[key];
 // Keep saved drawings for this pane if the same indicator is added again.
 if(metadata(selected)?.pane===key) selection(null);
 saveInd();refreshLegend();layoutIndicatorPanes();redrawLayer();
}
function toggleIndicator(v){
 indicator(v.name,v.params,{...v,hidden:!v.hidden});saveInd();
}
function refreshLegend() {
 const legend=$('legend');
 legend.replaceChildren();
 legend.hidden=!Object.keys(indicators).length;
 for(const v of Object.values(indicators)){
  const el=document.createElement('button');
  el.textContent=v.name+' '+v.params.join(', ');
  el.classList.toggle('is-hidden',v.hidden);
  el.onclick=()=>indicatorSettings(v.key);
  legend.append(el);
 }
}
function indicatorSettings(key) {
 const v=indicators[key];
 if(!v)return;
 menu(()=>{
  const periods = v.name==='MACD'?['دوره سریع','دوره کند','دوره سیگنال'] :
   v.name==='BOLL'?['دوره','انحراف معیار'] : [];
  const fields=v.params.map((value,i)=>makeField(periods[i]||('دوره '+(i+1)),value,'number'));
  if(v.name==='BOLL'&&fields[1])fields[1].step='any';
  const source=['EMA','MA','SMA'].includes(v.name) ?
   choice('منبع قیمت',[['close','Close'],['open','Open'],['high','High'],['low','Low']],v.source):null;
  const color=makeField('رنگ',v.color,'color'),width=makeField('ضخامت',v.width,'number');
  if(v.name==='VOL')width.closest('label').hidden=true;
  btn('اعمال',()=>{
   const values=fields.map(x=>Number(x.value)),w=Number(width.value);
   if(values.some((x,i)=>!Number.isFinite(x)||x<=0||x>10000||(!(v.name==='BOLL'&&i===1)&&!Number.isInteger(x)))||
     (v.name==='MACD'&&values[0]>=values[1])||!Number.isFinite(w)||w<1||w>6){say('پارامتر نامعتبر است');return}
   indicator(v.name,values,{...v,source:source?.value||v.source,color:color.value,width:w,styled:true});
   saveInd();closeSheet();say('');
  });
  btn(v.hidden?'نمایش':'مخفی کردن',()=>{toggleIndicator(v);closeSheet()});
  btn('حذف',()=>{removeInd(key);closeSheet()});
  btn('فهرست اندیکاتورها',indicatorMenu);
 },indicatorLabel(v.name));
}
function indicatorMenu(){
 menu(()=>{
  const list=document.createElement('div');
  for(const [key,v] of Object.entries(indicators)){
   const row=document.createElement('div');row.className='indicator-row';
   const name=document.createElement('strong');name.textContent=indicatorLabel(v.name)+' '+v.params.join(', ');
   row.append(name);
   for(const [label,action] of [
    ['ویرایش',()=>indicatorSettings(key)],
    [v.hidden?'نمایش':'مخفی',()=>{toggleIndicator(v);indicatorMenu()}],
    ['حذف',()=>{removeInd(key);indicatorMenu()}]
   ]) {
    const button=document.createElement('button');button.textContent=label;button.onclick=action;row.append(button);
   }
   list.append(row);
  }
  panel.append(list);
  const filter=document.createElement('input');filter.type='search';filter.className='search';
  filter.placeholder='افزودن اندیکاتور';panel.append(filter);
  const choices=document.createElement('div');choices.className='tabs';choices.style.flexWrap='wrap';panel.append(choices);
  for(const name of indicatorNames){
   const multiple=['EMA','SMA','MA'].includes(name);
   const el=btn('＋ '+indicatorLabel(name),()=>{
    const key=multiple?name+'_'+newDrawingId():name;
    if(!indicators[key])indicator(name,[],{key});
    saveInd();indicatorSettings(key);
   });
   choices.append(el);el.dataset.name=(name+' '+indicatorLabel(name)).toUpperCase();
  }
  filter.oninput=()=>{for(const el of choices.children)el.hidden=!el.dataset.name.includes(filter.value.toUpperCase().trim())};
 },'اندیکاتورها');
}
function templatesMenu(){menu(()=>{
 const name=makeField('نام قالب','');
 btn('ذخیره قالب فعلی',()=>{const n=name.value.trim().slice(0,60);if(!n)return;const t=read('templates',{});t[n]={indicators:Object.values(indicators).map(({pane,engine,...v})=>v),style:currentStyle};save('templates',t);templatesMenu()});
 for(const [n,t] of Object.entries(read('templates',{}))){
  btn('بارگذاری '+n,()=>{if(drawings.some(d=>d.pane!=='main')){say('پیش از تغییر قالب، ترسیم‌های پنل اندیکاتور را حذف یا منتقل کنید');return}for(const k of Object.keys(indicators))removeInd(k);for(const v of t.indicators||[])if(indicatorNames.includes(v.name))indicator(v.name,v.params||[],v);currentStyle=t.style||'candle_solid';chart.setStyles({candle:{type:currentStyle}});save('chartStyle',currentStyle);saveInd();closeSheet()});
  btn('حذف قالب '+n,()=>{const all=read('templates',{});delete all[n];save('templates',all);templatesMenu()})
 }
 btn('بستن',()=>closeSheet())
})}
function chartAppearance(theme){
 document.body.classList.toggle('light',theme==='light');
 chart.setStyles(theme);
 chart.setStyles({candle:{type:currentStyle,bar:{upColor:'#18a999',downColor:'#e34b68',noChangeColor:'#8994a7',
  upBorderColor:'#18a999',downBorderColor:'#e34b68',upWickColor:'#18a999',downWickColor:'#e34b68'}}});
 redrawLayer();
}
function updateLevels(){
 if(!chart||!initialized||!levelsEnabled)return;chart.removeOverlay({groupId:'trade-level'});
 ['#a5b4fc','#fb7185','#2dd4bf'].forEach((color,i)=>{const value=Number(levelValues[i]);if(value>0&&Number.isFinite(value))chart.createOverlay({name:'horizontalStraightLine',groupId:'trade-level',lock:true,points:[{value}],styles:{line:{color,size:1,style:'dashed'}}},'candle_pane')});
}
function chooseLevel(kind){
 startPlacement('horizontalStraightLine');if(placement){placement.level=kind;say('قیمت مورد نظر را روی چارت لمس و رها کنید')}
}
function olderRequest(cb){
 if(pendingOlder||!more)return false;const data=chart.getDataList();if(!data.length)return false;
 pendingOlder=true;pendingCallback=cb||null;ChartNative.older(String(data[0].timestamp));say('در حال دریافت تاریخچه قدیمی‌تر…');return true
}
let nativeHeight=0,nativeWidth=0;
function fitChart(hintHeight,hintWidth){
 if(Number.isFinite(hintHeight)&&hintHeight>0)nativeHeight=hintHeight;
 if(Number.isFinite(hintWidth)&&hintWidth>0)nativeWidth=hintWidth;
 const root=document.documentElement,body=document.body,toolbar=$('toolbar'),wrap=$('chartwrap');
 const h=Math.max(0,nativeHeight||window.innerHeight||root.clientHeight),w=Math.max(0,nativeWidth||window.innerWidth||root.clientWidth);
 if(!h||!w)return;
 body.style.height=h+'px';body.style.width=w+'px';
 const bottom=Math.ceil(toolbar.getBoundingClientRect().height);
 const available=Math.max(1,h-bottom);
 wrap.style.top='0';wrap.style.height=available+'px';wrap.style.bottom='auto';
 $('chart').style.height=available+'px';$('chart').style.width=w+'px';
 if(chart){chart.resize();layoutIndicatorPanes();redrawLayer()}
}
let fitFrame=0;
function scheduleFit(){if(fitFrame)return;fitFrame=requestAnimationFrame(()=>{fitFrame=0;fitChart()})}
window.addEventListener('resize',scheduleFit);
window.visualViewport?.addEventListener('resize',scheduleFit);
window.SafeChart={
 fit:fitChart,
 init(symbol,interval,type,precision,allowLevels=true){
  if(chart)return;timelineInterval=interval;pricePrecision=precision;levelsEnabled=allowLevels;chart=klinecharts.init('chart',{styles:read('appearance','dark'),timezone:'Etc/UTC',locale:'en-US'});
  if(!chart){say('موتور نمودار آماده نشد');return}
  chart.setPriceVolumePrecision(precision,6);chart.setOffsetRightDistance(Math.round(Math.max(56,Math.min(160,surface.clientWidth*.19))));
  for(const action of ['onScroll','onZoom','onDataReady','onVisibleRangeChange','onPaneDrag'])chart.subscribeAction(action,redrawLayer);
  restoreDrawings(symbol,type);
  currentStyle=read('chartStyle','candle_solid');
  chartAppearance(read('appearance','dark'));
  for(const v of read('indicators',[{name:'VOL',params:[]}]))if(indicatorNames.includes(v.name))try{indicator(v.name,v.params||[],v)}catch(_){}
  chart.setLoadDataCallback(({type,data,callback})=>{if(data && data.timestamp===chart.getDataList()[0]?.timestamp){if(!olderRequest(callback))callback([],more)}else callback([],false)});
  const resize=new ResizeObserver(scheduleFit);resize.observe($('toolbar'));refreshQuick();fitChart();setTimeout(fitChart,100);setTimeout(fitChart,500);say('آماده دریافت داده بیت‌یونیکس')
 },
 reset(data){
  if(!chart||!data.length)return;
  more=data.length===200;chart.applyNewData(data,more);initialized=true;
  updateLevels();fitChart();say('');
 },
 sync(data) {
  if(!chart||!data.length)return;
  const prev=chart.getDataList();
  if(!prev.length){this.reset(data);return}
  const last=prev[prev.length-1].timestamp;
  const bars=new Map(prev.map(x=>[x.timestamp,x]));
  const earlier=data.some(b=>{
   const old=bars.get(b.timestamp);
   return b.timestamp<last&&(!old||['open','high','low','close','volume'].some(k=>old[k]!==b[k]));
  });
  if(earlier){
   const visible=chart.getVisibleRange();
   const index=Math.max(0,Math.min(prev.length-1,visible.to-1));
   const finder={paneId:'candle_pane'};
   const x=chart.convertToPixel({dataIndex:index},finder).x;
   const time=prev[index].timestamp;
   for(const bar of data)bars.set(bar.timestamp,bar);
   chart.applyNewData([...bars.values()].sort((a,b)=>a.timestamp-b.timestamp),more);
   if(visible.to<prev.length-2)chart.scrollByDistance(x-chart.convertToPixel({timestamp:time},finder).x);
  }else for(const bar of data)if(bar.timestamp>=last)chart.updateData(bar);
  redrawLayer();
 },
 tick(b){if(!chart||!initialized||!b)return;const d=chart.getDataList();if(d.length&&b.timestamp>=d[d.length-1].timestamp)chart.updateData(b);redrawLayer()},
 older(data,hasMore){if(!chart)return;more=hasMore;const cb=pendingCallback;pendingCallback=null;pendingOlder=false;if(cb)cb(data,hasMore);else if(data.length)chart.applyMoreData(data,hasMore);say(data.length?'':'تاریخچه بیشتری دریافت نشد');redrawLayer()},
 olderFailed(){const cb=pendingCallback;pendingCallback=null;pendingOlder=false;if(cb)cb([],true);say('دریافت تاریخچه ناموفق بود؛ دوباره امتحان کنید')},
 precision(digits){if(chart){pricePrecision=digits;chart.setPriceVolumePrecision(digits,6);redrawLayer()}},
 levels(e,s,t){levelValues=[e,s,t];updateLevels()}
};
$('ind').onclick=()=>{if(chart)indicatorMenu()};
$('draw').onclick=()=>{if(chart)drawMenu()};
$('options').onclick=()=>{if(!chart)return;menu(()=>{
 btn('ترسیم‌های ذخیره‌شده',objectMenu);
 btn('نوع نمودار',()=>$('style').click());
 btn('قالب‌های تحلیل',()=>$('templates').click());
 if(levelsEnabled)btn('سطوح ورود / SL / TP',()=>$('levels').click());
 btn('دریافت تاریخچه قدیمی‌تر',()=>{closeSheet();$('more').click()});
 btn(read('appearance','dark')==='dark'?'حالت روشن':'حالت تیره',()=>{
  const next=read('appearance','dark')==='dark'?'light':'dark';save('appearance',next);chartAppearance(next);closeSheet();fitChart()
 });
 btn('حذف همه ترسیم‌ها',()=>menu(()=>{btn('تأیید حذف همه ابزارها',()=>{editDrawings([],null);closeSheet()});btn('انصراف',()=>$('options').click())},'حذف ترسیم‌ها'));
},'تنظیمات چارت')};
$('objects').onclick=()=>{if(chart)objectMenu()};
$('templates').onclick=()=>{if(chart)templatesMenu()};
$('style').onclick=()=>{if(!chart)return;menu(()=>{for(const [id,title] of [['candle_solid','کندل'],['candle_stroke','کندل توخالی'],['candle_up_stroke','صعودی توخالی'],['candle_down_stroke','نزولی توخالی'],['ohlc','OHLC'],['area','خطی']])btn(title,()=>{chart.setStyles({candle:{type:id}});currentStyle=id;save('chartStyle',id);closeSheet()},id===currentStyle);btn('بستن',()=>closeSheet())})};
$('levels').onclick=()=>{if(!chart)return;menu(()=>{btn('قیمت ورود',()=>chooseLevel('entry'));btn('حد ضرر',()=>chooseLevel('sl'));btn('حد سود',()=>chooseLevel('tp'));btn('بستن',()=>closeSheet())})};
$('more').onclick=()=>{if(chart&&!olderRequest(null))say('تاریخچه دیگری در دسترس نیست یا در حال دریافت است')};
$('live').onclick=()=>chart?.scrollToRealTime();
document.addEventListener('DOMContentLoaded',()=>{
 if(window.klinecharts&&window.ChartNative)ChartNative.ready();
 else if(!window.klinecharts)say('فایل موتور نمودار در APK موجود نیست');
});
$('magnet').onclick=()=>menu(()=>{
 for(const [mode,label] of [['normal','خاموش'],['weak_magnet','ضعیف · نزدیک OHLC'],['strong_magnet','قوی · نزدیک‌ترین OHLC']]) {
  btn(label,()=>{magnet=mode;save('magnet',mode);updateMagnet();closeSheet()},mode===(magnet??'normal'));
 }
},'آهنربای ترسیم');
function updateMagnet() {
 $('magnet').classList.toggle('active',!!magnet&&magnet!=='normal');
 $('magnet').setAttribute('aria-pressed',String(!!magnet&&magnet!=='normal'));
}
document.addEventListener('DOMContentLoaded',updateMagnet);

package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.*
import kotlinx.coroutines.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

private fun fmt(n: BigDecimal) = n.stripTrailingZeros().toPlainString()
private fun fmtShort(n: BigDecimal) = fmt(n.setScale(4,RoundingMode.HALF_UP))

@Composable
fun MarketPlannerScreen(onManual: () -> Unit, onWatch: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val vault = remember { BitunixVault(context) }
    val pending = remember { context.getSharedPreferences("bitunix_planner_pending",0) }
    var unresolved by remember { mutableStateOf(pending.getString("id","") ?: "") }
    var pairs by remember { mutableStateOf<List<MarketPair>>(emptyList()) }
    var symbol by remember { mutableStateOf("BTCUSDT") }
    var interval by remember { mutableStateOf("15m") }
    var candles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var quote by remember { mutableStateOf<Quote?>(null) }
    var updated by remember { mutableStateOf(0L) }
    var marketError by remember { mutableStateOf("") }
    var available by remember { mutableStateOf<BigDecimal?>(null) }
    var entry by remember { mutableStateOf("") }
    var stop by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var risk by remember { mutableStateOf("1") }
    var fee by remember { mutableStateOf("0.06") }
    var long by remember { mutableStateOf(true) }
    var pick by remember { mutableStateOf("entry") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<BitunixPreview?>(null) }
    var previewKey by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var checklist by remember { mutableStateOf(setOf<Int>()) }
    val tasks = remember { (context.getSharedPreferences("risk_studio_v1",0).getString("checklist","ستاپ مطابق استراتژی است\nحد ضرر مشخص است\nحجم محاسبه شده است\nورود احساسی نیست") ?: "").lines().filter { it.isNotBlank() }.take(12) }
    val pair = pairs.firstOrNull { it.symbol == symbol }
    val riskCap = context.getSharedPreferences("risk_studio_v1",0).getString("risk","1")?.toBigDecimalOrNull() ?: BigDecimal.ONE
    suspend fun loadAccount(): BitunixCredentials {
        val c = withContext(Dispatchers.IO) { vault.load() } ?: error("ابتدا حساب بیت‌یونیکس را در داشبورد متصل کنید")
        available = BitunixClient.fetchAccount(c).available.toBigDecimal()
        return c
    }
    suspend fun loadPairs() { pairs = MarketData.pairs() }
    LaunchedEffect(Unit) {
        try { loadPairs() } catch(e: CancellationException) { throw e } catch(_: Exception) { marketError = "دریافت فهرست نمادها ناموفق بود" }
        try { loadAccount() } catch(e: CancellationException) { throw e } catch(_: Exception) { message = "حساب در دسترس نیست؛ اتصال را بررسی کنید" }
    }
    LaunchedEffect(symbol,interval) {
        candles = emptyList(); quote = null; updated = 0
        while (isActive) {
            if (MarketData.validSymbol(symbol)) {
                try {
                    val c = MarketData.candles(symbol,interval)
                    val q = MarketData.quotes(listOf(symbol))[symbol] ?: error("No quote")
                    candles = c; quote = q; updated = System.currentTimeMillis(); marketError = ""
                } catch(e: CancellationException) { throw e } catch(_: Exception) { marketError = "دریافت بازار ناموفق؛ داده قبلی ممکن است قدیمی باشد" }
            }
            delay(15000)
        }
    }
    fun calculate(p: MarketPair, balance: BigDecimal): Sizing {
        val r = risk.toBigDecimal()
        require(r <= riskCap) { "ریسک از سقف شخصی بیشتر است" }
        return MarketData.size(p,balance,r,entry.toBigDecimal(),stop.toBigDecimal(),target.takeIf { it.isNotBlank() }?.toBigDecimal(),long,fee.toBigDecimal())
    }
    val sizing = if (pair != null && available != null) runCatching { calculate(pair,available!!) }.getOrNull() else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("طراحی معامله • Bitunix",style = MaterialTheme.typography.headlineSmall,fontWeight = FontWeight.Bold)
        Row { TextButton(onClick = onManual) { Text("ماشین حساب دستی") }; TextButton(onClick = onWatch) { Text("رصد بازار") } }
        Card {
            Column(Modifier.padding(14.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(symbol,{ symbol = it.uppercase(Locale.US).replace("/","").trim().take(24); entry="";stop="";target="";checklist=emptySet() },label={Text("جستجوی نماد")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    pairs.filter { it.symbol.contains(symbol,true) || it.symbol in listOf("BTCUSDT","ETHUSDT","SOLUSDT") }.distinctBy { it.symbol }.take(12).forEach { p ->
                        FilterChip(p.symbol==symbol,{symbol=p.symbol;entry="";stop="";target="";checklist=emptySet()}, {Text(p.symbol)},enabled=!busy)
                    }
                }
                Text("آخرین قیمت: " + (quote?.last?.let(::fmt) ?: "—") + " | Mark: " + (quote?.mark?.let(::fmt) ?: "—"))
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    MarketData.intervals.forEach { t -> FilterChip(t==interval,{interval=t},{Text(t)}) }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("entry" to "ورود","sl" to "ضرر","tp" to "سود").forEach { (id,label) -> FilterChip(pick==id,{pick=id},{Text(label)},enabled=!busy) }
                }
                Text("برای تعیین سطح انتخاب‌شده روی چارت لمس کنید؛ کندل‌های بسته‌شده، تازه‌سازی دوره‌ای.")
                if (candles.isNotEmpty()) {
                    CandleChart(candles,entry.toDoubleOrNull(),stop.toDoubleOrNull(),target.toDoubleOrNull()) { v ->
                        if (!busy && pair != null) {
                            val value = fmt(BigDecimal.valueOf(v).setScale(pair.pricePrecision,RoundingMode.HALF_UP))
                            when(pick) { "entry" -> entry=value; "sl" -> stop=value; else -> target=value }
                        }
                    }
                }
                if(updated>0) Text("آخرین دریافت: "+java.text.DateFormat.getTimeInstance().format(java.util.Date(updated)),style=MaterialTheme.typography.labelSmall)
                if(marketError.isNotBlank()) Text(marketError,color=MaterialTheme.colorScheme.error)
                TextButton(enabled=!busy && quote!=null,onClick={if(pair!=null) entry=fmt(quote!!.last.setScale(pair.pricePrecision,RoundingMode.HALF_UP))}) { Text("قیمت فعلی به عنوان ورود") }
            }
        }
        Card {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("محاسبه حجم براساس موجودی آزاد",style=MaterialTheme.typography.titleLarge)
                Text("موجودی آزاد: "+(available?.let(::fmt) ?: "—")+" USDT • سقف ریسک "+fmt(riskCap)+"٪")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(long,{long=true;checklist=emptySet()},{Text("LONG")},enabled=!busy)
                    FilterChip(!long,{long=false;checklist=emptySet()},{Text("SHORT")},enabled=!busy)
                }
                OutlinedTextField(entry,{entry=it},label={Text("قیمت ورود Limit")},singleLine=true,enabled=!busy)
                OutlinedTextField(stop,{stop=it},label={Text("حد ضرر (Mark Price)")},singleLine=true,enabled=!busy)
                OutlinedTextField(target,{target=it},label={Text("حد سود (اختیاری، Mark Price)")},singleLine=true,enabled=!busy)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(risk,{risk=it},label={Text("ریسک %")},modifier=Modifier.weight(1f),singleLine=true,enabled=!busy)
                    OutlinedTextField(fee,{fee=it},label={Text("برآورد کارمزد هر سمت %")},modifier=Modifier.weight(1f),singleLine=true,enabled=!busy)
                }
                sizing?.let { s ->
                    HorizontalDivider()
                    Text("حجم ارز: "+fmt(s.quantity),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    Text("ارزش پوزیشن: "+fmtShort(s.notional)+" USDT")
                    Text("زیان نظری با کارمزد تخمینی: "+fmtShort(s.loss),color=MaterialTheme.colorScheme.error)
                    s.reward?.let { r -> Text("سود نظری: "+fmtShort(r)+" | R:R "+fmtShort(r.divide(s.loss,4,RoundingMode.DOWN))) }
                } ?: Text("برای محاسبه، نماد معتبر، موجودی و قیمت‌های درست وارد کنید؛ سقف ریسک و دقت صرافی رعایت شود.")
                Text("اهرم فعلی صرافی در پیش‌نمایش خوانده می‌شود. لغزش، فاندینگ و لیکوییدیشن ممکن است زیان را افزایش دهند.",style=MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("چک‌لیست دستی پیش از ورود",fontWeight=FontWeight.Bold)
                tasks.forEachIndexed { i,t -> Row { Checkbox(i in checklist,{on -> checklist=if(on) checklist+i else checklist-i},enabled=!busy);Text(t,modifier=Modifier.padding(top=12.dp)) } }
                Row { Checkbox(confirmed,{confirmed=it},enabled=!busy);Text("ارسال سفارش واقعی پس از تأیید نهایی",modifier=Modifier.padding(top=12.dp)) }
                Button(enabled=!busy && sizing!=null && confirmed && checklist.size==tasks.size && unresolved.isBlank(),modifier=Modifier.fillMaxWidth(),onClick={
                    busy=true;message=""
                    scope.launch {
                        try {
                            val c=loadAccount()
                            val marketPair=MarketData.pairs().first { it.symbol==symbol }
                            val s=calculate(marketPair,available!!)
                            val d=BitunixDraft(symbol,if(long)"BUY" else "SELL","LIMIT",fmt(s.quantity),entry,stop,target)
                            val v=BitunixClient.prepare(c,d)
                            require(v.mode=="HEDGE") { "برای جلوگیری از تغییر پوزیشن قبلی، این مسیر فعلاً فقط Hedge است" }
                            require(v.leverage>0 && s.notional.divide(BigDecimal(v.leverage),8,RoundingMode.UP)<v.available.toBigDecimal()) { "مارجین کافی نیست" }
                            require(s.loss<=v.available.toBigDecimal()*risk.toBigDecimal().movePointLeft(2)) { "موجودی تغییر کرده است" }
                            previewKey=c.apiKey
                            preview=v
                        } catch(e:CancellationException){throw e} catch(e:IllegalArgumentException){message=e.message?:"ورودی نامعتبر"} catch(e:BitunixApiException){message="خطای API: "+e.code} catch(_:Exception){message="آماده‌سازی سفارش ناموفق بود"}
                        finally{busy=false}
                    }
                }) { Text("بررسی و ثبت در بیت‌یونیکس") }
                if(unresolved.isNotBlank()) {
                    Text("درخواست قبلی نیاز به بررسی دارد: "+unresolved,color=MaterialTheme.colorScheme.error)
                    TextButton(enabled=!busy,onClick={busy=true;scope.launch{try{val c=loadAccount();message=BitunixClient.orderStatus(c,unresolved)}catch(e:CancellationException){throw e}catch(_:Exception){message="استعلام ناموفق؛ صرافی را بررسی کنید"}finally{busy=false}}}) {Text("استعلام سفارش")}
                    TextButton(enabled=!busy,onClick={pending.edit().remove("id").commit();unresolved="";confirmed=false}){Text("وضعیت را در صرافی بررسی کردم")}
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(message.isNotBlank()) Text(message)
            }
        }
    }
    preview?.let { v ->
        val d=v.draft
        AlertDialog(onDismissRequest={preview=null;previewKey=""},title={Text("تأیید سفارش واقعی Limit")},text={
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(d.symbol+" • "+d.side+" • "+v.leverage+"x "+v.marginMode)
                Text("حجم: "+d.qty+" | ورود: "+d.price)
                Text("SL: "+d.sl+" | TP: "+d.tp.ifBlank{"—"})
                Text("مارجین تقریبی: "+fmtShort((d.qty.toBigDecimal()*d.price.toBigDecimal()).divide(BigDecimal(v.leverage),8,RoundingMode.UP))+" USDT")
                Text("این درخواست واقعی است؛ اجرا و حد ضرر در قیمت دقیق تضمین نمی‌شود.",color=MaterialTheme.colorScheme.error)
            }
        },confirmButton={Button(enabled=!busy && confirmed && unresolved.isBlank(),onClick={
            preview=null;busy=true
            val key=previewKey;previewKey=""
            scope.launch {
                try {
                    val c=loadAccount()
                    require(c.apiKey==key)
                    val pairNow=MarketData.pairs().first { it.symbol==d.symbol }
                    val s=calculate(pairNow,available!!)
                    require(s.quantity>=d.qty.toBigDecimal() && (d.qty.toBigDecimal()*d.price.toBigDecimal()).divide(BigDecimal(v.leverage),8,RoundingMode.UP)<available!!)
                    require(withContext(Dispatchers.IO){pending.edit().putString("id",v.clientId).commit()})
                    unresolved=v.clientId
                    val id=BitunixClient.place(c,v)
                    require(withContext(Dispatchers.IO){pending.edit().remove("id").commit()})
                    unresolved="";confirmed=false;checklist=emptySet()
                    message="درخواست سفارش پذیرفته شد: "+id+"؛ اجرای آن هنوز تأیید نشده."
                }catch(e:CancellationException){throw e}catch(_:Exception){message="ارسال ناموفق یا نامشخص؛ قبل از تلاش مجدد وضعیت را در صرافی بررسی کنید"}
                finally{busy=false}
            }
        }){Text("ارسال سفارش")}},dismissButton={TextButton(onClick={preview=null;previewKey=""}){Text("انصراف")}})
    }
}
@Composable
private fun CandleChart(candles:List<Candle>,entry:Double?,stop:Double?,target:Double?,onPick:(Double)->Unit) {
    val shown=candles.takeLast(60)
    val hi=shown.maxOf{it.high}
    val lo=shown.minOf{it.low}
    val span=(hi-lo).coerceAtLeast(hi*0.0001)
    val green=MaterialTheme.colorScheme.tertiary;val red=MaterialTheme.colorScheme.error;val blue=MaterialTheme.colorScheme.primary;val grid=MaterialTheme.colorScheme.outline
    val callback by rememberUpdatedState(onPick)
    Text("High: "+hi+" | Low: "+lo,style=MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(230.dp).pointerInput(hi,lo){detectTapGestures{p->callback(hi-(p.y/size.height).coerceIn(0f,1f)*span)}}) {
        fun y(v:Double)=((hi-v)/span*size.height).toFloat()
        val w=size.width/shown.size
        for(i in 0..4){val py=size.height*i/4;drawLine(grid,Offset(0f,py),Offset(size.width,py),1f)}
        shown.forEachIndexed{i,c->val x=(i+0.5f)*w;val color=if(c.close>=c.open)green else red;drawLine(color,Offset(x,y(c.high)),Offset(x,y(c.low)),1.5f);drawRect(color,Offset(x-w*0.31f,minOf(y(c.open),y(c.close))),Size(w*0.62f,kotlin.math.abs(y(c.open)-y(c.close)).coerceAtLeast(2f)))}
        listOf(entry to blue,stop to red,target to green).forEach{(v,c)->if(v!=null && v in lo..hi)drawLine(c,Offset(0f,y(v)),Offset(size.width,y(v)),2f)}
    }
    val maxVol=shown.maxOf{it.volume}.coerceAtLeast(1e-12)
    Canvas(Modifier.fillMaxWidth().height(35.dp)){val w=size.width/shown.size;shown.forEachIndexed{i,c->val h=(c.volume/maxVol*size.height).toFloat().coerceAtLeast(0f);drawRect(if(c.close>=c.open)green else red,Offset(i*w,size.height-h),Size(w*0.7f,h))}}
}

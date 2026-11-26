package com.example.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.MarketData
import com.example.data.MarketPair
import com.example.data.Quote
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Locale
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
private data class DemoTrade(val symbol:String,val long:Boolean,val entry:Double,val stop:Double,val target:Double,val qty:Double,val fee:Double,val at:Long,val exit:Double?=null,val pnl:Double?=null,val reason:String="")
@Serializable
private data class DemoState(val cash:Double=1000.0,val active:DemoTrade?=null,val history:List<DemoTrade> = emptyList())
private fun demoPrefs(c:Context)=c.getSharedPreferences("demo_simulator_v1",0)
private fun loadDemo(c:Context):DemoState?=try{val raw=demoPrefs(c).getString("state",null);if(raw==null)DemoState() else Json.decodeFromString<DemoState>(raw)}catch(_:Exception){null}
private fun saveDemo(c:Context,state:DemoState)=demoPrefs(c).edit().putString("state",Json.encodeToString(state)).commit()
private fun profit(t:DemoTrade,price:Double)=t.qty*(if(t.long)price-t.entry else t.entry-price)-t.qty*(price+t.entry)*t.fee

@Composable
fun DemoTradingScreen(){
    val context=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    var state by remember{mutableStateOf(loadDemo(context))}
    var symbol by remember{mutableStateOf("BTCUSDT")}
    var long by remember{mutableStateOf(true)}
    var stop by remember{mutableStateOf("")}
    var target by remember{mutableStateOf("")}
    var risk by remember{mutableStateOf("1")}
    var pairs by remember{mutableStateOf<List<MarketPair>>(emptyList())}
    var quote by remember{mutableStateOf<Quote?>(null)}
    var status by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var confirm by remember{mutableStateOf(false)}
    var reset by remember{mutableStateOf(false)}
    var chart by remember{mutableStateOf(false)}
    val account=state
    val active=account?.active
    val monitored=active?.symbol?:symbol
    fun commit(s:DemoState){if(saveDemo(context,s))state=s else status="ذخیره محلی ناموفق بود"}
    fun closeAt(t:DemoTrade,px:Double,reason:String){
        val current=state?:return;if(current.active!=t)return
        val pnl=profit(t,px);val cash=current.cash+pnl
        if(pnl.isFinite()&&cash.isFinite())commit(current.copy(cash=cash,active=null,history=(listOf(t.copy(exit=px,pnl=pnl,reason=reason))+current.history).take(1000)))
    }
    LaunchedEffect(Unit){try{pairs=MarketData.pairs()}catch(e:CancellationException){throw e}catch(_:Exception){status="فهرست ارزها دریافت نشد"}}
    LaunchedEffect(monitored){
        quote=null
        while(isActive){
            try{
                if(MarketData.validSymbol(monitored)){
                    val q=MarketData.quotes(listOf(monitored))[monitored]?:error("No price")
                    quote=q
                    state?.active?.takeIf{it.symbol==monitored}?.let{t->
                        val p=q.mark.toDouble()
                        if((t.long&&p<=t.stop)||(!t.long&&p>=t.stop))closeAt(t,q.last.toDouble(),"SL")
                        else if((t.long&&p>=t.target)||(!t.long&&p<=t.target))closeAt(t,q.last.toDouble(),"TP")
                    }
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){quote=null;status="قیمت به‌روز در دسترس نیست"}
            delay(5000)
        }
    }
    val pair=pairs.firstOrNull{it.symbol==symbol}
    fun sizing(p:MarketPair,q:Quote,a:DemoState):DemoTrade{
        val r=risk.toBigDecimal();require(r>BigDecimal.ZERO&&r<=BigDecimal("5"))
        val en=q.last.setScale(p.pricePrecision,RoundingMode.HALF_UP)
        val sl=stop.toBigDecimal();val tp=target.toBigDecimal()
        require(if(long)sl<q.mark&&tp>q.mark else sl>q.mark&&tp<q.mark)
        val s=MarketData.size(p.copy(maxQty=p.maxMarketQty),BigDecimal.valueOf(a.cash),r,en,sl,tp,long,BigDecimal("0.06"))
        require(s.quantity<=p.maxMarketQty&&s.notional+s.notional*BigDecimal("0.0012")<=BigDecimal.valueOf(a.cash)){"در این دمو بدون اهرم، سرمایه کافی نیست"}
        return DemoTrade(symbol,long,en.toDouble(),sl.toDouble(),tp.toDouble(),s.quantity.toDouble(),0.0006,System.currentTimeMillis())
    }
    val draft=if(account!=null&&account.active==null&&pair!=null&&quote!=null)runCatching{sizing(pair,quote!!,account)}.getOrNull()else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("DEMO • حساب تمرینی",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)){
            Column(Modifier.fillMaxWidth().padding(16.dp)){
                Text("سرمایه مجازی • بدون اتصال خصوصی")
                Text(account?.let{money(it.cash)+" USDT"}?:"—",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold)
                Text("قیمت عمومی بیت‌یونیکس؛ هیچ سفارش واقعی ارسال نمی‌شود")
            }
        }
        if(account==null){Text("خواندن حساب تمرینی ناموفق بود؛ برای جلوگیری از حذف داده، معامله غیرفعال است");return@Column}
        Button(onClick={chart=true},enabled=MarketData.validSymbol(monitored),modifier=Modifier.fillMaxWidth()){Text("چارت تمرینی")}
        if(active==null){
            OutlinedTextField(symbol,{symbol=it.uppercase(Locale.US).replace("/","").trim().take(24)},label={Text("نماد")},singleLine=true,enabled=!busy)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(long,{long=true},{Text("LONG")},enabled=!busy);FilterChip(!long,{long=false},{Text("SHORT")},enabled=!busy)}
            Text("قیمت فعلی: "+(quote?.last?.stripTrailingZeros()?.toPlainString()?:"—"))
            OutlinedTextField(stop,{stop=it},label={Text("حد ضرر")},singleLine=true,enabled=!busy)
            OutlinedTextField(target,{target=it},label={Text("حد سود")},singleLine=true,enabled=!busy)
            OutlinedTextField(risk,{risk=it},label={Text("ریسک٪ (حداکثر ۵)")},singleLine=true,enabled=!busy)
            draft?.let{Text("حجم: "+BigDecimal.valueOf(it.qty).stripTrailingZeros().toPlainString()+" • زیان تخمینی: "+money(-profit(it,it.stop))+" USDT")}
            Button(enabled=draft!=null&&!busy,onClick={confirm=true},modifier=Modifier.fillMaxWidth()){Text("تأیید موقعیت تمرینی")}
        }else{
            Card{
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("پوزیشن تمرینی • "+active.symbol+" • "+(if(active.long)"LONG" else "SHORT"),fontWeight=FontWeight.Bold)
                    Text("ورود: "+active.entry+" | SL: "+active.stop+" | TP: "+active.target)
                    Text("PnL تخمینی: "+(quote?.last?.toDouble()?.let{money(profit(active,it))}?:"—")+" USDT")
                    Button(enabled=!busy,onClick={busy=true;scope.launch{try{val q=MarketData.quotes(listOf(active.symbol))[active.symbol]?:error("No quote");closeAt(active,q.last.toDouble(),"MANUAL")}catch(e:CancellationException){throw e}catch(_:Exception){status="خروج تمرینی ناموفق؛ قیمت دریافت نشد"}finally{busy=false}}}){Text("بستن تمرینی")}
                }
            }
        }
        Text("ژورنال مستقل دمو",style=MaterialTheme.typography.titleLarge)
        Text("معاملات بسته: "+account.history.size+" | سود/زیان: "+money(account.history.sumOf{it.pnl?:0.0})+" USDT")
        account.history.take(20).forEach{t->Text(t.symbol+" • "+t.reason+" • "+money(t.pnl?:0.0)+" USDT")}
        Text("شبیه‌ساز ساده Market بدون اهرم، با کارمزد فرضی ۰٫۰۶٪ هر سمت؛ حدها فقط هنگام باز بودن این صفحه هر ۵ ثانیه بررسی می‌شوند. لغزش، فاندینگ و عمق بازار شبیه‌سازی نمی‌شوند.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={reset=true},enabled=!busy){Text("بازنشانی حساب دمو",color=MaterialTheme.colorScheme.error)}
        if(status.isNotBlank())Text(status)
    }
    if(chart)AdvancedChartDialog(monitored,"15m",pairs.firstOrNull{it.symbol==monitored}?.pricePrecision?:8,"",stop,target,onDismiss={chart=false},onSelect={kind,price->if(state?.active==null){if(kind=="sl")stop=price;if(kind=="tp")target=price}})
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("تأیید معامله مجازی")},text={Text("با سرمایه تمرینی، قیمت بازار دوباره دریافت و حجم محاسبه می‌شود.")},confirmButton={Button(onClick={confirm=false;busy=true;scope.launch{try{val a=state?:error("No demo");require(a.active==null);val p=pair?:error("No pair");val q=MarketData.quotes(listOf(symbol))[symbol]?:error("No price");val t=sizing(p,q,a);commit(a.copy(active=t));status="پوزیشن تمرینی ثبت شد"}catch(e:CancellationException){throw e}catch(e:Exception){status=e.message?:"محاسبه تمرینی ناموفق"}finally{busy=false}}}){Text("باز کردن دمو")}},dismissButton={TextButton(onClick={confirm=false}){Text("لغو")}})
    if(reset)AlertDialog(onDismissRequest={reset=false},title={Text("بازنشانی فقط دمو؟")},text={Text("سرمایه مجازی به ۱۰۰۰ USDT برمی‌گردد و ژورنال دمو پاک می‌شود. حساب واقعی دست‌نخورده است.")},confirmButton={Button(onClick={commit(DemoState());reset=false}){Text("بازنشانی دمو")}},dismissButton={TextButton(onClick={reset=false}){Text("لغو")}})
}

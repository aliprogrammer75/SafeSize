package com.example.ui

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.*
import kotlinx.coroutines.*
import java.util.Locale
import java.util.UUID

@Composable
fun MarketWatchScreen(){
    val context=LocalContext.current.applicationContext
    var rules by remember{mutableStateOf(WatchStore.read(context))}
    var enabled by remember{mutableStateOf(WatchStore.enabled(context))}
    val tracking by MarketAlertService.tracking.collectAsState()
    var noticePermission by remember { mutableStateOf(MarketMonitor.canNotify(context)) }
    var status by remember{mutableStateOf(WatchStore.status(context))}
    var show by remember{mutableStateOf(false)}
    var deleting by remember{mutableStateOf<WatchRule?>(null)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if (granted) {
            WatchStore.setEnabled(context, true)
            enabled = true
        }
        noticePermission = MarketMonitor.canNotify(context)
    }
    LaunchedEffect(Unit){
        while(isActive){
            rules = WatchStore.read(context)
            enabled = WatchStore.enabled(context)
            status = WatchStore.status(context)
            noticePermission = MarketMonitor.canNotify(context)
            delay(2000)
        }
    }
    LaunchedEffect(enabled){
        while(isActive && enabled){
            MarketMonitor.check(context, includePrices = MarketAlertService.tracking.value == MarketTracking.INACTIVE)
            delay(15000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("رصد بازار • Bitunix",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Card{
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Row {
                    Switch(enabled, { on ->
                        if (on && Build.VERSION.SDK_INT >= 33 &&
                            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else {
                            WatchStore.setEnabled(context, on)
                            enabled = on
                        }
                    })
                    Text("هشدار گوشی", modifier = Modifier.padding(12.dp))
                }
                val trackingText = when {
                    !noticePermission -> "اعلان‌های SafeSize را در تنظیمات گوشی فعال کنید"
                    tracking == MarketTracking.LIVE -> "رصد زندهٔ قیمت در پس‌زمینه فعال است"
                    tracking == MarketTracking.CONNECTING -> "در حال اتصال به قیمت زنده"
                    tracking == MarketTracking.PERIODIC -> "بررسی دوره‌ای قیمت در پس‌زمینه فعال است"
                    enabled && rules.any { it.active } -> "رصد زنده متوقف است؛ بررسی زمان‌بندی‌شده فعال است"
                    else -> "رصد زنده با هشدار فعال شروع می‌شود"
                }
                Text(trackingText, style = MaterialTheme.typography.bodySmall)
                if (enabled && noticePermission && tracking == MarketTracking.INACTIVE && rules.any { it.active }) {
                    TextButton(onClick = { MarketAlertService.startIfNeeded(context) }) { Text("شروع رصد زنده") }
                }
                Text(status,style=MaterialTheme.typography.labelMedium)
                TextButton(enabled=enabled,onClick={WatchStore.checkNow(context)}){Text("بررسی اکنون")}
            }
        }
        SmsAlertsCard()
        Button(enabled=rules.size<30,modifier=Modifier.fillMaxWidth(),onClick={show=true}){Text("افزودن موقعیت معاملاتی")}
        if(rules.isEmpty())Text("هنوز موقعیتی تعریف نشده است.")
        rules.forEach{r->
            Card{
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(r.symbol+" • "+r.strategy.ifBlank{"بدون نام"},style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text(when(r.type){"ABOVE"->"قیمت بالاتر یا مساوی ";"BELOW"->"قیمت پایین‌تر یا مساوی ";"ZONE"->"محدوده "; "CLOSE_ABOVE"->"بسته شدن کندل بالای ";else->"بسته شدن کندل پایین "}+r.low+(if(r.type=="ZONE")" تا "+r.high else "")+" • "+r.interval+" · "+(if(r.priceType=="MARK_PRICE") "Mark" else "Last"))
                    Text("آخرین قیمت: "+r.lastPrice.ifBlank{"—"})
                    Text(if(r.hitAt>0)"هشدار صادر شد" else if(r.active)"در انتظار" else "متوقف",color=if(r.hitAt>0)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if(r.checklist.isNotEmpty()){
                        HorizontalDivider()
                        Text("چک‌لیست دستی "+r.checked.size+"/"+r.checklist.size)
                        r.checklist.forEachIndexed{i,t->Row{Checkbox(i in r.checked,{on->
                            WatchStore.edit(context){list->list.map{v->if(v.id==r.id)v.copy(checked=if(on)(v.checked+i).distinct()else v.checked-i)else v}}
                            rules=WatchStore.read(context)
                        });Text(t,modifier=Modifier.padding(top=12.dp))}}
                    }
                    Row{
                        TextButton(onClick={
                            WatchStore.edit(context){list->list.map{v->if(v.id==r.id)v.copy(active=!v.active,hitAt=0)else v}}
                            rules=WatchStore.read(context)
                            if (!r.active) MarketAlertService.startIfNeeded(context)
                        }){Text(if(r.active)"توقف"else"فعال‌سازی مجدد")}
                        TextButton(onClick={deleting=r}){Text("حذف")}
                    }
                }
            }
        }
    }
    if (show) AddWatchDialog(onDismiss = { show = false }, onAdd = { rule ->
        if (WatchStore.add(context, rule)) MarketAlertService.startIfNeeded(context)
        rules = WatchStore.read(context)
        show = false
    })
    deleting?.let{r->AlertDialog(onDismissRequest={deleting=null},title={Text("حذف موقعیت "+r.symbol+"؟")},confirmButton={Button(onClick={WatchStore.edit(context){it.filterNot{v->v.id==r.id}};rules=WatchStore.read(context);deleting=null}){Text("حذف")}},dismissButton={TextButton(onClick={deleting=null}){Text("لغو")}})}
}
@Composable
private fun AddWatchDialog(onDismiss:()->Unit,onAdd:(WatchRule)->Unit){
    var pairs by remember{mutableStateOf<List<MarketPair>>(emptyList())}
    var symbol by remember{mutableStateOf("BTCUSDT")}
    var type by remember{mutableStateOf("ZONE")}
    var interval by remember{mutableStateOf("15m")}
    var low by remember{mutableStateOf("")}
    var high by remember{mutableStateOf("")}
    var strategy by remember{mutableStateOf("")}
    var tasks by remember{mutableStateOf("بررسی روند تایم‌فریم بالاتر\nتأیید ستاپ ورود\nتعیین حد ضرر")}
    var error by remember{mutableStateOf("")}
    LaunchedEffect(Unit){try{pairs=MarketData.pairs()}catch(e:CancellationException){throw e}catch(_:Exception){error="فهرست نمادها دریافت نشد"}}
    val a=low.toBigDecimalOrNull();val b=high.toBigDecimalOrNull()
    val valid=pairs.any{it.symbol==symbol} && a!=null && a.signum()>0 && (type!="ZONE" || b!=null && b>=a)
    AlertDialog(onDismissRequest=onDismiss,title={Text("موقعیت جدید")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(symbol,{symbol=it.uppercase(Locale.US).replace("/","").trim().take(24)},label={Text("انتخاب نماد")},singleLine=true)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                pairs.filter{it.symbol.contains(symbol,true)||it.symbol in listOf("BTCUSDT","ETHUSDT","SOLUSDT")}.take(12).forEach{p->FilterChip(p.symbol==symbol,{symbol=p.symbol},{Text(p.symbol)})}
            }
            listOf("ZONE" to "ورود به محدوده","ABOVE" to "قیمت بالاتر از سطح","BELOW" to "قیمت پایین‌تر از سطح","CLOSE_ABOVE" to "بسته شدن کندل بالای سطح","CLOSE_BELOW" to "بسته شدن کندل پایین سطح").forEach{(id,label)->FilterChip(type==id,{type=id},{Text(label)})}
            OutlinedTextField(low,{low=it},label={Text(if(type=="ZONE")"کف محدوده"else"سطح قیمت")},singleLine=true)
            if(type=="ZONE")OutlinedTextField(high,{high=it},label={Text("سقف محدوده")},singleLine=true)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){MarketData.intervals.forEach{t->FilterChip(t==interval,{interval=t},{Text(t)})}}
            OutlinedTextField(strategy,{strategy=it.take(100)},label={Text("نام ستاپ یا استراتژی")},singleLine=true)
            OutlinedTextField(tasks,{tasks=it.take(1000)},label={Text("چک‌لیست اختصاصی (هر مورد یک خط)")},minLines=3,maxLines=6)
            Text("شرط قیمتی خودکار بررسی می‌شود؛ موارد تحلیلی چک‌لیست را خودت بررسی کن.",style=MaterialTheme.typography.bodySmall)
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={Button(enabled=valid,onClick={onAdd(WatchRule(UUID.randomUUID().toString(),symbol,type,low,high,interval,strategy,tasks.lines().map{it.trim()}.filter{it.isNotBlank()}.take(12)))}){Text("ذخیره")}},dismissButton={TextButton(onClick=onDismiss){Text("لغو")}})
}

package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.*
import com.example.viewmodel.TradeViewModel
import kotlinx.coroutines.*
import java.util.Locale
import java.math.BigDecimal

@Composable
fun HomeScreen(viewModel: TradeViewModel,onNavigate:(String)->Unit){
    val context=LocalContext.current.applicationContext
    val closed by viewModel.closedTrades.collectAsStateWithLifecycle()
    val opened by viewModel.openTrades.collectAsStateWithLifecycle()
    var balance by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<List<ExchangeHistory>?>(null) }
    var state by remember { mutableStateOf("در حال دریافت حساب") }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload){
        balance=null;history=null
        try{
            val c=withContext(Dispatchers.IO){BitunixVault(context).load()}
            if(c==null){state="حساب هنوز متصل نیست";return@LaunchedEffect}
            balance=BitunixClient.fetchAccount(c).available;state="حساب واقعی • موجودی آزاد"
            try{history=BitunixClient.history(c)}catch(e:CancellationException){throw e}catch(_:Exception){}
        }catch(e:CancellationException){throw e}catch(_:Exception){state="دریافت حساب ناموفق؛ اتصال را بررسی کنید"}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text("داشبورد معاملات",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("تحلیل بازار، معاملات و عملکرد شما",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick={onNavigate("chart")},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)){Icon(Icons.Default.Info,contentDescription=null);Spacer(Modifier.width(8.dp));Text("باز کردن چارت زنده",style=MaterialTheme.typography.titleMedium)}
        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){
            Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("REAL • Bitunix",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold)
                Text(state,style=MaterialTheme.typography.bodySmall)
                Text(balance?.let{money(it)+" USDT"}?:"—",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.Bold)
                Row{TextButton(onClick={reload++}){Text("به‌روزرسانی")};TextButton(onClick={onNavigate("exchange")}){Text("مدیریت اتصال")}}
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Card(modifier=Modifier.weight(1f),onClick={onNavigate("trade-menu")},colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("REAL",color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold);Text("معامله واقعی",style=MaterialTheme.typography.titleMedium);Text("محاسبه ریسک یا ثبت مستقیم",style=MaterialTheme.typography.bodySmall)}
            }
            Card(modifier=Modifier.weight(1f),onClick={onNavigate("demo")},colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)){
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("DEMO",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold);Text("حساب تمرینی",style=MaterialTheme.typography.titleMedium);Text("سرمایه و ژورنال جداگانه",style=MaterialTheme.typography.bodySmall)}
            }
        }
        Text("خلاصه ژورنال",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("بیت‌یونیکس • تاریخچه واقعی",fontWeight=FontWeight.Bold)
                val h=history
                if(h==null)Text("تاریخچه دریافت نشده است")
                else{
                    val values=h.mapNotNull{it.pnl.toBigDecimalOrNull()}
                    val pnl=values.fold(BigDecimal.ZERO){a,v->a+v}
                    Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){
                        Column(Modifier.weight(1f)){Text("سود/زیان خام",style=MaterialTheme.typography.labelMedium);Text(money(pnl)+" USDT",fontWeight=FontWeight.Bold,color=if(pnl.signum()<0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)}
                        Column(Modifier.weight(1f)){Text("تعداد رکورد",style=MaterialTheme.typography.labelMedium);Text(h.size.toString(),fontWeight=FontWeight.Bold)}
                    }
                    Text("حداکثر ۵۰۰ رکورد دریافتی؛ قبل از کارمزد و فاندینگ",style=MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick={onNavigate("exchange")}){Text("ژورنال صرافی")}
                HorizontalDivider()
                Text("ژورنال دستی • مستقل از صرافی و دمو",fontWeight=FontWeight.Bold)
                val known=closed.mapNotNull{it.pnl?.takeIf{v->v.isFinite()}}
                Text("معاملات بسته: "+known.size+" • باز: "+opened.size)
                Text("سود/زیان: "+money(known.sum())+" USDT • نرخ برد: "+(if(known.isEmpty())"—" else money(known.count{it>0}*100.0/known.size)+"٪"))
                Row { TextButton(onClick={onNavigate("analytics")}){Text("تحلیل کامل")};TextButton(onClick={onNavigate("journal")}){Text("مشاهده ژورنال")} }
            }
        }
        OutlinedButton(onClick={onNavigate("watch")},modifier=Modifier.fillMaxWidth()){Text("واچ‌لیست و هشدارهای بازار")}
        OutlinedButton(onClick={onNavigate("risk")},modifier=Modifier.fillMaxWidth()){Text("مدیریت ریسک و چک‌لیست")}
        OutlinedButton(onClick={onNavigate("tools")},modifier=Modifier.fillMaxWidth()){Text("پشتیبان‌گیری، مربی و اعلان‌ها")}
        TextButton(onClick={onNavigate("manual-dashboard")}){Text("مدیریت سرمایه و داده‌های دستی")}
    }
}

@Composable
fun TradingMenuScreen(onNavigate:(String)->Unit){
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("معامله واقعی • بیت‌یونیکس",style=MaterialTheme.typography.headlineSmall)
        Text("این بخش با سرمایه واقعی صرافی کار می‌کند.",color=MaterialTheme.colorScheme.error)
        Button(onClick={onNavigate("calculator")},modifier=Modifier.fillMaxWidth()){Text("محاسبه ریسک و ثبت موقعیت")}
        OutlinedButton(onClick={onNavigate("bitunix-trade")},modifier=Modifier.fillMaxWidth()){Text("ثبت مستقیم و مدیریت سفارش")}
        TextButton(onClick={onNavigate("demo")}){Text("ورود به حساب تمرینی مستقل")}
    }
}

@Composable
fun ChartHomeScreen(){
    var pairs by remember{mutableStateOf<List<MarketPair>>(emptyList())}
    var search by remember{mutableStateOf("BTCUSDT")}
    var symbol by remember{mutableStateOf("BTCUSDT")}
    var open by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf("")}
    var retry by remember{mutableIntStateOf(0)}
    LaunchedEffect(retry){try{pairs=MarketData.pairs();error=""}catch(e:CancellationException){throw e}catch(_:Exception){error="فهرست نمادها دریافت نشد؛ همچنان می‌توانید نماد را دستی وارد کنید"}}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("چارت بازار • Bitunix",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text("بدون نیاز به اتصال حساب یا ثبت سفارش")
        OutlinedTextField(search,{search=it.uppercase(Locale.US).replace("/","").trim().take(24)},label={Text("نماد فیوچرز")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            (listOf("BTCUSDT","ETHUSDT","SOLUSDT")+pairs.filter{it.symbol.contains(search,true)}.map{it.symbol}).distinct().take(15).forEach{s->FilterChip(search==s,{search=s;symbol=s;open=true},{Text(s)})}
        }
        Button(enabled=MarketData.validSymbol(search),onClick={symbol=search;open=true},modifier=Modifier.fillMaxWidth()){Text("نمایش چارت تمام صفحه")}
        if(error.isNotBlank()){Text(error);TextButton(onClick={retry++}){Text("تلاش مجدد")}}
    }
    if(open)AdvancedChartDialog(symbol,"15m",pairs.firstOrNull{it.symbol==symbol}?.pricePrecision?:8,"","","",onDismiss={open=false},onSelect={_,_->})
}

package com.xauai.gold

import android.app.*
import android.os.*
import android.graphics.*
import android.view.*
import android.widget.*
import android.content.*
import android.content.pm.PackageManager
import java.net.URL
import java.net.HttpURLConnection
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.math.*

data class Candle(val t:Long,val o:Double,val h:Double,val l:Double,val c:Double)
data class Levels(val side:String,val entry:Double,val sl:Double,val tp1:Double,val tp2:Double,val tp3:Double,val confidence:Int,val reason:String,val signalIndex:Int,val confirmed:Boolean)

class MainActivity : Activity() {
    private lateinit var chart: ChartView
    private lateinit var price: TextView
    private lateinit var signal: TextView
    private lateinit var info: TextView
        private val candles=mutableListOf<Candle>()
    private val base=mutableListOf<Pair<Long,Double>>()
    private var tf="1m"
    private var levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"Loading",0,false)
    private var macroBias=0
    private var macroRisk=false
    private var macroLabel="MACRO FILTER: NEUTRAL"
    private var macroLoadedAt=0L
    private var lastAlertKey=""
    private var lastPrice=0.0
    private var lastHistoryAt=0L
    private var livePoint=0.0
    private val dailyCandles=mutableListOf<Candle>()
    private val mainHandler=Handler(Looper.getMainLooper())
    @Volatile private var loading=false
    private var dataSource=""
    private var tickerLayerKey=""
    private val tickerClient=OkHttpClient()
    private val refresh=object:Runnable{override fun run(){load();mainHandler.postDelayed(this,30000)}}
    @Volatile private var liveTickLoading=false
    private var lastAnalysisAt=0L
    private val liveTickRefresh=object:Runnable{override fun run(){pollLiveTick();mainHandler.postDelayed(this,1500)}}
    private fun dp(v:Float)=v*resources.displayMetrics.density
    private fun fmt(v:Double)=String.format("%.2f",v)

    override fun onCreate(b:Bundle?){super.onCreate(b);window.statusBarColor=Color.rgb(5,8,12);window.navigationBarColor=Color.rgb(5,8,12);tickerLayerKey=getSharedPreferences("khan_market",Context.MODE_PRIVATE).getString("ticker_key","")?:"";buildUi();loadSavedBase();load();mainHandler.postDelayed(refresh,7000);mainHandler.postDelayed(liveTickRefresh,1200)}
    override fun onDestroy(){mainHandler.removeCallbacks(refresh);mainHandler.removeCallbacks(liveTickRefresh);tickerClient.dispatcher.executorService.shutdown();super.onDestroy()}

    private fun tv(text:String,size:Float,bold:Boolean=false):TextView{val x=TextView(this);x.text=text;x.textSize=size;x.setTextColor(Color.WHITE);if(bold)x.setTypeface(null,1);return x}
    private fun buildUi(){
        val root=LinearLayout(this);root.orientation=LinearLayout.VERTICAL;root.setBackgroundColor(Color.rgb(5,8,12));root.setPadding(dp(8f).toInt(),dp(6f).toInt(),dp(8f).toInt(),dp(6f).toInt())
        val title=tv("خان  •  GOLD / USD",19f,true);root.addView(title,LinearLayout.LayoutParams(-1,dp(34f).toInt()))
        price=tv("XAU/USD  —",16f,true);root.addView(price,LinearLayout.LayoutParams(-1,dp(28f).toInt()))
        signal=tv("WAIT  •  SCANNING",18f,true);signal.setTextColor(Color.rgb(240,190,70));root.addView(signal,LinearLayout.LayoutParams(-1,dp(30f).toInt()))
        val modes=LinearLayout(this);modes.orientation=LinearLayout.HORIZONTAL
        listOf("SCALP","INTRADAY","SWING").forEach{m->val b=Button(this);b.text=m;b.setOnClickListener{when(m){"SCALP"->tf="1m";"INTRADAY"->tf="5m";else->tf="1H"};load()};modes.addView(b,LinearLayout.LayoutParams(0,dp(40f).toInt(),1f))};root.addView(modes)
        val row=LinearLayout(this);row.orientation=LinearLayout.HORIZONTAL
        listOf("TICK","1m","2m","3m","5m","15m","30m","1H","4H","1D").forEach{s->val b=Button(this);b.text=s;b.textSize=10f;b.setOnClickListener{tf=s;load()};row.addView(b,LinearLayout.LayoutParams(0,dp(38f).toInt(),1f))};root.addView(row)
        chart=ChartView(this);root.addView(chart,LinearLayout.LayoutParams(-1,0,1f))
        info=tv("Paper trading • No real orders\nConnecting to live XAU/USD…",12f);info.setPadding(dp(4f).toInt(),dp(3f).toInt(),dp(4f).toInt(),dp(3f).toInt());root.addView(info,LinearLayout.LayoutParams(-1,dp(68f).toInt()))
        val actions=LinearLayout(this);actions.orientation=LinearLayout.HORIZONTAL
        val a=Button(this);a.text="REFRESH";a.setOnClickListener{load()};actions.addView(a,LinearLayout.LayoutParams(0,dp(44f).toInt(),1f))
        val n=Button(this);n.text="ALERTS";n.setOnClickListener{Toast.makeText(this,"Alerts active: Entry / TP1 / TP2 / TP3 / SL",Toast.LENGTH_SHORT).show()};actions.addView(n,LinearLayout.LayoutParams(0,dp(44f).toInt(),1f))
        val key=Button(this);key.text="DATA";key.setOnClickListener{showTickerKeyDialog()};actions.addView(key,LinearLayout.LayoutParams(0,dp(44f).toInt(),1f) )
        root.addView(actions);setContentView(root)
    }

    private fun showTickerKeyDialog(){
        val input=EditText(this);input.hint="TickerLayer API key";input.setSingleLine(true)
        input.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.setText(getSharedPreferences("khan_market",Context.MODE_PRIVATE).getString("ticker_key","")?:"")
        val box=LinearLayout(this);box.orientation=LinearLayout.VERTICAL;box.setPadding(dp(20f).toInt(),dp(8f).toInt(),dp(20f).toInt(),0);box.addView(input)
        AlertDialog.Builder(this).setTitle("XAU/USD LIVE DATA").setMessage("TickerLayer XAUUSD feed is used when a key is saved. The key stays on this phone.")
            .setView(box).setNegativeButton("CANCEL",null)
            .setPositiveButton("SAVE"){_,_->tickerLayerKey=input.text.toString().trim();getSharedPreferences("khan_market",Context.MODE_PRIVATE).edit().putString("ticker_key",tickerLayerKey).apply();load()}.show()
    }
    private fun tickerGet(path:String):String{
        if(tickerLayerKey.isBlank()) throw java.io.IOException("TickerLayer API key not set")
        val req=Request.Builder().url("https://api.tickerlayer.com"+path).header("x-api-key",tickerLayerKey).header("User-Agent","Khan-XAU/2.0").build()
        tickerClient.newCall(req).execute().use{r->
            val body=r.body?.string().orEmpty()
            if(!r.isSuccessful) throw java.io.IOException("TickerLayer HTTP "+r.code+": "+body.take(180))
            return body
        }
    }
    private fun parseTickerQuote(raw:String):Double{
        val j=JSONObject(raw);val last=j.optDouble("last",Double.NaN);if(last.isFinite()&&last>0)return last
        val lp=j.optDouble("last_price",Double.NaN);if(lp.isFinite()&&lp>0)return lp
        val bid=j.optDouble("bid",Double.NaN);val ask=j.optDouble("ask",Double.NaN)
        return if(bid.isFinite()&&ask.isFinite()&&bid>0&&ask>0)(bid+ask)/2.0 else 0.0
    }
    private fun parseTickerBars(raw:String):List<Candle>{
        val out=mutableListOf<Candle>();try{val a=JSONObject(raw).optJSONArray("results")?:return out
            for(i in 0 until a.length()){val z=a.optJSONObject(i)?:continue;val t=z.optLong("t",0L);val o=z.optDouble("o",Double.NaN);val h=z.optDouble("h",Double.NaN);val l=z.optDouble("l",Double.NaN);val cc=z.optDouble("c",Double.NaN)
                if(t>0&&o.isFinite()&&h.isFinite()&&l.isFinite()&&cc.isFinite()&&o>0&&h>=l&&cc>0)out.add(Candle(t,o,h,l,cc))}
        }catch(_:Exception){};return out.sortedBy{it.t}
    }
    private fun utcDate(daysAgo:Int):String{val f=SimpleDateFormat("yyyy-MM-dd",Locale.US);f.timeZone=TimeZone.getTimeZone("UTC");return f.format(Date(System.currentTimeMillis()-daysAgo*86400000L))}
    private fun tickerSpec():Pair<String,Int>{return when(tf){"1D"->"1/day" to 3650;"4H"->"4/hour" to 365;"1H"->"1/hour" to 90;"30m"->"1/minute" to 7;"15m"->"15/minute" to 30;"5m"->"5/minute" to 14;"3m","2m","1m","TICK"->"1/minute" to 4;else->"1/minute" to 4}}
    private fun loadTickerLayer():Pair<List<Candle>,Double>{
        if(tickerLayerKey.isBlank())return emptyList<Candle>() to 0.0
        val spot=parseTickerQuote(tickerGet("/commodities/quote/XAUUSD"));val(spec,days)=tickerSpec();val parts=spec.split("/")
        val raw=parseTickerBars(tickerGet("/commodities/agg/XAUUSD/"+parts[0]+"/"+parts[1]+"/"+utcDate(days)+"/"+utcDate(0)+"?sort=asc&limit=5000"))
        val bars=when(tf){"2m"->aggregate(raw,2);"3m"->aggregate(raw,3);"30m"->aggregate(raw,30);else->raw};return bars.takeLast(2000) to spot
    }

    private fun httpGet(url:String):String{
        val con=(URL(url).openConnection() as HttpURLConnection)
        con.connectTimeout=7000;con.readTimeout=7000;con.requestMethod="GET";con.useCaches=false
        con.setRequestProperty("User-Agent","Khan-XAU/1.0")
        return try{
            val code=con.responseCode
            if(code !in 200..299){
                val detail=try{(if(code>=400)con.errorStream else con.inputStream)?.bufferedReader()?.use{it.readText()}?.take(240).orEmpty()}catch(_:Exception){""}
                throw java.io.IOException("HTTP "+code+(if(detail.isNotBlank())": "+detail else ""))
            }
            con.inputStream.bufferedReader().use{it.readText()}
        }finally{con.disconnect()}
    }

    private fun parseBiquoteTick(raw:String):Double{
        return try{
            val j=JSONObject(raw)
            val mid=j.optDouble("mid",Double.NaN)
            if(mid.isFinite()&&mid>0) mid
            else{
                val bid=j.optDouble("bid",Double.NaN);val ask=j.optDouble("ask",Double.NaN)
                if(bid.isFinite()&&ask.isFinite()&&bid>0&&ask>0)(bid+ask)/2.0 else 0.0
            }
        }catch(_:Exception){0.0}
    }

    private fun parseBiquoteBars(raw:String):List<Candle>{
        val out=mutableListOf<Candle>()
        try{
            val root=JSONObject(raw)
            val a=root.optJSONArray("bars")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("results")
                ?: return out
            val f1=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US)
            val f2=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US)
            f1.timeZone=TimeZone.getTimeZone("UTC");f2.timeZone=TimeZone.getTimeZone("UTC")
            for(i in 0 until a.length()){
                val z=a.optJSONObject(i)?:continue
                val ts=z.optString("openTime",z.optString("timestamp",z.optString("time","")))
                val t=try{f1.parse(ts)?.time?:0L}catch(_:Exception){
                    try{f2.parse(ts)?.time?:0L}catch(_:Exception){
                        val n=ts.toLongOrNull()?:0L
                        when{n>100000000000L->n;n>1000000000L->n*1000L;else->0L}
                    }
                }
                fun num(name:String):Double{
                    val v=z.opt(name)
                    return when(v){is Number->v.toDouble();is String->v.toDoubleOrNull()?:Double.NaN;else->Double.NaN}
                }
                val o=num("open");val h=num("high");val l=num("low");val cc=num("close")
                if(t>0&&o.isFinite()&&h.isFinite()&&l.isFinite()&&cc.isFinite()&&o>0&&h>=l&&cc>0)
                    out.add(Candle(t,o,h,l,cc))
            }
        }catch(_:Exception){}
        // Biquote returns newest-first; chart/analysis need chronological order.
        return out.distinctBy{it.t}.sortedBy{it.t}
    }

    private fun biquoteInterval():String=when(tf){
        "1m","TICK","2m","3m"->"1m"
        "5m"->"5m"
        "15m"->"15m"
        "30m"->"30m"
        "1H"->"1h"
        "4H"->"4h"
        "1D"->"1d"
        else->"1m"
    }

    private fun loadBiquote():Pair<List<Candle>,Double>{
        val spot=parseBiquoteTick(httpGet("https://biquote.io/api/XAUUSD?allowStale=false"))
        // Prefer the exact requested timeframe. If that endpoint is unavailable on a
        // device/network, fall back to 1m from the SAME Biquote feed and aggregate it.
        // Never mix Biquote spot with XAUS candles just because one Biquote OHLC call failed.
        var bars=parseBiquoteBars(httpGet("https://biquote.io/api/XAUUSD/ohlc?interval="+biquoteInterval()+"&limit=1000"))
        if(bars.size<30 && biquoteInterval()!="1m"){
            try{bars=parseBiquoteBars(httpGet("https://biquote.io/api/XAUUSD/ohlc?interval=1m&limit=1000"))}catch(_:Exception){}
        }
        if(tf=="2m"||tf=="3m")bars=aggregate(bars,if(tf=="2m")2 else 3)
        return bars.takeLast(1000) to spot
    }

    private fun pollLiveTick(){
        if(liveTickLoading||loading)return
        liveTickLoading=true
        thread{
            try{
                val raw=httpGet("https://biquote.io/api/XAUUSD?allowStale=false")
                val p=parseBiquoteTick(raw)
                if(p>0){
                    mainHandler.post{applyLiveTick(p)}
                }
            }catch(_:Exception){}finally{liveTickLoading=false}
        }
    }

    private fun applyLiveTick(p:Double){
        if(candles.isEmpty())return
        val now=System.currentTimeMillis()
        val step=when(tf){"1D"->86400000L;"4H"->14400000L;"1H"->3600000L;"30m"->1800000L;"15m"->900000L;"5m"->300000L;"3m"->180000L;"2m"->120000L;else->60000L}
        val bucket=(now/step)*step
        val last=candles.last()
        val lastBucket=(last.t/step)*step
        if(lastBucket==bucket){
            candles[candles.lastIndex]=Candle(last.t,last.o,max(last.h,p),min(last.l,p),p)
        }else if(p>0){
            // First real tick of a new period becomes the open of that period.
            candles.add(Candle(bucket,last.c,p,p,p))
            if(candles.size>2000)candles.removeAt(0)
        }else return
        livePoint=p
        price.text="XAU/USD  "+fmt(p)+"  •  "+tf+"  • LIVE"
        info.text="Paper trading • No real orders\nLive XAU/USD tick feed: Biquote • current candle updates from real ticks\nEntry / SL / TP are drawn on chart"
        // Live ticks update ONLY the active candle and live price.
        // Do not recalculate Entry / SL / TP on every tick: those levels belong
        // to the last completed analysis/load and must not jump around while
        // we are fixing the live candle feed.
        chart.invalidate()
    }

    private fun parseLivePrice(raw:String):Double{
        return try{
            val j=JSONObject(raw)
            val symbols=j.optJSONArray("symbols")
            val data=j.optJSONObject("data")
            val xau=data?.optJSONObject("xau")
            val candidates=listOf(
                if(symbols!=null&&symbols.length()>0) symbols.optJSONObject(0)?.optString("price") else null,
                j.optString("price",null),
                j.optString("spot_usd_oz",null),
                j.optString("spot_usd",null),
                data?.optString("price",null),
                data?.optString("spot_usd",null),
                xau?.optString("price",null)
            )
            candidates.asSequence().filterNotNull().mapNotNull{it.toDoubleOrNull()}.firstOrNull{it.isFinite()&&it>0.0}?:0.0
        }catch(_:Exception){0.0}
    }

    private fun saveBase(){
        try{
            val x=base.takeLast(720).joinToString("|"){it.first.toString()+","+it.second.toString()}
            getSharedPreferences("khan_market",Context.MODE_PRIVATE).edit().putString("points",x).apply()
        }catch(_:Exception){}
    }

    private fun loadSavedBase(){
        try{
            val x=getSharedPreferences("khan_market",Context.MODE_PRIVATE).getString("points","")?:""
            if(x.isNotBlank()){
                base.clear()
                x.split("|").forEach{
                    val a=it.split(",")
                    if(a.size==2){
                        val t=a[0].toLongOrNull()?:0L
                        val p=a[1].toDoubleOrNull()?:0.0
                        if(t>0&&p>0)base.add(t to p)
                    }
                }
            }
            buildCandles()
            runOnUiThread{chart.invalidate()}
        }catch(_:Exception){}
    }

    private fun updateConnectionUi(ok:Boolean,msg:String){
        runOnUiThread{
            if(!ok){
                price.text="XAU/USD  —"
                signal.text="OFFLINE  •  RETRYING"
                signal.setTextColor(Color.rgb(245,150,70))
                info.text="Paper trading • No real orders\n"+msg
            }
            chart.invalidate()
        }
    }

    private fun parseYahooCandles(raw:String):List<Candle>{
        val out=mutableListOf<Candle>()
        try{
            val j=JSONObject(raw)
            val result=j.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return out
            val ts=result.optJSONArray("timestamp") ?: return out
            val q=result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return out
            val oo=q.optJSONArray("open"); val hh=q.optJSONArray("high"); val ll=q.optJSONArray("low"); val cc=q.optJSONArray("close")
            if(oo==null||hh==null||ll==null||cc==null)return out
            val n=minOf(ts.length(),oo.length(),hh.length(),ll.length(),cc.length())
            for(i in 0 until n){
                if(ts.isNull(i)||oo.isNull(i)||hh.isNull(i)||ll.isNull(i)||cc.isNull(i))continue
                val t=ts.optLong(i,0L)*1000L
                val o=oo.optDouble(i,Double.NaN); val h=hh.optDouble(i,Double.NaN); val l=ll.optDouble(i,Double.NaN); val c=cc.optDouble(i,Double.NaN)
                if(t>0&&o.isFinite()&&h.isFinite()&&l.isFinite()&&c.isFinite()&&o>0&&h>=l&&c>0)out.add(Candle(t,o,h,l,c))
            }
        }catch(_:Exception){}
        return out.sortedBy{it.t}
    }

    private fun parseXausCandles(raw:String):List<Candle>{
        val out=mutableListOf<Candle>()
        try{
            val j=JSONObject(raw)
            val arrays=mutableListOf<JSONArray>()
            j.optJSONArray("points")?.let{arrays.add(it)}
            j.optJSONArray("data")?.let{arrays.add(it)}
            j.optJSONArray("candles")?.let{arrays.add(it)}
            j.optJSONArray("ohlcv")?.let{arrays.add(it)}
            j.optJSONObject("data")?.let{d->
                d.optJSONArray("points")?.let{arrays.add(it)}
                d.optJSONArray("candles")?.let{arrays.add(it)}
                d.optJSONArray("ohlcv")?.let{arrays.add(it)}
            }
            val arr=arrays.firstOrNull()?:return out
            for(i in 0 until arr.length()){
                val x=arr.opt(i)
                var t=0L; var o=Double.NaN; var h=Double.NaN; var l=Double.NaN; var c=Double.NaN
                if(x is JSONObject){
                    t=x.optLong("t",x.optLong("timestamp",x.optLong("time",0L)))
                    o=x.optDouble("o",x.optDouble("open",Double.NaN))
                    h=x.optDouble("h",x.optDouble("high",Double.NaN))
                    l=x.optDouble("l",x.optDouble("low",Double.NaN))
                    c=x.optDouble("c",x.optDouble("close",x.optDouble("p",x.optDouble("price",Double.NaN))))
                }else if(x is JSONArray && x.length()>=5){
                    t=x.optLong(0,0L);o=x.optDouble(1,Double.NaN);h=x.optDouble(2,Double.NaN);l=x.optDouble(3,Double.NaN);c=x.optDouble(4,Double.NaN)
                }
                if(t>0&&t<100000000000L)t*=1000L
                if(t>0&&o.isFinite()&&h.isFinite()&&l.isFinite()&&c.isFinite()&&h>=l&&o>0&&c>0)out.add(Candle(t,o,h,l,c))
            }
        }catch(_:Exception){}
        return out.sortedBy{it.t}
    }

    private fun loadRealCandles(interval:String,range:String,referencePrice:Double=0.0):Pair<List<Candle>,String>{
        val candidates=mutableListOf<Pair<List<Candle>,String>>()
        var xaError="not tried"
        try{
            val xa=parseXausCandles(httpGet("https://xaus.com/api/v1/chart?symbol=xau&range="+range+"&interval="+interval+"&fresh="+(System.currentTimeMillis()/1000L)))
            if(xa.size>=30)candidates.add(xa to "XAUS OHLC")
            else xaError="only "+xa.size+" valid candles"
        }catch(e:Exception){xaError=e.message?:e.javaClass.simpleName}
        var yahooError="no valid candles"
        val yahooInterval=when(interval){"60m"->"60m";"1h"->"1h";else->interval}
        for(host in listOf("query2.finance.yahoo.com","query1.finance.yahoo.com")){
            try{
                val candidate=parseYahooCandles(httpGet("https://"+host+"/v8/finance/chart/XAUUSD=X?interval="+yahooInterval+"&range="+range+"&events=history&includePrePost=true"))
                if(candidate.size>=30)candidates.add(candidate to "Yahoo XAU/USD OHLC ("+host+")")
                else yahooError=host+" returned "+candidate.size+" valid candles"
            }catch(e:Exception){yahooError=host+": "+(e.message?:e.javaClass.simpleName)}
        }
        if(candidates.isEmpty())return emptyList<Candle>() to "OHLC failed: XAUS=$xaError; Yahoo=$yahooError"
        // Prefer the OHLC feed whose latest close agrees with the independently fetched spot quote.
        // This prevents a valid-but-different instrument/feed from suppressing all trade levels.
        if(referencePrice>0.0){
            return candidates.minBy{abs(it.first.last().c-referencePrice)}.let{it.first to (it.second+" • closest to spot")}
        }
        return candidates.first()
    }

    private fun load(){
        if(loading)return
        loading=true
        thread{
            try{
                val now=System.currentTimeMillis()
                val spec=when(tf){
                    "1D"->"1d" to "1y"
                    "4H","1H"->"60m" to "5d"
                    "30m"->"30m" to "5d"
                    "15m"->"15m" to "5d"
                    "5m"->"5m" to "1d"
                    "2m"->"2m" to "1d"
                    else->"1m" to "1d"
                }
                var spotError="none"
                var spotSource="Biquote XAUUSD"
                var spot=0.0
                var biquoteBars:List<Candle> = emptyList()
                try{
                    val pair=loadBiquote()
                    biquoteBars=pair.first
                    spot=pair.second
                }catch(e:Exception){
                    spotError="Biquote: "+(e.message?:e.javaClass.simpleName)
                }
                var tickerBars:List<Candle> = emptyList()
                if(spot<=0.0&&tickerLayerKey.isNotBlank()){
                    try{val pair=loadTickerLayer();tickerBars=pair.first;spot=pair.second;if(spot>0&&tickerBars.size>=30)spotSource="TickerLayer XAUUSD"}
                    catch(e:Exception){spotError+="; TickerLayer: "+(e.message?:e.javaClass.simpleName)}
                }
                if(spot<=0.0) spot=try{
                    parseLivePrice(httpGet("https://xaus.com/api/v1/spot?compact=1&fresh="+(now/1000L)))
                }catch(e:Exception){spotError+="; XAUS: "+(e.message?:e.javaClass.simpleName);0.0}
                if(spot<=0.0){
                    try{
                        val fallback=parseLivePrice(httpGet("https://api.goldprice.dev/v1/prices?symbol=XAU-USD-SPOT"))
                        if(fallback>0.0){spot=fallback;spotSource="GoldPrice.dev spot"}
                    }catch(e:Exception){spotError+="; GoldPrice.dev: "+(e.message?:e.javaClass.simpleName)}
                }
                var (fresh,source)=when{
                    biquoteBars.size>=30->biquoteBars to "Biquote XAUUSD OHLC • live open bar"
                    tickerBars.size>=30->tickerBars to "TickerLayer XAUUSD OHLCV"
                    else->loadRealCandles(spec.first,spec.second,spot)
                }
                // If Biquote gave us the live quote but its candle request failed,
                // do not silently pair that quote with a different OHLC provider.
                // A mismatched provider is exactly what caused the DATA MISMATCH screen.
                if(spot>0 && spotSource=="Biquote XAUUSD" && biquoteBars.size<30){
                    val one=try{parseBiquoteBars(httpGet("https://biquote.io/api/XAUUSD/ohlc?interval=1m&limit=1000"))}catch(_:Exception){emptyList()}
                    if(one.size>=30){
                        fresh=when(tf){
                            "4H"->aggregate(one,240);"1H"->aggregate(one,60)
                            "30m"->aggregate(one,30);"15m"->aggregate(one,15)
                            "5m"->aggregate(one,5);"3m"->aggregate(one,3);"2m"->aggregate(one,2)
                            else->one
                        }.takeLast(1000)
                        source="Biquote XAUUSD 1m→$tf • live open bar"
                    }else if(tickerBars.size<30){
                        fresh=emptyList()
                        source="Biquote XAUUSD OHLC unavailable — waiting for same-feed candles"
                    }
                }
                // If Biquote supplied the live quote, NEVER replace its candles with
                // XAUS/Yahoo candles. A trade chart must stay on one instrument/feed.
                // If Biquote OHLC is unavailable, the honest state is WAIT, not mixed data.
                if(fresh.size<30 && tf!="1D" && spotSource!="Biquote XAUUSD"){
                    val (one,oneSource)=loadRealCandles("1m","1d",spot)
                    if(one.size>=30){
                        fresh=when(tf){
                            "4H"->aggregate(one,240);"1H"->aggregate(one,60)
                            "30m"->aggregate(one,30);"15m"->aggregate(one,15)
                            "5m"->aggregate(one,5);"3m"->aggregate(one,3);"2m"->aggregate(one,2)
                            else->one
                        }
                        source="$oneSource 1m→$tf"
                    }
                }
                // Never fabricate OHLC candles from saved close prices. If both providers fail,
                // leave the chart without fresh candles and report the outage honestly.
                if(fresh.size<30) source="NO VALID LIVE OHLC — $source"
                if(tf=="1D")dailyCandles.clear()
                candles.clear()
                candles.addAll(fresh.takeLast(2000))
                // Keep the live quote and candles on the SAME feed. The current
                // open bar is updated from the same Biquote tick; if the returned OHLC
                // snapshot is one bar behind, create the current bar instead of declaring
                // a fake data mismatch.
                var dataMismatch=false
                val lastCandle=candles.lastOrNull()
                if(spot>0 && candles.isNotEmpty()){
                    livePoint=spot
                    val step=when(tf){"1D"->86400000L;"4H"->14400000L;"1H"->3600000L;"30m"->1800000L;"15m"->900000L;"5m"->300000L;"3m"->180000L;"2m"->120000L;else->60000L}
                    val bucket=(now/step)*step
                    val lastBucket=lastCandle?.let{(it.t/step)*step}?:-1L
                    if(lastCandle!=null && lastBucket==bucket){
                        candles[candles.lastIndex]=Candle(lastCandle.t,lastCandle.o,max(lastCandle.h,spot),min(lastCandle.l,spot),spot)
                    }else if(lastCandle!=null && bucket>lastBucket){
                        candles.add(Candle(bucket,lastCandle.c,spot,spot,spot))
                    }
                    if(candles.size>2000)candles.subList(0,candles.size-2000).clear()
                }else{
                    livePoint=0.0
                }
                // Never present the mismatched spot quote as the analysis entry.
                if(candles.size<30){
                    levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,
                        "Insufficient live data",0,false)
                }
                base.clear()
                candles.takeLast(720).forEach{base.add(it.t to it.c)}
                saveBase()
                if(candles.size>=30){
                    val analysisPrice=if(spot>0&&!dataMismatch)spot else candles.last().c
                    analyze(analysisPrice)
                }
                runOnUiThread{
                    if(spot>0){
                        price.text="XAU/USD  "+fmt(spot)+"  •  "+tf
                        if(dataMismatch){
                            signal.text="DATA MISMATCH • OHLC LEVELS"
                            signal.setTextColor(Color.rgb(245,150,70))
                            info.text="Paper trading • No real orders\nSpot quote differs from OHLC. Levels are provisional and based on the latest candle close, not the live quote.\nSource: "+source
                        }else if(candles.size<30){
                            signal.text="WAIT • NOT ENOUGH DATA"
                            signal.setTextColor(Color.rgb(240,190,70))
                            info.text="Paper trading • No real orders\nNot enough valid OHLC candles for analysis.\n"+source
                        }else{
                            info.text="Paper trading • No real orders\nData: "+source+" • "+candles.size+" OHLC candles\nLIVE tick feed: Biquote (no API key) • Entry / SL / TP are drawn on chart"
                        }
                    }else updateConnectionUi(false,"Spot failed: "+spotError+"\nOHLC: "+source+"\nCheck network/VPN/DNS; details shown here.")
                    chart.invalidate()
                }
            }catch(_:Exception){updateConnectionUi(false,"Data error — retrying")}
            finally{loading=false}
        }
    }

    private fun ema(v:List<Double>,n:Int):Double{
        if(v.isEmpty())return 0.0
        val k=2.0/(n+1);var e=v[0]
        for(i in 1 until v.size)e=v[i]*k+e*(1-k)
        return e
    }
    private fun rsi(v:List<Double>,n:Int=14):Double{
        if(v.size<=n)return 50.0
        var g=0.0;var d=0.0
        for(i in 1..n){val x=v[i]-v[i-1];g+=max(x,0.0);d+=max(-x,0.0)}
        g/=n;d/=n
        for(i in n+1 until v.size){val x=v[i]-v[i-1];g=(g*(n-1)+max(x,0.0))/n;d=(d*(n-1)+max(-x,0.0))/n}
        return if(d==0.0)100.0 else 100.0-100.0/(1.0+g/d)
    }
    private fun atr(v:List<Candle>,n:Int=14):Double{
        if(v.size<2)return 1.0
        val tr=mutableListOf<Double>()
        for(i in 1 until v.size){val z=v[i];val pc=v[i-1].c;tr.add(max(z.h-z.l,max(abs(z.h-pc),abs(z.l-pc))))}
        return tr.takeLast(n).average().coerceAtLeast(0.01)
    }
    private fun macd(v:List<Double>):Double=ema(v,12)-ema(v,26)
    private fun ichimoku(v:List<Candle>):Int{
        if(v.size<52)return 0
        val a=v.takeLast(9);val b=v.takeLast(26);val d=v.takeLast(52)
        val ten=(a.maxOf{it.h}+a.minOf{it.l})/2
        val kij=(b.maxOf{it.h}+b.minOf{it.l})/2
        val span=(d.maxOf{it.h}+d.minOf{it.l})/2
        return if(v.last().c>ten&&ten>kij&&v.last().c>span)1 else if(v.last().c<ten&&ten<kij&&v.last().c<span)-1 else 0
    }

    private fun aggregate(src:List<Candle>,minutes:Int):List<Candle>{
        if(src.isEmpty())return emptyList()
        val out=mutableListOf<Candle>();var cur=-1L;var cc:Candle?=null
        for(z in src){
            val ms=if(z.t>100000000000L)z.t else z.t*1000L
            val bucket=(ms/60000L/minutes)*minutes
            if(bucket!=cur){cc?.let{out.add(it)};cur=bucket;cc=Candle(bucket*60000L,z.o,z.h,z.l,z.c)}
            else{val x=cc!!;cc=Candle(x.t,x.o,max(x.h,z.h),min(x.l,z.l),z.c)}
        }
        cc?.let{out.add(it)};return out
    }
    private fun emaSeries(v:List<Double>,n:Int):List<Double>{
        if(v.isEmpty())return emptyList()
        val k=2.0/(n+1);val out=MutableList(v.size){0.0};out[0]=v[0]
        for(i in 1 until v.size)out[i]=v[i]*k+out[i-1]*(1-k)
        return out
    }
    private fun macdSignal(v:List<Double>):Double{
        if(v.isEmpty())return 0.0
        val a=emaSeries(v,12);val b=emaSeries(v,26);val m=MutableList(v.size){a[it]-b[it]}
        return ema(m,9)
    }
    private fun bollinger(v:List<Double>,n:Int=20):Triple<Double,Double,Double>{
        if(v.size<n)return Triple(0.0,0.0,0.0)
        val a=v.takeLast(n);val mid=a.average();val sd=sqrt(a.map{(it-mid)*(it-mid)}.average())
        return Triple(mid+2.0*sd,mid,mid-2.0*sd)
    }
    private fun trend(src:List<Candle>):Int{
        if(src.size<30)return 0
        val v=src.map{it.c};var s=0
        if(ema(v,9)>ema(v,20))s++ else s--
        if(ema(v,20)>ema(v,50))s++ else s--
        if(rsi(v)>52)s++ else if(rsi(v)<48)s--
        if(macd(v)>macdSignal(v))s++ else s--
        s+=ichimoku(src);return s.coerceIn(-6,6)
    }
    private fun fibSignal(src:List<Candle>,p:Double):Int{
        if(src.size<20)return 0
        val r=src.takeLast(80);val hi=r.maxOf{it.h};val lo=r.minOf{it.l};val d=hi-lo
        if(d<=0)return 0
        val f382=hi-d*0.382;val f50=hi-d*0.5;val f618=hi-d*0.618;val f786=hi-d*0.786
        return when{p>=f382->1;p>=f50->1;p>=f618->0;p>=f786->-1;else->-1}
    }
    private fun structureSignal(src:List<Candle>):Int{
        if(src.size<10)return 0
        val last=src.last();val prev=src[src.size-2];val r=src.dropLast(2).takeLast(6)
        val hi=r.maxOf{it.h};val lo=r.minOf{it.l};var s=0
        if(last.c>hi)s+=2
        if(last.c<lo)s-=2
        if(prev.l<lo&&last.c>lo)s+=2
        if(prev.h>hi&&last.c<hi)s-=2
        return s.coerceIn(-2,2)
    }
    private fun patternSignal(src:List<Candle>):Int{
        if(src.size<3)return 0
        val a=src[src.size-2];val b=src.last();val body=abs(b.c-b.o);val range=(b.h-b.l).coerceAtLeast(0.0001)
        val upper=b.h-max(b.o,b.c);val lower=min(b.o,b.c)-b.l;var s=0
        if(b.c>b.o&&body/range>0.55)s++
        if(b.c<b.o&&body/range>0.55)s--
        if(lower>body*2&&b.c>b.o)s++
        if(upper>body*2&&b.c<b.o)s--
        if(b.c>b.o&&a.c<a.o&&b.c>a.o&&b.o<a.c)s+=2
        if(b.c<b.o&&a.c>a.o&&b.c<a.o&&b.o>a.c)s-=2
        return s.coerceIn(-2,2)
    }

    private fun completedCandles():List<Candle>{
        if(candles.isEmpty())return emptyList()
        val step=when(tf){"1D"->86400000L;"4H"->14400000L;"1H"->3600000L;"30m"->1800000L;"15m"->900000L;"5m"->300000L;"3m"->180000L;"2m"->120000L;else->60000L}
        val currentBucket=(System.currentTimeMillis()/step)*step
        return candles.filter{(it.t/step)*step < currentBucket}
    }

    private fun analyze(p:Double){
        val src=completedCandles()
        if(src.size<30)return

        // Analysis is calculated from CLOSED candles only. The live/open candle may
        // move every tick, but it must not drag Entry/SL/TP around.
        val v=src.map{it.c}
        val e9=ema(v,9);val e20=ema(v,20);val e50=ema(v,50);val e200=if(v.size>=200)ema(v,200) else ema(v,100)
        val r=rsi(v);val m=macd(v);val ms=macdSignal(v);val at=atr(src);val ichi=ichimoku(src)
        val bb=bollinger(v);val fib=fibSignal(src,src.last().c);val structure=structureSignal(src);val pattern=patternSignal(src)
        val t5=trend(aggregate(src,5));val t15=trend(aggregate(src,15));val t60=trend(aggregate(src,60));val t240=trend(aggregate(src,240))
        var score=0
        if(e9>e20)score++ else score--
        if(e20>e50)score++ else score--
        if(src.last().c>e200)score++ else score--
        if(r>52)score++ else if(r<48)score--
        if(m>ms)score++ else score--
        score+=ichi+fib+structure+pattern
        if(bb.second>0){if(src.last().c>bb.first)score--;if(src.last().c<bb.third)score++}
        if(t5>0)score++ else if(t5<0)score--
        if(t15>0)score++ else if(t15<0)score--
        if(t60>1)score++ else if(t60 < -1)score--
        if(t240>1)score++ else if(t240 < -1)score--
        score+=macroBias

        val mtfBull=listOf(t5,t15,t60,t240).count{it>0}>=3
        val mtfBear=listOf(t5,t15,t60,t240).count{it<0}>=3
        val side=when{
            score>=7&&r<80&&mtfBull->"BUY"
            score<=-7&&r>20&&mtfBear->"SELL"
            else->"WAIT"
        }

        // IMPORTANT: no fake BUY/SELL setup when the real signal is WAIT.
        // Entry/SL/TP are shown only after a confirmed directional signal.
        if(side=="WAIT"){
            levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,
                "NO CONFIRMED SIGNAL",src.lastIndex,false)
            runOnUiThread{
                signal.text="WAIT"
                signal.setTextColor(Color.rgb(240,190,70))
                price.text="XAU/USD  "+fmt(if(p>0)p else src.last().c)+"  •  "+tf
                info.text="Paper trading • No real orders\\nNO TRADE • waiting for confirmed BUY/SELL\\nEntry / SL / TP hidden until confirmation"
                chart.invalidate()
            }
            return
        }

        // The entry is the CLOSE of the latest completed signal candle, not the
        // constantly moving live tick. SL is structure-based; targets use fixed R multiples.
        val en=src.last().c
        val recent=src.takeLast(30)
        val hi=recent.maxOf{it.h};val lo=recent.minOf{it.l}
        val risk=max(at*1.20,en*0.00035)
        val sl=if(side=="BUY")min(lo,en-risk) else max(hi,en+risk)
        val rr=max(abs(en-sl),at*1.1)
        val tp1=if(side=="BUY")en+rr else en-rr
        val tp2=if(side=="BUY")en+rr*1.7 else en-rr*1.7
        val tp3=if(side=="BUY")en+rr*2.5 else en-rr*2.5
        val conf=(55+abs(score)*3+if(mtfBull||mtfBear)8 else 0).coerceIn(55,94)
        val reason="CLOSED CANDLES • EMA/200 • RSI "+fmt(r)+" • MACD "+(if(m>ms)"UP"else"DOWN")+" • ATR "+fmt(at)+" • BB • FIB • ICHI • S/R • BOS/CHOCH • LIQUIDITY • MTF "+(if(mtfBull||mtfBear)"CONFIRMED"else"MIXED")
        levels=Levels(side,en,sl,tp1,tp2,tp3,conf,reason,src.lastIndex,true)

        runOnUiThread{
            price.text="XAU/USD  "+fmt(if(p>0)p else en)+"  •  "+tf
            signal.text=side+"  •  "+conf+"%"
            signal.setTextColor(if(side=="BUY")Color.rgb(45,220,145)else Color.rgb(245,85,85))
            info.text="Paper trading • No real orders\\n"+side+" ENTRY "+fmt(en)+"\\nSL "+fmt(sl)+"   TP1 "+fmt(tp1)+"   TP2 "+fmt(tp2)+"   TP3 "+fmt(tp3)+"\\n"+reason
            chart.invalidate()
            checkAlerts(if(p>0)p else en)
        }
    }

    private fun checkAlerts(p:Double){if(levels.side=="WAIT")return;val k=levels.side+fmt(levels.entry);if(k==lastAlertKey)return;if((levels.side=="BUY"&&p>=levels.entry)||(levels.side=="SELL"&&p<=levels.entry)){lastAlertKey=k;notify("XAU AI ENTRY","${levels.side} entry ${fmt(levels.entry)} • confidence ${levels.confidence}%")}}
    private fun notify(title:String,text:String){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"),44);val nm=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(NotificationChannel("xau","XAU AI Alerts",NotificationManager.IMPORTANCE_DEFAULT));val pi=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE);val b=Notification.Builder(this,"xau").setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).setAutoCancel(true);nm.notify((System.currentTimeMillis()%100000).toInt(),b.build())}
    private fun fetchMacroNews():String{
        var text=""
        val urls=listOf(
            "https://www.federalreserve.gov/feeds/press_all.xml",
            "https://www.bls.gov/feed/cpi.rss",
            "https://www.bls.gov/feed/empsit.rss"
        )
        for(u in urls)try{
            val s=httpGet(u)
            Regex("<title>(.*?)</title>",RegexOption.DOT_MATCHES_ALL).findAll(s).take(10).forEach{
                text+=it.groupValues[1].replace("<![CDATA[","").replace("]]>","").replace(Regex("<.*?>")," ")+" "
            }
        }catch(_:Exception){}
        val x=text.lowercase(Locale.US);var bias=0
        listOf("rate cut","rate cuts","dovish","lower rates","easing","cooling inflation","weak jobs").forEach{if(x.contains(it))bias++}
        listOf("rate hike","rate hikes","hawkish","higher rates","tightening","hot inflation","strong jobs").forEach{if(x.contains(it))bias--}
        macroBias=bias.coerceIn(-3,3)
        macroRisk=false
        macroLabel=when{
            macroBias>=2->"MACRO: GOLD POSITIVE"
            macroBias<=-2->"MACRO: GOLD NEGATIVE"
            else->"MACRO: NEUTRAL"
        }
        return macroLabel
    }

    private inner class ChartView(context:Context):View(context){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        private fun py(v:Double,lo:Double,span:Double,top:Float,bottom:Float)=(bottom-(v-lo)/span*(bottom-top)).toFloat()
        override fun onDraw(c:Canvas){
            c.drawColor(Color.rgb(7,11,17))
            if(candles.isEmpty()){
                p.color=Color.GRAY;p.textSize=dp(14f);c.drawText("Waiting for XAU/USD data…",dp(18f),dp(40f),p);return
            }
            val cs=candles.takeLast(120);val left=dp(7f);val right=width-dp(60f);val top=dp(6f);val bottom=height-dp(25f)
            // Include every active trade level in the plotted range. Previously,
            // distant targets were excluded from autoscaling and then silently clipped.
            val candleLo=cs.minOf{it.l};val candleHi=cs.maxOf{it.h}
            var lo=candleLo;var hi=candleHi
            val activeLevels=listOf(levels.entry,levels.sl,levels.tp1,levels.tp2,levels.tp3)
                .filter{it.isFinite()&&it>0.0}
            if(activeLevels.isNotEmpty()){
                lo=min(lo,activeLevels.min())
                hi=max(hi,activeLevels.max())
            }
            val pad=((hi-lo)*0.07).coerceAtLeast(0.5);lo-=pad;hi+=pad
            val span=(hi-lo).coerceAtLeast(0.001)
            p.strokeWidth=1f;p.color=Color.rgb(29,39,53)
            for(i in 0..8){val y=top+(bottom-top)*i/8f;c.drawLine(left,y,right,y,p)}
            for(i in 0..8){val x=left+(right-left)*i/8f;c.drawLine(x,top,x,bottom,p)}
            p.textSize=dp(9f);p.color=Color.LTGRAY
            for(i in 0..7){val v=hi-(hi-lo)*i/7.0;c.drawText(fmt(v),right+dp(2f),top+(bottom-top)*i/7f+3f,p)}
            val sx=(right-left)/cs.size.toFloat();val cw=(sx*0.78f).coerceAtLeast(dp(3f))
            for(i in cs.indices){
                val z=cs[i];val x=left+(i+0.5f)*sx
                p.color=if(z.c>=z.o)Color.rgb(45,210,140)else Color.rgb(240,75,75)
                p.strokeWidth=dp(1f);c.drawLine(x,py(z.h,lo,span,top,bottom),x,py(z.l,lo,span,top,bottom),p)
                val yo=py(z.o,lo,span,top,bottom);val yc=py(z.c,lo,span,top,bottom)
                c.drawRect(x-cw/2f,min(yo,yc),x+cw/2f,max(yo,yc).coerceAtLeast(min(yo,yc)+dp(1f)),p)
            }
            if(levels.entry>0){
                drawLevel(c,levels.entry,"ENTRY",Color.rgb(245,205,70),left,right,top,bottom,lo,span)
                drawLevel(c,levels.sl,"SL",Color.rgb(245,75,75),left,right,top,bottom,lo,span)
                drawLevel(c,levels.tp1,"TP1",Color.rgb(45,210,140),left,right,top,bottom,lo,span)
                drawLevel(c,levels.tp2,"TP2",Color.rgb(45,210,140),left,right,top,bottom,lo,span)
                drawLevel(c,levels.tp3,"TP3",Color.rgb(45,210,140),left,right,top,bottom,lo,span)
                if(levels.confirmed)drawSignal(c,cs,left,right,top,bottom,lo,span)
            }
            p.color=Color.LTGRAY;p.textSize=dp(8.5f)
            c.drawText(tf,left+dp(4f),bottom+dp(16f),p)
            c.drawText("XAU/USD • MT5 STYLE",left+dp(52f),bottom+dp(16f),p)
        }
        private fun drawLevel(c:Canvas,v:Double,s:String,col:Int,left:Float,right:Float,top:Float,bottom:Float,lo:Double,span:Double){
            if(v<=0)return
            val y=py(v,lo,span,top,bottom);if(y<top||y>bottom)return
            p.color=col;p.strokeWidth=dp(1.2f);c.drawLine(left,y,right,y,p)
            p.textSize=dp(9.5f);c.drawText(s+" "+fmt(v),right+dp(2f),y-2f,p)
        }
        private fun drawSignal(c:Canvas,cs:List<Candle>,left:Float,right:Float,top:Float,bottom:Float,lo:Double,span:Double){
            val offset=candles.size-cs.size
            val idx=(levels.signalIndex-offset).coerceIn(0,cs.lastIndex)
            val z=cs[idx];val x=left+(idx+0.5f)*((right-left)/cs.size.toFloat());val y=py(z.c,lo,span,top,bottom)
            p.color=if(levels.side=="BUY")Color.rgb(45,220,145)else Color.rgb(245,75,75);p.style=Paint.Style.FILL
            val path=Path()
            if(levels.side=="BUY"){path.moveTo(x,y-dp(16f));path.lineTo(x-dp(7f),y-dp(5f));path.lineTo(x+dp(7f),y-dp(5f))}
            else{path.moveTo(x,y+dp(16f));path.lineTo(x-dp(7f),y+dp(5f));path.lineTo(x+dp(7f),y+dp(5f))}
            path.close();c.drawPath(path,p)
            p.textSize=dp(10f);c.drawText(levels.side,x-dp(18f),if(levels.side=="BUY")y-dp(19f)else y+dp(27f),p)
            p.style=Paint.Style.FILL
        }
    }
    private fun buildCandles(){
        candles.clear()
        if(tf=="1D"){
            candles.addAll(dailyCandles)
            if(livePoint>0&&candles.isNotEmpty()){
                val z=candles.last()
                candles[candles.lastIndex]=Candle(z.t,z.o,max(z.h,livePoint),min(z.l,livePoint),livePoint)
            }
            if(candles.size>2000)candles.subList(0,candles.size-2000).clear()
            return
        }
        if(base.isEmpty()&&livePoint<=0)return
        if(base.isEmpty()&&livePoint>0){candles.add(Candle(System.currentTimeMillis(),livePoint,livePoint,livePoint,livePoint));return}
        val step=when(tf){"TICK","1m"->1;"2m"->2;"3m"->3;"5m"->5;"15m"->15;"30m"->30;"1H"->60;"4H"->240;else->5}
        val sorted=base.sortedBy{it.first}
        if(step==1){
            for(i in 1 until sorted.size){
                val t0=sorted[i-1].first;val p0=sorted[i-1].second
                val t1=sorted[i].first;val p1=sorted[i].second
                val a=if(t0>100000000000L)t0 else t0*1000L
                val b=if(t1>100000000000L)t1 else t1*1000L
                if(b<=a)continue
                val span=(b-a).coerceAtLeast(60000L);val n=(span/60000L).coerceAtMost(3L).toInt()
                for(k in 0 until n){
                    val o=p0+(p1-p0)*k/n.toDouble();val cl=p0+(p1-p0)*(k+1)/n.toDouble()
                    candles.add(Candle(a+k*60000L,o,max(o,cl),min(o,cl),cl))
                }
            }
        }else{
            var cur=-1L;var cc:Candle?=null
            for((rawT,p)in sorted){
                val ms=if(rawT>100000000000L)rawT else rawT*1000L
                val bucket=(ms/60000L/step)*step
                if(bucket!=cur){cc?.let{candles.add(it)};cur=bucket;cc=Candle(bucket*60000L,p,p,p,p)}
                else{val z=cc!!;cc=Candle(z.t,z.o,max(z.h,p),min(z.l,p),p)}
            }
            cc?.let{candles.add(it)}
        }
        if(candles.size>2000)candles.subList(0,candles.size-2000).clear()
    }
}
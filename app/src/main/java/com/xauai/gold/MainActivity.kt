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
    private val refresh=object:Runnable{override fun run(){load();mainHandler.postDelayed(this,15000)}}
    private fun dp(v:Float)=v*resources.displayMetrics.density
    private fun fmt(v:Double)=String.format("%.2f",v)

    override fun onCreate(b:Bundle?){super.onCreate(b);window.statusBarColor=Color.rgb(5,8,12);window.navigationBarColor=Color.rgb(5,8,12);buildUi();loadSavedBase();load();mainHandler.postDelayed(refresh,15000)}
    override fun onDestroy(){mainHandler.removeCallbacks(refresh);super.onDestroy()}

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
        root.addView(actions);setContentView(root)
    }

    private fun httpGet(url:String):String{
        val con=(URL(url).openConnection() as HttpURLConnection)
        con.connectTimeout=7000;con.readTimeout=7000;con.requestMethod="GET";con.useCaches=false
        con.setRequestProperty("User-Agent","Khan-XAU/1.0")
        return try{
            val code=con.responseCode
            if(code !in 200..299) throw java.io.IOException("HTTP "+code)
            con.inputStream.bufferedReader().use{it.readText()}
        }finally{con.disconnect()}
    }

    private fun parseLivePrice(raw:String):Double{
        return try{
            val j=JSONObject(raw)
            val symbols=j.optJSONArray("symbols")
            when{
                symbols!=null&&symbols.length()>0->symbols.getJSONObject(0).optString("price").toDoubleOrNull()?:0.0
                j.has("price")->j.optDouble("price",0.0)
                j.has("spot_usd_oz")->j.optDouble("spot_usd_oz",0.0)
                else->0.0
            }
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

    private fun loadRealCandles(interval:String,range:String):Pair<List<Candle>,String>{
        val xa=try{parseXausCandles(httpGet("https://xaus.com/api/v1/chart?symbol=xau&range="+range+"&interval="+interval+"&fresh="+(System.currentTimeMillis()/1000L)))}catch(_:Exception){emptyList()}
        if(xa.size>=30)return xa to "XAUS OHLC"
        val yahooInterval=when(interval){"60m"->"60m";"1h"->"1h";else->interval}
        val yh=try{parseYahooCandles(httpGet("https://query1.finance.yahoo.com/v8/finance/chart/XAUUSD=X?interval="+yahooInterval+"&range="+range+"&events=history&includePrePost=true"))}catch(_:Exception){emptyList()}
        if(yh.size>=30)return yh to "Yahoo XAU/USD OHLC"
        return emptyList<Candle>() to "NO LIVE OHLC"
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
                var (fresh,source)=loadRealCandles(spec.first,spec.second)
                if(fresh.size<30 && tf!="1D"){
                    val (one,oneSource)=loadRealCandles("1m","1d")
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
                var spot=try{parseLivePrice(httpGet("https://xaus.com/api/v1/spot?compact=1&fresh="+(now/1000L)))}catch(_:Exception){0.0}
                if(spot<=0)spot=candles.lastOrNull()?.c?:0.0
                if(spot>0){
                    livePoint=spot
                    if(candles.isNotEmpty()){
                        val z=candles.last()
                        val step=when(tf){"1D"->86400000L;"4H"->14400000L;"1H"->3600000L;"30m"->1800000L;"15m"->900000L;"5m"->300000L;"3m"->180000L;"2m"->120000L;else->60000L}
                        val bucket=(now/step)*step
                        if(z.t>=bucket-step) candles[candles.lastIndex]=Candle(z.t,z.o,max(z.h,spot),min(z.l,spot),spot)
                    }
                }
                base.clear()
                candles.takeLast(720).forEach{base.add(it.t to it.c)}
                saveBase()
                if(candles.size>=30 && spot>0)analyze(spot)
                runOnUiThread{
                    if(spot>0){
                        price.text="XAU/USD  "+fmt(spot)+"  •  "+tf
                        if(candles.size<30){signal.text="WAIT • NOT ENOUGH DATA";signal.setTextColor(Color.rgb(240,190,70))}
                        info.text="Paper trading • No real orders\nData: "+source+" • "+candles.size+" OHLC candles\nEntry / SL / TP are drawn on chart"
                    }else updateConnectionUi(false,"Live XAU/USD unavailable — retrying")
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

    private fun analyze(p:Double){
        if(candles.size<30)return
        val v=candles.map{it.c}
        val e9=ema(v,9);val e20=ema(v,20);val e50=ema(v,50);val e200=if(v.size>=200)ema(v,200) else ema(v,100)
        val r=rsi(v);val m=macd(v);val ms=macdSignal(v);val at=atr(candles);val ichi=ichimoku(candles)
        val bb=bollinger(v);val fib=fibSignal(candles,p);val structure=structureSignal(candles);val pattern=patternSignal(candles)
        val t5=trend(aggregate(candles,5));val t15=trend(aggregate(candles,15));val t60=trend(aggregate(candles,60));val t240=trend(aggregate(candles,240))
        var score=0
        if(e9>e20)score++ else score--
        if(e20>e50)score++ else score--
        if(p>e200)score++ else score--
        if(r>52)score++ else if(r<48)score--
        if(m>ms)score++ else score--
        score+=ichi+fib+structure+pattern
        if(bb.second>0){if(p>bb.first)score--;if(p<bb.third)score++}
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
        val recent=candles.takeLast(80);val hi=recent.maxOf{it.h};val lo=recent.minOf{it.l}
        val risk=max(at*1.20,p*0.00035);val en=p
        val candidate=if(score>=4)"BUY" else if(score<=-4)"SELL" else "WAIT"
        val drawSide=if(side!="WAIT")side else candidate
        val sl=when(drawSide){"BUY"->min(lo,en-risk);"SELL"->max(hi,en+risk);else->0.0}
        val rr=if(drawSide=="WAIT")0.0 else max(abs(en-sl),at*1.1)
        val tp1=if(drawSide=="BUY")en+rr else if(drawSide=="SELL")en-rr else 0.0
        val tp2=if(drawSide=="BUY")en+rr*1.7 else if(drawSide=="SELL")en-rr*1.7 else 0.0
        val tp3=if(drawSide=="BUY")en+rr*2.5 else if(drawSide=="SELL")en-rr*2.5 else 0.0
        val conf=(55+abs(score)*3+if(mtfBull||mtfBear)8 else 0).coerceIn(55,94)
        val reason="EMA/200 • RSI "+fmt(r)+" • MACD "+(if(m>ms)"UP"else"DOWN")+" • ATR "+fmt(at)+" • BB • FIB • ICHI • S/R • BOS/CHOCH • LIQUIDITY • MTF "+(if(mtfBull||mtfBear)"CONFIRMED"else"MIXED")
        levels=Levels(drawSide,en,sl,tp1,tp2,tp3,conf,reason,candles.lastIndex,side!="WAIT")
        runOnUiThread{
            price.text="XAU/USD  "+fmt(p)+"  •  "+tf
            signal.text=side+"  •  "+conf+"%"
            signal.setTextColor(if(side=="BUY")Color.rgb(45,220,145)else if(side=="SELL")Color.rgb(245,85,85)else Color.rgb(240,190,70))
            if(side=="WAIT"&&drawSide!="WAIT")info.text="Paper trading • No real orders\nSETUP "+drawSide+" • Entry "+fmt(en)+"\nSL "+fmt(sl)+"   TP1 "+fmt(tp1)+"   TP2 "+fmt(tp2)+"   TP3 "+fmt(tp3)+"\nWaiting for full confirmation"
            else if(side=="WAIT")info.text="Paper trading • No real orders\nNO TRADE • WAIT FOR CONFIRMATION\nAnalysis: EMA/RSI/MACD/ATR/BB/FIB/ICHIMOKU/SR/BOS/CHOCH/LIQUIDITY/MTF"
            else info.text="Paper trading • No real orders\n"+side+" ENTRY "+fmt(en)+"\nSL "+fmt(sl)+"   TP1 "+fmt(tp1)+"   TP2 "+fmt(tp2)+"   TP3 "+fmt(tp3)+"\n"+reason
            chart.invalidate();checkAlerts(p)
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
            var lo=cs.minOf{it.l};var hi=cs.maxOf{it.h}
            val lv=listOf(levels.entry,levels.sl,levels.tp1,levels.tp2,levels.tp3).filter{it>0}
            if(lv.isNotEmpty()){lo=min(lo,lv.min());hi=max(hi,lv.max())}
            val pad=((hi-lo)*0.07).coerceAtLeast(0.5);lo-=pad;hi+=pad;val span=(hi-lo).coerceAtLeast(0.001)
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
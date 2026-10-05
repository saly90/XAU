package com.xauai.gold

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.*
import android.view.View
import android.widget.*
import android.content.Context
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.*

data class Candle(val t:Long,val o:Double,val h:Double,val l:Double,val c:Double)
data class Quote(val price:Double,val time:Long,val ageSec:Long,val stale:Boolean,val state:String)
data class Levels(val side:String,val entry:Double,val sl:Double,val tp1:Double,val tp2:Double,val tp3:Double,val confidence:Int,val reason:String,val confirmed:Boolean)

class LiveMainActivity:Activity(){
    private lateinit var chart:Chart
    private lateinit var price:TextView
    private lateinit var signal:TextView
    private lateinit var info:TextView
    private val bars=mutableListOf<Candle>()
    private val client=OkHttpClient.Builder().cache(null).build()
    private val handler=Handler(Looper.getMainLooper())
    private var tf="5m"
    private var live=0.0
    private var lastQuoteTime=0L
    private var quoteAge=Long.MAX_VALUE
    private var quoteState="UNKNOWN"
    private var loading=false
    private var levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"WAIT",false)
    private var trend="NEUTRAL"
    private var rsiV=50.0
    private var atrV=0.0
    private var support=0.0
    private var resistance=0.0
    private var fib="—"
    private var structure="—"
    private var mtf="—"
    private val historyTask=object:Runnable{override fun run(){refreshHistory();handler.postDelayed(this,15000)}}
    private val tickTask=object:Runnable{override fun run(){pollTick();handler.postDelayed(this,700)}}

    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        window.statusBarColor=Color.rgb(5,8,12);window.navigationBarColor=Color.rgb(5,8,12)
        buildUi();refreshHistory();handler.postDelayed(historyTask,15000);handler.postDelayed(tickTask,700)
    }
    override fun onDestroy(){handler.removeCallbacks(historyTask);handler.removeCallbacks(tickTask);client.dispatcher.executorService.shutdown();super.onDestroy()}
    private fun dp(v:Float)=v*resources.displayMetrics.density
    private fun Int.dp()=dp(toFloat()).toInt()
    private fun Float.dp()=dp(this).toInt()
    private fun fmt(v:Double)=String.format(Locale.US,"%.2f",v)
    private fun tv(s:String,size:Float,bold:Boolean=false)=TextView(this).apply{text=s;textSize=size;setTextColor(Color.WHITE);if(bold)setTypeface(null,1)}

    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(5,8,12));setPadding(8.dp(),6.dp(),8.dp(),5.dp())}
        root.addView(tv("خان  •  XAU/USD PRO",19f,true),LinearLayout.LayoutParams(-1,34.dp()))
        price=tv("XAU/USD  —",16f,true);root.addView(price,LinearLayout.LayoutParams(-1,28.dp()))
        signal=tv("WAIT  •  CONNECTING LIVE FEED",17f,true);signal.setTextColor(Color.rgb(240,190,70));root.addView(signal,LinearLayout.LayoutParams(-1,30.dp()))
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D").forEach{s->val b=Button(this).apply{text=s;textSize=10f;setOnClickListener{if(tf!=s){tf=s;refreshHistory()}}};row.addView(b,LinearLayout.LayoutParams(0,38.dp(),1f))}
        root.addView(row)
        chart=Chart(this);root.addView(chart,LinearLayout.LayoutParams(-1,0,1f))
        info=tv("Paper trading • No real orders\nSource: Biquote MT5 feed • connecting…",11.5f);info.setPadding(4.dp(),2.dp(),4.dp(),2.dp());root.addView(info,LinearLayout.LayoutParams(-1,96.dp()))
        val bottom=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val refresh=Button(this).apply{text="REFRESH";setOnClickListener{refreshHistory()}}
        val alert=Button(this).apply{text="ALERT";setOnClickListener{Toast.makeText(this@LiveMainActivity,"Alerts are informational only. No real orders.",Toast.LENGTH_SHORT).show()}}
        bottom.addView(refresh,LinearLayout.LayoutParams(0,42.dp(),1f));bottom.addView(alert,LinearLayout.LayoutParams(0,42.dp(),1f));root.addView(bottom)
        setContentView(root)
    }

    private fun http(url:String):String{
        val req=Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).header("Cache-Control","no-cache").header("Pragma","no-cache").header("User-Agent","Khan-XAU-PRO/5.0").build()
        client.newCall(req).execute().use{r->if(!r.isSuccessful)throw java.io.IOException("HTTP ${r.code}");return r.body?.string().orEmpty()}
    }
    private fun parseTime(v:Any?):Long{
        when(v){
            is Number->return if(v.toLong()<100000000000L)v.toLong()*1000L else v.toLong()
            is String->{v.toLongOrNull()?.let{return if(it<100000000000L)it*1000L else it};val fs=listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'","yyyy-MM-dd'T'HH:mm:ss'Z'","yyyy-MM-dd'T'HH:mm:ss.SSSXXX","yyyy-MM-dd'T'HH:mm:ssXXX");for(f in fs)try{return SimpleDateFormat(f,Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.parse(v)?.time?:0L}catch(_:Exception){}}
        }
        return 0L
    }
    private fun quote(raw:String):Quote{
        val j=JSONObject(raw);val mid=j.optDouble("mid",Double.NaN);val bid=j.optDouble("bid",Double.NaN);val ask=j.optDouble("ask",Double.NaN)
        val p=when{mid.isFinite()&&mid>0->mid;bid.isFinite()&&ask.isFinite()&&bid>0&&ask>0->(bid+ask)/2;bid.isFinite()&&bid>0->bid;else->Double.NaN}
        if(!p.isFinite()||p<=0)throw IllegalArgumentException("invalid quote")
        val ts=parseTime(j.opt("timestamp"));val age=j.optLong("quoteAgeSeconds",if(ts>0)max(0,(System.currentTimeMillis()-ts)/1000) else Long.MAX_VALUE);val stale=j.optBoolean("stale",age>300);val state=j.optString("marketState",if(stale)"stale" else "open")
        return Quote(p,ts,age,stale,state)
    }
    private fun parseBars(raw:String):List<Candle>{
        val a=JSONObject(raw).optJSONArray("bars")?:return emptyList();val out=ArrayList<Candle>(a.length())
        for(i in 0 until a.length()){val z=a.optJSONObject(i)?:continue;val t=parseTime(z.opt("openTime")?:z.opt("timestamp")?:z.opt("time"));val o=z.optDouble("open",Double.NaN);val h=z.optDouble("high",Double.NaN);val l=z.optDouble("low",Double.NaN);val c=z.optDouble("close",Double.NaN);if(t>0&&o>0&&c>0&&h>=l&&o.isFinite()&&h.isFinite()&&l.isFinite()&&c.isFinite())out.add(Candle(t,o,h,l,c))}
        return out.distinctBy{it.t}.sortedBy{it.t}
    }
    private fun interval(s:String)=when(s){"1m","2m","3m"->"1m";"5m"->"5m";"15m"->"15m";"30m"->"30m";"1H"->"1h";"4H"->"4h";"1D"->"1d";else->"5m"}
    private fun aggregate(src:List<Candle>,minutes:Int):List<Candle>{if(minutes<=1)return src;val step=minutes*60000L;val out=ArrayList<Candle>();for(z in src){val b=(z.t/step)*step;val last=out.lastOrNull();if(last==null||last.t!=b)out.add(Candle(b,z.o,z.h,z.l,z.c))else out[out.lastIndex]=Candle(last.t,last.o,max(last.h,z.h),min(last.l,z.l),z.c)};return out}
    private fun loadBars(s:String,limit:Int):List<Candle>{val rawLimit=if(s=="2m"||s=="3m")min(1000,limit*4) else min(1000,limit);val raw=parseBars(http("https://biquote.io/api/XAUUSD/ohlc?interval=${interval(s)}&limit=$rawLimit"));return when(s){"2m"->aggregate(raw,2).takeLast(limit);"3m"->aggregate(raw,3).takeLast(limit);else->raw.takeLast(limit)}}
    private fun stepMs(s:String)=when(s){"1m"->60000L;"2m"->120000L;"3m"->180000L;"5m"->300000L;"15m"->900000L;"30m"->1800000L;"1H"->3600000L;"4H"->14400000L;"1D"->86400000L;else->300000L}
    private fun closed(src:List<Candle>):List<Candle>{if(src.size<2)return emptyList();val step=stepMs(tf);val now=(System.currentTimeMillis()/step)*step;return src.filter{(it.t/step)*step<now}}

    private fun ema(v:List<Double>,n:Int):Double{if(v.isEmpty())return 0.0;val k=2.0/(n+1);var e=v.first();for(i in 1 until v.size)e=v[i]*k+e*(1-k);return e}
    private fun rsi(v:List<Double>,n:Int=14):Double{if(v.size<=n)return 50.0;var g=0.0;var d=0.0;for(i in 1..n){val x=v[i]-v[i-1];g+=max(0.0,x);d+=max(0.0,-x)};g/=n;d/=n;for(i in n+1 until v.size){val x=v[i]-v[i-1];g=(g*(n-1)+max(0.0,x))/n;d=(d*(n-1)+max(0.0,-x))/n};return if(d==0.0)100.0 else 100.0-100.0/(1+g/d)}
    private fun atr(v:List<Candle>,n:Int=14):Double{if(v.size<2)return 0.0;val tr=ArrayList<Double>();for(i in 1 until v.size){val z=v[i];val pc=v[i-1].c;tr.add(max(z.h-z.l,max(abs(z.h-pc),abs(z.l-pc))))};return tr.takeLast(n).average()}
    private fun macd(v:List<Double>):Double{if(v.size<35)return 0.0;fun es(n:Int):List<Double>{val out=MutableList(v.size){0.0};out[0]=v[0];val k=2.0/(n+1);for(i in 1 until v.size)out[i]=v[i]*k+out[i-1]*(1-k);return out};val a=es(12);val b=es(26);val m=MutableList(v.size){a[it]-b[it]};val k=0.2;var sig=m.first();for(i in 1 until m.size)sig=m[i]*k+sig*(1-k);return m.last()-sig}
    private fun ichi(v:List<Candle>):Int{if(v.size<52)return 0;fun mid(n:Int):Double{val x=v.takeLast(n);return(x.maxOf{it.h}+x.minOf{it.l})/2};val ten=mid(9);val kij=mid(26);val cloud=mid(52);val c=v.last().c;return if(c>ten&&ten>kij&&c>cloud)1 else if(c<ten&&ten<kij&&c<cloud)-1 else 0}
    private fun direction(v:List<Candle>):Int{if(v.size<55)return 0;val c=v.map{it.c};var s=0;s+=if(ema(c,20)>ema(c,50))1 else -1;s+=if(c.last()>ema(c,200.coerceAtMost(c.size)))1 else -1;s+=if(rsi(c)>52)1 else if(rsi(c)<48)-1 else 0;s+=if(macd(c)>0)1 else -1;s+=ichi(v);return s.coerceIn(-5,5)}

    private fun refreshHistory(){if(loading)return;loading=true;thread{try{val main=loadBars(tf,500);val mtfs=mutableMapOf<String,List<Candle>>();listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf}.forEach{s->try{mtfs[s]=loadBars(s,220)}catch(_:Exception){}};val q=try{quote(http("https://biquote.io/api/XAUUSD?allowStale=false"))}catch(_:Exception){null};runOnUiThread{bars.clear();bars.addAll(main.takeLast(700));if(q!=null)applyQuote(q);if(bars.size>=80)analyze(bars.toList(),mtfs) else levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"NO VALID CLOSED HISTORY",false);render();loading=false}}catch(_:Exception){runOnUiThread{levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"DATA ERROR • NO FAKE DATA",false);signal.text="DATA ERROR • WAIT";signal.setTextColor(Color.rgb(245,150,70));info.text="Paper trading • No real orders\nLive feed unavailable. No fake candles or levels are generated.";chart.invalidate();loading=false}}}}
    private fun pollTick(){thread{try{val q=quote(http("https://biquote.io/api/XAUUSD?allowStale=false"));if(q.time==0L||q.time>=lastQuoteTime||abs(q.price-live)>0.0001)runOnUiThread{applyQuote(q)}}catch(_:Exception){}}}
    private fun applyQuote(q:Quote){if(q.price<=0)return;live=q.price;if(q.time>0)lastQuoteTime=max(lastQuoteTime,q.time);quoteAge=q.ageSec;quoteState=q.state;if(bars.isNotEmpty()){val last=bars.last();val step=stepMs(tf);val bucket=(q.time/step)*step;val lb=(last.t/step)*step;if(q.time>0&&bucket==lb)bars[bars.lastIndex]=Candle(last.t,last.o,max(last.h,q.price),min(last.l,q.price),q.price) else if(q.time>0&&bucket>lb)bars.add(Candle(bucket,last.c,q.price,q.price,q.price));if(bars.size>700)bars.removeAt(0)};render()}

    private fun analyze(main:List<Candle>,mtfs:Map<String,List<Candle>>){
        val src=closed(main);if(src.size<80){levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"NOT ENOUGH CLOSED DATA",false);return}
        val c=src.map{it.c};val last=src.last();val e20=ema(c,20);val e50=ema(c,50);val e200=ema(c,200);rsiV=rsi(c);atrV=atr(src);val mh=macd(c);val ic=ichi(src);val recent=src.takeLast(50).dropLast(1);support=recent.minOf{it.l};resistance=recent.maxOf{it.h};val sw=src.takeLast(80);val hi=sw.maxOf{it.h};val lo=sw.minOf{it.l};val range=hi-lo;val f382=hi-range*.382;val f50=hi-range*.5;val f618=hi-range*.618;fib="38.2 ${fmt(f382)} | 50 ${fmt(f50)} | 61.8 ${fmt(f618)}";val fibZone=last.c in f618..f382;val buyFib=fibZone&&e20>=e50;val sellFib=fibZone&&e20<=e50
        val list=listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf};val scores=list.mapNotNull{mtfs[it]?.let{direction(closed(it))}};val bull=scores.count{it>=2};val bear=scores.count{it<=-2};mtf="BULL $bull/${scores.size} • BEAR $bear/${scores.size}";val bosUp=last.c>resistance&&(last.c-resistance)>atrV*.05;val bosDn=last.c<support&&(support-last.c)>atrV*.05
        val bs=(if(e20>e50)1 else 0)+(if(last.c>e20)1 else 0)+(if(last.c>e200)1 else 0)+(if(rsiV in 50.0..72.0)1 else 0)+(if(mh>0)1 else 0)+(if(ic>=0)1 else 0)+(if(bull>=1)1 else 0)+(if(bosUp||buyFib)1 else 0);val ss=(if(e20<e50)1 else 0)+(if(last.c<e20)1 else 0)+(if(last.c<e200)1 else 0)+(if(rsiV in 28.0..50.0)1 else 0)+(if(mh<0)1 else 0)+(if(ic<=0)1 else 0)+(if(bear>=1)1 else 0)+(if(bosDn||sellFib)1 else 0)
        val side=when{bs>=5&&bs>ss->"BUY";ss>=5&&ss>bs->"SELL";else->"WAIT"};trend=when{bs>=5->"BULLISH";ss>=5->"BEARISH";bs>=4&&bs>ss->"BULLISH BIAS";ss>=4&&ss>bs->"BEARISH BIAS";else->"NEUTRAL"};structure=when{bosUp->"BOS UP";bosDn->"BOS DOWN";buyFib->"BULLISH FIB ZONE";sellFib->"BEARISH FIB ZONE";else->"TREND / MOMENTUM"}
        if(side=="WAIT"){levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"WAIT • BUY $bs/8 • SELL $ss/8",false);return}
        val entry=last.c;if(live>0&&atrV>0&&abs(live-entry)>atrV*1.2){levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"SETUP EXPIRED • WAIT FOR RETEST",false);return};val look=src.takeLast(30).dropLast(1);val swingLow=look.minOf{it.l};val swingHigh=look.maxOf{it.h};val sl=if(side=="BUY")min(swingLow-atrV*.15,entry-atrV) else max(swingHigh+atrV*.15,entry+atrV);val risk=abs(entry-sl);if(risk<=0||!risk.isFinite()){levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"INVALID RISK MODEL",false);return};val structural=if(side=="BUY"&&resistance>entry)resistance else if(side=="SELL"&&support<entry)support else Double.NaN;val base=if(side=="BUY")entry+risk*1.5 else entry-risk*1.5;val tp1=if(structural.isFinite()&&abs(structural-entry)>=risk*1.2)structural else base;val tp2=if(side=="BUY")max(tp1+risk*.5,entry+risk*2.2) else min(tp1-risk*.5,entry-risk*2.2);val tp3=if(side=="BUY")max(tp2+risk*.5,entry+risk*3.0) else min(tp2-risk*.5,entry-risk*3.0);val conf=(62+max(bs,ss)*4+abs(bull-bear)*2).coerceIn(62,90);levels=Levels(side,entry,sl,tp1,tp2,tp3,conf,"STRUCTURE SL • ATR BUFFER • S/R TARGET • 1.5R/2.2R/3R",true)
    }

    private fun render(){
        val freshness=when{quoteState=="closed"->"MARKET CLOSED • last quote ${quoteAge.coerceAtLeast(0)}s ago";quoteAge<=5->"LIVE • quote ${quoteAge}s ago • MT5 source";quoteAge<=60->"LIVE/DELAYED • quote ${quoteAge}s ago";else->"STALE • quote ${quoteAge}s ago"};price.text="XAU/USD  ${if(live>0)fmt(live) else "—"}  •  $tf";signal.text=if(levels.side=="WAIT")"WAIT" else "${levels.side}  •  ${levels.confidence}%";signal.setTextColor(if(levels.side=="BUY")Color.rgb(45,220,145) else if(levels.side=="SELL")Color.rgb(245,80,80) else Color.rgb(240,190,70));info.text="Paper trading • No real orders\nFEED: $freshness\nTREND: $trend   RSI: ${fmt(rsiV)}   ATR: ${fmt(atrV)}\nS/R: ${fmt(support)} / ${fmt(resistance)}\nFIB: $fib\nSTRUCTURE: $structure   MTF: $mtf\n"+(if(levels.confirmed)"ENTRY ${fmt(levels.entry)}   SL ${fmt(levels.sl)}   TP1 ${fmt(levels.tp1)}   TP2 ${fmt(levels.tp2)}   TP3 ${fmt(levels.tp3)}" else levels.reason));chart.invalidate()
    }

    private inner class Chart(c:Context):View(c){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){
            c.drawColor(Color.rgb(7,11,17));if(bars.isEmpty()){p.color=Color.GRAY;p.textSize=dp(14f);c.drawText("Waiting for real XAU/USD candles…",18f,40f,p);return};val cs=bars.takeLast(140);val left=dp(8f);val right=width-dp(72f);val top=dp(8f);val bottom=height-dp(30f);var lo=cs.minOf{it.l};var hi=cs.maxOf{it.h};val extra=listOf(levels.entry,levels.sl,levels.tp1,levels.tp2,levels.tp3,live).filter{it>0&&it.isFinite()};if(extra.isNotEmpty()){lo=min(lo,extra.min());hi=max(hi,extra.max())};val pad=max((hi-lo)*.06,.5);lo-=pad;hi+=pad;val span=max(hi-lo,.001);p.strokeWidth=1f
            for(i in 0..8){val y=top+(bottom-top)*i/8f;p.color=Color.rgb(28,38,52);c.drawLine(left,y,right,y,p);p.color=Color.rgb(130,140,150);p.textSize=dp(10f);c.drawText(fmt(hi-(hi-lo)*i/8.0),right+dp(5f),y+dp(3f),p)}
            val w=(right-left)/cs.size;for(i in cs.indices){val z=cs[i];val x=left+w*(i+.5f);fun y(v:Double)=bottom-((v-lo)/span*(bottom-top)).toFloat();p.color=if(z.c>=z.o)Color.rgb(40,210,145) else Color.rgb(240,80,80);p.strokeWidth=max(1f,dp(1f));c.drawLine(x,y(z.h),x,y(z.l),p);val bw=max(dp(2f),w*.65f).toFloat();c.drawRect(x-bw/2,y(max(z.o,z.c)),x+bw/2,y(min(z.o,z.c)),p)}
            fun level(v:Double,label:String){if(v<=0||!v.isFinite())return;val y=bottom-((v-lo)/span*(bottom-top)).toFloat();p.color=Color.rgb(245,190,70);p.strokeWidth=1.5f;c.drawLine(left,y,right,y,p);p.textSize=dp(9f);c.drawText("$label ${fmt(v)}",left+dp(4f),y-dp(2f),p)}
            if(levels.confirmed){level(levels.entry,"E");level(levels.sl,"SL");level(levels.tp1,"TP1");level(levels.tp2,"TP2");level(levels.tp3,"TP3")};if(live>0){val y=bottom-((live-lo)/span*(bottom-top)).toFloat();p.color=Color.WHITE;p.strokeWidth=2f;c.drawLine(left,y,right,y,p);p.textSize=dp(10f);c.drawText(fmt(live),right+dp(5f),y+dp(3f),p)};p.color=Color.rgb(120,130,140);p.textSize=dp(10f);c.drawText("$tf • ${cs.size} candles • LIVE chart",left,bottom+dp(20f),p)
        }
    }
}

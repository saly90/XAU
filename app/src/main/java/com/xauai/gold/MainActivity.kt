package com.xauai.gold

import android.app.*
import android.os.*
import android.graphics.*
import android.view.*
import android.widget.*
import android.content.*
import android.content.pm.PackageManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.*

data class Candle(val t:Long,val o:Double,val h:Double,val l:Double,val c:Double)
data class Tick(val price:Double,val time:Long)
data class Levels(val side:String,val entry:Double,val sl:Double,val tp1:Double,val tp2:Double,val tp3:Double,val confidence:Int,val reason:String,val signalIndex:Int,val confirmed:Boolean)
data class Analysis(val levels:Levels,val trend:String,val rsi:Double,val atr:Double,val support:Double,val resistance:Double,val fib:String,val structure:String,val mtf:String,val freshness:String)

class MainActivity:Activity(){
    private lateinit var chart:ChartView
    private lateinit var price:TextView
    private lateinit var signal:TextView
    private lateinit var info:TextView
    private val candles=mutableListOf<Candle>()
    private var tf="5m"
    private var livePrice=0.0
    private var lastTickTime=0L
    private var levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"WAIT",0,false)
    private var analysis=Analysis(levels,"NEUTRAL",50.0,0.0,0.0,0.0,"—","—","—","—")
    private val client=OkHttpClient()
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var loading=false
    private val refresh=object:Runnable{override fun run(){load();handler.postDelayed(this,20000)}}
    private val tickRefresh=object:Runnable{override fun run(){pollTick();handler.postDelayed(this,1200)}}

    override fun onCreate(b:Bundle?){super.onCreate(b);window.statusBarColor=Color.rgb(5,8,12);window.navigationBarColor=Color.rgb(5,8,12);buildUi();load();handler.postDelayed(refresh,20000);handler.postDelayed(tickRefresh,1000)}
    override fun onDestroy(){handler.removeCallbacks(refresh);handler.removeCallbacks(tickRefresh);client.dispatcher.executorService.shutdown();super.onDestroy()}

    private fun tv(s:String,size:Float,bold:Boolean=false)=TextView(this).apply{text=s;textSize=size;setTextColor(Color.WHITE);if(bold)setTypeface(null,1)}
    private fun dp(x:Float)=x*resources.displayMetrics.density
    private fun fmt(x:Double)=String.format(Locale.US,"%.2f",x)

    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(5,8,12));setPadding(dp(8f).toInt(),dp(6f).toInt(),dp(8f).toInt(),dp(5f).toInt())}
        root.addView(tv("خان  •  XAU/USD PRO",19f,true),LinearLayout.LayoutParams(-1,34.dp()))
        price=tv("XAU/USD  —",16f,true);root.addView(price,LinearLayout.LayoutParams(-1,28.dp()))
        signal=tv("WAIT  •  BUILDING MARKET MODEL",17f,true);signal.setTextColor(Color.rgb(240,190,70));root.addView(signal,LinearLayout.LayoutParams(-1,30.dp()))
        val tfRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("1m","5m","15m","30m","1H","4H","1D").forEach{s->val b=Button(this).apply{text=s;textSize=10f;setOnClickListener{tf=s;load()}};tfRow.addView(b,LinearLayout.LayoutParams(0,38.dp(),1f))}
        root.addView(tfRow)
        chart=ChartView(this);root.addView(chart,LinearLayout.LayoutParams(-1,0,1f))
        info=tv("Paper trading • No real orders\nConnecting to live XAU/USD…",11.5f);info.setPadding(4.dp(),2.dp(),4.dp(),2.dp());root.addView(info,LinearLayout.LayoutParams(-1,92.dp()))
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val r=Button(this).apply{text="REFRESH";setOnClickListener{load()}}
        val a=Button(this).apply{text="ALERT";setOnClickListener{Toast.makeText(this@MainActivity,"Alerts are informational only. No real orders.",Toast.LENGTH_SHORT).show()}}
        row.addView(r,LinearLayout.LayoutParams(0,42.dp(),1f));row.addView(a,LinearLayout.LayoutParams(0,42.dp(),1f));root.addView(row)
        setContentView(root)
    }
    private fun Float.dp()=dp(this)
    private fun Int.dp()=dp(toFloat())

    private fun http(url:String):String{
        val req=Request.Builder().url(url).header("User-Agent","Khan-XAU-PRO/3.0").build()
        client.newCall(req).execute().use{r->if(!r.isSuccessful)throw java.io.IOException("HTTP "+r.code);return r.body?.string().orEmpty()}
    }
    private fun parseTick(raw:String):Tick{
        val j=JSONObject(raw);val p=j.optDouble("mid",Double.NaN).let{if(it.isFinite()&&it>0)it else (j.optDouble("bid",0.0)+j.optDouble("ask",0.0))/2.0}
        val ts=j.opt("timestamp")
        val t=when(ts){is Number->if(ts.toLong()>100000000000L)ts.toLong() else ts.toLong()*1000L;is String->parseTime(ts);else->System.currentTimeMillis()}
        return Tick(p,t)
    }
    private fun parseTime(s:String):Long{
        val formats=listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'","yyyy-MM-dd'T'HH:mm:ss'Z'")
        for(p in formats)try{return SimpleDateFormat(p,Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.parse(s)?.time?:0L}catch(_:Exception){}
        return s.toLongOrNull()?.let{if(it<100000000000L)it*1000L else it}?:System.currentTimeMillis()
    }
    private fun parseBars(raw:String):List<Candle>{
        val a=JSONObject(raw).optJSONArray("bars")?:return emptyList()
        val out=mutableListOf<Candle>()
        for(i in 0 until a.length()){
            val z=a.optJSONObject(i)?:continue
            val t=parseTime(z.optString("openTime",z.optString("timestamp",z.optString("time",""))))
            val o=z.optDouble("open",Double.NaN);val h=z.optDouble("high",Double.NaN);val l=z.optDouble("low",Double.NaN);val c=z.optDouble("close",Double.NaN)
            if(t>0&&o.isFinite()&&h.isFinite()&&l.isFinite()&&c.isFinite()&&o>0&&c>0&&h>=l)out.add(Candle(t,o,h,l,c))
        }
        return out.distinctBy{it.t}.sortedBy{it.t}
    }
    private fun interval(s:String)=when(s){"1m"->"1m";"5m"->"5m";"15m"->"15m";"30m"->"30m";"1H"->"1h";"4H"->"4h";"1D"->"1d";else->"5m"}
    private fun loadBars(s:String,limit:Int=500):List<Candle>{
        return parseBars(http("https://biquote.io/api/XAUUSD/ohlc?interval="+interval(s)+"&limit="+limit))
    }

    private fun pollTick(){
        if(loading)return
        thread{try{val t=parseTick(http("https://biquote.io/api/XAUUSD?allowStale=false"));if(t.price>0)runOnUiThread{applyTick(t)}}catch(_:Exception){}}
    }
    private fun applyTick(t:Tick){
        livePrice=t.price;lastTickTime=t.time
        if(candles.isNotEmpty()){
            val last=candles.last()
            val step=stepMs(tf)
            val bucket=(t.time/step)*step
            val lastBucket=(last.t/step)*step
            if(bucket==lastBucket)candles[candles.lastIndex]=Candle(last.t,last.o,max(last.h,t.price),min(last.l,t.price),t.price)
            else if(bucket>lastBucket)candles.add(Candle(bucket,last.c,t.price,t.price,t.price))
            if(candles.size>1000)candles.removeAt(0)
        }
        price.text="XAU/USD  "+fmt(t.price)+"  •  "+tf+"  • LIVE"
        chart.invalidate()
    }

    private fun stepMs(s:String)=when(s){"1m"->60000L;"5m"->300000L;"15m"->900000L;"30m"->1800000L;"1H"->3600000L;"4H"->14400000L;"1D"->86400000L;else->300000L}
    private fun closed(src:List<Candle>):List<Candle>{
        if(src.size<=2)return emptyList()
        val last=src.last()
        val nowBucket=(System.currentTimeMillis()/stepMs(tf))*stepMs(tf)
        return if((last.t/stepMs(tf))*stepMs(tf)>=nowBucket)src.dropLast(1) else src.dropLast(1)
    }

    private fun ema(v:List<Double>,n:Int):Double{if(v.isEmpty())return 0.0;val k=2.0/(n+1);var e=v.first();for(i in 1 until v.size)e=v[i]*k+e*(1-k);return e}
    private fun emaSeries(v:List<Double>,n:Int):List<Double>{if(v.isEmpty())return emptyList();val k=2.0/(n+1);val o=MutableList(v.size){0.0};o[0]=v[0];for(i in 1 until v.size)o[i]=v[i]*k+o[i-1]*(1-k);return o}
    private fun rsi(v:List<Double>,n:Int=14):Double{if(v.size<=n)return 50.0;var g=0.0;var d=0.0;for(i in 1..n){val x=v[i]-v[i-1];g+=max(0.0,x);d+=max(0.0,-x)};g/=n;d/=n;for(i in n+1 until v.size){val x=v[i]-v[i-1];g=(g*(n-1)+max(0.0,x))/n;d=(d*(n-1)+max(0.0,-x))/n};return if(d==0.0)100.0 else 100.0-100.0/(1+g/d)}
    private fun atr(v:List<Candle>,n:Int=14):Double{if(v.size<2)return 0.0;val tr=mutableListOf<Double>();for(i in 1 until v.size){val z=v[i];val pc=v[i-1].c;tr.add(max(z.h-z.l,max(abs(z.h-pc),abs(z.l-pc))))};return tr.takeLast(n).average()}
    private fun macdHist(v:List<Double>):Double{if(v.size<35)return 0.0;val a=emaSeries(v,12);val b=emaSeries(v,26);val m=MutableList(v.size){a[it]-b[it]};return m.last()-ema(m.takeLast(60),9)}
    private fun ichimoku(v:List<Candle>):Int{if(v.size<52)return 0;fun mid(n:Int):Double{val a=v.takeLast(n);return(a.maxOf{it.h}+a.minOf{it.l})/2};val ten=mid(9);val kij=mid(26);val cloud=mid(52);return if(v.last().c>ten&&ten>kij&&v.last().c>cloud)1 else if(v.last().c<ten&&ten<kij&&v.last().c<cloud)-1 else 0}
    private fun trend(v:List<Candle>):Int{if(v.size<55)return 0;val c=v.map{it.c};var s=0;if(ema(c,20)>ema(c,50))s++ else s--;if(c.last()>ema(c,200.coerceAtMost(c.size-1)))s++ else s--;if(rsi(c)>52)s++ else if(rsi(c)<48)s--;if(macdHist(c)>0)s++ else s--;s+=ichimoku(v);return s.coerceIn(-5,5)}

    private fun analyze(main:List<Candle>, mtf:Map<String,List<Candle>>, live:Double){
        val src=closed(main);if(src.size<80){levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"NOT ENOUGH CLOSED DATA",0,false);return}
        val c=src.map{it.c};val last=src.last();val e20=ema(c,20);val e50=ema(c,50);val e200=ema(c,200);val rr=rsi(c);val at=atr(src);val mh=macdHist(c);val ichi=ichimoku(src)
        val recent=src.takeLast(50);val resistance=recent.dropLast(1).maxOf{it.h};val support=recent.dropLast(1).minOf{it.l}
        val swing=src.takeLast(80);val hi=swing.maxOf{it.h};val lo=swing.minOf{it.l};val range=hi-lo
        val f382=hi-range*0.382;val f50=hi-range*0.5;val f618=hi-range*0.618
        val bosUp=last.c>resistance;val bosDn=last.c<support
        val pullBuy=last.c in f618..f382 && last.c>e20
        val pullSell=last.c in f382..f618 && last.c<e20
        val bullTrend=e20>e50&&last.c>e200&&rr in 52.0..74.0&&mh>0&&ichi>=0
        val bearTrend=e20<e50&&last.c<e200&&rr in 26.0..48.0&&mh<0&&ichi<=0
        val hts=listOf("5m","15m","1H","4H","1D").distinct().filter{it!=tf}
        val bullMtf=hts.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it>=2}
        val bearMtf=hts.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it<=-2}
        val required=max(1,(hts.size+1)/2)
        val alignedBull=bullMtf>=required;val alignedBear=bearMtf>=required
        val breakoutBuy=bosUp&&last.c>resistance+at*0.10
        val breakoutSell=bosDn&&last.c<support-at*0.10
        val side=when{
            bullTrend&&(breakoutBuy||pullBuy)&&alignedBull->"BUY"
            bearTrend&&(breakoutSell||pullSell)&&alignedBear->"SELL"
            else->"WAIT"
        }
        val mtfLabel="BULL "+bullMtf+"/"+hts.size+" • BEAR "+bearMtf+"/"+hts.size
        val fibLabel="38.2 "+fmt(f382)+" | 50 "+fmt(f50)+" | 61.8 "+fmt(f618)
        val structure=when{breakoutBuy->"BOS UP";breakoutSell->"BOS DOWN";pullBuy->"BULLISH FIB RETEST";pullSell->"BEARISH FIB RETEST";else->"NO CONFIRMED BREAK"}
        if(side=="WAIT"){
            levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"NO CONFIRMED ENTRY • WAIT",src.lastIndex,false)
            analysis=Analysis(levels,when{bullTrend->"BULLISH";bearTrend->"BEARISH";else->"NEUTRAL"},rr,at,support,resistance,fibLabel,structure,mtfLabel,"LIVE")
        }else{
            val lp=if(live>0)live else last.c
            val distance=abs(lp-last.c)
            if(at<=0||distance>at*0.80){
                levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"SETUP CONFIRMED BUT PRICE LEFT ENTRY ZONE",src.lastIndex,false)
                analysis=Analysis(levels,if(side=="BUY")"BULLISH" else "BEARISH",rr,at,support,resistance,fibLabel,structure,mtfLabel,"LIVE")
            }else{
                val entry=lp
                val sl=if(side=="BUY")min(support-at*0.20,entry-at*1.15) else max(resistance+at*0.20,entry+at*1.15)
                val risk=abs(entry-sl)
                val tp1=if(side=="BUY")entry+risk*1.5 else entry-risk*1.5
                val tp2=if(side=="BUY")entry+risk*2.2 else entry-risk*2.2
                val tp3=if(side=="BUY")entry+risk*3.0 else entry-risk*3.0
                val conf=(68+min(18,abs(if(side=="BUY")bullMtf-bearMtf else bearMtf-bullMtf)*5)).coerceIn(68,90)
                val reason="CLOSED CANDLES • EMA20/50/200 • RSI "+fmt(rr)+" • MACD • ATR • ICHIMOKU • FIB • S/R • BOS/CHOCH • MTF"
                levels=Levels(side,entry,sl,tp1,tp2,tp3,conf,reason,src.lastIndex,true)
                analysis=Analysis(levels,if(side=="BUY")"BULLISH" else "BEARISH",rr,at,support,resistance,fibLabel,structure,mtfLabel,"LIVE")
            }
        }
        runOnUiThread{renderAnalysis()}
    }

    private fun renderAnalysis(){
        val l=levels
        signal.text=if(l.side=="WAIT")"WAIT" else l.side+"  •  "+l.confidence+"%"
        signal.setTextColor(if(l.side=="BUY")Color.rgb(45,220,145) else if(l.side=="SELL")Color.rgb(245,80,80) else Color.rgb(240,190,70))
        val p=if(livePrice>0)livePrice else 0.0
        price.text="XAU/USD  "+(if(p>0)fmt(p) else "—")+"  •  "+tf
        info.text="Paper trading • No real orders\n"+
                "TREND: "+analysis.trend+"   RSI: "+fmt(analysis.rsi)+"   ATR: "+fmt(analysis.atr)+"\n"+
                "S/R: "+fmt(analysis.support)+" / "+fmt(analysis.resistance)+"\n"+
                "FIB: "+analysis.fib+"\n"+
                "STRUCTURE: "+analysis.structure+"   MTF: "+analysis.mtf+"\n"+
                if(l.confirmed)"ENTRY "+fmt(l.entry)+"   SL "+fmt(l.sl)+"   TP1 "+fmt(l.tp1)+"   TP2 "+fmt(l.tp2)+"   TP3 "+fmt(l.tp3)
                else l.reason
        chart.invalidate()
    }

    private fun load(){
        if(loading)return
        loading=true
        thread{
            try{
                val main=loadBars(tf,1000)
                val map=mutableMapOf<String,List<Candle>>()
                listOf("5m","15m","1H","4H","1D").forEach{s->try{map[s]=loadBars(s,300)}catch(_:Exception){}}
                val t=try{parseTick(http("https://biquote.io/api/XAUUSD?allowStale=false"))}catch(_:Exception){Tick(0.0,0L)}
                runOnUiThread{
                    candles.clear();candles.addAll(main.takeLast(1000))
                    livePrice=t.price;if(t.price>0)lastTickTime=t.time
                    if(t.price>0&&!candles.isEmpty())applyTick(t)
                    if(candles.size<80){
                        levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"NO VALID CLOSED HISTORY",0,false)
                        info.text="Paper trading • No real orders\nWaiting for at least 80 closed candles.\nSource: Biquote XAU/USD"
                    }else analyze(candles.toList(),map,t.price)
                    chart.invalidate()
                }
            }catch(e:Exception){
                runOnUiThread{signal.text="DATA ERROR • WAIT";signal.setTextColor(Color.rgb(245,150,70));info.text="Paper trading • No real orders\nBiquote data unavailable. No fake candles or levels will be generated."}
            }finally{loading=false}
        }
    }

    private inner class ChartView(c:Context):View(c){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        private fun y(v:Double,lo:Double,span:Double,t:Float,b:Float)=b-((v-lo)/span*(b-t)).toFloat()
        override fun onDraw(c:Canvas){
            c.drawColor(Color.rgb(7,11,17))
            if(candles.isEmpty()){p.color=Color.GRAY;p.textSize=dp(14f);c.drawText("Waiting for real XAU/USD candles…",18f,40f,p);return}
            val cs=candles.takeLast(140);val left=dp(7f);val right=width-dp(68f);val top=dp(8f);val bottom=height-dp(28f)
            var lo=cs.minOf{it.l};var hi=cs.maxOf{it.h}
            val lv=listOf(levels.entry,levels.sl,levels.tp1,levels.tp2,levels.tp3,livePrice).filter{it.isFinite()&&it>0}
            if(lv.isNotEmpty()){lo=min(lo,lv.min());hi=max(hi,lv.max())}
            val pad=max((hi-lo)*0.06,0.5);lo-=pad;hi+=pad;val span=max(hi-lo,0.001)
            p.strokeWidth=1f;p.color=Color.rgb(28,38,52)
            for(i in 0..8){val yy=top+(bottom-top)*i/8f;c.drawLine(left,yy,right,yy,p)}
            for(i in 0..8){val xx=left+(right-left)*i/8f;c.drawLine(xx,top,xx,bottom,p)}
            p.textSize=dp(9f);p.color=Color.LTGRAY
            for(i in 0..7)c.drawText(fmt(hi-(hi-lo)*i/7),right+2f,top+(bottom-top)*i/7f+3,p)
            val sx=(right-left)/cs.size;val cw=max(sx*0.72f,dp(2.5f))
            for(i in cs.indices){
                val z=cs[i];val x=left+(i+0.5f)*sx;p.color=if(z.c>=z.o)Color.rgb(45,210,140)else Color.rgb(240,75,75)
                c.drawLine(x,y(z.h,lo,span,top,bottom),x,y(z.l,lo,span,top,bottom),p)
                val yo=y(z.o,lo,span,top,bottom);val yc=y(z.c,lo,span,top,bottom);c.drawRect(x-cw/2,min(yo,yc),x+cw/2,max(yo,yc).coerceAtLeast(min(yo,yc)+dp(1f)),p)
            }
            if(livePrice>0){drawLine(c,livePrice,"LIVE "+fmt(livePrice),Color.rgb(210,210,210),left,right,top,bottom,lo,span)}
            if(levels.confirmed){
                drawLine(c,levels.entry,"ENTRY "+fmt(levels.entry),Color.rgb(245,205,70),left,right,top,bottom,lo,span)
                drawLine(c,levels.sl,"SL "+fmt(levels.sl),Color.rgb(245,75,75),left,right,top,bottom,lo,span)
                drawLine(c,levels.tp1,"TP1 "+fmt(levels.tp1),Color.rgb(45,210,140),left,right,top,bottom,lo,span)
                drawLine(c,levels.tp2,"TP2 "+fmt(levels.tp2),Color.rgb(45,210,140),left,right,top,bottom,lo,span)
                drawLine(c,levels.tp3,"TP3 "+fmt(levels.tp3),Color.rgb(45,210,140),left,right,top,bottom,lo,span)
                val idx=(levels.signalIndex-(candles.size-cs.size)).coerceIn(0,cs.lastIndex);val z=cs[idx];val x=left+(idx+0.5f)*sx;val yy=y(z.c,lo,span,top,bottom)
                p.color=if(levels.side=="BUY")Color.rgb(45,220,145)else Color.rgb(245,75,75)
                val path=Path()
                if(levels.side=="BUY"){path.moveTo(x,yy-dp(18f));path.lineTo(x-dp(8f),yy-dp(6f));path.lineTo(x+dp(8f),yy-dp(6f))}else{path.moveTo(x,yy+dp(18f));path.lineTo(x-dp(8f),yy+dp(6f));path.lineTo(x+dp(8f),yy+dp(6f))}
                path.close();c.drawPath(path,p)
            }
            p.color=Color.LTGRAY;p.textSize=dp(8.5f);c.drawText(tf+"  •  REAL OHLC  •  LIVE TICK",left+4,bottom+18,p)
        }
        private fun drawLine(c:Canvas,v:Double,s:String,col:Int,left:Float,right:Float,top:Float,bottom:Float,lo:Double,span:Double){
            val yy=y(v,lo,span,top,bottom);if(yy<top||yy>bottom)return;p.color=col;p.strokeWidth=dp(1.1f);c.drawLine(left,yy,right,yy,p);p.textSize=dp(9f);c.drawText(s,right+2,yy-2,p)
        }
    }
}

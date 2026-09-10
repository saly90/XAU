package com.xauai.gold

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chart: GoldChartView
    private lateinit var price: TextView
    private lateinit var status: TextView
    private lateinit var signal: TextView
    private lateinit var details: TextView
    private lateinit var tfRow: LinearLayout
    private var timeframe = 2
    private var candles = listOf<Candle>()
    private var lastPrice = 0.0
    private var current = Signal("WAIT",0.0,0.0,0.0,0.0,0)
    private var busy = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.rgb(5,8,12)
        window.navigationBarColor = Color.rgb(5,8,12)
        buildUi()
        load()
        handler.postDelayed(object: Runnable {
            override fun run() { load(); handler.postDelayed(this,10000) }
        },10000)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5,8,12))
            setPadding(dp(12),dp(8),dp(12),dp(8))
        }
        val top = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val brand = TextView(this).apply {
            text="XAU AI  PRO"; textSize=23f; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        }
        top.addView(brand,LinearLayout.LayoutParams(0,dp(40),1f))
        val live = TextView(this).apply { text="● LIVE"; textSize=11f; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(45,225,145)) }
        top.addView(live,LinearLayout.LayoutParams(dp(62),dp(40)))
        val refresh = TextView(this).apply {
            text="↻"; textSize=29f; gravity=Gravity.CENTER; setTextColor(Color.WHITE); setOnClickListener{load()}
        }
        top.addView(refresh,LinearLayout.LayoutParams(dp(42),dp(40)))
        root.addView(top)

        val pr = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        price = TextView(this).apply { text="XAU/USD  —"; textSize=29f; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        pr.addView(price,LinearLayout.LayoutParams(0,dp(48),1f))
        val paper = TextView(this).apply { text="PAPER"; textSize=10f; gravity=Gravity.CENTER; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(238,190,65)); background=box(Color.rgb(28,23,12),9) }
        pr.addView(paper,LinearLayout.LayoutParams(dp(62),dp(30)))
        root.addView(pr)
        status = TextView(this).apply { text="Connecting to market data…"; textSize=11f; setTextColor(Color.rgb(135,151,170)) }
        root.addView(status,LinearLayout.LayoutParams(-1,dp(23)))

        val modes = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        mode(modes,"SCALP",1); mode(modes,"INTRADAY",5); mode(modes,"SWING",60)
        root.addView(modes,LinearLayout.LayoutParams(-1,dp(36)))

        tfRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        listOf(1,2,3,5,15,30,60).forEach{m->
            val v=TextView(this).apply{
                text=if(m==60)"1H" else "${m}m"; textSize=11f; gravity=Gravity.CENTER; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background=box(if(m==timeframe)Color.rgb(38,82,120)else Color.rgb(16,23,32),10)
                setOnClickListener{timeframe=m;refreshTf();render()}
            }
            v.tag=m
            tfRow.addView(v,LinearLayout.LayoutParams(0,dp(38),1f).apply{setMargins(dp(2),0,dp(2),0)})
        }
        root.addView(tfRow)

        chart=GoldChartView(this)
        root.addView(chart,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(9);bottomMargin=dp(8)})

        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10));background=box(Color.rgb(14,21,30),16)}
        signal=TextView(this).apply{text="WAIT";textSize=22f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(240,190,70))}
        card.addView(signal,LinearLayout.LayoutParams(-1,dp(32)))
        details=TextView(this).apply{text="Entry —    SL —    TP1 —    TP2 —    TP3 —";textSize=10f;setTextColor(Color.rgb(218,225,235));setSingleLine(false)}
        card.addView(details,LinearLayout.LayoutParams(-1,dp(42)))
        val methods=TextView(this).apply{text="PRICE ACTION  •  EMA 9/21/50  •  RSI  •  ATR  •  FIB  •  MTF";textSize=8f;setTextColor(Color.rgb(105,128,151))}
        card.addView(methods)
        root.addView(card,LinearLayout.LayoutParams(-1,dp(91)))
        setContentView(root)
    }

    private fun mode(row:LinearLayout,name:String,m:Int){
        val v=TextView(this).apply{text=name;gravity=Gravity.CENTER;textSize=9f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(160,176,195));background=box(Color.rgb(10,15,23),9);setOnClickListener{timeframe=m;refreshTf();render()}}
        row.addView(v,LinearLayout.LayoutParams(0,dp(32),1f).apply{setMargins(dp(2),0,dp(2),0)})
    }
    private fun refreshTf(){for(i in 0 until tfRow.childCount){val v=tfRow.getChildAt(i);val m=v.tag as Int;v.background=box(if(m==timeframe)Color.rgb(38,82,120)else Color.rgb(16,23,32),10)}}
    private fun box(c:Int,r:Int)=GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun fmt(v:Double)=String.format(Locale.US,"%.2f",v)

    private fun get(url:String,timeout:Int):String{
        val c=(URL(url).openConnection() as HttpURLConnection).apply{
            connectTimeout=timeout;readTimeout=timeout;requestMethod="GET";useCaches=true
            setRequestProperty("User-Agent","Mozilla/5.0 XAU-AI-Android")
            setRequestProperty("Accept","application/json")
        }
        return try{c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}
    }

    private fun load(){
        if(busy)return
        busy=true
        status.text="LIVE DATA  •  loading candles…"
        io.execute{
            try{
                val url="https://query1.finance.yahoo.com/v8/finance/chart/GC=F?range=1d&interval=1m&includePrePost=true&events=div%2Csplits"
                val root=JSONObject(get(url,6000))
                val result=root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val meta=result.getJSONObject("meta")
                val q=result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
                val ts=result.getJSONArray("timestamp")
                val oa=q.optJSONArray("open");val ha=q.optJSONArray("high");val la=q.optJSONArray("low");val ca=q.optJSONArray("close")
                val raw=ArrayList<Candle>()
                for(i in 0 until ts.length()){
                    if(oa==null||ha==null||la==null||ca==null)continue
                    if(oa.isNull(i)||ha.isNull(i)||la.isNull(i)||ca.isNull(i))continue
                    val o=oa.getDouble(i);val h=ha.getDouble(i);val l=la.getDouble(i);val c=ca.getDouble(i)
                    if(o>0&&h>0&&l>0&&c>0)raw.add(Candle(ts.getLong(i)*1000L,o,h,l,c))
                }
                if(raw.size<30)throw Exception("No market candles")
                val built=aggregate(raw,timeframe)
                val lp=meta.optDouble("regularMarketPrice",built.last().close)
                runOnUiThread{
                    candles=built.takeLast(120);lastPrice=if(lp>0)lp:built.last().close;busy=false;render()
                    status.text="● LIVE  •  ${candles.size} candles  •  10s refresh  •  paper trading"
                }
            }catch(e:Exception){
                runOnUiThread{busy=false;status.text="⚠ Market feed unavailable — retrying automatically"}
            }
        }
    }

    private fun aggregate(raw:List<Candle>,m:Int):List<Candle>{
        if(m==1)return raw
        val size=m*60000L;val out=ArrayList<Candle>();var key=Long.MIN_VALUE;var o=0.0;var h=0.0;var l=0.0;var c=0.0
        for(x in raw){val k=(x.t/size)*size;if(k!=key){if(key!=Long.MIN_VALUE)out.add(Candle(key,o,h,l,c));key=k;o=x.open;h=x.high;l=x.low;c=x.close}else{h=max(h,x.high);l=min(l,x.low);c=x.close}}
        if(key!=Long.MIN_VALUE)out.add(Candle(key,o,h,l,c));return out
    }
    private fun ema(v:List<Double>,n:Int):Double{if(v.size<n)return v.last();var e=v.take(n).average();val k=2.0/(n+1);for(i in n until v.size)e=v[i]*k+e*(1-k);return e}
    private fun rsi(v:List<Double>,n:Int):Double{if(v.size<=n)return 50.0;var g=0.0;var d=0.0;for(i in 1..n){val x=v[i]-v[i-1];if(x>=0)g+=x else d-=x};var ag=g/n;var ad=d/n;for(i in n+1 until v.size){val x=v[i]-v[i-1];ag=(ag*(n-1)+max(x,0.0))/n;ad=(ad*(n-1)+max(-x,0.0))/n};return if(ad==0.0)100.0 else 100.0-100.0/(1.0+ag/ad)}
    private fun atr(v:List<Candle>,n:Int):Double{if(v.size<2)return .1;val a=ArrayList<Double>();for(i in 1 until v.size)a.add(max(v[i].high-v[i].low,max(abs(v[i].high-v[i-1].close),abs(v[i].low-v[i-1].close))));return a.takeLast(min(n,a.size)).average().coerceAtLeast(.1)}

    private fun render(){
        if(candles.size<25)return
        val close=candles.map{it.close};val e9=ema(close,9);val e21=ema(close,21);val e50=ema(close,50);val r=rsi(close,14);val a=atr(candles,14);val last=lastPrice.coerceAtLeast(close.last());
        val prev=close[close.size-8];val mom=last-prev;val trend=if(e9>e21&&e21>e50)1 else if(e9<e21&&e21<e50)-1 else 0
        val rangeHi=candles.takeLast(40).maxOf{it.high};val rangeLo=candles.takeLast(40).minOf{it.low};val f382=rangeHi-(rangeHi-rangeLo)*.382;val f618=rangeHi-(rangeHi-rangeLo)*.618
        val fibNear=abs(last-f382)<a*.7||abs(last-f618)<a*.7
        val score=50+trend*16+(if(mom>0)8 else -8)+(if(r>52)6 else if(r<48)-6 else 0)+(if(fibNear)5 else 0)
        val type=when{score>=70&&r<74->"BUY";score<=30&&r>26->"SELL";else->"WAIT"}
        val conf=min(94,max(52,abs(score-50)+52));val risk=max(a*1.25,last*.00035)
        val sl=if(type=="SELL")last+risk else last-risk;val tp1=if(type=="SELL")last-risk else last+risk;val tp2=if(type=="SELL")last-risk*1.7 else last+risk*1.7;val tp3=if(type=="SELL")last-risk*2.4 else last+risk*2.4
        current=Signal(type,last,sl,tp1,tp2,tp3,conf)
        price.text="XAU/USD  $${fmt(last)}"
        signal.text="$type   •   ${conf}% CONFIDENCE"
        signal.setTextColor(if(type=="BUY")Color.rgb(55,220,150)else if(type=="SELL")Color.rgb(255,82,95)else Color.rgb(240,190,70))
        details.text="Entry ${fmt(last)}    SL ${fmt(sl)}\nTP1 ${fmt(tp1)}    TP2 ${fmt(tp2)}    TP3 ${fmt(tp3)}"
        chart.data=candles;chart.signal=current;chart.invalidate()
    }
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy()}

    data class Candle(val t:Long,val open:Double,val high:Double,val low:Double,val close:Double)
    data class Signal(val type:String,val entry:Double,val sl:Double,val tp1:Double,val tp2:Double,val tp3:Double,val conf:Int)

    class GoldChartView(context:android.content.Context):View(context){
        var data=listOf<Candle>();var signal=Signal("WAIT",0.0,0.0,0.0,0.0,0);private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val grid=Paint(Paint.ANTI_ALIAS_FLAG);private val txt=Paint(Paint.ANTI_ALIAS_FLAG)
        init{grid.color=Color.rgb(28,37,48);grid.strokeWidth=1f;txt.color=Color.rgb(132,148,166);txt.textSize=10f}
        override fun onDraw(c:Canvas){
            c.drawColor(Color.rgb(8,13,19));if(data.size<2)return
            val left=10f;val right=width-76f;val top=18f;val bottom=height-28f;val v=data.takeLast(90);val lo=v.minOf{it.low};val hi=v.maxOf{it.high};val pad=(hi-lo)*.08;val minP=lo-pad;val maxP=hi+pad;val range=max(maxP-minP,.01)
            for(i in 0..6){val y=top+(bottom-top)*i/6f;c.drawLine(left,y,right,y,grid);val pv=maxP-range*i/6;c.drawText(String.format(Locale.US,"%.1f",pv),right+5,y+4,txt)}
            val step=(right-left)/v.size.toFloat();val bw=max(2.5f,step*.62f)
            for(i in v.indices){val q=v[i];val x=left+step*i+step/2;val yo=y(q.open,minP,range,top,bottom);val yh=y(q.high,minP,range,top,bottom);val yl=y(q.low,minP,range,top,bottom);val yc=y(q.close,minP,range,top,bottom);val up=q.close>=q.open;p.color=if(up)Color.rgb(45,215,150)else Color.rgb(245,76,91);p.style=Paint.Style.STROKE;p.strokeWidth=1.2f;c.drawLine(x,yh,x,yl,p);p.style=Paint.Style.FILL;c.drawRect(x-bw/2,min(yo,yc),x+bw/2,max(yo,yc)+1.5f,p)}
            if(signal.entry>0){line(c,signal.entry,"ENTRY",Color.rgb(245,190,65),minP,range,left,right,top,bottom);if(signal.type!="WAIT"){line(c,signal.sl,"SL",Color.rgb(245,76,91),minP,range,left,right,top,bottom);line(c,signal.tp1,"TP1",Color.rgb(45,215,150),minP,range,left,right,top,bottom);line(c,signal.tp2,"TP2",Color.rgb(45,215,150),minP,range,left,right,top,bottom);line(c,signal.tp3,"TP3",Color.rgb(45,215,150),minP,range,left,right,top,bottom)}}
            txt.color=Color.rgb(100,118,138);txt.textSize=9f;c.drawText("XAU/USD  •  LIVE CANDLES",left,12f,txt)
        }
        private fun y(v:Double,lo:Double,range:Double,top:Float,bottom:Float)=bottom-(((v-lo)/range)*(bottom-top)).toFloat()
        private fun line(c:Canvas,v:Double,label:String,col:Int,lo:Double,range:Double,left:Float,right:Float,top:Float,bottom:Float){if(v<=0)return;p.color=col;p.strokeWidth=1.4f;p.style=Paint.Style.STROKE;val yy=y(v,lo,range,top,bottom);c.drawLine(left,yy,right,yy,p);p.style=Paint.Style.FILL;txt.color=col;txt.textSize=9f;c.drawText(label,left+4,yy-4,txt)}
    }
}
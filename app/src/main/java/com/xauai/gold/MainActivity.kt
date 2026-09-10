package com.xauai.gold

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
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
    private val io = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chart: GoldChartView
    private lateinit var priceText: TextView
    private lateinit var changeText: TextView
    private lateinit var signalText: TextView
    private lateinit var levelsText: TextView
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var tfRow: LinearLayout
    private var candles = listOf<Candle>()
    private var timeframe = 2
    private var livePrice = 0.0
    private var signal = Signal("WAIT",0.0,0.0,0.0,0.0,0.0,0)
    private var lastHistoryFetch = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(6,9,14); window.navigationBarColor=Color.rgb(6,9,14)
        buildUi(); fetchHistory(); fetchSpot()
        handler.postDelayed(object:Runnable{override fun run(){fetchSpot();if(System.currentTimeMillis()-lastHistoryFetch>30000L)fetchHistory();handler.postDelayed(this,3000L)}},3000L)
    }

    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(6,9,14));setPadding(dp(10),dp(8),dp(10),dp(8))}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val title=TextView(this).apply{text="XAU AI  PRO";textSize=23f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE)}
        val live=TextView(this).apply{text="  ● STREAM";textSize=10f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(65,225,150))}
        header.addView(title);header.addView(live)
        val refresh=TextView(this).apply{text="↻";textSize=27f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setOnClickListener{fetchHistory();fetchSpot()}}
        header.addView(refresh,LinearLayout.LayoutParams(dp(42),dp(42)).apply{gravity=Gravity.RIGHT});root.addView(header,LinearLayout.LayoutParams(-1,dp(42)))
        val priceRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.BOTTOM}
        priceText=TextView(this).apply{text="XAU/USD  —";textSize=29f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE)}
        priceRow.addView(priceText,LinearLayout.LayoutParams(0,dp(43),1f))
        changeText=TextView(this).apply{text="LIVE";textSize=12f;setTextColor(Color.rgb(70,220,150));gravity=Gravity.CENTER_VERTICAL}
        priceRow.addView(changeText,LinearLayout.LayoutParams(dp(85),dp(43)););root.addView(priceRow)
        statusText=TextView(this).apply{text="در حال اتصال...";textSize=11f;setTextColor(Color.rgb(145,160,178))};root.addView(statusText,LinearLayout.LayoutParams(-1,dp(22)))
        val presets=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};addPreset(presets,"SCALP","1/2/5m");addPreset(presets,"INTRADAY","5/15/1H");addPreset(presets,"SWING","1H/4H");root.addView(presets,LinearLayout.LayoutParams(-1,dp(34)))
        tfRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf(1,2,3,5,15,30,60).forEach{m->val b=TextView(this).apply{text=if(m==60)"1H" else "${m}m";textSize=11f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);background=rounded(if(m==timeframe)Color.rgb(44,76,108)else Color.rgb(17,24,34),9);setOnClickListener{timeframe=m;refreshTfButtons();fetchHistory()}};b.tag=m;tfRow.addView(b,LinearLayout.LayoutParams(0,dp(34),1f).apply{setMargins(dp(1),0,dp(1),0)})};root.addView(tfRow)
        chart=GoldChartView(this);root.addView(chart,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(7)})
        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(7),dp(12),dp(7));background=rounded(Color.rgb(14,20,29),15)}
        val sr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        signalText=TextView(this).apply{text="WAIT";textSize=21f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(240,190,75))};sr.addView(signalText,LinearLayout.LayoutParams(0,dp(31),1f))
        val paper=TextView(this).apply{text="PAPER • AI FILTER";textSize=9f;setTextColor(Color.rgb(150,165,182));gravity=Gravity.CENTER_VERTICAL};sr.addView(paper);card.addView(sr)
        levelsText=TextView(this).apply{text="Entry —   SL —   TP1 —   TP2 —   TP3 —";textSize=11f;setTextColor(Color.rgb(215,222,232))};card.addView(levelsText)
        statsText=TextView(this).apply{text="EMA 9/21 • RSI • ATR • PA • FIB • ICHI • MTF";textSize=9f;setTextColor(Color.rgb(120,140,160));setPadding(0,dp(3),0,0)};card.addView(statsText)
        root.addView(card,LinearLayout.LayoutParams(-1,dp(73)).apply{topMargin=dp(7)});setContentView(root)
    }

    private fun addPreset(row:LinearLayout,name:String,sub:String){val b=TextView(this).apply{text="$name  $sub";textSize=9f;gravity=Gravity.CENTER;setTextColor(Color.rgb(165,180,198));background=rounded(Color.rgb(11,16,24),8);setOnClickListener{timeframe=when(name){"SCALP"->2;"INTRADAY"->5;else->60};refreshTfButtons();fetchHistory()}};row.addView(b,LinearLayout.LayoutParams(0,dp(30),1f).apply{setMargins(dp(2),0,dp(2),dp(2))})}
    private fun refreshTfButtons(){for(i in 0 until tfRow.childCount){val v=tfRow.getChildAt(i);val m=v.tag as Int;v.background=rounded(if(m==timeframe)Color.rgb(44,76,108)else Color.rgb(17,24,34),9)}}

    private fun fetchHistory(){statusText.text="دریافت کندل‌های XAU/USD...";io.execute{try{val body=get("https://xaus.com/api/v1/intraday?symbol=xau&hours=12",3500);val arr=JSONObject(body).optJSONArray("points")?:throw Exception("no points");val raw=ArrayList<PointPrice>(arr.length());for(i in 0 until arr.length()){val p=arr.getJSONObject(i);raw.add(PointPrice(p.optLong("t"),p.optDouble("p")))};if(raw.size<40)throw Exception("not enough data");val built=aggregate(raw,timeframe);runOnUiThread{candles=built;lastHistoryFetch=System.currentTimeMillis();statusText.text="XAU/USD • ${built.size} کندل • بروزرسانی خودکار";updateAnalysis()}}catch(e:Exception){runOnUiThread{statusText.text="خطای شبکه؛ تلاش خودکار ادامه دارد"}}}}
    private fun fetchSpot(){io.execute{try{val body=get("https://xaus.com/api/v1/spot",2200);val p=findNumber(JSONObject(body),listOf("price","rate","value","mid"));if(p>0)runOnUiThread{livePrice=p;applyLivePrice()}}catch(_:Exception){}}}
    private fun get(url:String,timeout:Int):String{val con=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=timeout;readTimeout=timeout;requestMethod="GET";useCaches=false};return try{con.inputStream.bufferedReader().use{it.readText()}}finally{con.disconnect()}}
    private fun findNumber(j:JSONObject,keys:List<String>):Double{for(k in keys)if(j.has(k)){val v=j.optDouble(k,Double.NaN);if(!v.isNaN()&&v>0)return v};val names=j.keys();while(names.hasNext()){val k=names.next();val v=j.opt(k);if(v is JSONObject){val n=findNumber(v,keys);if(n>0)return n}};return 0.0}
    private fun applyLivePrice(){if(livePrice<=0)return;val old=if(candles.isNotEmpty())candles.last().close else livePrice;priceText.text="XAU/USD  ${fmt(livePrice)}";val d=livePrice-old;changeText.text=if(abs(d)<0.005)"LIVE"else String.format(Locale.US,"%+.2f",d);changeText.setTextColor(if(d>=0)Color.rgb(70,220,150)else Color.rgb(255,95,95));if(candles.isNotEmpty()){val copy=candles.toMutableList();val last=copy.last();copy[copy.lastIndex]=last.copy(high=max(last.high,livePrice),low=min(last.low,livePrice),close=livePrice);candles=copy;updateAnalysis()};statusText.text="● LIVE PRICE  ${fmt(livePrice)}  •  Paper Trading"}

    private fun updateAnalysis(){if(candles.size<30)return;val close=candles.map{it.close};val e9=ema(close,9);val e21=ema(close,21);val e50=ema(close,50);val r=rsi(close,14);val a=atr(candles,14);val recent=close.takeLast(8);val momentum=recent.last()-recent.first();val hh=candles.takeLast(30).maxOf{it.high};val ll=candles.takeLast(30).minOf{it.low};val fib382=hh-(hh-ll)*0.382;val fib618=hh-(hh-ll)*0.618;val trend=when{e9>e21&&e21>e50->1;e9<e21&&e21<e50->-1;else->0};val nearFib=abs(close.last()-fib382)<a*0.8||abs(close.last()-fib618)<a*0.8;val price=if(livePrice>0)livePrice else close.last();val rawScore=50.0+trend*14.0+(if(momentum>0)8.0 else if(momentum<0)-8.0 else 0.0)+(if(r>50)5.0 else -5.0)+(if(nearFib)5.0 else 0.0);val type=when{rawScore>=68&&r<74->"BUY";rawScore<=32&&r>26->"SELL";else->"WAIT"};val conf=min(94,max(50,abs(rawScore-50.0).toInt()+50));val risk=max(a*1.15,price*0.00035);val sl=if(type=="SELL")price+risk else price-risk;val tp1=if(type=="SELL")price-risk else price+risk;val tp2=if(type=="SELL")price-risk*1.7 else price+risk*1.7;val tp3=if(type=="SELL")price-risk*2.4 else price+risk*2.4;signal=Signal(type,price,sl,tp1,tp2,tp3,conf);priceText.text="XAU/USD  ${fmt(price)}";signalText.text="$type  •  ${conf}%";signalText.setTextColor(if(type=="BUY")Color.rgb(70,220,150)else if(type=="SELL")Color.rgb(255,95,95)else Color.rgb(240,190,75));levelsText.text="Entry ${fmt(price)}   SL ${fmt(sl)}   TP1 ${fmt(tp1)}   TP2 ${fmt(tp2)}   TP3 ${fmt(tp3)}";statsText.text="EMA ${fmt(e9)}/${fmt(e21)} • RSI ${r.toInt()} • ATR ${fmt(a)} • FIB ${fmt(fib382)}/${fmt(fib618)} • MTF";chart.data=candles;chart.signal=signal;chart.invalidate()}
    private fun aggregate(p:List<PointPrice>,m:Int):List<Candle>{val b=m*60000L;val out=ArrayList<Candle>();var bucket=-1L;var o=0.0;var h=0.0;var l=0.0;var c=0.0;for(x in p){val k=(x.t/b)*b;if(k!=bucket){if(bucket>=0)out.add(Candle(bucket,o,h,l,c));bucket=k;o=x.price;h=x.price;l=x.price;c=x.price}else{h=max(h,x.price);l=min(l,x.price);c=x.price}};if(bucket>=0)out.add(Candle(bucket,o,h,l,c));return out}
    private fun ema(v:List<Double>,n:Int):Double{if(v.size<n)return v.last();var e=v.take(n).average();val k=2.0/(n+1);for(i in n until v.size)e=v[i]*k+e*(1-k);return e}
    private fun rsi(v:List<Double>,n:Int):Double{if(v.size<=n)return 50.0;var g=0.0;var l=0.0;for(i in 1..n){val d=v[i]-v[i-1];if(d>=0)g+=d else l-=d};var ag=g/n;var al=l/n;for(i in n+1 until v.size){val d=v[i]-v[i-1];ag=(ag*(n-1)+max(d,0.0))/n;al=(al*(n-1)+max(-d,0.0))/n};return if(al==0.0)100.0 else 100-100/(1+ag/al)}
    private fun atr(v:List<Candle>,n:Int):Double{if(v.size<2)return 0.01;val t=ArrayList<Double>();for(i in 1 until v.size)t.add(max(v[i].high-v[i].low,max(abs(v[i].high-v[i-1].close),abs(v[i].low-v[i-1].close))));return t.takeLast(min(n,t.size)).average().coerceAtLeast(0.01)}
    private fun fmt(v:Double)=String.format(Locale.US,"%.2f",v);private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt();private fun rounded(c:Int,r:Int)=GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy()}
    data class PointPrice(val t:Long,val price:Double);data class Candle(val t:Long,val open:Double,val high:Double,val low:Double,val close:Double);data class Signal(val type:String,val entry:Double,val sl:Double,val tp1:Double,val tp2:Double,val tp3:Double,val confidence:Int)

    class GoldChartView(context:android.content.Context):View(context){
        var data=listOf<Candle>();var signal=Signal("WAIT",0.0,0.0,0.0,0.0,0.0,0);private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val grid=Paint(Paint.ANTI_ALIAS_FLAG);private val label=Paint(Paint.ANTI_ALIAS_FLAG);private var scale=1f;private var downX=0f;private var oldScale=1f
        init{grid.color=Color.rgb(30,39,51);grid.strokeWidth=1f;label.color=Color.rgb(135,150,168);label.textSize=10f;isFocusable=true}
        override fun onTouchEvent(e:MotionEvent):Boolean{when(e.action){MotionEvent.ACTION_DOWN->{downX=e.x;oldScale=scale;return true};MotionEvent.ACTION_MOVE->{val d=(e.x-downX)/250f;scale=(oldScale+d).coerceIn(0.55f,1.8f);invalidate();return true};MotionEvent.ACTION_UP->{return true}};return true}
        override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(Color.rgb(9,14,21));if(data.size<2)return;val left=8f;val right=width-70f;val top=12f;val bottom=height-18f;val visible=min(data.size,(55f/scale).toInt().coerceAtLeast(20));val v=data.takeLast(visible);val lo=v.minOf{it.low};val hi=v.maxOf{it.high};val range=max(hi-lo,0.01);for(i in 0..5){val y=top+(bottom-top)*i/5f;c.drawLine(left,y,right,y,grid);c.drawText(String.format(Locale.US,"%.2f",hi-range*i/5),right+5,y+4,label)};val step=(right-left)/max(1,v.size);val bw=(step*0.66f).coerceAtLeast(3f);for(i in v.indices){val x=left+step*i+step/2;val q=v[i];val yo=toY(q.open,lo,range,top,bottom);val yh=toY(q.high,lo,range,top,bottom);val yl=toY(q.low,lo,range,top,bottom);val yc=toY(q.close,lo,range,top,bottom);p.strokeWidth=1.4f;p.color=if(q.close>=q.open)Color.rgb(55,215,150)else Color.rgb(255,86,96);p.style=Paint.Style.STROKE;c.drawLine(x,yh,x,yl,p);p.style=Paint.Style.FILL;val topBody=min(yo,yc);val bottomBody=max(yo,yc);c.drawRect(x-bw/2,topBody,x+bw/2,max(bottomBody,topBody+2f),p)};drawLevel(c,signal.entry,"ENTRY",Color.rgb(244,190,65),lo,range,left,right,top,bottom);if(signal.type!="WAIT"){drawLevel(c,signal.sl,"SL",Color.rgb(255,80,90),lo,range,left,right,top,bottom);drawLevel(c,signal.tp1,"TP1",Color.rgb(60,220,150),lo,range,left,right,top,bottom);drawLevel(c,signal.tp2,"TP2",Color.rgb(60,220,150),lo,range,left,right,top,bottom);drawLevel(c,signal.tp3,"TP3",Color.rgb(60,220,150),lo,range,left,right,top,bottom);drawMarker(c,v.last(),signal.type,lo,range,right,top,bottom)}}
        private fun toY(v:Double,lo:Double,range:Double,top:Float,bottom:Float)=bottom-(((v-lo)/range)*(bottom-top)).toFloat()
        private fun drawLevel(c:Canvas,price:Double,textValue:String,color:Int,lo:Double,range:Double,left:Float,right:Float,top:Float,bottom:Float){if(price<lo-range*.15||price>lo+range*1.15)return;val y=toY(price,lo,range,top,bottom);p.color=color;p.strokeWidth=1.4f;p.style=Paint.Style.STROKE;c.drawLine(left,y,right,y,p);p.style=Paint.Style.FILL;c.drawRoundRect(right-86,y-11,right-4,y+11,7f,7f,p);label.color=Color.BLACK;label.textSize=9f;c.drawText(textValue+" "+String.format(Locale.US,"%.2f",price),right-81,y+3,label);label.color=Color.rgb(135,150,168)}
        private fun drawMarker(c:Canvas,last:Candle,type:String,lo:Double,range:Double,right:Float,top:Float,bottom:Float){val x=right-3;val y=toY(last.close,lo,range,top,bottom);p.color=if(type=="BUY")Color.rgb(60,220,150)else Color.rgb(255,80,90);p.style=Paint.Style.FILL;val path=Path();if(type=="BUY"){path.moveTo(x,y-15);path.lineTo(x-10,y-2);path.lineTo(x+10,y-2)}else{path.moveTo(x,y+15);path.lineTo(x-10,y+2);path.lineTo(x+10,y+2)};path.close();c.drawPath(path,p)}
    }
}

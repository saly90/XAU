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
    private lateinit var priceText: TextView
    private lateinit var signalText: TextView
    private lateinit var levelsText: TextView
    private lateinit var statusText: TextView
    private var points = listOf<PointPrice>()
    private var timeframe = 5
    private var signal = Signal("WAIT", 0.0, 0.0, 0.0, 0.0, 0.0, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 11, 17)
        window.navigationBarColor = Color.rgb(7, 11, 17)
        buildUi()
        refreshMarket()
        handler.postDelayed(object : Runnable {
            override fun run() { refreshMarket(); handler.postDelayed(this, 15000) }
        }, 15000)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 11, 17))
            setPadding(dp(12), dp(10), dp(12), dp(8))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply { text = "XAU AI"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        val live = TextView(this).apply { text = "  ● LIVE"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(70,220,150)) }
        header.addView(title); header.addView(live)
        val refresh = TextView(this).apply { text = "↻"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setOnClickListener { refreshMarket() } }
        header.addView(refresh, LinearLayout.LayoutParams(dp(45), dp(45)).apply { gravity = Gravity.RIGHT })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(45)))

        priceText = TextView(this).apply { text = "XAU/USD  —"; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0,dp(5),0,0) }
        root.addView(priceText)
        statusText = TextView(this).apply { text = "اتصال به بازار..."; textSize = 12f; setTextColor(Color.LTGRAY) }
        root.addView(statusText, LinearLayout.LayoutParams(-1, dp(25)))

        val tf = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(2,5,15,30,60).forEach { m ->
            val b = TextView(this).apply {
                text = if (m == 60) "1H" else "${m}m"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = rounded(if (m == timeframe) Color.rgb(39,66,94) else Color.rgb(20,28,39), 10)
                setOnClickListener { timeframe=m; updateAnalysis() }
            }
            tf.addView(b, LinearLayout.LayoutParams(0,dp(38),1f).apply { setMargins(dp(2),0,dp(2),0) })
        }
        root.addView(tf)
        chart = GoldChartView(this)
        root.addView(chart, LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=dp(8) })

        val card = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)); background=rounded(Color.rgb(16,24,34),16) }
        signalText = TextView(this).apply { text="WAIT"; textSize=22f; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(240,190,75)) }
        card.addView(signalText)
        levelsText = TextView(this).apply { text="Entry —   SL —   TP1 —   TP2 —   TP3 —"; textSize=12f; setTextColor(Color.rgb(210,218,228)); setPadding(0,dp(3),0,0) }
        card.addView(levelsText)
        root.addView(card, LinearLayout.LayoutParams(-1,dp(76)).apply { topMargin=dp(8) })
        setContentView(root)
    }

    private fun refreshMarket() {
        statusText.text = "دریافت سریع XAU/USD..."
        io.execute {
            try {
                val con = (URL("https://xaus.com/api/v1/intraday?symbol=xau&hours=6").openConnection() as HttpURLConnection).apply { connectTimeout=2500; readTimeout=3000; requestMethod="GET" }
                val body = con.inputStream.bufferedReader().use { it.readText() }; con.disconnect()
                val arr = JSONObject(body).optJSONArray("points") ?: throw Exception("no data")
                val out=ArrayList<PointPrice>(arr.length())
                for(i in 0 until arr.length()) { val p=arr.getJSONObject(i); out.add(PointPrice(p.optLong("t"),p.optDouble("p"))) }
                if(out.size<30) throw Exception("few points")
                points=out
                runOnUiThread { updateAnalysis() }
            } catch(e:Exception) { runOnUiThread { statusText.text="اتصال ناموفق؛ تلاش بعدی خودکار" } }
        }
    }

    private fun updateAnalysis() {
        if(points.isEmpty()) return
        val candles=aggregate(points,timeframe); if(candles.size<25) return
        val close=candles.map{it.close}; val fast=ema(close,9); val slow=ema(close,21); val r=rsi(close,14); val a=atr(candles,14)
        val momentum=close.last()-close[max(0,close.size-5)]
        val type=when { fast>slow && momentum>0 && r in 48.0..72.0 -> "BUY"; fast<slow && momentum<0 && r in 28.0..52.0 -> "SELL"; else -> "WAIT" }
        val entry=close.last(); val risk=max(a*1.10,entry*0.00030)
        val sl=if(type=="SELL") entry+risk else entry-risk
        val tp1=if(type=="SELL") entry-risk else entry+risk
        val tp2=if(type=="SELL") entry-risk*1.7 else entry+risk*1.7
        val tp3=if(type=="SELL") entry-risk*2.4 else entry+risk*2.4
        val conf=min(96,(52.0+abs(fast-slow)/max(a,0.01)*10+abs(r-50)*0.30)).toInt()
        signal=Signal(type,entry,sl,tp1,tp2,tp3,conf)
        priceText.text="XAU/USD  ${fmt(entry)}"
        signalText.text="$type  •  ${conf}%"
        signalText.setTextColor(if(type=="BUY") Color.rgb(70,220,150) else if(type=="SELL") Color.rgb(255,95,95) else Color.rgb(240,190,75))
        levelsText.text="Entry ${fmt(entry)}   SL ${fmt(sl)}   TP1 ${fmt(tp1)}   TP2 ${fmt(tp2)}   TP3 ${fmt(tp3)}"
        statusText.text="اسکالپ ${tfLabel()}  •  RSI ${r.toInt()}  •  ATR ${fmt(a)}  •  Paper Trading"
        chart.data=candles; chart.signal=signal; chart.invalidate()
    }

    private fun tfLabel()=if(timeframe==60)"1H" else "${timeframe}m"
    private fun fmt(v:Double)=String.format(Locale.US,"%.2f",v)
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun rounded(c:Int,r:Int)=GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    private fun aggregate(p:List<PointPrice>,m:Int):List<Candle>{ if(m==2)return p.map{Candle(it.t,it.price,it.price,it.price,it.price)}; val b=m*60000L; val o=ArrayList<Candle>(); var s=-1L; var op=0.0;var h=0.0;var l=0.0;var c=0.0; for(x in p){val k=(x.t/b)*b;if(k!=s){if(s>=0)o.add(Candle(s,op,h,l,c));s=k;op=x.price;h=x.price;l=x.price;c=x.price}else{h=max(h,x.price);l=min(l,x.price);c=x.price}};if(s>=0)o.add(Candle(s,op,h,l,c));return o }
    private fun ema(v:List<Double>,n:Int):Double{var e=v.take(n).average();val k=2.0/(n+1);for(i in n until v.size)e=v[i]*k+e*(1-k);return e}
    private fun rsi(v:List<Double>,n:Int):Double{var g=0.0;var l=0.0;for(i in 1..n){val d=v[i]-v[i-1];if(d>=0)g+=d else l-=d};var ag=g/n;var al=l/n;for(i in n+1 until v.size){val d=v[i]-v[i-1];ag=(ag*(n-1)+max(d,0.0))/n;al=(al*(n-1)+max(-d,0.0))/n};return if(al==0.0)100.0 else 100-100/(1+ag/al)}
    private fun atr(v:List<Candle>,n:Int):Double{val t=ArrayList<Double>();for(i in 1 until v.size)t.add(max(v[i].high-v[i].low,max(abs(v[i].high-v[i-1].close),abs(v[i].low-v[i-1].close))));return t.takeLast(n).average()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy()}

    data class PointPrice(val t:Long,val price:Double)
    data class Candle(val t:Long,val open:Double,val high:Double,val low:Double,val close:Double)
    data class Signal(val type:String,val entry:Double,val sl:Double,val tp1:Double,val tp2:Double,val tp3:Double,val confidence:Int)

    class GoldChartView(context:android.content.Context):View(context){
        var data=listOf<Candle>();var signal=Signal("WAIT",0.0,0.0,0.0,0.0,0.0,0)
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val grid=Paint(Paint.ANTI_ALIAS_FLAG);private val text=Paint(Paint.ANTI_ALIAS_FLAG)
        init{grid.color=Color.rgb(35,45,58);grid.strokeWidth=1f;text.color=Color.rgb(150,165,182);text.textSize=11f}
        override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(Color.rgb(10,15,22));if(data.size<2)return;val left=8f;val right=width-62f;val top=14f;val bottom=height-12f;val v=data.takeLast(80);val lo=v.minOf{it.low};val hi=v.maxOf{it.high};val range=max(hi-lo,0.01);for(i in 0..4){val y=top+(bottom-top)*i/4f;c.drawLine(left,y,right,y,grid);c.drawText(String.format(Locale.US,"%.2f",hi-range*i/4),right+4,y+4,text)};val step=(right-left)/max(1,v.size-1);p.style=Paint.Style.STROKE;p.strokeWidth=2.5f; p.color=Color.rgb(70,180,255);val path=Path();v.forEachIndexed{i,x->val px=left+i*step;val py=bottom-((x.close-lo)/range*(bottom-top)).toFloat();if(i==0)path.moveTo(px,py)else path.lineTo(px,py)};c.drawPath(path,p);drawLevel(c,signal.entry,"ENTRY",Color.rgb(245,190,65),lo,range,left,right,top,bottom);if(signal.type!="WAIT"){drawLevel(c,signal.sl,"SL",Color.rgb(255,80,80),lo,range,left,right,top,bottom);drawLevel(c,signal.tp1,"TP1",Color.rgb(70,220,150),lo,range,left,right,top,bottom);drawLevel(c,signal.tp2,"TP2",Color.rgb(70,220,150),lo,range,left,right,top,bottom);drawLevel(c,signal.tp3,"TP3",Color.rgb(70,220,150),lo,range,left,right,top,bottom)};p.style=Paint.Style.FILL;p.color=Color.WHITE;val last=v.last();val y=bottom-((last.close-lo)/range*(bottom-top)).toFloat();c.drawCircle(right,y,5f,p)}
        private fun drawLevel(c:Canvas,price:Double,label:String,color:Int,lo:Double,range:Double,left:Float,right:Float,top:Float,bottom:Float){if(price<lo-range*.2||price>lo+range*1.2)return;val y=bottom-((price-lo)/range*(bottom-top)).toFloat();p.color=color;p.strokeWidth=1.5f;p.style=Paint.Style.STROKE;c.drawLine(left,y,right,y,p);p.style=Paint.Style.FILL;c.drawRoundRect(right-82,y-12,right-4,y+12,8f,8f,p);text.color=Color.BLACK;text.textSize=10f;c.drawText(label+" "+String.format(Locale.US,"%.2f",price),right-77,y+4,text)}
    }
}
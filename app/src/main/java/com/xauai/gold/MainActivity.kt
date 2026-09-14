package com.xauai.gold

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double)

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chart: ChartView
    private lateinit var price: TextView
    private lateinit var signal: TextView
    private lateinit var info: TextView
    private var candles: List<Candle> = emptyList()
    private var tf = 1
    private var busy = false
    private var entry = 0.0
    private var sl = 0.0
    private var tp1 = 0.0
    private var tp2 = 0.0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.rgb(5, 8, 12)
        window.navigationBarColor = Color.rgb(5, 8, 12)
        buildUi()
        load()
        handler.postDelayed(object : Runnable {
            override fun run() { load(); handler.postDelayed(this, 10000) }
        }, 10000)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(5, 8, 12))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "XAU AI PRO"; textSize = 23f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        }
        head.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        val live = TextView(this).apply {
            text = "● LIVE"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.rgb(45,225,145))
        }
        head.addView(live, LinearLayout.LayoutParams(dp(65), dp(42)))
        val refresh = TextView(this).apply {
            text = "↻"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setOnClickListener { load() }
        }
        head.addView(refresh, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(head)

        price = TextView(this).apply {
            text = "XAU/USD  —"; textSize = 29f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        }
        root.addView(price, LinearLayout.LayoutParams(-1, dp(48)))

        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf(1,2,3,5,15,30,60).forEach { m ->
            val v = TextView(this).apply {
                text = if (m == 60) "1H" else "${m}m"; textSize = 10f; gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                setBackgroundColor(if (m == tf) Color.rgb(38,82,120) else Color.rgb(16,23,32))
                setOnClickListener { tf = m; load() }
            }
            tabs.addView(v, LinearLayout.LayoutParams(0, dp(36), 1f).apply { setMargins(dp(2),0,dp(2),0) })
        }
        root.addView(tabs)

        chart = ChartView()
        root.addView(chart, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })

        signal = TextView(this).apply {
            text = "WAIT"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(240,190,70))
        }
        root.addView(signal, LinearLayout.LayoutParams(-1, dp(34)))
        info = TextView(this).apply {
            text = "Connecting to XAU/USD…"; textSize = 10f; setTextColor(Color.rgb(160,175,195))
        }
        root.addView(info, LinearLayout.LayoutParams(-1, dp(45)))
        setContentView(root)
    }

    private fun request(url: String, timeout: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout; c.useCaches = false
        c.setRequestProperty("User-Agent", "XAU-AI-PRO/1.0")
        return try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun load() {
        if (busy) return
        busy = true
        info.text = "LIVE DATA • loading XAU/USD…"
        io.execute {
            try {
                val now = System.currentTimeMillis() / 1000L
                val j = JSONObject(request("https://xaus.com/api/v1/intraday?symbol=xau&hours=48&fresh=$now", 8000))
                val a = j.getJSONArray("points")
                val raw = ArrayList<Candle>()
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i); val ts = p.getLong("t"); val t = if (ts < 10000000000L) ts * 1000L else ts
                    val v = p.getDouble("p"); if (v > 0.0) raw.add(Candle(t,v,v,v,v))
                }
                val built = aggregate(raw, tf)
                if (built.size < 15) error("Not enough data")
                val spot = j.optDouble("spot_usd_oz", built.last().c)
                publish(built, spot, "● XAU/USD SPOT • 10s refresh • PAPER")
            } catch (_: Exception) {
                yahoo()
            }
        }
    }

    private fun yahoo() {
        try {
            val j = JSONObject(request("https://query2.finance.yahoo.com/v8/finance/chart/GC=F?range=1d&interval=1m&includePrePost=true", 7000))
            val r = j.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val ts = r.getJSONArray("timestamp")
            val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val oa=q.getJSONArray("open"); val ha=q.getJSONArray("high"); val la=q.getJSONArray("low"); val ca=q.getJSONArray("close")
            val raw=ArrayList<Candle>()
            for(i in 0 until ts.length()) if(!oa.isNull(i)&&!ha.isNull(i)&&!la.isNull(i)&&!ca.isNull(i)) raw.add(Candle(ts.getLong(i)*1000L,oa.getDouble(i),ha.getDouble(i),la.getDouble(i),ca.getDouble(i)))
            val built=aggregate(raw,tf); if(built.size<15) error("No data")
            publish(built, r.getJSONObject("meta").optDouble("regularMarketPrice",built.last().c), "● FALLBACK • GOLD FUTURES PROXY • PAPER")
        } catch (_: Exception) {
            runOnUiThread { busy=false; info.text="⚠ Market data unavailable • tap ↻" }
        }
    }

    private fun publish(built: List<Candle>, spot: Double, text: String) {
        runOnUiThread {
            candles=built.takeLast(140); busy=false; analyze(spot); info.text=text; chart.invalidate()
        }
    }

    private fun aggregate(raw: List<Candle>, minutes: Int): List<Candle> {
        val size=minutes.toLong()*60000L; val out=ArrayList<Candle>(); var key=Long.MIN_VALUE
        var o=0.0; var h=0.0; var l=0.0; var c=0.0
        for(x in raw){ val k=(x.t/size)*size; if(k!=key){ if(key!=Long.MIN_VALUE) out.add(Candle(key,o,h,l,c)); key=k;o=x.o;h=x.h;l=x.l;c=x.c } else { h=max(h,x.h); l=min(l,x.l); c=x.c } }
        if(key!=Long.MIN_VALUE) out.add(Candle(key,o,h,l,c)); return out
    }

    private fun ema(v: List<Double>, n: Int): Double {
        if(v.isEmpty()) return 0.0; if(v.size<n) return v.last(); var e=v.take(n).average(); val k=2.0/(n+1.0)
        for(i in n until v.size) e=v[i]*k+e*(1.0-k); return e
    }

    private fun rsi(v: List<Double>): Double {
        if(v.size<15) return 50.0; var g=0.0; var d=0.0
        for(i in 1..14){ val x=v[i]-v[i-1]; if(x>=0) g+=x else d-=x }
        var ag=g/14.0; var ad=d/14.0
        for(i in 15 until v.size){ val x=v[i]-v[i-1]; ag=(ag*13.0+max(x,0.0))/14.0; ad=(ad*13.0+max(-x,0.0))/14.0 }
        return if(ad==0.0) 100.0 else 100.0-100.0/(1.0+ag/ad)
    }

    private fun analyze(p: Double) {
        if(candles.size<15) return
        val v=candles.map{it.c}; val e9=ema(v,9); val e21=ema(v,21); val e50=ema(v,50); val r=rsi(v)
        val hi=candles.takeLast(min(40,candles.size)).maxOf{it.h}; val lo=candles.takeLast(min(40,candles.size)).minOf{it.l}
        val range=max(hi-lo,0.1); val risk=max(range*0.08,p*0.00035)
        val score=(if(e9>e21) 1 else -1)+(if(e21>e50) 1 else -1)+(if(r>52) 1 else if(r<48) -1 else 0)
        val side=if(score>=3&&r<74)"BUY" else if(score<=-3&&r>26)"SELL" else "WAIT"
        entry=p; sl=if(side=="SELL")p+risk else p-risk; tp1=if(side=="SELL")p-risk else p+risk; tp2=if(side=="SELL")p-risk*1.7 else p+risk*1.7
        val conf=min(94,max(52,52+abs(score)*12))
        price.text="XAU/USD  ${fmt(p)}"; signal.text="$side  $conf%"; signal.setTextColor(if(side=="BUY")Color.rgb(45,220,145) else if(side=="SELL")Color.rgb(245,85,85) else Color.rgb(240,190,70))
        info.text += "\nEntry ${fmt(entry)} • SL ${fmt(sl)} • TP1 ${fmt(tp1)} • TP2 ${fmt(tp2)}"
    }

    private inner class ChartView: View(this@MainActivity) {
        private val grid=Paint(1); private val wick=Paint(1); private val body=Paint(1); private val txt=Paint(1)
        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(7,11,17)); if(candles.isEmpty()){txt.color=Color.GRAY;txt.textSize=dp(14).toFloat();c.drawText("Waiting for market data…",dp(18).toFloat(),dp(40).toFloat(),txt);return}
            val cs=candles.takeLast(80); val left=dp(8).toFloat(); val right=(width-dp(50)).toFloat(); val top=dp(10).toFloat(); val bottom=(height-dp(14)).toFloat()
            var lo=cs.minOf{it.l}; var hi=cs.maxOf{it.h}; val pad=(hi-lo)*0.08; lo-=pad; hi+=pad; val span=max(hi-lo,0.0001)
            grid.color=Color.rgb(25,35,48); grid.strokeWidth=1f
            for(i in 0..5){ val yy=top+(bottom-top)*i.toFloat()/5f; c.drawLine(left,yy,right,yy,grid) }
            fun py(v:Double):Float=(bottom-(v-lo)/span*(bottom-top)).toFloat()
            val step=(right-left)/cs.size.toFloat(); val bw=max(2f,step*0.62f)
            for(i in cs.indices){ val z=cs[i]; val x=left+step*(i.toFloat()+0.5f); val up=z.c>=z.o; val col=if(up)Color.rgb(45,210,145)else Color.rgb(240,80,90); wick.color=col; wick.strokeWidth=dp(1).toFloat(); c.drawLine(x,py(z.h),x,py(z.l),wick); body.color=col; val y1=py(z.o); val y2=py(z.c); val topBody=min(y1,y2); val bottomBody=max(y1,y2); c.drawRect(x-bw/2f,topBody,x+bw/2f,max(bottomBody,topBody+dp(1).toFloat()),body) }
            level(c,entry,Color.rgb(235,190,60),"ENTRY",lo,span,top,bottom,left,right); level(c,sl,Color.rgb(245,80,90),"SL",lo,span,top,bottom,left,right); level(c,tp1,Color.rgb(45,210,145),"TP1",lo,span,top,bottom,left,right); level(c,tp2,Color.rgb(45,180,145),"TP2",lo,span,top,bottom,left,right)
            txt.color=Color.rgb(160,175,195); txt.textSize=dp(9).toFloat(); c.drawText(fmt(hi),right+dp(3).toFloat(),top+dp(8).toFloat(),txt); c.drawText(fmt(lo),right+dp(3).toFloat(),bottom,txt)
        }
        private fun level(c:Canvas,v:Double,col:Int,label:String,lo:Double,span:Double,top:Float,bottom:Float,left:Float,right:Float){if(v<=0)return;val yy=(bottom-(v-lo)/span*(bottom-top)).toFloat();val p=Paint(1);p.color=col;p.strokeWidth=dp(1).toFloat();c.drawLine(left,yy,right,yy,p);val t=Paint(1);t.color=col;t.textSize=dp(8).toFloat();c.drawText(label,right+dp(3).toFloat(),yy-dp(2).toFloat(),t)}
    }

    override fun onDestroy(){ super.onDestroy(); io.shutdownNow(); handler.removeCallbacksAndMessages(null) }
}

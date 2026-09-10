package com.xauai.gold

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
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

data class Candle(val t: Long, val open: Double, val high: Double, val low: Double, val close: Double)
data class Signal(val side: String, val entry: Double, val sl: Double, val tp1: Double, val tp2: Double, val tp3: Double, val conf: Double)

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chart: GoldChartView
    private lateinit var price: TextView
    private lateinit var status: TextView
    private lateinit var signal: TextView
    private lateinit var details: TextView
    private lateinit var tfRow: LinearLayout
    private var timeframe = 1
    private var candles = emptyList<Candle>()
    private var lastPrice = 0.0
    private var busy = false
    private var current = Signal("WAIT", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(5, 8, 12)
        window.navigationBarColor = Color.rgb(5, 8, 12)
        buildUi()
        load()
        handler.postDelayed(object : Runnable {
            override fun run() { load(); handler.postDelayed(this, 10000) }
        }, 10000)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun box(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(5, 8, 12))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = TextView(this).apply { text = "XAU AI  PRO"; textSize = 23f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        top.addView(brand, LinearLayout.LayoutParams(0, dp(42), 1f))
        val live = TextView(this).apply { text = "● LIVE"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.rgb(45,225,145)) }
        top.addView(live, LinearLayout.LayoutParams(dp(62), dp(42)))
        val refresh = TextView(this).apply { text = "↻"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setOnClickListener { load() } }
        top.addView(refresh, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(top)

        val pr = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        price = TextView(this).apply { text = "XAU/USD  —"; textSize = 29f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        pr.addView(price, LinearLayout.LayoutParams(0, dp(48), 1f))
        val paper = TextView(this).apply { text = "PAPER"; textSize = 10f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(238,190,65)); background = box(Color.rgb(28,23,12), 9) }
        pr.addView(paper, LinearLayout.LayoutParams(dp(62), dp(30)))
        root.addView(pr)
        status = TextView(this).apply { text = "Connecting to XAU/USD…"; textSize = 11f; setTextColor(Color.rgb(135,151,170)) }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(23)))

        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        mode(modes, "SCALP", 1); mode(modes, "INTRADAY", 5); mode(modes, "SWING", 60)
        root.addView(modes, LinearLayout.LayoutParams(-1, dp(36)))

        tfRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf(1,2,3,5,15,30,60).forEach { m ->
            val v = TextView(this).apply {
                text = if (m == 60) "1H" else "${m}m"; textSize = 11f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background = box(if (m == timeframe) Color.rgb(38,82,120) else Color.rgb(16,23,32), 10)
                setOnClickListener { timeframe = m; refreshTf(); rebuild() }
            }
            v.tag = m
            tfRow.addView(v, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(2),0,dp(2),0) })
        }
        root.addView(tfRow)

        chart = GoldChartView(this)
        root.addView(chart, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(9); bottomMargin = dp(8) })

        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14),dp(10),dp(14),dp(10)); background = box(Color.rgb(14,21,30),16) }
        signal = TextView(this).apply { text = "WAIT"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(240,190,70)) }
        card.addView(signal, LinearLayout.LayoutParams(-1, dp(32)))
        details = TextView(this).apply { text = "Entry —   SL —   TP1 —   TP2 —   TP3 —"; textSize = 10f; setTextColor(Color.rgb(218,225,235)) }
        card.addView(details, LinearLayout.LayoutParams(-1, dp(42)))
        val methods = TextView(this).apply { text = "PRICE ACTION  •  EMA 9/21/50  •  RSI  •  ATR  •  FIB  •  MTF"; textSize = 8f; setTextColor(Color.rgb(105,128,151)) }
        card.addView(methods)
        root.addView(card, LinearLayout.LayoutParams(-1, dp(91)))
        setContentView(root)
    }

    private fun mode(row: LinearLayout, name: String, tf: Int) {
        val v = TextView(this).apply {
            text = name; gravity = Gravity.CENTER; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(160,176,195)); background = box(Color.rgb(10,15,23),9)
            setOnClickListener { timeframe = tf; refreshTf(); rebuild() }
        }
        row.addView(v, LinearLayout.LayoutParams(0,dp(32),1f).apply { setMargins(dp(2),0,dp(2),0) })
    }

    private fun refreshTf() {
        for (i in 0 until tfRow.childCount) {
            val v = tfRow.getChildAt(i)
            val m = v.tag as Int
            v.background = box(if (m == timeframe) Color.rgb(38,82,120) else Color.rgb(16,23,32),10)
        }
    }

    private fun get(url: String, timeout: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout; c.requestMethod = "GET"; c.useCaches = false
        c.setRequestProperty("User-Agent", "XAU-AI-Android/2.0")
        c.setRequestProperty("Accept", "application/json")
        return try {
            if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun load() {
        if (busy) return
        busy = true
        status.text = "LIVE DATA  •  loading XAU/USD…"
        io.execute {
            try {
                val now = System.currentTimeMillis() / 1000L
                val root = JSONObject(get("https://xaus.com/api/v1/intraday?symbol=xau&hours=48&fresh=$now", 8000))
                val points = root.getJSONArray("points")
                val raw = ArrayList<Candle>()
                for (i in 0 until points.length()) {
                    val p = points.getJSONObject(i)
                    val ts = p.getLong("t")
                    val t = if (ts < 10000000000L) ts * 1000L else ts
                    val v = p.getDouble("p")
                    if (v > 0.0) raw.add(Candle(t,v,v,v,v))
                }
                if (raw.size < 20) throw Exception("No points")
                val built = aggregatePrices(raw, timeframe)
                if (built.size < 15) throw Exception("No candles")
                val spot = root.optDouble("spot_usd_oz", built.last().close)
                runOnUiThread {
                    candles = built.takeLast(140)
                    lastPrice = if (spot > 0) spot else candles.last().close
                    busy = false
                    render()
                    status.text = "● XAU/USD SPOT  •  ${candles.size} candles  •  10s refresh  •  PAPER"
                }
            } catch (first: Exception) {
                tryYahoo()
            }
        }
    }

    private fun tryYahoo() {
        try {
            val root = JSONObject(get("https://query2.finance.yahoo.com/v8/finance/chart/GC=F?range=1d&interval=1m&includePrePost=true", 7000))
            val result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val ts = result.getJSONArray("timestamp")
            val q = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val oa = q.optJSONArray("open"); val ha = q.optJSONArray("high"); val la = q.optJSONArray("low"); val ca = q.optJSONArray("close")
            if (oa == null || ha == null || la == null || ca == null) throw Exception("No quote")
            val raw = ArrayList<Candle>()
            for (i in 0 until ts.length()) {
                if (oa.isNull(i) || ha.isNull(i) || la.isNull(i) || ca.isNull(i)) continue
                raw.add(Candle(ts.getLong(i)*1000L,oa.getDouble(i),ha.getDouble(i),la.getDouble(i),ca.getDouble(i)))
            }
            if (raw.size < 15) throw Exception("Yahoo empty")
            val built = aggregatePrices(raw,timeframe)
            val lp = result.getJSONObject("meta").optDouble("regularMarketPrice",built.last().close)
            runOnUiThread {
                candles = built.takeLast(140); lastPrice = lp; busy = false; render()
                status.text = "● MARKET FALLBACK  •  gold futures proxy  •  PAPER"
            }
        } catch (_: Exception) {
            runOnUiThread { busy = false; status.text = "⚠ Market data unavailable — tap ↻ to retry" }
        }
    }

    private fun rebuild() { load() }

    private fun aggregatePrices(raw: List<Candle>, minutes: Int): List<Candle> {
        val size = minutes.toLong() * 60000L
        val out = ArrayList<Candle>()
        var key = Long.MIN_VALUE
        var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0
        for (x in raw) {
            val k = (x.t / size) * size
            if (k != key) {
                if (key != Long.MIN_VALUE) out.add(Candle(key,o,h,l,c))
                key = k; o = x.open; h = x.high; l = x.low; c = x.close
            } else {
                h = max(h,x.high); l = min(l,x.low); c = x.close
            }
        }
        if (key != Long.MIN_VALUE) out.add(Candle(key,o,h,l,c))
        return out
    }

    private fun ema(values: List<Double>, n: Int): Double {
        if (values.isEmpty()) return 0.0
        if (values.size < n) return values.last()
        var e = values.take(n).average(); val k = 2.0/(n+1.0)
        for (i in n until values.size) e = values[i]*k + e*(1.0-k)
        return e
    }

    private fun rsi(values: List<Double>, n: Int): Double {
        if (values.size <= n) return 50.0
        var gain = 0.0; var loss = 0.0
        for (i in 1..n) { val d = values[i]-values[i-1]; if (d >= 0) gain += d else loss -= d }
        var ag = gain/n; var al = loss/n
        for (i in n+1 until values.size) { val d=values[i]-values[i-1]; ag=(ag*(n-1)+max(d,0.0))/n; al=(al*(n-1)+max(-d,0.0))/n }
        return if (al == 0.0) 100.0 else 100.0-100.0/(1.0+ag/al)
    }

    private fun atr(cs: List<Candle>, n: Int): Double {
        if (cs.size < 2) return 1.0
        val tr = ArrayList<Double>()
        for (i in 1 until cs.size) tr.add(max(cs[i].high-cs[i].low,max(abs(cs[i].high-cs[i-1].close),abs(cs[i].low-cs[i-1].close))))
        return tr.takeLast(min(n,tr.size)).average().coerceAtLeast(0.1)
    }

    private fun render() {
        if (candles.size < 15) return
        val closes = candles.map { it.close }
        val e9=ema(closes,9); val e21=ema(closes,21); val e50=ema(closes,50); val r=rsi(closes,14); val a=atr(candles,14); val p=lastPrice.coerceAtLeast(closes.last())
        val mom=p-closes[max(0,closes.size-8)]
        val trend=when { e9>e21 && e21>e50 -> 1; e9<e21 && e21<e50 -> -1; else -> 0 }
        val hi=candles.takeLast(min(40,candles.size)).maxOf { it.high }; val lo=candles.takeLast(min(40,candles.size)).minOf { it.low }
        val f382=hi-(hi-lo)*0.382; val f618=hi-(hi-lo)*0.618; val nearFib=abs(p-f382)<a*0.7 || abs(p-f618)<a*0.7
        val score=50.0+trend*16.0+if(mom>0)8.0 else -8.0+if(r>52)6.0 else if(r<48)-6.0 else 0.0+if(nearFib)5.0 else 0.0
        val side=when { score>=70.0 && r<74.0 -> "BUY"; score<=30.0 && r>26.0 -> "SELL"; else -> "WAIT" }
        val conf=min(94.0,max(52.0,abs(score-50.0)+52.0)); val risk=max(a*1.25,p*0.00035)
        val sl=if(side=="SELL") p+risk else p-risk; val tp1=if(side=="SELL") p-risk else p+risk; val tp2=if(side=="SELL") p-risk*1.7 else p+risk*1.7; val tp3=if(side=="SELL") p-risk*2.4 else p+risk*2.4
        current=Signal(side,p,sl,tp1,tp2,tp3,conf)
        price.text="XAU/USD  ${fmt(p)}"
        signal.text="$side   ${conf.toInt()}%"
        signal.setTextColor(if(side=="BUY") Color.rgb(50,220,145) else if(side=="SELL") Color.rgb(245,90,90) else Color.rgb(240,190,70))
        details.text="Entry ${fmt(p)}   SL ${fmt(sl)}   TP1 ${fmt(tp1)}\nTP2 ${fmt(tp2)}   TP3 ${fmt(tp3)}"
        chart.invalidate()
    }

    inner class GoldChartView(ctx: android.content.Context): View(ctx) {
        private val grid=Paint(Paint.ANTI_ALIAS_FLAG); private val wick=Paint(Paint.ANTI_ALIAS_FLAG); private val body=Paint(Paint.ANTI_ALIAS_FLAG); private val text=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            super.onDraw(c); c.drawColor(Color.rgb(7,11,17)); if(candles.isEmpty()) { text.color=Color.rgb(120,135,150); text.textSize=dp(14).toFloat(); c.drawText("Waiting for market data…",dp(20).toFloat(),dp(50).toFloat(),text); return }
            val left=dp(8).toFloat(); val right=width-dp(48).toFloat(); val top=dp(12).toFloat(); val bottom=height-dp(18).toFloat(); val cs=candles.takeLast(80); var lo=cs.minOf { it.low }; var hi=cs.maxOf { it.high }; val pad=(hi-lo)*0.08; lo-=pad; hi+=pad
            grid.color=Color.rgb(24,34,46); grid.strokeWidth=1f
            for(i in 0..5){ val y=top+(bottom-top)*i/5f; c.drawLine(left,y,right,y,grid) }
            fun y(v:Double)=bottom-(v-lo)/(hi-lo)*(bottom-top)
            val step=(right-left)/cs.size; val bw=max(2f,step*0.65f)
            for(i in cs.indices){ val x=left+step*(i+0.5f); val z=cs[i]; val up=z.close>=z.open; wick.color=if(up) Color.rgb(45,210,145) else Color.rgb(240,80,90); wick.strokeWidth=dp(1).toFloat(); c.drawLine(x,y(z.high),x,y(z.low),wick); body.color=wick.color; val yy1=y(z.open); val yy2=y(z.close); c.drawRect(x-bw/2,min(yy1,yy2),x+bw/2,max(yy1,yy2).coerceAtLeast(min(yy1,yy2)+dp(1)),body) }
            if(current.entry>0){ drawLevel(c,y(current.entry),Color.rgb(230,190,60),"ENTRY"); drawLevel(c,y(current.sl),Color.rgb(245,80,90),"SL"); drawLevel(c,y(current.tp1),Color.rgb(45,210,145),"TP1"); drawLevel(c,y(current.tp2),Color.rgb(45,180,145),"TP2") }
            text.color=Color.rgb(160,175,195); text.textSize=dp(9).toFloat(); c.drawText(fmt(hi),right+dp(4),top+dp(8),text); c.drawText(fmt(lo),right+dp(4),bottom,text)
        }
        private fun drawLevel(c:Canvas,y:Float,color:Int,label:String){ val p=Paint(Paint.ANTI_ALIAS_FLAG); p.color=color; p.strokeWidth=dp(1).toFloat(); c.drawLine(dp(8).toFloat(),y,width-dp(48).toFloat(),y,p); val t=Paint(Paint.ANTI_ALIAS_FLAG); t.color=color; t.textSize=dp(8).toFloat(); c.drawText(label,width-dp(44).toFloat(),y-dp(2),t) }
    }

    override fun onDestroy(){ super.onDestroy(); io.shutdownNow(); handler.removeCallbacksAndMessages(null) }
}

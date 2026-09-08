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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chart: GoldChartView
    private lateinit var priceText: TextView
    private lateinit var changeText: TextView
    private lateinit var signalText: TextView
    private lateinit var levelsText: TextView
    private lateinit var statusText: TextView
    private var allPoints = listOf<PointPrice>()
    private var timeframe = 5
    private var lastPrice = 0.0
    private var lastSignal = Signal("WAIT", 0.0, 0.0, 0.0, 0.0, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 12, 18)
        window.navigationBarColor = Color.rgb(8, 12, 18)
        buildUi()
        loadMarket(true)
        handler.postDelayed(object : Runnable {
            override fun run() {
                loadMarket(false)
                handler.postDelayed(this, 30_000)
            }
        }, 30_000)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 12, 18))
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brand = TextView(this).apply {
            text = "XAU AI"
            textSize = 25f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        val live = TextView(this).apply {
            text = "  ● LIVE"
            textSize = 12f; setTextColor(Color.rgb(72, 220, 150)); typeface = Typeface.DEFAULT_BOLD
        }
        top.addView(brand); top.addView(live)
        val refresh = TextView(this).apply {
            text = "↻"
            textSize = 27f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { loadMarket(true) }
        }
        top.addView(refresh, LinearLayout.LayoutParams(dp(45), dp(45)).apply { gravity = Gravity.RIGHT })
        root.addView(top, LinearLayout.LayoutParams(-1, dp(46)))

        val market = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(8)) }
        priceText = TextView(this).apply { text = "XAU/USD  —"; textSize = 30f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        changeText = TextView(this).apply { text = "دریافت قیمت..."; textSize = 13f; setTextColor(Color.LTGRAY) }
        market.addView(priceText); market.addView(changeText)
        root.addView(market)

        val tfRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf(2, 5, 15, 30, 60).forEach { tf ->
            val b = TextView(this).apply {
                text = if (tf == 60) "1H" else "${tf}m"
                textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = rounded(if (tf == timeframe) Color.rgb(42, 60, 83) else Color.rgb(20, 27, 37), 12)
                setOnClickListener { timeframe = tf; updateAnalysis() }
            }
            tfRow.addView(b, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        root.addView(tfRow)

        chart = GoldChartView(this)
        root.addView(chart, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        val signalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Color.rgb(17, 24, 34), 16)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        signalText = TextView(this).apply { text = "WAIT"; textSize = 23f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        row.addView(signalText, LinearLayout.LayoutParams(0, dp(40), 1f))
        statusText = TextView(this).apply { text = "در حال تحلیل"; textSize = 12f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER_VERTICAL }
        row.addView(statusText)
        signalCard.addView(row)
        levelsText = TextView(this).apply { text = "Entry —   SL —   TP1 —   TP2 —"; textSize = 12.5f; setTextColor(Color.rgb(205, 214, 225)); setPadding(0, dp(3), 0, 0) }
        signalCard.addView(levelsText)
        root.addView(signalCard, LinearLayout.LayoutParams(-1, dp(78)).apply { topMargin = dp(8) })
        setContentView(root)
    }

    private fun loadMarket(force: Boolean) {
        statusText.text = if (force) "اتصال سریع به بازار..." else "به‌روزرسانی..."
        io.execute {
            try {
                val url = URL("https://xaus.com/api/v1/intraday?symbol=xau&hours=6")
                val con = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500; readTimeout = 4500; requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                val body = con.inputStream.bufferedReader().use { it.readText() }
                con.disconnect()
                val json = JSONObject(body)
                val arr = json.optJSONArray("points") ?: throw Exception("No points")
                val list = ArrayList<PointPrice>()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    list.add(PointPrice(p.optLong("t"), p.optDouble("p")))
                }
                if (list.size < 10) throw Exception("Not enough data")
                allPoints = list
                lastPrice = list.last().price
                runOnUiThread { updateAnalysis() }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "اتصال ناموفق؛ دوباره تلاش می‌کنیم" }
            }
        }
    }

    private fun updateAnalysis() {
        if (allPoints.isEmpty()) return
        val candles = aggregate(allPoints, timeframe)
        if (candles.size < 20) return
        val closes = candles.map { it.close }
        val emaFast = ema(closes, 9)
        val emaSlow = ema(closes, 21)
        val rsiVal = rsi(closes, 14)
        val atrVal = atr(candles, 14)
        val momentum = closes.last() - closes[max(0, closes.size - 6)]
        val bullish = emaFast > emaSlow && momentum > 0
        val bearish = emaFast < emaSlow && momentum < 0
        val signal = when {
            bullish && rsiVal in 48.0..72.0 -> "BUY"
            bearish && rsiVal in 28.0..52.0 -> "SELL"
            else -> "WAIT"
        }
        val entry = closes.last()
        val risk = max(atrVal * 1.15, entry * 0.00035)
        val sl = if (signal == "SELL") entry + risk else entry - risk
        val tp1 = if (signal == "SELL") entry - risk * 1.0 else entry + risk * 1.0
        val tp2 = if (signal == "SELL") entry - risk * 1.8 else entry + risk * 1.8
        val tp3 = if (signal == "SELL") entry - risk * 2.6 else entry + risk * 2.6
        val conf = min(96, (55 + abs(emaFast - emaSlow) / max(atrVal, 0.01) * 9 + abs(rsiVal - 50) * 0.35).toInt())
        lastSignal = Signal(signal, entry, sl, tp1, tp2, tp3, conf)
        priceText.text = "XAU/USD  ${fmt(entry)}"
        val delta = entry - closes[max(0, closes.size - 16)]
        changeText.text = "${if (delta >= 0) "▲" else "▼"} ${fmt(abs(delta))}   •   RSI ${rsiVal.toInt()}   •   ATR ${fmt(atrVal)}   •   ${timeframeLabel()}"
        signalText.text = "$signal  •  ${conf}%"
        signalText.setTextColor(if (signal == "BUY") Color.rgb(75, 220, 150) else if (signal == "SELL") Color.rgb(255, 105, 105) else Color.rgb(240, 190, 75))
        levelsText.text = "Entry ${fmt(entry)}   SL ${fmt(sl)}   TP1 ${fmt(tp1)}   TP2 ${fmt(tp2)}   TP3 ${fmt(tp3)}"
        statusText.text = "اسکالپینگ کوتاه‌مدت • فقط Paper Trading"
        chart.data = candles
        chart.signal = lastSignal
        chart.invalidate()
    }

    private fun timeframeLabel() = if (timeframe == 60) "1H" else "${timeframe}m"
    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun aggregate(points: List<PointPrice>, minutes: Int): List<Candle> {
        if (minutes == 2) return points.map { Candle(it.t, it.price, it.price, it.price, it.price) }
        val bucket = minutes * 60_000L
        val out = ArrayList<Candle>()
        var start = -1L; var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0
        for (p in points) {
            val b = (p.t / bucket) * bucket
            if (b != start) {
                if (start >= 0) out.add(Candle(start, o, h, l, c))
                start = b; o = p.price; h = p.price; l = p.price; c = p.price
            } else { h = max(h, p.price); l = min(l, p.price); c = p.price }
        }
        if (start >= 0) out.add(Candle(start, o, h, l, c))
        return out
    }
    private fun ema(v: List<Double>, n: Int): Double { var e = v.take(n).average(); val k = 2.0 / (n + 1); for (i in n until v.size) e = v[i] * k + e * (1 - k); return e }
    private fun rsi(v: List<Double>, n: Int): Double {
        var gain = 0.0; var loss = 0.0
        for (i in 1..n) { val d = v[i] - v[i - 1]; if (d >= 0) gain += d else loss -= d }
        var ag = gain / n; var al = loss / n
        for (i in n + 1 until v.size) { val d = v[i] - v[i - 1]; ag = (ag * (n - 1) + max(d, 0.0)) / n; al = (al * (n - 1) + max(-d, 0.0)) / n }
        return if (al == 0.0) 100.0 else 100 - 100 / (1 + ag / al)
    }
    private fun atr(c: List<Candle>, n: Int): Double { val tr = ArrayList<Double>(); for (i in 1 until c.size) tr.add(max(c[i].high - c[i].low, max(abs(c[i].high - c[i-1].close), abs(c[i].low - c[i-1].close)))); return tr.takeLast(n).average() }

    override fun onDestroy() { super.onDestroy(); handler.removeCallbacksAndMessages(null); io.shutdownNow() }

    data class PointPrice(val t: Long, val price: Double)
    data class Candle(val t: Long, val open: Double, val high: Double, val low: Double, val close: Double)
    data class Signal(val type: String, val entry: Double, val sl: Double, val tp1: Double, val tp2: Double, val tp3: Double, val confidence: Int)

    class GoldChartView(context: android.content.Context) : View(context) {
        var data = listOf<Candle>(); var signal = Signal("WAIT", 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG); private val line = Paint(Paint.ANTI_ALIAS_FLAG); private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        init { grid.color = Color.rgb(31, 41, 54); grid.strokeWidth = 1f; line.strokeWidth = 3f; text.textSize = 11f; text.color = Color.rgb(150, 162, 178) }
        override fun onDraw(c: Canvas) {
            super.onDraw(c); c.drawColor(Color.rgb(10, 15, 22)); if (data.size < 2) return
            val left = 8f; val right = width - 58f; val top = 16f; val bottom = height - 18f
            val lo = data.takeLast(70).minOf { it.low }; val hi = data.takeLast(70).maxOf { it.high }; val range = max(hi - lo, 0.01)
            for (i in 0..4) { val y = top + (bottom - top) * i / 4f; c.drawLine(left, y, right, y, grid); val p = hi - range * i / 4.0; c.drawText(String.format(Locale.US, "%.0f", p), right + 5, y + 4, text) }
            val visible = data.takeLast(70); val step = (right - left) / max(1, visible.size - 1)
            line.style = Paint.Style.STROKE; line.color = Color.rgb(78, 178, 255); line.strokeWidth = 3f
            val path = Path()
            visible.forEachIndexed { i, x -> val px = left + i * step; val py = bottom - ((x.close - lo) / range * (bottom - top)).toFloat(); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
            c.drawPath(path, line)
            drawLevel(c, signal.entry, "ENTRY", Color.rgb(255, 193, 70), lo, range, left, right, top, bottom)
            if (signal.type != "WAIT") {
                drawLevel(c, signal.sl, "SL", Color.rgb(255, 90, 90), lo, range, left, right, top, bottom)
                drawLevel(c, signal.tp1, "TP1", Color.rgb(75, 220, 150), lo, range, left, right, top, bottom)
                drawLevel(c, signal.tp2, "TP2", Color.rgb(75, 220, 150), lo, range, left, right, top, bottom)
            }
            val last = visible.last(); val x = right; val y = bottom - ((last.close - lo) / range * (bottom - top)).toFloat(); line.color = Color.WHITE; line.style = Paint.Style.FILL; c.drawCircle(x, y, 5f, line)
        }
        private fun drawLevel(c: Canvas, price: Double, label: String, color: Int, lo: Double, range: Double, left: Float, right: Float, top: Float, bottom: Float) {
            if (price < lo - range * .15 || price > lo + range * 1.15) return
            val y = bottom - ((price - lo) / range * (bottom - top)).toFloat(); line.color = color; line.strokeWidth = 1.5f; line.pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f); c.drawLine(left, y, right, y, line); line.pathEffect = null
            text.color = color; text.textSize = 10f; c.drawText(label + " " + String.format(Locale.US, "%.2f", price), left + 5, y - 4, text); text.color = Color.rgb(150, 162, 178)
        }
    }
}

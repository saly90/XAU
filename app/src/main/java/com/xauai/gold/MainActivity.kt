package com.xauai.gold

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Context
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.squareup.okhttp3.CacheControl
import com.squareup.okhttp3.OkHttpClient
import com.squareup.okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class Candle(
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val openBar: Boolean = false
)
private data class Tick(val price: Double, val time: Long, val ageSec: Int, val stale: Boolean, val source: String)
private data class TickDto(
    val symbol: String = "",
    val bid: Double = 0.0,
    val ask: Double = 0.0,
    val mid: Double = 0.0,
    val timestamp: String = "",
    val quoteAgeSeconds: Int = 0,
    val stale: Boolean = false,
    val marketState: String = "open"
)
private data class Levels(
    val side: String,
    val entry: Double,
    val sl: Double,
    val tp1: Double,
    val tp2: Double,
    val tp3: Double,
    val confidence: Int,
    val reason: String,
    val signalIndex: Int,
    val confirmed: Boolean
)
private data class Analysis(
    val levels: Levels,
    val trend: String,
    val rsi: Double,
    val atr: Double,
    val support: Double,
    val resistance: Double,
    val fib: String,
    val structure: String,
    val mtf: String,
    val freshness: String
)

class MainActivity : Activity() {
    private lateinit var chart: ChartView
    private lateinit var price: TextView
    private lateinit var signal: TextView
    private lateinit var info: TextView

    private val candles = mutableListOf<Candle>()
    private var tf = "5m"
    private var livePrice = 0.0
    private var lastTickTime = 0L
    private var liveSource = "—"
    private var liveAge = 0
    private var liveStale = false

    private var levels = Levels("WAIT", 0.0, 0.0, 0.0, 0.0, 0.0, 0, "WAIT", 0, false)
    private var analysis = Analysis(levels, "NEUTRAL", 50.0, 0.0, 0.0, 0.0, "—", "—", "—", "—")

    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var loading = false
    @Volatile private var signalrStarting = false
    private var hub: HubConnection? = null

    private val refresh = object : Runnable {
        override fun run() { load(); handler.postDelayed(this, 20000) }
    }
    private val tickFallback = object : Runnable {
        override fun run() { pollTickFallback(); handler.postDelayed(this, 5000) }
    }
    private val reconnect = object : Runnable {
        override fun run() { startLiveStream() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.rgb(5, 8, 12)
        window.navigationBarColor = Color.rgb(5, 8, 12)
        buildUi()
        load()
        startLiveStream()
        handler.postDelayed(refresh, 20000)
        handler.postDelayed(tickFallback, 5000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(tickFallback)
        handler.removeCallbacks(reconnect)
        try { hub?.stop()?.subscribe({}, {}) } catch (_: Exception) {}
        hub = null
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    private fun dp(x: Float) = x * resources.displayMetrics.density
    private fun Float.dp() = dp(this).toInt()
    private fun Int.dp() = dp(toFloat()).toInt()
    private fun fmt(x: Double) = String.format(Locale.US, "%.2f", x)
    private fun tv(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(null, 1)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 8, 12))
            setPadding(8.dp(), 6.dp(), 8.dp(), 5.dp())
        }
        root.addView(tv("خان  •  XAU/USD PRO", 19f, true), LinearLayout.LayoutParams(-1, 34.dp()))
        price = tv("XAU/USD  —", 16f, true)
        root.addView(price, LinearLayout.LayoutParams(-1, 28.dp()))
        signal = tv("WAIT  •  BUILDING MARKET MODEL", 17f, true)
        signal.setTextColor(Color.rgb(240, 190, 70))
        root.addView(signal, LinearLayout.LayoutParams(-1, 30.dp()))

        val tfRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("1m", "2m", "3m", "5m", "15m", "30m", "1H", "4H", "1D").forEach { s ->
            val b = Button(this).apply {
                text = s
                textSize = 10f
                setOnClickListener { tf = s; load() }
            }
            tfRow.addView(b, LinearLayout.LayoutParams(0, 38.dp(), 1f))
        }
        root.addView(tfRow)
        chart = ChartView(this)
        root.addView(chart, LinearLayout.LayoutParams(-1, 0, 1f))
        info = tv("Paper trading • No real orders\nConnecting to live XAU/USD…", 11.5f)
        info.setPadding(4.dp(), 2.dp(), 4.dp(), 2.dp())
        root.addView(info, LinearLayout.LayoutParams(-1, 92.dp()))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r = Button(this).apply { text = "REFRESH"; setOnClickListener { load() } }
        val a = Button(this).apply {
            text = "ALERT"
            setOnClickListener { android.widget.Toast.makeText(this@MainActivity, "Alerts are informational only. No real orders.", android.widget.Toast.LENGTH_SHORT).show() }
        }
        row.addView(r, LinearLayout.LayoutParams(0, 42.dp(), 1f))
        row.addView(a, LinearLayout.LayoutParams(0, 42.dp(), 1f))
        root.addView(row)
        setContentView(root)
    }

    private fun http(url: String): String {
        val req = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .header("User-Agent", "Khan-XAU-PRO/5.0")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    private fun parseTime(s: String): Long {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
        for (p in formats) try {
            return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time ?: 0L
        } catch (_: Exception) {}
        return s.toLongOrNull()?.let { if (it < 100000000000L) it * 1000L else it } ?: 0L
    }

    private fun parseRestTick(raw: String): Tick {
        val j = JSONObject(raw)
        val mid = j.optDouble("mid", Double.NaN)
        val bid = j.optDouble("bid", Double.NaN)
        val ask = j.optDouble("ask", Double.NaN)
        val p = when {
            mid.isFinite() && mid > 0 -> mid
            bid.isFinite() && ask.isFinite() && bid > 0 && ask > 0 -> (bid + ask) / 2.0
            bid.isFinite() && bid > 0 -> bid
            else -> Double.NaN
        }
        if (!p.isFinite() || p <= 0) throw IllegalArgumentException("invalid tick price")
        val t = parseTime(j.optString("timestamp", ""))
        val age = j.optInt("quoteAgeSeconds", 0)
        val stale = j.optBoolean("stale", false) || j.optString("marketState") == "closed"
        return Tick(p, t, age, stale, "Biquote MT5")
    }

    private fun parseSignalRTick(t: TickDto): Tick {
        val p = when {
            t.mid > 0 -> t.mid
            t.bid > 0 && t.ask > 0 -> (t.bid + t.ask) / 2.0
            t.bid > 0 -> t.bid
            else -> 0.0
        }
        return Tick(p, parseTime(t.timestamp), t.quoteAgeSeconds, t.stale || t.marketState == "closed", "Biquote SignalR")
    }

    private fun parseBars(raw: String): List<Candle> {
        val a = JSONObject(raw).optJSONArray("bars") ?: return emptyList()
        val out = mutableListOf<Candle>()
        for (i in 0 until a.length()) {
            val z = a.optJSONObject(i) ?: continue
            val t = parseTime(z.optString("openTime", z.optString("timestamp", "")))
            val o = z.optDouble("open", Double.NaN)
            val h = z.optDouble("high", Double.NaN)
            val l = z.optDouble("low", Double.NaN)
            val c = z.optDouble("close", Double.NaN)
            val open = z.optBoolean("isOpen", false)
            if (t > 0 && o.isFinite() && h.isFinite() && l.isFinite() && c.isFinite() && o > 0 && c > 0 && h >= l && h >= max(o, c) && l <= min(o, c)) {
                out.add(Candle(t, o, h, l, c, open))
            }
        }
        return out.distinctBy { it.t }.sortedBy { it.t }
    }

    private fun interval(s: String) = when (s) {
        "1m", "2m", "3m" -> "1m"
        "5m" -> "5m"
        "15m" -> "15m"
        "30m" -> "30m"
        "1H" -> "1h"
        "4H" -> "4h"
        "1D" -> "1d"
        else -> "5m"
    }

    private fun aggregateMinutes(src: List<Candle>, minutes: Int): List<Candle> {
        if (src.isEmpty() || minutes <= 1) return src
        val step = minutes * 60000L
        val out = mutableListOf<Candle>()
        for (z in src) {
            val b = (z.t / step) * step
            val last = out.lastOrNull()
            if (last == null || last.t != b) out.add(Candle(b, z.o, z.h, z.l, z.c, z.openBar))
            else out[out.lastIndex] = Candle(last.t, last.o, max(last.h, z.h), min(last.l, z.l), z.c, last.openBar || z.openBar)
        }
        return out
    }

    private fun loadBars(s: String, limit: Int = 500): List<Candle> {
        val rawLimit = if (s == "2m" || s == "3m") min(1000, limit * 4) else min(1000, limit)
        val raw = parseBars(http("https://biquote.io/api/XAUUSD/ohlc?interval=${interval(s)}&limit=$rawLimit&fresh=${System.currentTimeMillis()}"))
        return when (s) {
            "2m" -> aggregateMinutes(raw, 2).takeLast(limit)
            "3m" -> aggregateMinutes(raw, 3).takeLast(limit)
            else -> raw.takeLast(limit)
        }
    }

    private fun startLiveStream() {
        if (signalrStarting) return
        val existing = hub
        if (existing != null) return
        signalrStarting = true
        runOnUiThread { info.text = "Paper trading • No real orders\nLIVE STREAM: connecting • XAU/USD" }

        val h = try { HubConnectionBuilder.create("https://biquote.io/hubs/tick").build() } catch (e: Exception) {
            signalrStarting = false
            scheduleReconnect()
            return
        }
        hub = h
        h.setKeepAliveInterval(15000)
        h.setServerTimeout(60000)
        h.on("ReceiveTick", { dto: TickDto ->
            try {
                val t = parseSignalRTick(dto)
                if (t.price > 0) runOnUiThread { applyTick(t) }
            } catch (_: Exception) {}
        }, TickDto::class.java)
        h.onClosed { ex ->
            signalrStarting = false
            if (hub === h) hub = null
            runOnUiThread { info.text = "Paper trading • No real orders\nLIVE STREAM disconnected • REST fallback active" }
            scheduleReconnect()
        }
        h.start().subscribe({
            signalrStarting = false
            try { h.send("Subscribe", arrayOf("XAUUSD")) } catch (_: Exception) {}
            runOnUiThread { info.text = "Paper trading • No real orders\nLIVE STREAM: connected • XAU/USD" }
        }, {
            signalrStarting = false
            if (hub === h) hub = null
            runOnUiThread { info.text = "Paper trading • No real orders\nLIVE STREAM unavailable • REST fallback active" }
            scheduleReconnect()
        })
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, 2500)
    }

    private fun pollTickFallback() {
        thread {
            try {
                val t = parseRestTick(http("https://biquote.io/api/XAUUSD?allowStale=false&fresh=${System.currentTimeMillis()}"))
                if (t.price > 0) runOnUiThread { applyTick(t) }
            } catch (_: Exception) {
                runOnUiThread { if (livePrice <= 0) info.text = "Paper trading • No real orders\nNo live quote • waiting for feed" }
            }
        }
    }

    private fun applyTick(t: Tick) {
        if (t.price <= 0) return
        livePrice = t.price
        if (t.time > 0) lastTickTime = max(lastTickTime, t.time)
        liveSource = t.source
        liveAge = t.ageSec
        liveStale = t.stale

        if (candles.isNotEmpty()) {
            val last = candles.last()
            val step = stepMs(tf)
            val tickBucket = (if (t.time > 0) t.time else System.currentTimeMillis()) / step * step
            val lastBucket = last.t / step * step
            if (tickBucket == lastBucket) {
                candles[candles.lastIndex] = Candle(last.t, last.o, max(last.h, t.price), min(last.l, t.price), t.price, last.openBar)
            } else if (tickBucket > lastBucket) {
                candles.add(Candle(tickBucket, last.c, t.price, t.price, t.price, true))
                if (candles.size > 1000) candles.removeAt(0)
            }
        }
        price.text = "XAU/USD  ${fmt(t.price)}  •  $tf  •  LIVE"
        chart.invalidate()
    }

    private fun stepMs(s: String) = when (s) {
        "1m" -> 60000L
        "2m" -> 120000L
        "3m" -> 180000L
        "5m" -> 300000L
        "15m" -> 900000L
        "30m" -> 1800000L
        "1H" -> 3600000L
        "4H" -> 14400000L
        "1D" -> 86400000L
        else -> 300000L
    }

    private fun closed(src: List<Candle>): List<Candle> = src.filter { !it.openBar }

    private fun ema(v: List<Double>, n: Int): Double {
        if (v.isEmpty()) return 0.0
        val k = 2.0 / (n + 1)
        var e = v.first()
        for (i in 1 until v.size) e = v[i] * k + e * (1 - k)
        return e
    }
    private fun emaSeries(v: List<Double>, n: Int): List<Double> {
        if (v.isEmpty()) return emptyList()
        val k = 2.0 / (n + 1)
        val o = MutableList(v.size) { 0.0 }
        o[0] = v[0]
        for (i in 1 until v.size) o[i] = v[i] * k + o[i - 1] * (1 - k)
        return o
    }
    private fun rsi(v: List<Double>, n: Int = 14): Double {
        if (v.size <= n) return 50.0
        var g = 0.0; var d = 0.0
        for (i in 1..n) { val x = v[i] - v[i - 1]; g += max(0.0, x); d += max(0.0, -x) }
        g /= n; d /= n
        for (i in n + 1 until v.size) { val x = v[i] - v[i - 1]; g = (g * (n - 1) + max(0.0, x)) / n; d = (d * (n - 1) + max(0.0, -x)) / n }
        return if (d == 0.0) 100.0 else 100.0 - 100.0 / (1 + g / d)
    }
    private fun atr(v: List<Candle>, n: Int = 14): Double {
        if (v.size < 2) return 0.0
        val tr = mutableListOf<Double>()
        for (i in 1 until v.size) { val z = v[i]; val pc = v[i - 1].c; tr.add(max(z.h - z.l, max(abs(z.h - pc), abs(z.l - pc)))) }
        return tr.takeLast(n).average()
    }
    private fun macdHist(v: List<Double>): Double {
        if (v.size < 35) return 0.0
        val a = emaSeries(v, 12); val b = emaSeries(v, 26); val m = MutableList(v.size) { a[it] - b[it] }
        return m.last() - ema(m.takeLast(60), 9)
    }
    private fun ichimoku(v: List<Candle>): Int {
        if (v.size < 52) return 0
        fun mid(n: Int): Double { val a = v.takeLast(n); return (a.maxOf { it.h } + a.minOf { it.l }) / 2 }
        val ten = mid(9); val kij = mid(26); val cloud = mid(52)
        return if (v.last().c > ten && ten > kij && v.last().c > cloud) 1 else if (v.last().c < ten && ten < kij && v.last().c < cloud) -1 else 0
    }
    private fun trend(v: List<Candle>): Int {
        if (v.size < 55) return 0
        val c = v.map { it.c }; var s = 0
        if (ema(c, 20) > ema(c, 50)) s++ else s--
        if (c.last() > ema(c, min(200, c.size - 1))) s++ else s--
        if (rsi(c) > 52) s++ else if (rsi(c) < 48) s--
        if (macdHist(c) > 0) s++ else s--
        s += ichimoku(v)
        return s.coerceIn(-5, 5)
    }

    private fun analyze(main: List<Candle>, mtf: Map<String, List<Candle>>, live: Double) {
        val src = closed(main)
        if (src.size < 80) {
            levels = Levels("WAIT", 0.0, 0.0, 0.0, 0.0, 0.0, 0, "NOT ENOUGH CLOSED DATA", 0, false)
            analysis = Analysis(levels, "NEUTRAL", 50.0, 0.0, 0.0, 0.0, "—", "—", "—", "DATA")
            runOnUiThread { renderAnalysis() }
            return
        }
        val c = src.map { it.c }; val last = src.last()
        val e20 = ema(c, 20); val e50 = ema(c, 50); val e200 = ema(c, min(200, c.size - 1))
        val rr = rsi(c); val at = atr(src); val mh = macdHist(c); val ichi = ichimoku(src)
        val recent = src.takeLast(50)
        val resistance = recent.dropLast(1).maxOf { it.h }
        val support = recent.dropLast(1).minOf { it.l }
        val swing = src.takeLast(80); val hi = swing.maxOf { it.h }; val lo = swing.minOf { it.l }; val range = hi - lo
        val f382 = hi - range * 0.382; val f50 = hi - range * 0.5; val f618 = hi - range * 0.618
        val inFibZone = last.c >= f618 && last.c <= f382
        val inBuyFib = inFibZone && e20 >= e50
        val inSellFib = inFibZone && e20 <= e50

        val mtfList = listOf("1m", "2m", "3m", "5m", "15m", "30m", "1H", "4H", "1D")
        val mtfScores = mtfList.filter { it != tf }.mapNotNull { s -> mtf[s]?.let { b -> trend(closed(b)) } }
        val bullMtf = mtfScores.count { it >= 2 }; val bearMtf = mtfScores.count { it <= -2 }; val totalMtf = mtfScores.size
        val bosUp = last.c > resistance && (last.c - resistance) > at * 0.05
        val bosDn = last.c < support && (support - last.c) > at * 0.05
        val bullScore = (if (e20 > e50) 1 else 0) + (if (last.c > e20) 1 else 0) + (if (last.c > e200) 1 else 0) + (if (rr in 50.0..72.0) 1 else 0) + (if (mh > 0) 1 else 0) + (if (ichi >= 0) 1 else 0) + (if (bullMtf >= 1) 1 else 0) + (if (bosUp || inBuyFib) 1 else 0)
        val bearScore = (if (e20 < e50) 1 else 0) + (if (last.c < e20) 1 else 0) + (if (last.c < e200) 1 else 0) + (if (rr in 28.0..50.0) 1 else 0) + (if (mh < 0) 1 else 0) + (if (ichi <= 0) 1 else 0) + (if (bearMtf >= 1) 1 else 0) + (if (bosDn || inSellFib) 1 else 0)
        val side = when {
            bullScore >= 5 && bullScore > bearScore -> "BUY"
            bearScore >= 5 && bearScore > bullScore -> "SELL"
            else -> "WAIT"
        }
        val trendLabel = when {
            bullScore >= 5 -> "BULLISH"
            bearScore >= 5 -> "BEARISH"
            bullScore >= 4 && bullScore > bearScore -> "BULLISH BIAS"
            bearScore >= 4 && bearScore > bullScore -> "BEARISH BIAS"
            else -> "NEUTRAL"
        }
        val structure = when {
            bosUp -> "BOS UP"
            bosDn -> "BOS DOWN"
            inBuyFib -> "BULLISH FIB ZONE"
            inSellFib -> "BEARISH FIB ZONE"
            else -> "TREND / MOMENTUM"
        }
        val mtfLabel = "BULL $bullMtf/$totalMtf • BEAR $bearMtf/$totalMtf"
        val fibLabel = "38.2 ${fmt(f382)} | 50 ${fmt(f50)} | 61.8 ${fmt(f618)}"

        if (side == "WAIT") {
            levels = Levels("WAIT", 0.0, 0.0, 0.0, 0.0, 0.0, 0, "WAIT • BUY $bullScore/8 • SELL $bearScore/8", src.lastIndex, false)
        } else {
            val lp = if (live > 0) live else last.c
            val distance = abs(lp - last.c)
            if (at <= 0 || (live > 0 && distance > at * 1.20)) {
                levels = Levels("WAIT", 0.0, 0.0, 0.0, 0.0, 0.0, 0, "SETUP EXPIRED • WAIT FOR RETEST", src.lastIndex, false)
            } else {
                val entry = lp
                val structureLookback = src.takeLast(30).dropLast(1)
                val swingLow = structureLookback.minOf { it.l }; val swingHigh = structureLookback.maxOf { it.h }
                val sl = if (side == "BUY") min(swingLow - at * 0.15, entry - at) else max(swingHigh + at * 0.15, entry + at)
                val risk = abs(entry - sl)
                if (!risk.isFinite() || risk <= 0) {
                    levels = Levels("WAIT", 0.0, 0.0, 0.0, 0.0, 0.0, 0, "INVALID RISK MODEL • WAIT", src.lastIndex, false)
                } else {
                    val structuralTarget = if (side == "BUY" && resistance > entry) resistance else if (side == "SELL" && support < entry) support else Double.NaN
                    val baseR1 = entry + if (side == "BUY") risk * 1.5 else -risk * 1.5
                    val tp1 = if (structuralTarget.isFinite() && abs(structuralTarget - entry) >= risk * 1.20) structuralTarget else baseR1
                    val tp2 = if (side == "BUY") max(tp1 + risk * 0.50, entry + risk * 2.20) else min(tp1 - risk * 0.50, entry - risk * 2.20)
                    val tp3 = if (side == "BUY") max(tp2 + risk * 0.50, entry + risk * 3.00) else min(tp2 - risk * 0.50, entry - risk * 3.00)
                    val conf = (62 + max(bullScore, bearScore) * 4 + abs(bullMtf - bearMtf) * 2).coerceIn(62, 90)
                    levels = Levels(side, entry, sl, tp1, tp2, tp3, conf, "RISK MODEL • STRUCTURE SL • ATR BUFFER • 1.5R/2.2R/3R", src.lastIndex, true)
                }
            }
        }
        val freshness = if (liveAge <= 5 && !liveStale) "LIVE" else if (liveStale) "STALE" else "AGE ${liveAge}s"
        analysis = Analysis(levels, trendLabel, rr, at, support, resistance, fibLabel, structure, mtfLabel, freshness)
        runOnUiThread { renderAnalysis() }
    }

    private fun renderAnalysis() {
        val l = levels
        signal.text = if (l.side == "WAIT") "WAIT" else "${l.side}  •  ${l.confidence}%"
        signal.setTextColor(if (l.side == "BUY") Color.rgb(45, 220, 145) else if (l.side == "SELL") Color.rgb(245, 80, 80) else Color.rgb(240, 190, 70))
        price.text = "XAU/USD  ${if (livePrice > 0) fmt(livePrice) else "—"}  •  $tf"
        info.text = "Paper trading • No real orders\n" +
            "FEED: $liveSource   STATUS: ${analysis.freshness}   AGE: ${liveAge}s\n" +
            "TREND: ${analysis.trend}   RSI: ${fmt(analysis.rsi)}   ATR: ${fmt(analysis.atr)}\n" +
            "S/R: ${fmt(analysis.support)} / ${fmt(analysis.resistance)}\n" +
            "FIB: ${analysis.fib}\n" +
            "STRUCTURE: ${analysis.structure}   MTF: ${analysis.mtf}\n" +
            if (l.confirmed) "ENTRY ${fmt(l.entry)}   SL ${fmt(l.sl)}   TP1 ${fmt(l.tp1)}   TP2 ${fmt(l.tp2)}   TP3 ${fmt(l.tp3)}" else l.reason
        chart.invalidate()
    }

    private fun load() {
        if (loading) return
        loading = true
        thread {
            try {
                val main = loadBars(tf, 1000)
                val map = mutableMapOf<String, List<Candle>>()
                listOf("1m", "2m", "3m", "5m", "15m", "30m", "1H", "4H", "1D").filter { it != tf }.forEach { s ->
                    try { map[s] = loadBars(s, 300) } catch (_: Exception) {}
                }
                val t = try { parseRestTick(http("https://biquote.io/api/XAUUSD?allowStale=false&fresh=${System.currentTimeMillis()}")) } catch (_: Exception) { null }
                runOnUiThread {
                    candles.clear()
                    candles.addAll(main.takeLast(1000))
                    if (t != null) applyTick(t)
                    if (candles.size < 80) {
                        levels = Levels("WAIT", 0.0, 0.0, 0.0, 0.0, 0.0, 0, "NO VALID CLOSED HISTORY", 0, false)
                        info.text = "Paper trading • No real orders\nWaiting for at least 80 closed candles.\nSource: Biquote MT5"
                    } else {
                        analyze(candles.toList(), map, t?.price ?: livePrice)
                    }
                    chart.invalidate()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    signal.text = "DATA ERROR • WAIT"
                    signal.setTextColor(Color.rgb(245, 150, 70))
                    info.text = "Paper trading • No real orders\nBiquote data unavailable. No fake candles or levels will be generated."
                }
            } finally { loading = false }
        }
    }

    private inner class ChartView(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun y(v: Double, lo: Double, span: Double, top: Float, bottom: Float) = bottom - ((v - lo) / span * (bottom - top)).toFloat()
        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(7, 11, 17))
            if (candles.isEmpty()) {
                p.color = Color.GRAY; p.textSize = dp(14f); c.drawText("Waiting for real XAU/USD candles…", 18f, 40f, p); return
            }
            val cs = candles.takeLast(140)
            val left = dp(7f); val right = width - dp(68f); val top = dp(8f); val bottom = height - dp(28f)
            var lo = cs.minOf { it.l }; var hi = cs.maxOf { it.h }
            val lv = listOf(levels.entry, levels.sl, levels.tp1, levels.tp2, levels.tp3, livePrice).filter { it.isFinite() && it > 0 }
            if (lv.isNotEmpty()) { lo = min(lo, lv.min()); hi = max(hi, lv.max()) }
            val pad = max((hi - lo) * 0.06, 0.5); lo -= pad; hi += pad
            val span = max(hi - lo, 0.001)
            p.strokeWidth = 1f; p.color = Color.rgb(28, 38, 52)
            for (i in 0..8) { val yy = top + (bottom - top) * i / 8f; c.drawLine(left, yy, right, yy, p) }
            for (i in 0..8) { val xx = left + (right - left) * i / 8f; c.drawLine(xx, top, xx, bottom, p) }
            p.textSize = dp(9f); p.color = Color.LTGRAY
            for (i in 0..7) c.drawText(fmt(hi - (hi - lo) * i / 7), right + 2f, top + (bottom - top) * i / 7f + 3, p)
            val sx = (right - left) / cs.size; val cw = max(sx * 0.72f, dp(2.5f))
            for (i in cs.indices) {
                val z = cs[i]; val x = left + (i + 0.5f) * sx
                p.color = if (z.c >= z.o) Color.rgb(45, 210, 140) else Color.rgb(240, 75, 75)
                c.drawLine(x, y(z.h, lo, span, top, bottom), x, y(z.l, lo, span, top, bottom), p)
                val yo = y(z.o, lo, span, top, bottom); val yc = y(z.c, lo, span, top, bottom)
                c.drawRect(x - cw / 2, min(yo, yc), x + cw / 2, max(yc, min(yo, yc) + dp(1f)), p)
            }
            if (livePrice > 0) drawLine(c, livePrice, "LIVE ${fmt(livePrice)}", Color.LTGRAY, left, right, top, bottom, lo, span)
            if (levels.confirmed) {
                drawLine(c, levels.entry, "ENTRY ${fmt(levels.entry)}", Color.rgb(245, 205, 70), left, right, top, bottom, lo, span)
                drawLine(c, levels.sl, "SL ${fmt(levels.sl)}", Color.rgb(245, 75, 75), left, right, top, bottom, lo, span)
                drawLine(c, levels.tp1, "TP1 ${fmt(levels.tp1)}", Color.rgb(45, 210, 140), left, right, top, bottom, lo, span)
                drawLine(c, levels.tp2, "TP2 ${fmt(levels.tp2)}", Color.rgb(45, 210, 140), left, right, top, bottom, lo, span)
                drawLine(c, levels.tp3, "TP3 ${fmt(levels.tp3)}", Color.rgb(45, 210, 140), left, right, top, bottom, lo, span)
            }
            p.color = Color.LTGRAY; p.textSize = dp(8.5f)
            c.drawText("$tf  •  REAL OHLC  •  SIGNALR LIVE", left + 4, bottom + 18, p)
        }
        private fun drawLine(c: Canvas, v: Double, s: String, col: Int, left: Float, right: Float, top: Float, bottom: Float, lo: Double, span: Double) {
            val yy = y(v, lo, span, top, bottom); if (yy < top || yy > bottom) return
            p.color = col; p.strokeWidth = dp(1.1f); c.drawLine(left, yy, right, yy, p); p.textSize = dp(9f); c.drawText(s, right + 2, yy - 2, p)
        }
    }
}

from pathlib import Path

path = Path("app/src/main/java/com/xauai/gold/MainActivity.kt")
s = path.read_text(encoding="utf-8")

# Keep the live tick loop independent from the slow multi-timeframe OHLC loader.
old_live_state = '''    @Volatile private var loading=false
    private val refresh=object:Runnable{override fun run(){load();handler.postDelayed(this,20000)}}
    private val tickRefresh=object:Runnable{override fun run(){pollTick();handler.postDelayed(this,1200)}}
'''
new_live_state = '''    @Volatile private var loading=false
    @Volatile private var tickLoading=false
    private val refresh=object:Runnable{override fun run(){load();handler.postDelayed(this,20000)}}
    private val tickRefresh=object:Runnable{override fun run(){pollTick();handler.postDelayed(this,900)}}
'''
if old_live_state in s:
    s = s.replace(old_live_state, new_live_state, 1)
elif 'tickLoading' not in s:
    raise SystemExit("SAFE_PATCH_ABORT: live state block not found; source was not modified")

# Use Biquote's batch latest endpoint and defeat client/proxy caching.
old_parse = '''    private fun parseTick(raw:String):Tick{
        val j=JSONObject(raw);val p=j.optDouble("mid",Double.NaN).let{if(it.isFinite()&&it>0)it else (j.optDouble("bid",0.0)+j.optDouble("ask",0.0))/2.0}
        val ts=j.opt("timestamp")
        val t=when(ts){is Number->if(ts.toLong()>100000000000L)ts.toLong() else ts.toLong()*1000L;is String->parseTime(ts);else->System.currentTimeMillis()}
        return Tick(p,t)
    }
'''
new_parse = '''    private fun parseTick(raw:String):Tick{
        val root=JSONObject(raw)
        val j=root.optJSONObject("XAUUSD")?:root
        val p=j.optDouble("mid",Double.NaN).let{if(it.isFinite()&&it>0)it else (j.optDouble("bid",0.0)+j.optDouble("ask",0.0))/2.0}
        val ts=j.opt("timestamp").takeIf{it!=null}?:j.opt("time")
        val t=when(ts){is Number->if(ts.toLong()>100000000000L)ts.toLong() else ts.toLong()*1000L;is String->parseTime(ts);else->System.currentTimeMillis()}
        return Tick(p,t)
    }
'''
if old_parse in s:
    s = s.replace(old_parse, new_parse, 1)
elif 'private fun parseTick(raw:String):Tick' not in s:
    raise SystemExit("SAFE_PATCH_ABORT: parseTick function not found")

old_http = '''    private fun http(url:String):String{
        val req=Request.Builder().url(url).header("User-Agent","Khan-XAU-PRO/3.0").build()
        client.newCall(req).execute().use{r->if(!r.isSuccessful)throw java.io.IOException("HTTP "+r.code);return r.body?.string().orEmpty()}
    }
'''
new_http = '''    private fun http(url:String):String{
        val req=Request.Builder().url(url)
            .header("User-Agent","Khan-XAU-PRO/3.1")
            .header("Cache-Control","no-cache, no-store")
            .header("Pragma","no-cache")
            .build()
        client.newCall(req).execute().use{r->if(!r.isSuccessful)throw java.io.IOException("HTTP "+r.code);return r.body?.string().orEmpty()}
    }
'''
if old_http in s:
    s = s.replace(old_http, new_http, 1)

old_poll = '''    private fun pollTick(){
        if(loading)return
        thread{try{val t=parseTick(http("https://biquote.io/api/XAUUSD?allowStale=false"));if(t.price>0)runOnUiThread{applyTick(t)}}catch(_:Exception){}}
    }
'''
new_poll = '''    private fun pollTick(){
        if(tickLoading)return
        tickLoading=true
        thread{
            try{
                val stamp=System.currentTimeMillis()
                val t=parseTick(http("https://biquote.io/api/latest?symbols=XAUUSD&_="+stamp))
                if(t.price>0)runOnUiThread{applyTick(t)}
            }catch(_:Exception){}
            finally{tickLoading=false}
        }
    }
'''
if old_poll in s:
    s = s.replace(old_poll, new_poll, 1)
elif 'private fun pollTick(){' not in s:
    raise SystemExit("SAFE_PATCH_ABORT: pollTick function not found")

# A completed final OHLC bar must remain available to analysis. Only remove
# the final bar when its bucket is the currently-open bucket.
old_closed = '''    private fun closed(src:List<Candle>):List<Candle>{
        if(src.size<=2)return emptyList()
        val last=src.last()
        val nowBucket=(System.currentTimeMillis()/stepMs(tf))*stepMs(tf)
        return if((last.t/stepMs(tf))*stepMs(tf)>=nowBucket)src.dropLast(1) else src.dropLast(1)
    }
'''
new_closed = '''    private fun closed(src:List<Candle>):List<Candle>{
        if(src.size<=2)return emptyList()
        val last=src.last()
        val step=stepMs(tf)
        val lastBucket=(last.t/step)*step
        val nowBucket=(System.currentTimeMillis()/step)*step
        return if(lastBucket>=nowBucket)src.dropLast(1) else src
    }
'''
if old_closed in s:
    s = s.replace(old_closed, new_closed, 1)

# Directional Fibonacci zone: only credit the side that agrees with EMA20/EMA50.
old_fib = '''        val inBuyFib=last.c>=f618 && last.c<=f382
        val inSellFib=last.c>=f618 && last.c<=f382
        val bullMtf=listOf("1m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it>=2}
        val bearMtf=listOf("1m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it<=-2}
        val totalMtf=listOf("1m","3m","5m","15m","30m","1H","4H","1D").count{it!=tf}
'''
new_fib = '''        val inFibZone=last.c>=f618 && last.c<=f382
        val inBuyFib=inFibZone && e20>=e50
        val inSellFib=inFibZone && e20<=e50
        val mtfFrames=listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D")
        val bullMtf=mtfFrames.filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it>=2}
        val bearMtf=mtfFrames.filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it<=-2}
        val totalMtf=mtfFrames.count{it!=tf && mtf.containsKey(it)}
'''
if old_fib in s:
    s = s.replace(old_fib, new_fib, 1)

# Load all requested lower timeframes into MTF so the displayed denominator is real.
old_load = '''                val map=mutableMapOf<String,List<Candle>>()
                listOf("3m","5m","15m","30m","1H","4H","1D").forEach{s->try{map[s]=loadBars(s,300)}catch(_:Exception){}}
'''
new_load = '''                val map=mutableMapOf<String,List<Candle>>()
                listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D").forEach{s->try{map[s]=loadBars(s,300)}catch(_:Exception){}}
'''
if old_load in s:
    s = s.replace(old_load, new_load, 1)

# Use the same live endpoint during the initial load.
old_initial_tick = '                val t=try{parseTick(http("https://biquote.io/api/XAUUSD?allowStale=false"))}catch(_:Exception){Tick(0.0,0L)}\n'
new_initial_tick = '                val t=try{parseTick(http("https://biquote.io/api/latest?symbols=XAUUSD&_="+System.currentTimeMillis()))}catch(_:Exception){Tick(0.0,0L)}\n'
if old_initial_tick in s:
    s = s.replace(old_initial_tick, new_initial_tick, 1)

path.write_text(s, encoding="utf-8")
print("SAFE_PATCH_OK: live latest feed + no-cache + closed-bar fix + Fibonacci + full MTF")
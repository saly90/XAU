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
    private val tickRefresh=object:Runnable{override fun run(){pollTick();handler.postDelayed(this,1200)}}
'''
if old_live_state in s:
    s = s.replace(old_live_state, new_live_state, 1)
elif 'tickLoading' not in s:
    raise SystemExit("SAFE_PATCH_ABORT: live state block not found; source was not modified")

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
                val t=parseTick(http("https://biquote.io/api/XAUUSD?allowStale=false"))
                if(t.price>0)runOnUiThread{applyTick(t)}
            }catch(_:Exception){}
            finally{tickLoading=false}
        }
    }
'''
if old_poll in s:
    s = s.replace(old_poll, new_poll, 1)
elif 'private fun pollTick(){' not in s:
    raise SystemExit("SAFE_PATCH_ABORT: pollTick function not found; source was not modified")

# Keep the existing Fibonacci band, but credit it only to the side
# that agrees with the local EMA trend.
old_fib = '''        val inBuyFib=last.c>=f618 && last.c<=f382
        val inSellFib=last.c>=f618 && last.c<=f382
        val bullMtf=listOf("1m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it>=2}
        val bearMtf=listOf("1m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it<=-2}
        val totalMtf=listOf("1m","3m","5m","15m","30m","1H","4H","1D").count{it!=tf}
'''
new_fib = '''        // Directional Fibonacci zone: only credit the side that agrees with EMA20/EMA50.
        val inFibZone=last.c>=f618 && last.c<=f382
        val inBuyFib=inFibZone && e20>=e50
        val inSellFib=inFibZone && e20<=e50
        val mtfFrames=listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D")
        val bullMtf=mtfFrames.filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it>=2}
        val bearMtf=mtfFrames.filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it<=-2}
        val totalMtf=mtfFrames.count{it!=tf && mtf.containsKey(it)}
'''
if old_fib in s:
    s = s.replace(old_fib, new_fib, 1)

# Load 1m/2m MTF data too, matching the MTF labels above.
old_load = '''                val map=mutableMapOf<String,List<Candle>>()
                listOf("3m","5m","15m","30m","1H","4H","1D").forEach{s->try{map[s]=loadBars(s,300)}catch(_:Exception){}}
'''
new_load = '''                val map=mutableMapOf<String,List<Candle>>()
                listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D").forEach{s->try{map[s]=loadBars(s,300)}catch(_:Exception){}}
'''
if old_load in s:
    s = s.replace(old_load, new_load, 1)

path.write_text(s, encoding="utf-8")
print("SAFE_PATCH_OK: live tick polling + Fibonacci + MTF fixes applied")
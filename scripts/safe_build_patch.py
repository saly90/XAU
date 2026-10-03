from pathlib import Path

p = Path("app/src/main/java/com/xauai/gold/MainActivity.kt")
s = p.read_text(encoding="utf-8")

# 1) Keep the live tick loop independent from the slow multi-timeframe OHLC loader.
old = '''    @Volatile private var loading=false
    private val refresh=object:Runnable{override fun run(){load();handler.postDelayed(this,20000)}}
    private val tickRefresh=object:Runnable{override fun run(){pollTick();handler.postDelayed(this,1200)}}
'''
new = '''    @Volatile private var loading=false
    @Volatile private var tickLoading=false
    private val refresh=object:Runnable{override fun run(){load();handler.postDelayed(this,20000)}}
    private val tickRefresh=object:Runnable{override fun run(){pollTick();handler.postDelayed(this,1200)}}
'''
if old not in s:
    raise SystemExit("live-state block not found")
s = s.replace(old, new, 1)

old = '''    private fun pollTick(){
        if(loading)return
        thread{try{val t=parseTick(http("https://biquote.io/api/XAUUSD?allowStale=false"));if(t.price>0)runOnUiThread{applyTick(t)}}catch(_:Exception){}}
    }
'''
new = '''    private fun pollTick(){
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
if old not in s:
    raise SystemExit("pollTick block not found")
s = s.replace(old, new, 1)

# 2) Correct the directional Fibonacci zone and include every available MTF.
old = '''        val inBuyFib=last.c>=f618 && last.c<=f382
        val inSellFib=last.c>=f618 && last.c<=f382
        val bullMtf=listOf("1m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it>=2}
        val bearMtf=listOf("1m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it<=-2}
        val totalMtf=listOf("1m","3m","5m","15m","30m","1H","4H","1D").count{it!=tf}
'''
new = '''        val inFibZone=last.c>=f618 && last.c<=f382
        val inBuyFib=inFibZone && e20>=e50
        val inSellFib=inFibZone && e20<=e50
        val mtfFrames=listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D")
        val bullMtf=mtfFrames.filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it>=2}
        val bearMtf=mtfFrames.filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it<=-2}
        val totalMtf=mtfFrames.count{it!=tf && mtf.containsKey(it)}
'''
if old not in s:
    raise SystemExit("fib/mtf block not found")
s = s.replace(old, new, 1)

# 3) Load 1m/2m MTF data too, matching the MTF labels used above.
old = '''                val map=mutableMapOf<String,List<Candle>>()
                listOf("3m","5m","15m","30m","1H","4H","1D").forEach{s->try{map[s]=loadBars(s,300)}catch(_:Exception){}}
'''
new = '''                val map=mutableMapOf<String,List<Candle>>()
                listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D").forEach{s->try{map[s]=loadBars(s,300)}catch(_:Exception){}}
'''
if old not in s:
    raise SystemExit("MTF loader block not found")
s = s.replace(old, new, 1)

p.write_text(s, encoding="utf-8")
print("safe build patch applied")

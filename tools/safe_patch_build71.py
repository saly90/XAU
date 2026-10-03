from pathlib import Path

path = Path("app/src/main/java/com/xauai/gold/MainActivity.kt")
s = path.read_text(encoding="utf-8")

old_fib = '''        val inBuyFib=last.c>=f618 && last.c<=f382
        val inSellFib=last.c>=f618 && last.c<=f382
        val bullMtf=listOf("1m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it>=2}
        val bearMtf=listOf("1m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it<=-2}
        val totalMtf=listOf("1m","3m","5m","15m","30m","1H","4H","1D").count{it!=tf}
'''
new_fib = '''        // Keep the existing Fibonacci band, but credit it only to the side
        // that agrees with the local EMA trend. This avoids BUY/SELL both
        // receiving the same Fibonacci point at the same time.
        val inFibZone=last.c>=f618 && last.c<=f382
        val inBuyFib=inFibZone && e20>=e50
        val inSellFib=inFibZone && e20<=e50
        val mtfFrames=listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D")
        val bullMtf=mtfFrames.filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it>=2}
        val bearMtf=mtfFrames.filter{it!=tf}.mapNotNull{mtf[it]?.let{b->trend(closed(b))}}.count{it<=-2}
        val totalMtf=mtfFrames.count{it!=tf && mtf.containsKey(it)}
'''
if old_fib not in s:
    raise SystemExit("SAFE_PATCH_ABORT: Fibonacci/MTF block not found; source was not modified")
s = s.replace(old_fib, new_fib, 1)

old_load = '''                val map=mutableMapOf<String,List<Candle>>()
                listOf("3m","5m","15m","30m","1H","4H","1D").forEach{s->try{map[s]=loadBars(s,300)}catch(_:Exception){}}
'''
new_load = '''                val map=mutableMapOf<String,List<Candle>>()
                listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D").forEach{s->try{map[s]=loadBars(s,300)}catch(_:Exception){}}
'''
if old_load not in s:
    raise SystemExit("SAFE_PATCH_ABORT: MTF loading block not found; source was not modified")
s = s.replace(old_load, new_load, 1)

path.write_text(s, encoding="utf-8")
print("SAFE_PATCH_OK: Build 71 isolated Fibonacci + MTF fixes applied")
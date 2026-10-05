from pathlib import Path

PATH = Path("app/src/main/java/com/xauai/gold/MainActivity.kt")
s = PATH.read_text(encoding="utf-8")

if 'private val MT5_TOPIC=' in s:
    print("MT5 bridge patch already present")
    raise SystemExit(0)

repls = [
    (
        'import org.json.JSONObject\n',
        'import org.json.JSONObject\nimport java.io.BufferedReader\nimport java.io.InputStreamReader\n'
    ),
    (
        '    private val reconnect=object:Runnable{override fun run(){startLiveStream()}}\n',
        '''    private val reconnect=object:Runnable{override fun run(){startLiveStream()}}\n    private val MT5_TOPIC="xauai-htekvsg22lzwelwkmzomnox46pap116w"\n    private val mt5Bars=mutableMapOf<String,List<Candle>>()\n    @Volatile private var mt5BridgeRunning=false\n    @Volatile private var mt5Live=false\n    @Volatile private var lastMt5TickReceived=0L\n    private var mt5BridgeResponse:Response?=null\n'''
    ),
    (
        'override fun onCreate(b:Bundle?){super.onCreate(b);window.statusBarColor=Color.rgb(5,8,12);window.navigationBarColor=Color.rgb(5,8,12);buildUi();load();startLiveStream();handler.postDelayed(refresh,20000);handler.postDelayed(tickRefresh,1500)}',
        'override fun onCreate(b:Bundle?){super.onCreate(b);window.statusBarColor=Color.rgb(5,8,12);window.navigationBarColor=Color.rgb(5,8,12);buildUi();load();startMt5BridgeStream();startLiveStream();handler.postDelayed(refresh,20000);handler.postDelayed(tickRefresh,1500)}'
    ),
    (
        'override fun onDestroy(){handler.removeCallbacks(refresh);handler.removeCallbacks(tickRefresh);handler.removeCallbacks(reconnect);tickSocket?.close(1000,"app closed");tickSocket=null;client.dispatcher.executorService.shutdown();super.onDestroy()}',
        'override fun onDestroy(){handler.removeCallbacks(refresh);handler.removeCallbacks(tickRefresh);handler.removeCallbacks(reconnect);mt5BridgeRunning=false;mt5BridgeResponse?.close();mt5BridgeResponse=null;tickSocket?.close(1000,"app closed");tickSocket=null;client.dispatcher.executorService.shutdown();super.onDestroy()}'
    ),
]

bridge_methods = r'''
    private fun startMt5BridgeStream(){
        if(mt5BridgeRunning)return
        mt5BridgeRunning=true
        thread{
            while(mt5BridgeRunning){
                var response:Response?=null
                try{
                    val req=Request.Builder().url("https://ntfy.sh/"+MT5_TOPIC+"/json").header("Cache-Control","no-cache").header("User-Agent","Khan-XAU-PRO/MT5Bridge").build()
                    response=client.newCall(req).execute()
                    mt5BridgeResponse=response
                    if(!response.isSuccessful)throw java.io.IOException("MT5 bridge HTTP "+response.code)
                    val body=response.body?:throw java.io.IOException("empty MT5 bridge body")
                    val reader=BufferedReader(InputStreamReader(body.byteStream(),Charsets.UTF_8))
                    while(mt5BridgeRunning){
                        val line=reader.readLine()?:break
                        if(line.isBlank())continue
                        try{
                            val outer=JSONObject(line)
                            if(outer.optString("event")!="message")continue
                            val message=outer.optString("message","")
                            if(message.isNotBlank())handleMt5Message(message)
                        }catch(_:Exception){}
                    }
                }catch(_:Exception){
                    if(mt5BridgeRunning)Thread.sleep(1500)
                }finally{
                    try{response?.close()}catch(_:Exception){}
                    if(mt5BridgeResponse===response)mt5BridgeResponse=null
                }
            }
        }
    }

    private fun handleMt5Message(raw:String){
        try{
            val j=JSONObject(raw)
            when(j.optString("kind")){
                "tick"->{
                    val mid=j.optDouble("mid",Double.NaN)
                    val bid=j.optDouble("bid",Double.NaN)
                    val ask=j.optDouble("ask",Double.NaN)
                    val p=when{mid.isFinite()&&mid>0->mid;bid.isFinite()&&ask.isFinite()&&bid>0&&ask>0->(bid+ask)/2.0;bid.isFinite()&&bid>0->bid;else->Double.NaN}
                    if(!p.isFinite()||p<=0)return
                    val rawTime=j.optLong("time_msc",System.currentTimeMillis())
                    val tm=if(rawTime<100000000000L)rawTime*1000L else rawTime
                    lastMt5TickReceived=System.currentTimeMillis()
                    mt5Live=true
                    val t=Tick(p,tm)
                    runOnUiThread{
                        applyTick(t)
                        info.text="Paper trading • No real orders\\nMT5 LIVE • Alpari feed"
                    }
                }
                "history"->{
                    val tfName=j.optString("tf","")
                    val a=j.optJSONArray("bars")?:return
                    val out=mutableListOf<Candle>()
                    for(i in 0 until a.length()){
                        val z=a.optJSONArray(i)?:continue
                        if(z.length()<5)continue
                        val t0=z.optLong(0,0L)
                        val t=if(t0<100000000000L)t0*1000L else t0
                        val o=z.optDouble(1,Double.NaN);val h=z.optDouble(2,Double.NaN);val l=z.optDouble(3,Double.NaN);val c=z.optDouble(4,Double.NaN)
                        if(t>0&&o.isFinite()&&h.isFinite()&&l.isFinite()&&c.isFinite()&&o>0&&h>=l&&c>0)out.add(Candle(t,o,h,l,c))
                    }
                    if(out.isEmpty()||tfName.isBlank())return
                    val merged=(mt5Bars[tfName].orEmpty()+out).distinctBy{it.t}.sortedBy{it.t}.takeLast(if(tfName=="1m")600 else 180)
                    mt5Bars[tfName]=merged
                    refreshFromMt5History()
                }
            }
        }catch(_:Exception){}
    }

    private fun bridgeBars(s:String,limit:Int=1000):List<Candle>{
        val direct=mt5Bars[s].orEmpty()
        if(s=="1m" || s=="1H" || s=="4H" || s=="1D")return direct.takeLast(limit)
        if(s=="2m"||s=="3m")return aggregateMinutes(mt5Bars["1m"].orEmpty(),if(s=="2m")2 else 3).takeLast(limit)
        if(direct.size>=80)return direct.takeLast(limit)
        val mins=when(s){"5m"->5;"15m"->15;"30m"->30;else->0}
        return if(mins>0)aggregateMinutes(mt5Bars["1m"].orEmpty(),mins).takeLast(limit) else direct.takeLast(limit)
    }

    private fun bridgeMtf():Map<String,List<Candle>>{
        val map=mutableMapOf<String,List<Candle>>()
        listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf}.forEach{s->
            val b=bridgeBars(s,300)
            if(b.isNotEmpty())map[s]=b
        }
        return map
    }

    private fun refreshFromMt5History(){
        val main=bridgeBars(tf,1000)
        if(main.size<80)return
        runOnUiThread{
            candles.clear();candles.addAll(main.takeLast(1000))
            if(livePrice>0)applyTick(Tick(livePrice,lastTickTime))
            analyze(candles.toList(),bridgeMtf(),livePrice)
            info.text="Paper trading • No real orders\\nMT5 LIVE • Alpari feed"
            chart.invalidate()
        }
    }

'''
repls.append(('    private fun startLiveStream(){\n', bridge_methods + '    private fun startLiveStream(){\n'))

repls.append((
'''    private fun pollTick(){
        thread{try{
            val t=parseTick(http("https://biquote.io/api/XAUUSD?allowStale=false"))
            if(t.price>0&&t.time>=lastTickTime)runOnUiThread{applyTick(t)}
        }catch(_:Exception){}}
    }
''',
'''    private fun pollTick(){
        if(mt5Live && System.currentTimeMillis()-lastMt5TickReceived<6000)return
        thread{try{
            val t=parseTick(http("https://biquote.io/api/XAUUSD?allowStale=false"))
            if(t.price>0&&t.time>=lastTickTime)runOnUiThread{applyTick(t)}
        }catch(_:Exception){}}
    }
'''))

old_load = '''                val main=loadBars(tf,1000)
                val map=mutableMapOf<String,List<Candle>>()
                listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf}.forEach{s->try{map[s]=loadBars(s,300)}catch(_:Exception){}}
                val t=try{parseTick(http("https://biquote.io/api/XAUUSD?allowStale=false"))}catch(_:Exception){Tick(0.0,0L)}
'''
new_load = '''                val bridgeMain=bridgeBars(tf,1000)
                val useBridge=bridgeMain.size>=80
                val main=if(useBridge)bridgeMain else loadBars(tf,1000)
                val map=if(useBridge)bridgeMtf() else mutableMapOf<String,List<Candle>>().also{m->listOf("1m","2m","3m","5m","15m","30m","1H","4H","1D").filter{it!=tf}.forEach{s->try{m[s]=loadBars(s,300)}catch(_:Exception){}}}
                val freshMt5=mt5Live && System.currentTimeMillis()-lastMt5TickReceived<10000
                val t=if(freshMt5 && livePrice>0)Tick(livePrice,lastTickTime) else try{parseTick(http("https://biquote.io/api/XAUUSD?allowStale=false"))}catch(_:Exception){Tick(0.0,0L)}
'''
repls.append((old_load,new_load))

for old, new in repls:
    if old not in s:
        raise SystemExit("MISSING PATTERN: " + old[:180])
    s = s.replace(old, new, 1)

PATH.write_text(s, encoding="utf-8")
print("patched", PATH)

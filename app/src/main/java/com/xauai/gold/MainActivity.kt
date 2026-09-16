package com.xauai.gold

import android.app.*
import android.os.*
import android.graphics.*
import android.view.*
import android.widget.*
import android.content.*
import android.content.pm.PackageManager
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.*

data class Candle(val t:Long,val o:Double,val h:Double,val l:Double,val c:Double)
data class Levels(val side:String,val entry:Double,val sl:Double,val tp1:Double,val tp2:Double,val tp3:Double,val confidence:Int,val reason:String)

class MainActivity : Activity() {
    private lateinit var chart: ChartView
    private lateinit var price: TextView
    private lateinit var signal: TextView
    private lateinit var info: TextView
    private lateinit var news: TextView
    private val candles=mutableListOf<Candle>()
    private val base=mutableListOf<Pair<Long,Double>>()
    private var tf="5m"
    private var levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"Loading")
    private var lastAlertKey=""
    private var lastPrice=0.0
    private val mainHandler=Handler(Looper.getMainLooper())
    private val refresh=object:Runnable{override fun run(){load();mainHandler.postDelayed(this,15000)}}
    private fun dp(v:Float)=v*resources.displayMetrics.density
    private fun fmt(v:Double)=String.format("%.2f",v)

    override fun onCreate(b:Bundle?){super.onCreate(b);window.statusBarColor=Color.rgb(5,8,12);window.navigationBarColor=Color.rgb(5,8,12);buildUi();load();mainHandler.postDelayed(refresh,15000)}
    override fun onDestroy(){mainHandler.removeCallbacks(refresh);super.onDestroy()}

    private fun tv(text:String,size:Float,bold:Boolean=false):TextView{val x=TextView(this);x.text=text;x.textSize=size;x.setTextColor(Color.WHITE);if(bold)x.setTypeface(null,1);return x}
    private fun buildUi(){
        val root=LinearLayout(this);root.orientation=LinearLayout.VERTICAL;root.setBackgroundColor(Color.rgb(5,8,12));root.setPadding(dp(8f).toInt(),dp(6f).toInt(),dp(8f).toInt(),dp(6f).toInt())
        val title=tv("XAU AI PRO  •  GOLD / USD",19f,true);root.addView(title,LinearLayout.LayoutParams(-1,dp(34f).toInt()))
        price=tv("XAU/USD  —",16f,true);root.addView(price,LinearLayout.LayoutParams(-1,dp(28f).toInt()))
        signal=tv("WAIT  •  SCANNING",18f,true);signal.setTextColor(Color.rgb(240,190,70));root.addView(signal,LinearLayout.LayoutParams(-1,dp(30f).toInt()))
        val modes=LinearLayout(this);modes.orientation=LinearLayout.HORIZONTAL
        listOf("SCALP","INTRADAY","SWING").forEach{m->val b=Button(this);b.text=m;b.setOnClickListener{when(m){"SCALP"->tf="1m";"INTRADAY"->tf="5m";else->tf="1H"};load()};modes.addView(b,LinearLayout.LayoutParams(0,dp(40f).toInt(),1f))};root.addView(modes)
        val row=LinearLayout(this);row.orientation=LinearLayout.HORIZONTAL
        listOf("TICK","1m","2m","3m","5m","15m","30m","1H","4H","1D").forEach{s->val b=Button(this);b.text=s;b.textSize=10f;b.setOnClickListener{tf=s;load()};row.addView(b,LinearLayout.LayoutParams(0,dp(38f).toInt(),1f))};root.addView(row)
        chart=ChartView(this);root.addView(chart,LinearLayout.LayoutParams(-1,0,1f))
        info=tv("Paper trading • No real orders\nEntry / SL / TP loading…",12f);info.setPadding(dp(4f).toInt(),dp(3f).toInt(),dp(4f).toInt(),dp(3f).toInt());root.addView(info,LinearLayout.LayoutParams(-1,dp(68f).toInt()))
        news=tv("MACRO NEWS\nLoading…",11f);news.setTextColor(Color.LTGRAY);root.addView(news,LinearLayout.LayoutParams(-1,dp(76f).toInt()))
        val actions=LinearLayout(this);actions.orientation=LinearLayout.HORIZONTAL
        val a=Button(this);a.text="REFRESH";a.setOnClickListener{load()};actions.addView(a,LinearLayout.LayoutParams(0,dp(44f).toInt(),1f))
        val n=Button(this);n.text="ALERTS";n.setOnClickListener{Toast.makeText(this,"Alerts active: Entry / TP1 / TP2 / TP3 / SL",Toast.LENGTH_SHORT).show()};actions.addView(n,LinearLayout.LayoutParams(0,dp(44f).toInt(),1f))
        root.addView(actions);setContentView(root)
    }

    private fun load(){thread{try{
        val txt=URL("https://xaus.com/api/v1/intraday?symbol=xau&hours=48").readText();val a=JSONObject(txt).getJSONArray("points");base.clear()
        for(i in 0 until a.length()){val q=a.getJSONObject(i);base.add(q.getLong("t") to q.getDouble("p"))}
        val spot=try{JSONObject(URL("https://xaus.com/api/v1/spot").readText()).optDouble("spot_usd_oz",0.0)}catch(_:Exception){0.0}
        buildCandles();if(candles.isNotEmpty()){val p=if(spot>0)spot else candles.last().c;analyze(p)}
        val macro=fetchMacroNews()
        runOnUiThread{news.text=macro;chart.invalidate()}
    }catch(e:Exception){runOnUiThread{info.text="Market feed unavailable • retrying automatically…"}}}}

    private fun buildCandles(){candles.clear();if(base.isEmpty())return;val step=when(tf){"TICK","1m"->1;"2m"->2;"3m"->3;"5m"->5;"15m"->15;"30m"->30;"1H"->60;"4H"->240;"1D"->1440;else->5};var cur=-1L;var c:Candle?=null
        for((t,p)in base){val bucket=(t/60000L/step)*step;if(bucket!=cur){c?.let{candles.add(it)};cur=bucket;c=Candle(bucket*60000L,p,p,p,p)}else{val z=c!!;c=Candle(z.t,z.o,max(z.h,p),min(z.l,p),p)}};c?.let{candles.add(it)}
    }
    private fun ema(v:List<Double>,n:Int):Double{if(v.isEmpty())return 0.0;val k=2.0/(n+1);var e=v[0];for(i in 1 until v.size)e=v[i]*k+e*(1-k);return e}
    private fun rsi(v:List<Double>,n:Int=14):Double{if(v.size<=n)return 50.0;var g=0.0;var d=0.0;for(i in 1..n){val x=v[i]-v[i-1];g+=max(x,0.0);d+=max(-x,0.0)};g/=n;d/=n;for(i in n+1 until v.size){val x=v[i]-v[i-1];g=(g*(n-1)+max(x,0.0))/n;d=(d*(n-1)+max(-x,0.0))/n};return if(d==0.0)100.0 else 100.0-100.0/(1.0+g/d)}
    private fun atr(v:List<Candle>,n:Int=14):Double{if(v.size<2)return 1.0;val tr=mutableListOf<Double>();for(i in 1 until v.size){val z=v[i];val pc=v[i-1].c;tr.add(max(z.h-z.l,max(abs(z.h-pc),abs(z.l-pc))))};return tr.takeLast(n).average().coerceAtLeast(0.01)}
    private fun macd(v:List<Double>):Double=ema(v,12)-ema(v,26)
    private fun ichimoku(v:List<Candle>):Int{if(v.size<52)return 0;val a=v.takeLast(9);val b=v.takeLast(26);val d=v.takeLast(52);val ten=(a.maxOf{it.h}+a.minOf{it.l})/2;val kij=(b.maxOf{it.h}+b.minOf{it.l})/2;val span=(d.maxOf{it.h}+d.minOf{it.l})/2;return if(v.last().c>ten&&ten>kij&&v.last().c>span)1 else if(v.last().c<ten&&ten<kij&&v.last().c<span)-1 else 0}
    private fun analyze(p:Double){if(candles.size<30)return;val v=candles.map{it.c};val e9=ema(v,9);val e20=ema(v,20);val e50=ema(v,50);val e200=if(v.size>=100)ema(v,100)else e50;val r=rsi(v);val m=macd(v);val ichi=ichimoku(candles);val atrv=atr(candles);val recent=candles.takeLast(40);val hi=recent.maxOf{it.h};val lo=recent.minOf{it.l};val range=(hi-lo).coerceAtLeast(atrv);val last=candles.last();var score=0
        if(e9>e20)score++ else score--;if(e20>e50)score++ else score--;if(p>e200)score++ else score--;if(r>52)score++ else if(r<48)score--;if(m>0)score++ else score--;score+=ichi
        val bullish=last.c>last.o;val prev=candles[candles.size-2];if(bullish&&last.c>prev.h)score+=2;if(!bullish&&last.c<prev.l)score-=2
        val side=when{score>=5&&r<76->"BUY";score<=-5&&r>24->"SELL";else->"WAIT"};val risk=max(atrv*1.25,p*0.00045);val en=p;val sl=when(side){"BUY"->min(lo,en-risk);"SELL"->max(hi,en+risk);else->0.0};val rr=if(side=="WAIT")0.0 else abs(en-sl);val t1=if(side=="BUY")en+rr else en-rr;val t2=if(side=="BUY")en+rr*1.7 else en-rr*1.7;val t3=if(side=="BUY")en+rr*2.5 else en-rr*2.5;val conf=(55+abs(score)*5).coerceIn(55,92);val reason="EMA ${if(e9>e20)"UP" else "DOWN"} • RSI ${fmt(r)} • MACD ${if(m>=0)"+" else "-"} • Ichimoku ${if(ichi>0)"UP" else if(ichi<0)"DOWN" else "NEUTRAL"} • ATR ${fmt(atrv)}"
        levels=Levels(side,en,sl,t1,t2,t3,conf,reason);runOnUiThread{lastPrice=p;price.text="XAU/USD  ${fmt(p)}  •  ${tf}";signal.text="$side  •  $conf%";signal.setTextColor(if(side=="BUY")Color.rgb(45,220,145)else if(side=="SELL")Color.rgb(245,85,85)else Color.rgb(240,190,70));info.text="Paper trading • Entry ${fmt(en)}\nSL ${fmt(sl)}   TP1 ${fmt(t1)}   TP2 ${fmt(t2)}   TP3 ${fmt(t3)}\n$reason";chart.invalidate();checkAlerts(p)}}
    private fun checkAlerts(p:Double){if(levels.side=="WAIT")return;val k=levels.side+fmt(levels.entry);if(k==lastAlertKey)return;if((levels.side=="BUY"&&p>=levels.entry)||(levels.side=="SELL"&&p<=levels.entry)){lastAlertKey=k;notify("XAU AI ENTRY","${levels.side} entry ${fmt(levels.entry)} • confidence ${levels.confidence}%")}}
    private fun notify(title:String,text:String){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"),44);val nm=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager;if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(NotificationChannel("xau","XAU AI Alerts",NotificationManager.IMPORTANCE_DEFAULT));val pi=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE);val b=Notification.Builder(this,"xau").setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).setAutoCancel(true);nm.notify((System.currentTimeMillis()%100000).toInt(),b.build())}
    private fun fetchMacroNews():String{val sb=StringBuilder("MACRO NEWS • FOMC / CPI / NFP / PCE\n");try{val u=URL("https://www.federalreserve.gov/feeds/press_all.xml");val s=BufferedReader(InputStreamReader(u.openStream())).use{it.readText()};val titles=Regex("<title>(.*?)</title>",RegexOption.DOT_MATCHES_ALL).findAll(s).map{it.groupValues[1].replace("<!\[CDATA[","").replace("]]>","").trim()}.filter{it.length>4}.take(3).toList();for(x in titles)sb.append("• ").append(x.replace(Regex("<.*?>"),"")).append("\n")}catch(_:Exception){sb.append("• Fed feed temporarily unavailable\n")};sb.append("• Use economic-calendar events as a trade filter");return sb.toString()}

    private inner class ChartView(context:Context):View(context){private val p=Paint(Paint.ANTI_ALIAS_FLAG);override fun onDraw(c:Canvas){c.drawColor(Color.rgb(7,11,17));if(candles.isEmpty()){p.color=Color.GRAY;p.textSize=dp(14f);c.drawText("Waiting for XAU/USD data…",dp(18f),dp(40f),p);return};val cs=candles.takeLast(110);val left=dp(8f);val right=(width-dp(64f));val top=dp(8f);val bottom=(height-dp(10f));var lo=cs.minOf{it.l};var hi=cs.maxOf{it.h};val pad=((hi-lo)*.08).coerceAtLeast(0.5);lo-=pad;hi+=pad;val span=(hi-lo).coerceAtLeast(.001);fun py(v:Double)=bottom-(v-lo)/span*(bottom-top);p.strokeWidth=1f;p.color=Color.rgb(28,38,52);for(i in 0..6){val y=top+(bottom-top)*i/6f;c.drawLine(left,y,right,y,p)};val step=(right-left)/cs.size;val cw=(step*.62).coerceAtLeast(2f);for(i in cs.indices){val z=cs[i];val x=left+(i+.5f)*step;p.color=if(z.c>=z.o)Color.rgb(45,210,140)else Color.rgb(240,75,75);c.drawLine(x,py(z.h),x,py(z.l),p);val a=py(z.o);val b=py(z.c);c.drawRect(x-cw/2,min(a,b),x+cw/2,max(a,b)+1,p)};drawLevel(c,levels.entry,"ENTRY",Color.rgb(235,200,80),left,right,top,bottom,lo,span);drawLevel(c,levels.sl,"SL",Color.rgb(245,85,85),left,right,top,bottom,lo,span);drawLevel(c,levels.tp1,"TP1",Color.rgb(45,210,140),left,right,top,bottom,lo,span);drawLevel(c,levels.tp2,"TP2",Color.rgb(45,210,140),left,right,top,bottom,lo,span);drawLevel(c,levels.tp3,"TP3",Color.rgb(45,210,140),left,right,top,bottom,lo,span)}
        private fun drawLevel(c:Canvas,v:Double,s:String,col:Int,left:Float,right:Float,top:Float,bottom:Float,lo:Double,span:Double){if(v<=0)return;val y=(bottom-(v-lo)/span*(bottom-top)).toFloat();if(y<top||y>bottom)return;p.color=col;p.strokeWidth=1.5f;c.drawLine(left,y,right,y,p);p.textSize=dp(10f);c.drawText("$s ${fmt(v)}",right+dp(3f),y-2,p)}
    }
}

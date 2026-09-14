package com.xauai.gold

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.view.*
import android.widget.*
import android.content.Context
import java.net.URL
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.*

data class Candle(val t:Long,val o:Double,val h:Double,val l:Double,val c:Double)

class MainActivity : Activity() {
    private lateinit var chart: ChartView
    private lateinit var price: TextView
    private lateinit var signal: TextView
    private lateinit var info: TextView
    private val candles=mutableListOf<Candle>()
    private var entry=0.0; private var sl=0.0; private var tp1=0.0; private var tp2=0.0
    private var tf="1m"
    private fun dp(v:Int)=v*resources.displayMetrics.density
    private fun fmt(v:Double)=String.format("%.2f",v)

    override fun onCreate(b:Bundle?) { super.onCreate(b)
        window.statusBarColor=Color.rgb(5,8,12); window.navigationBarColor=Color.rgb(5,8,12)
        val root=LinearLayout(this); root.orientation=LinearLayout.VERTICAL; root.setBackgroundColor(Color.rgb(5,8,12)); root.setPadding(dp(10).toInt(),dp(8).toInt(),dp(10).toInt(),dp(8).toInt())
        val title=TextView(this); title.text="XAU AI PRO  •  GOLD / USD"; title.textSize=20f; title.setTextColor(Color.WHITE); title.setTypeface(null,1); root.addView(title,LinearLayout.LayoutParams(-1,dp(38).toInt()))
        price=TextView(this); price.text="XAU/USD  —"; price.textSize=17f; price.setTextColor(Color.LTGRAY); root.addView(price,LinearLayout.LayoutParams(-1,dp(30).toInt()))
        signal=TextView(this); signal.text="WAIT"; signal.textSize=19f; signal.setTypeface(null,1); signal.setTextColor(Color.rgb(240,190,70)); root.addView(signal,LinearLayout.LayoutParams(-1,dp(34).toInt()))
        val row=LinearLayout(this); row.orientation=LinearLayout.HORIZONTAL
        listOf("1m","2m","3m","5m","15m","30m","1H").forEach{ s -> val x=Button(this); x.text=s; x.setOnClickListener{tf=s; load()}; row.addView(x,LinearLayout.LayoutParams(0,dp(42).toInt(),1f)) }
        root.addView(row)
        chart=ChartView(this); root.addView(chart,LinearLayout.LayoutParams(-1,0,1f))
        info=TextView(this); info.text="Paper trading • No real orders\nWaiting for market data…"; info.textSize=13f; info.setTextColor(Color.LTGRAY); root.addView(info,LinearLayout.LayoutParams(-1,dp(76).toInt()))
        setContentView(root); load()
    }

    private fun load(){ thread { try {
        val hours=48; val txt=URL("https://xaus.com/api/v1/intraday?symbol=xau&hours=$hours").readText(); val a=JSONObject(txt).getJSONArray("points")
        val raw=mutableListOf<Pair<Long,Double>>(); for(i in 0 until a.length()){ val q=a.getJSONObject(i); raw.add(q.getLong("t") to q.getDouble("p")) }
        val step=when(tf){"2m"->2;"3m"->3;"5m"->5;"15m"->15;"30m"->30;"1H"->60;else->1}; candles.clear(); var cur=-1L; var cc:Candle?=null
        for((t,p) in raw){ val bucket=(t/60000L/step)*step; if(bucket!=cur){ cc?.let{candles.add(it)}; cur=bucket; cc=Candle(bucket*60000L,p,p,p,p) } else { val z=cc!!; cc=Candle(z.t,z.o,max(z.h,p),min(z.l,p),p) } }; cc?.let{candles.add(it)}
        runOnUiThread{ chart.invalidate(); if(candles.isNotEmpty()) analyze(candles.last().c) }
    }catch(e:Exception){ runOnUiThread{info.text="Market feed unavailable • retrying…"} } } }

    private fun ema(v:List<Double>,n:Int):Double { if(v.isEmpty()) return 0.0; var e=v.take(n).average(); val k=2.0/(n+1); for(i in n until v.size)e=v[i]*k+e*(1-k); return e }
    private fun rsi(v:List<Double>):Double { if(v.size<15)return 50.0; var ag=0.0;var ad=0.0; for(i in 1..14){val d=v[i]-v[i-1];ag+=max(d,0.0);ad+=max(-d,0.0)};ag/=14;ad/=14;for(i in 15 until v.size){val x=v[i]-v[i-1];ag=(ag*13.0+max(x,0.0))/14.0;ad=(ad*13.0+max(-x,0.0))/14.0};return if(ad==0.0)100.0 else 100.0-100.0/(1.0+ag/ad)}
    private fun analyze(p:Double){ if(candles.size<15)return; val v=candles.map{it.c};val e9=ema(v,9);val e21=ema(v,21);val e50=ema(v,50);val r=rsi(v);val hi=candles.takeLast(min(40,candles.size)).maxOf{it.h};val lo=candles.takeLast(min(40,candles.size)).minOf{it.l};val range=max(hi-lo,0.1);val risk=max(range*0.08,p*0.00035);val score=(if(e9>e21)1 else -1)+(if(e21>e50)1 else -1)+(if(r>52)1 else if(r<48)-1 else 0);val side=if(score>=3&&r<74)"BUY" else if(score<=-3&&r>26)"SELL" else "WAIT";entry=p;sl=if(side=="SELL")p+risk else p-risk;tp1=if(side=="SELL")p-risk else p+risk;tp2=if(side=="SELL")p-risk*1.7 else p+risk*1.7;val conf=min(94,max(52,52+abs(score)*12));price.text="XAU/USD  ${fmt(p)}";signal.text="$side  $conf%";signal.setTextColor(if(side=="BUY")Color.rgb(45,220,145) else if(side=="SELL")Color.rgb(245,85,85) else Color.rgb(240,190,70));info.text="Paper trading • $tf • Entry ${fmt(entry)} • SL ${fmt(sl)} • TP1 ${fmt(tp1)} • TP2 ${fmt(tp2)}"}

    private inner class ChartView:View(this@MainActivity){private val p=Paint(1);override fun onDraw(c:Canvas){c.drawColor(Color.rgb(7,11,17));if(candles.isEmpty()){p.color=Color.GRAY;p.textSize=dp(14f).toFloat();c.drawText("Waiting for market data…",dp(18f).toFloat(),dp(40f).toFloat(),p);return};val cs=candles.takeLast(90);val left=dp(8f).toFloat();val right=(width-dp(55f)).toFloat();val top=dp(10f).toFloat();val bottom=(height-dp(14f)).toFloat();var lo=cs.minOf{it.l};var hi=cs.maxOf{it.h};val pad=(hi-lo)*.08;lo-=pad;hi+=pad;val span=max(hi-lo,.0001);p.color=Color.rgb(25,35,48);p.strokeWidth=1f;for(i in 0..5){val y=top+(bottom-top)*i/5f;c.drawLine(left,y,right,y,p)};fun py(v:Double)= (bottom-(v-lo)/span*(bottom-top)).toFloat();val w=max(3f,(right-left)/cs.size*.62f);for(i in cs.indices){val z=cs[i];val x=left+(i+.5f)*(right-left)/cs.size;p.color=Color.rgb(150,160,175);c.drawLine(x,py(z.h),x,py(z.l),p);p.color=if(z.c>=z.o)Color.rgb(45,210,140)else Color.rgb(240,75,75);val y1=py(z.o);val y2=py(z.c);c.drawRect(x-w/2,min(y1,y2),x+w/2,max(y1,y2)+1,p)};val levels=listOf(entry to "ENTRY",sl to "SL",tp1 to "TP1",tp2 to "TP2");p.textSize=dp(11f).toFloat();for((v,s)in levels){if(v>0){p.color=Color.rgb(190,190,200);c.drawLine(left,py(v),right,py(v),p);c.drawText("$s ${fmt(v)}",right+dp(3f).toFloat(),py(v)-2,p)}}}}
}

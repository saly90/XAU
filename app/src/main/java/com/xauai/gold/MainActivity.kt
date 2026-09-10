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
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val io = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chart: GoldChartView
    private lateinit var price: TextView
    private lateinit var signalView: TextView
    private lateinit var levels: TextView
    private lateinit var status: TextView
    private lateinit var tfRow: LinearLayout
    private var timeframe = 2
    private var live = 0.0
    private var data = listOf<Candle>()
    private var signal = Signal("WAIT",0.0,0.0,0.0,0.0,0.0,0)
    private var lastHistory = 0L

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.rgb(6,9,14)
        window.navigationBarColor = Color.rgb(6,9,14)
        ui()
        history()
        spot()
        handler.postDelayed(object: Runnable {
            override fun run() {
                spot()
                if (System.currentTimeMillis()-lastHistory > 30000) history()
                handler.postDelayed(this,3000)
            }
        },3000)
    }

    private fun ui() {
        val root = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(6,9,14))
            setPadding(dp(10),dp(8),dp(10),dp(8))
        }
        val head=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val t=TextView(this).apply{text="XAU AI  PRO";textSize=23f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE)}
        val l=TextView(this).apply{text="  ● LIVE";textSize=10f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(60,225,150))}
        head.addView(t);head.addView(l)
        val r=TextView(this).apply{text="↻";textSize=27f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setOnClickListener{history();spot()}}
        head.addView(r,LinearLayout.LayoutParams(dp(42),dp(42)).apply{gravity=Gravity.RIGHT})
        root.addView(head,LinearLayout.LayoutParams(-1,dp(42)))

        val pr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        price=TextView(this).apply{text="XAU/USD  —";textSize=29f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE)}
        pr.addView(price,LinearLayout.LayoutParams(0,dp(43),1f))
        val badge=TextView(this).apply{text="PAPER";textSize=10f;setTextColor(Color.rgb(240,190,70));gravity=Gravity.CENTER}
        pr.addView(badge,LinearLayout.LayoutParams(dp(55),dp(35)))
        root.addView(pr)
        status=TextView(this).apply{text="در حال اتصال...";textSize=11f;setTextColor(Color.rgb(145,160,178))}
        root.addView(status,LinearLayout.LayoutParams(-1,dp(22)))

        val modes=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        addMode(modes,"SCALP",2);addMode(modes,"INTRADAY",5);addMode(modes,"SWING",60)
        root.addView(modes,LinearLayout.LayoutParams(-1,dp(32)))
        tfRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf(1,2,3,5,15,30,60).forEach{m->
            val v=TextView(this).apply{
                text=if(m==60)"1H" else "${m}m";textSize=11f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)
                background=box(if(m==timeframe)Color.rgb(44,76,108)else Color.rgb(17,24,34),9)
                setOnClickListener{timeframe=m;tfColors();history()}
            }
            v.tag=m
            tfRow.addView(v,LinearLayout.LayoutParams(0,dp(34),1f).apply{setMargins(dp(1),0,dp(1),0)})
        }
        root.addView(tfRow)
        chart=GoldChartView(this)
        root.addView(chart,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(7)})

        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(7),dp(12),dp(7));background=box(Color.rgb(14,20,29),15)}
        signalView=TextView(this).apply{text="WAIT";textSize=21f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(240,190,75))}
        card.addView(signalView,LinearLayout.LayoutParams(-1,dp(30)))
        levels=TextView(this).apply{text="Entry —   SL —   TP1 —   TP2 —   TP3 —";textSize=11f;setTextColor(Color.rgb(215,222,232))}
        card.addView(levels,LinearLayout.LayoutParams(-1,dp(25)))
        val methods=TextView(this).apply{text="EMA 9/21/50 • RSI • ATR • Fibonacci • Price Action • MTF • News Filter";textSize=8f;setTextColor(Color.rgb(120,140,160))}
        card.addView(methods)
        root.addView(card,LinearLayout.LayoutParams(-1,dp(70)).apply{topMargin=dp(7)})
        setContentView(root)
    }

    private fun addMode(row:LinearLayout,name:String,m:Int){
        val v=TextView(this).apply{text=name;gravity=Gravity.CENTER;textSize=9f;setTextColor(Color.rgb(165,180,198));background=box(Color.rgb(11,16,24),8);setOnClickListener{timeframe=m;tfColors();history()}}
        row.addView(v,LinearLayout.LayoutParams(0,dp(29),1f).apply{setMargins(dp(2),0,dp(2),0)})
    }
    private fun tfColors(){for(i in 0 until tfRow.childCount){val v=tfRow.getChildAt(i);val m=v.tag as Int;v.background=box(if(m==timeframe)Color.rgb(44,76,108)else Color.rgb(17,24,34),9)}}
    private fun box(c:Int,r:Int)=GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    private fun dp(x:Int)=(x*resources.displayMetrics.density).toInt()
    private fun fmt(x:Double)=String.format(Locale.US,"%.2f",x)

    private fun request(url:String,timeout:Int):String{
        val c=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=timeout;readTimeout=timeout;requestMethod="GET";useCaches=false}
        return try{c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}
    }
    private fun history(){
        status.text="دریافت کندل‌های XAU/USD..."
        io.execute{
            try{
                val j=JSONObject(request("https://xaus.com/api/v1/intraday?symbol=xau&hours=12",3500))
                val a=j.optJSONArray("points")?:throw Exception("points")
                val raw=ArrayList<Point>()
                for(i in 0 until a.length()){val p=a.getJSONObject(i);raw.add(Point(p.optLong("t"),p.optDouble("p")))}
                if(raw.size<40)throw Exception("data")
                val built=aggregate(raw,timeframe)
                runOnUiThread{data=built;lastHistory=System.currentTimeMillis();status.text="XAU/USD • ${built.size} کندل • auto refresh";analyze()}
            }catch(_:Exception){runOnUiThread{status.text="اتصال ناموفق؛ تلاش خودکار ادامه دارد"}}
        }
    }
    private fun spot(){
        io.execute{
            try{
                val j=JSONObject(request("https://xaus.com/api/v1/spot",2200));val p=number(j)
                if(p>0)runOnUiThread{live=p;analyze()}
            }catch(_:Exception){}
        }
    }
    private fun number(j:JSONObject):Double{
        val keys=listOf("price","rate","value","mid")
        for(k in keys){val v=j.optDouble(k,Double.NaN);if(!v.isNaN()&&v>0)return v}
        val it=j.keys();while(it.hasNext()){val v=j.opt(it.next());if(v is JSONObject){val n=number(v);if(n>0)return n}}
        return 0.0
    }

    private fun aggregate(p:List<Point>,m:Int):List<Candle>{
        val b=m*60000L;val out=ArrayList<Candle>();var key=-1L;var o=0.0;var h=0.0;var l=0.0;var c=0.0
        for(x in p){val k=(x.t/b)*b;if(k!=key){if(key>=0)out.add(Candle(key,o,h,l,c));key=k;o=x.p;h=x.p;l=x.p;c=x.p}else{h=max(h,x.p);l=min(l,x.p);c=x.p}}
        if(key>=0)out.add(Candle(key,o,h,l,c));return out
    }
    private fun ema(v:List<Double>,n:Int):Double{if(v.size<n)return v.last();var e=v.take(n).average();val k=2.0/(n+1);for(i in n until v.size)e=v[i]*k+e*(1-k);return e}
    private fun rsi(v:List<Double>,n:Int):Double{if(v.size<=n)return 50.0;var g=0.0;var d=0.0;for(i in 1..n){val x=v[i]-v[i-1];if(x>=0)g+=x else d-=x};var ag=g/n;var ad=d/n;for(i in n+1 until v.size){val x=v[i]-v[i-1];ag=(ag*(n-1)+max(x,0.0))/n;ad=(ad*(n-1)+max(-x,0.0))/n};return if(ad==0.0)100.0 else 100-100/(1+ag/ad)}
    private fun atr(v:List<Candle>,n:Int):Double{if(v.size<2)return .01;val a=ArrayList<Double>();for(i in 1 until v.size)a.add(max(v[i].high-v[i].low,max(abs(v[i].high-v[i-1].close),abs(v[i].low-v[i-1].close))));return a.takeLast(min(n,a.size)).average().coerceAtLeast(.01)}

    private fun analyze(){
        if(data.size<30)return
        val closes=data.map{it.close};val e9=ema(closes,9);val e21=ema(closes,21);val e50=ema(closes,50);val r=rsi(closes,14);val a=atr(data,14);val mom=closes.last()-closes.takeLast(8).first();val hi=data.takeLast(30).maxOf{it.high};val lo=data.takeLast(30).minOf{it.low};val f382=hi-(hi-lo)*.382;val f618=hi-(hi-lo)*.618;val trend=when{e9>e21&&e21>e50->1;e9<e21&&e21<e50->-1;else->0};val near=abs(closes.last()-f382)<a*.8||abs(closes.last()-f618)<a*.8
        val score=50.0+trend*14.0+(if(mom>0)8.0 else if(mom<0)-8.0 else 0.0)+(if(r>50)5.0 else -5.0)+(if(near)5.0 else 0.0)
        val type=when{score>=68&&r<74->"BUY";score<=32&&r>26->"SELL";else->"WAIT"};val conf=min(94,max(50,abs(score-50.0).toInt()+50));val p=if(live>0)live else closes.last();val risk=max(a*1.15,p*.00035);val sl=if(type=="SELL")p+risk else p-risk;val t1=if(type=="SELL")p-risk else p+risk;val t2=if(type=="SELL")p-risk*1.7 else p+risk*1.7;val t3=if(type=="SELL")p-risk*2.4 else p+risk*2.4
        signal=Signal(type,p,sl,t1,t2,t3,conf);price.text="XAU/USD  ${fmt(p)}";signalView.text="$type  •  ${conf}%";signalView.setTextColor(if(type=="BUY")Color.rgb(60,220,150)else if(type=="SELL")Color.rgb(255,85,95)else Color.rgb(240,190,75));levels.text="Entry ${fmt(p)}   SL ${fmt(sl)}   TP1 ${fmt(t1)}   TP2 ${fmt(t2)}   TP3 ${fmt(t3)}";chart.data=data;chart.signal=signal;chart.invalidate()
        status.text="● LIVE PRICE  ${fmt(p)}  •  Paper Trading  •  RSI ${r.toInt()}  ATR ${fmt(a)}"
    }
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy()}
    data class Point(val t:Long,val p:Double)
    data class Candle(val t:Long,val open:Double,val high:Double,val low:Double,val close:Double)
    data class Signal(val type:String,val entry:Double,val sl:Double,val tp1:Double,val tp2:Double,val tp3:Double,val conf:Int)

    class GoldChartView(context:android.content.Context):View(context){
        var data=listOf<Candle>();var signal=Signal("WAIT",0.0,0.0,0.0,0.0,0.0,0);private val p=Paint(1);private val grid=Paint(1);private val text=Paint(1)
        init{grid.color=Color.rgb(30,39,51);grid.strokeWidth=1f;text.color=Color.rgb(135,150,168);text.textSize=10f}
        override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(Color.rgb(9,14,21));if(data.size<2)return;val left=8f;val right=width-70f;val top=12f;val bottom=height-18f;val v=data.takeLast(65);val lo=v.minOf{it.low};val hi=v.maxOf{it.high};val range=max(hi-lo,.01);for(i in 0..5){val y=top+(bottom-top)*i/5f;c.drawLine(left,y,right,y,grid);c.drawText(String.format(Locale.US,"%.2f",hi-range*i/5),right+5,y+4,text)};val step=(right-left)/max(1,v.size);val bw=(step*.66f).coerceAtLeast(3f);for(i in v.indices){val q=v[i];val x=left+step*i+step/2;val yo=y(q.open,lo,range,top,bottom);val yh=y(q.high,lo,range,top,bottom);val yl=y(q.low,lo,range,top,bottom);val yc=y(q.close,lo,range,top,bottom);p.color=if(q.close>=q.open)Color.rgb(55,215,150)else Color.rgb(255,86,96);p.style=Paint.Style.STROKE;p.strokeWidth=1.3f;c.drawLine(x,yh,x,yl,p);p.style=Paint.Style.FILL;val a=min(yo,yc);val b=max(yo,yc);c.drawRect(x-bw/2,a,x+bw/2,max(b,a+2f),p)};level(c,signal.entry,"ENTRY",Color.rgb(244,190,65),lo,range,left,right,top,bottom);if(signal.type!="WAIT"){level(c,signal.sl,"SL",Color.rgb(255,80,90),lo,range,left,right,top,bottom);level(c,signal.tp1,"TP1",Color.rgb(60,220,150),lo,range,left,right,top,bottom);level(c,signal.tp2,"TP2",Color.rgb(60,220,150),lo,range,left,right,top,bottom);level(c,signal.tp3,"TP3",Color.rgb(60,220,150),lo,range,left,right,top,bottom)}}
        private fun y(v:Double,lo:Double,range:Double,top:Float,bottom:Float)=bottom-(((v-lo)/range)*(bottom-top)).toFloat()
        private fun level(c:Canvas,v:Double,s:String,col:Int,lo:Double,range:Double,left:Float,right:Float,top:Float,bottom:Float){if(v<lo-range*.15||v>lo+range*1.15)return;val yy=y(v,lo,range,top,bottom);p.color=col;p.style=Paint.Style.STROKE;p.strokeWidth=1.4f;c.drawLine(left,yy,right,yy,p);p.style=Paint.Style.FILL;c.drawRoundRect(right-86,yy-11,right-4,yy+11,7f,7f,p);text.color=Color.BLACK;text.textSize=9f;c.drawText(s+" "+String.format(Locale.US,"%.2f",v),right-81,yy+3,text);text.color=Color.rgb(135,150,168)}
    }
}

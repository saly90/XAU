from pathlib import Path

p = Path("app/src/main/java/com/xauai/gold/MainActivity.kt")
s = p.read_text(encoding="utf-8")

old = '''            val lp=if(live>0)live else last.c;val distance=abs(lp-last.c)
            if(at<=0||(live>0&&distance>at*1.20))levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"SETUP EXPIRED • WAIT FOR RETEST",src.lastIndex,false)else{
                val entry=lp;val structureLookback=src.takeLast(30).dropLast(1);val swingLow=structureLookback.minOf{it.l};val swingHigh=structureLookback.maxOf{it.h};val sl=if(side=="BUY")min(swingLow-at*0.15,entry-at)else max(swingHigh+at*0.15,entry+at);val risk=abs(entry-sl)
                if(!risk.isFinite()||risk<=0)levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"INVALID RISK MODEL • WAIT",src.lastIndex,false)else{
                    val structuralTarget=if(side=="BUY"&&resistance>entry)resistance else if(side=="SELL"&&support<entry)support else Double.NaN;val rewardToTarget=if(side=="BUY")structuralTarget-entry else entry-structuralTarget;val rrToTarget=if(risk>0&&rewardToTarget.isFinite())rewardToTarget/risk else Double.NaN
                    if(risk>at*2.0)levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"STOP TOO FAR FROM ENTRY • WAIT FOR RETEST",src.lastIndex,false)else if(!structuralTarget.isFinite()||rewardToTarget<=0||!rrToTarget.isFinite()||rrToTarget<1.20)levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"NEAREST STRUCTURE TOO CLOSE • WAIT FOR BETTER ENTRY",src.lastIndex,false)else{
                        val recentStructure=src.takeLast(80).dropLast(1);val pivotLows=mutableListOf<Double>();val pivotHighs=mutableListOf<Double>()
                        for(i in 2 until recentStructure.size-2){val z=recentStructure[i];if(z.l<=recentStructure[i-1].l&&z.l<=recentStructure[i-2].l&&z.l<=recentStructure[i+1].l&&z.l<=recentStructure[i+2].l)pivotLows.add(z.l);if(z.h>=recentStructure[i-1].h&&z.h>=recentStructure[i-2].h&&z.h>=recentStructure[i+1].h&&z.h>=recentStructure[i+2].h)pivotHighs.add(z.h)}
                        val targets=if(side=="BUY")pivotHighs.filter{it>entry}.distinct().sorted()else pivotLows.filter{it<entry}.distinct().sortedDescending();val tp1=structuralTarget
                        val tp2Candidate=targets.firstOrNull{if(side=="BUY")it>tp1+risk*0.50&&it>=entry+risk*2.20 else it<tp1-risk*0.50&&it<=entry-risk*2.20}
                        val tp3Candidate=targets.firstOrNull{if(side=="BUY")it>max(tp1,tp2Candidate?:tp1)+risk*0.50&&it>=entry+risk*3.00 else it<min(tp1,tp2Candidate?:tp1)-risk*0.50&&it<=entry-risk*3.00}
                        val tp2=tp2Candidate?:entry+if(side=="BUY")risk*2.20 else -risk*2.20;val tp3=tp3Candidate?:entry+if(side=="BUY")risk*3.00 else -risk*3.00;val conf=(62+max(bullScore,bearScore)*4+abs(bullMtf-bearMtf)*2).coerceIn(62,90)
                        levels=Levels(side,entry,sl,tp1,tp2,tp3,conf,"RISK MODEL • STRUCTURE SL • STRUCTURE TP1 • PIVOT TP2/TP3 • MAX 2ATR RISK",src.lastIndex,true)
                    }
                }
            }'''

new = '''            val entry=if(live>0)live else last.c
            val structureLookback=src.takeLast(30).dropLast(1)
            val swingLow=structureLookback.minOf{it.l}
            val swingHigh=structureLookback.maxOf{it.h}
            val structureSl=if(side=="BUY")min(swingLow-at*0.15,entry-at)else max(swingHigh+at*0.15,entry+at)
            val structureRisk=abs(entry-structureSl)
            val sl=if(structureRisk>at*2.0)entry+if(side=="BUY")-at*1.25 else at*1.25
            val risk=abs(entry-sl)
            if(at<=0||!risk.isFinite()||risk<=0)levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"INVALID RISK MODEL • WAIT",src.lastIndex,false)else{
                val structuralTarget=if(side=="BUY"&&resistance>entry)resistance else if(side=="SELL"&&support<entry)support else Double.NaN
                val rewardToTarget=if(side=="BUY")structuralTarget-entry else entry-structuralTarget
                val rrToTarget=if(risk>0&&rewardToTarget.isFinite())rewardToTarget/risk else Double.NaN
                val structuralTpOk=structuralTarget.isFinite()&&rewardToTarget>0&&rrToTarget.isFinite()&&rrToTarget>=1.20
                val tp1=if(structuralTpOk)structuralTarget else entry+if(side=="BUY")risk*1.50 else -risk*1.50
                val recentStructure=src.takeLast(80).dropLast(1);val pivotLows=mutableListOf<Double>();val pivotHighs=mutableListOf<Double>()
                for(i in 2 until recentStructure.size-2){val z=recentStructure[i];if(z.l<=recentStructure[i-1].l&&z.l<=recentStructure[i-2].l&&z.l<=recentStructure[i+1].l&&z.l<=recentStructure[i+2].l)pivotLows.add(z.l);if(z.h>=recentStructure[i-1].h&&z.h>=recentStructure[i-2].h&&z.h>=recentStructure[i+1].h&&z.h>=recentStructure[i+2].h)pivotHighs.add(z.h)}
                val targets=if(side=="BUY")pivotHighs.filter{it>entry}.distinct().sorted()else pivotLows.filter{it<entry}.distinct().sortedDescending()
                val tp2Candidate=targets.firstOrNull{if(side=="BUY")it>tp1+risk*0.50&&it>=entry+risk*2.20 else it<tp1-risk*0.50&&it<=entry-risk*2.20}
                val tp3Candidate=targets.firstOrNull{if(side=="BUY")it>max(tp1,tp2Candidate?:tp1)+risk*0.50&&it>=entry+risk*3.00 else it<min(tp1,tp2Candidate?:tp1)-risk*0.50&&it<=entry-risk*3.00}
                val tp2=tp2Candidate?:entry+if(side=="BUY")risk*2.20 else -risk*2.20
                val tp3=tp3Candidate?:entry+if(side=="BUY")risk*3.00 else -risk*3.00
                val conf=(62+max(bullScore,bearScore)*4+abs(bullMtf-bearMtf)*2).coerceIn(62,90)
                val reason=if(structureRisk>at*2.0)"RISK MODEL • ATR SL 1.25R • STRUCTURE/ATR TARGETS" else if(structuralTpOk)"RISK MODEL • STRUCTURE SL • STRUCTURE TP1 • PIVOT TP2/TP3" else "RISK MODEL • STRUCTURE SL • 1.5R TP1 • PIVOT TP2/TP3"
                levels=Levels(side,entry,sl,tp1,tp2,tp3,conf,reason,src.lastIndex,true)
            }'''

if old not in s:
    raise SystemExit("target signal block not found; refusing to modify source")
s = s.replace(old, new, 1)

old_side = 'val side=when{bullScore>=5&&bullScore>bearScore->"BUY";bearScore>=5&&bearScore>bullScore->"SELL";else->"WAIT"}'
new_side = 'val side=when{bullScore>=5&&bullScore>=bearScore->"BUY";bearScore>=5&&bearScore>=bullScore->"SELL";else->"WAIT"}'
if old_side not in s:
    raise SystemExit("signal side logic not found; refusing to modify source")
s = s.replace(old_side, new_side, 1)

old_chart = 'val cs=candles.takeLast(140);val left=dp(7f);val right=width-dp(132f);'
if old_chart not in s:
    old_chart = 'val cs=candles.takeLast(140);val left=dp(7f);val right=width-dp(68f);'
new_chart = 'val cs=candles.takeLast(160);val left=dp(7f);val right=width-dp(142f);'
if old_chart not in s:
    raise SystemExit("chart bounds not found; refusing to modify source")
s = s.replace(old_chart, new_chart, 1)

old_label = 'c.drawText(s,right+2,yy-2,p)'
new_label = 'c.drawText(s,right+dp(3f),yy-2,p)'
if old_label in s:
    s = s.replace(old_label, new_label, 1)
else:
    old_label2 = 'c.drawText(s,right+dp(3f),yy-2,p)'
    if old_label2 not in s:
        raise SystemExit("chart label code not found; refusing to modify source")

p.write_text(s, encoding="utf-8")
print("XAU signal/chart patch applied")

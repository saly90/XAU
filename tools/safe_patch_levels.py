from pathlib import Path

PATH = Path("app/src/main/java/com/xauai/gold/MainActivity.kt")
s = PATH.read_text(encoding="utf-8")
start = s.index('                val entry=lp\n                val structureLookback=src.takeLast(30).dropLast(1)')
end_marker = '                    levels=Levels(side,entry,sl,tp1,tp2,tp3,conf,"RISK MODEL • STRUCTURE SL • ATR BUFFER • NEAREST S/R TARGET • 1.5R/2.2R/3R",src.lastIndex,true)\n                }'
end = s.index(end_marker, start) + len(end_marker)
new = '''                val entry=lp
                val structureLookback=src.takeLast(30).dropLast(1)
                val swingLow=structureLookback.minOf{it.l}
                val swingHigh=structureLookback.maxOf{it.h}
                val sl=if(side=="BUY") min(swingLow-at*0.15,entry-at*1.00) else max(swingHigh+at*0.15,entry+at*1.00)
                val risk=abs(entry-sl)
                if(risk<=0 || !risk.isFinite()){
                    levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"INVALID RISK MODEL • WAIT",src.lastIndex,false)
                }else{
                    val structuralTarget=if(side=="BUY" && resistance>entry) resistance
                    else if(side=="SELL" && support<entry) support
                    else Double.NaN
                    val rewardToTarget=if(side=="BUY") structuralTarget-entry else entry-structuralTarget
                    val rrToTarget=if(risk>0 && rewardToTarget.isFinite()) rewardToTarget/risk else Double.NaN
                    val maxRiskAtr=2.0
                    val minTp1R=1.20
                    if(risk>at*maxRiskAtr){
                        levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"STOP TOO FAR FROM ENTRY • WAIT FOR RETEST",src.lastIndex,false)
                    }else if(!structuralTarget.isFinite() || rewardToTarget<=0 || !rrToTarget.isFinite() || rrToTarget<minTp1R){
                        levels=Levels("WAIT",0.0,0.0,0.0,0.0,0.0,0,"NEAREST STRUCTURE TOO CLOSE • WAIT FOR BETTER ENTRY",src.lastIndex,false)
                    }else{
                        val recentStructure=src.takeLast(80).dropLast(1)
                        val pivotLows=mutableListOf<Double>()
                        val pivotHighs=mutableListOf<Double>()
                        for(i in 2 until recentStructure.size-2){
                            val z=recentStructure[i]
                            if(z.l<=recentStructure[i-1].l && z.l<=recentStructure[i-2].l && z.l<=recentStructure[i+1].l && z.l<=recentStructure[i+2].l) pivotLows.add(z.l)
                            if(z.h>=recentStructure[i-1].h && z.h>=recentStructure[i-2].h && z.h>=recentStructure[i+1].h && z.h>=recentStructure[i+2].h) pivotHighs.add(z.h)
                        }
                        val targets=if(side=="BUY") pivotHighs.filter{it>entry}.distinct().sorted() else pivotLows.filter{it<entry}.distinct().sortedDescending()
                        val tp1=structuralTarget
                        val tp2Candidate=targets.firstOrNull{if(side=="BUY") it>tp1+risk*0.50 && it>=entry+risk*2.20 else it<tp1-risk*0.50 && it<=entry-risk*2.20}
                        val tp3Candidate=targets.firstOrNull{if(side=="BUY") it>max(tp1,tp2Candidate?:tp1)+risk*0.50 && it>=entry+risk*3.00 else it<min(tp1,tp2Candidate?:tp1)-risk*0.50 && it<=entry-risk*3.00}
                        val tp2=tp2Candidate ?: (entry+if(side=="BUY") risk*2.20 else -risk*2.20)
                        val tp3=tp3Candidate ?: (entry+if(side=="BUY") risk*3.00 else -risk*3.00)
                        val conf=(62+max(bullScore,bearScore)*4+abs(bullMtf-bearMtf)*2).coerceIn(62,90)
                        levels=Levels(side,entry,sl,tp1,tp2,tp3,conf,"RISK MODEL • STRUCTURE SL • STRUCTURE TP1 • PIVOT TP2/TP3 • MAX 2ATR RISK",src.lastIndex,true)
                    }
                }'''
PATH.write_text(s[:start] + new + s[end:], encoding="utf-8")
print("patched", PATH)

#property strict
#property version   "1.0"

// XAU AI -> MT5 -> ntfy live bridge.
// Attach this EA to the Alpari MT5 XAUUSD/GOLD chart.
// In MT5: Tools -> Options -> Expert Advisors -> Allow WebRequest for
// https://ntfy.sh

input string InpTopic = "xauai-htekvsg22lzwelwkmzomnox46pap116w";
input string InpSymbol = "";
input int    InpHistoryM1 = 300;
input int    InpHistoryOther = 90;

string g_symbol="";

string Num(double v){ return DoubleToString(v,Digits()); }
string JsonEscape(string s){ StringReplace(s,"\\","\\\\"); StringReplace(s,"\"","\\\""); return s; }

string ResolveSymbol(){
   if(InpSymbol!="" && SymbolSelect(InpSymbol,true)) return InpSymbol;
   if(SymbolSelect("XAUUSD",true)) return "XAUUSD";
   int total=SymbolsTotal(false);
   for(int i=0;i<total;i++){
      string s=SymbolName(i,false);
      string u=s; StringToUpper(u);
      if(StringFind(u,"XAUUSD")>=0 || StringFind(u,"GOLD")>=0){
         SymbolSelect(s,true);
         return s;
      }
   }
   return "";
}

bool Publish(string payload){
   string url="https://ntfy.sh/"+InpTopic;
   string headers="Content-Type: application/json\r\nTitle: XAU AI MT5\r\nPriority: min\r\n";
   char data[]; char result[]; string result_headers;
   int n=StringToCharArray(payload,data,0,-1,CP_UTF8);
   if(n>0) ArrayResize(data,n-1);
   ResetLastError();
   int code=WebRequest("POST",url,headers,5000,data,result,result_headers);
   if(code<200 || code>=300){
      Print("XAU AI Bridge publish failed code=",code," err=",GetLastError());
      return false;
   }
   return true;
}

string PackBars(ENUM_TIMEFRAMES tf,int startPos,int count){
   MqlRates r[];
   ArraySetAsSeries(r,true);
   int copied=CopyRates(g_symbol,tf,startPos,count,r);
   if(copied<=0) return "[]";
   string out="[";
   for(int i=copied-1;i>=0;i--){
      if(out!="[") out+=",";
      out+="["+IntegerToString((long)r[i].time)+","+Num(r[i].open)+","+Num(r[i].high)+","+Num(r[i].low)+","+Num(r[i].close)+"]";
   }
   return out+"]";
}

bool SendHistoryChunk(string tfName,ENUM_TIMEFRAMES tf,int startPos,int count){
   string payload="{\"kind\":\"history\",\"symbol\":\""+JsonEscape(g_symbol)+"\",\"tf\":\""+tfName+"\",\"bars\":"+PackBars(tf,startPos,count)+"}";
   return Publish(payload);
}

void SendAllHistory(){
   // 1m history is split so every ntfy message stays small.
   int chunk=75;
   for(int start=InpHistoryM1-chunk;start>=0;start-=chunk){
      int count=MathMin(chunk,start+chunk);
      if(count>0) SendHistoryChunk("1m",PERIOD_M1,start,count);
   }
   SendHistoryChunk("5m",PERIOD_M5,0,InpHistoryOther);
   SendHistoryChunk("15m",PERIOD_M15,0,InpHistoryOther);
   SendHistoryChunk("30m",PERIOD_M30,0,InpHistoryOther);
   SendHistoryChunk("1H",PERIOD_H1,0,InpHistoryOther);
   SendHistoryChunk("4H",PERIOD_H4,0,InpHistoryOther);
   SendHistoryChunk("1D",PERIOD_D1,0,InpHistoryOther);
}

void SendTick(){
   MqlTick t;
   if(!SymbolInfoTick(g_symbol,t)) return;
   double mid=(t.bid>0 && t.ask>0)?(t.bid+t.ask)/2.0:(t.bid>0?t.bid:t.ask);
   if(mid<=0) return;
   string payload="{\"kind\":\"tick\",\"symbol\":\""+JsonEscape(g_symbol)+"\",\"time_msc\":"+IntegerToString((long)t.time_msc)+",\"bid\":"+Num(t.bid)+",\"ask\":"+Num(t.ask)+",\"mid\":"+Num(mid)+"}";
   Publish(payload);
}

int OnInit(){
   g_symbol=ResolveSymbol();
   if(g_symbol==""){
      Print("XAU AI Bridge: XAUUSD/GOLD symbol not found");
      return INIT_FAILED;
   }
   EventSetTimer(1);
   Print("XAU AI Bridge started on ",g_symbol," topic ",InpTopic);
   SendAllHistory();
   SendTick();
   return INIT_SUCCEEDED;
}

void OnDeinit(const int reason){ EventKillTimer(); }
void OnTimer(){ SendTick(); }

#property strict
#property version   "1.00"
#property description "XAU AI read-only MT5 bridge"

input string InpSymbol = "XAUUSD";
input string InpEndpoint = "https://YOUR-SERVER.example.com/mt5/update";
input string InpToken = "CHANGE_ME";
input int    InpTimerSeconds = 1;
input int    InpBars = 500;

string JsonEscape(string s){ StringReplace(s,"\\","\\\\"); StringReplace(s,"\"","\\\""); return s; }
string IsoTime(datetime t){ MqlDateTime d; TimeToStruct(t,d); return StringFormat("%04d-%02d-%02dT%02d:%02d:%02dZ",d.year,d.mon,d.day,d.hour,d.min,d.sec); }
string Num(double v){ return DoubleToString(v,Digits()); }

bool BuildBars(string symbol, ENUM_TIMEFRAMES tf, int count, string &json){
   MqlRates r[]; ArraySetAsSeries(r,true);
   int n=CopyRates(symbol,tf,0,count,r);
   if(n<=0) return false;
   string key=EnumToString(tf);
   json="\""+key+"\":[";
   for(int i=n-1;i>=0;i--){
      if(i<n-1) json+=",";
      bool openBar=(i==0);
      json+="{\"t\":"+LongToString((long)r[i].time)+",\"o\":"+Num(r[i].open)+",\"h\":"+Num(r[i].high)+",\"l\":"+Num(r[i].low)+",\"c\":"+Num(r[i].close)+",\"v\":"+LongToString((long)r[i].tick_volume)+",\"open\":"+(openBar?"true":"false")+"}";
   }
   json+="]";
   return true;
}

void Push(){
   MqlTick tick;
   if(!SymbolInfoTick(InpSymbol,tick)) return;
   string bars="";
   string one;
   ENUM_TIMEFRAMES tfs[]={PERIOD_M1,PERIOD_M5,PERIOD_M15,PERIOD_M30,PERIOD_H1,PERIOD_H4,PERIOD_D1};
   for(int i=0;i<ArraySize(tfs);i++){
      if(BuildBars(InpSymbol,tfs[i],InpBars,one)){
         if(StringLen(bars)>0) bars+=",";
         bars+=one;
      }
   }
   string body="{\"symbol\":\""+JsonEscape(InpSymbol)+"\",\"serverTime\":\""+IsoTime((datetime)tick.time)+"\",\"timeMsc\":"+LongToString((long)tick.time_msc)+",\"bid\":"+Num(tick.bid)+",\"ask\":"+Num(tick.ask)+",\"last\":"+Num(tick.last)+",\"bars\":{"+bars+"}}";
   char data[]; StringToCharArray(body,data,0,WHOLE_ARRAY,CP_UTF8);
   char result[]; string headers="Content-Type: application/json\r\nAuthorization: Bearer "+InpToken+"\r\n";
   ResetLastError();
   WebRequest("POST",InpEndpoint,headers,5000,data,result,headers);
}

int OnInit(){
   if(!SymbolSelect(InpSymbol,true)) return INIT_FAILED;
   EventSetTimer(MathMax(1,InpTimerSeconds));
   return INIT_SUCCEEDED;
}
void OnDeinit(const int reason){ EventKillTimer(); }
void OnTimer(){ Push(); }

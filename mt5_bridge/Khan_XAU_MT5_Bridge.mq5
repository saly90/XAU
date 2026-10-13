// Khan XAU MT5 Bridge EA
// Sends real Alpari MT5 ticks and OHLC bars to a user-controlled HTTPS endpoint.
// IMPORTANT: Set InpEndpoint to your deployed bridge API URL and allow that URL in
// MT5: Tools > Options > Expert Advisors > Allow WebRequest for listed URL.
// This EA does not place orders and never transmits the MT5 account password.
#property strict
#property version   "1.00"
#property description "Read-only XAU market-data bridge for Khan XAU Android app"

input string InpSymbol = "XAUUSD";
input string InpEndpoint = "https://YOUR-BRIDGE-DOMAIN.example/api/mt5";
input string InpApiKey = "CHANGE-THIS-TO-A-LONG-RANDOM-KEY";
input int    InpTimerMs = 1000;
input int    InpBarsPerTimeframe = 300;

string ResolveSymbol()
{
   if(SymbolSelect(InpSymbol, true)) return InpSymbol;
   int total = SymbolsTotal(false);
   for(int i=0; i<total; i++)
   {
      string s = SymbolName(i, false);
      if(StringFind(s, "XAUUSD") >= 0 || StringFind(s, "GOLD") >= 0)
      {
         if(SymbolSelect(s, true)) return s;
      }
   }
   return "";
}

string EscapeJson(string s)
{
   StringReplace(s, "\\", "\\\\");
   StringReplace(s, "\"", "\\\"");
   return s;
}

string RatesJson(const string symbol, const ENUM_TIMEFRAMES tf, const int count)
{
   MqlRates rates[];
   ArraySetAsSeries(rates, false);
   int copied = CopyRates(symbol, tf, 0, count, rates);
   if(copied <= 0) return "[]";
   string out = "[";
   for(int i=0; i<copied; i++)
   {
      if(i>0) out += ",";
      out += StringFormat("{\"time\":%I64d,\"open\":%.8f,\"high\":%.8f,\"low\":%.8f,\"close\":%.8f,\"tick_volume\":%I64d}",
                          (long)rates[i].time, rates[i].open, rates[i].high,
                          rates[i].low, rates[i].close, rates[i].tick_volume);
   }
   out += "]";
   return out;
}

bool PostPayload(const string body)
{
   if(StringFind(InpEndpoint, "https://YOUR-BRIDGE-DOMAIN") == 0 ||
      StringLen(InpApiKey) < 24)
   {
      Print("Bridge not configured: set InpEndpoint and a long random InpApiKey.");
      return false;
   }
   char data[], result[];
   string result_headers;
   int n = StringToCharArray(body, data, 0, WHOLE_ARRAY, CP_UTF8);
   if(n > 0) ArrayResize(data, n-1); // omit terminal NUL
   string headers = "Content-Type: application/json\r\nX-API-Key: " + InpApiKey + "\r\n";
   ResetLastError();
   int code = WebRequest("POST", InpEndpoint, headers, 2500, data, result, result_headers);
   if(code == -1)
   {
      Print("Bridge WebRequest failed: ", GetLastError(),
            ". Add endpoint domain to MT5 WebRequest allow-list.");
      return false;
   }
   if(code < 200 || code >= 300)
   {
      Print("Bridge HTTP status: ", code);
      return false;
   }
   return true;
}

void SendSnapshot()
{
   string symbol = ResolveSymbol();
   if(symbol == "") { Print("No XAUUSD/GOLD symbol found. Set InpSymbol to broker's exact symbol."); return; }
   MqlTick tick;
   if(!SymbolInfoTick(symbol, tick) || tick.bid <= 0 || tick.ask <= 0)
   {
      Print("No valid tick yet for ", symbol);
      return;
   }
   long nowMs = (long)TimeCurrent() * 1000;
   string body = "{";
   body += "\"symbol\":\"" + EscapeJson(symbol) + "\",";
   body += "\"serverTime\":" + IntegerToString((long)tick.time) + ",";
   body += "\"timestampMs\":" + IntegerToString((long)tick.time_msc) + ",";
   body += StringFormat("\"bid\":%.8f,\"ask\":%.8f,\"mid\":%.8f,",
                        tick.bid, tick.ask, (tick.bid+tick.ask)/2.0);
   body += "\"receivedAtMs\":" + IntegerToString(nowMs) + ",";
   body += "\"bars\":{";
   body += "\"M1\":" + RatesJson(symbol, PERIOD_M1, InpBarsPerTimeframe) + ",";
   body += "\"M5\":" + RatesJson(symbol, PERIOD_M5, InpBarsPerTimeframe) + ",";
   body += "\"M15\":" + RatesJson(symbol, PERIOD_M15, InpBarsPerTimeframe) + ",";
   body += "\"M30\":" + RatesJson(symbol, PERIOD_M30, InpBarsPerTimeframe) + ",";
   body += "\"H1\":" + RatesJson(symbol, PERIOD_H1, InpBarsPerTimeframe) + ",";
   body += "\"H4\":" + RatesJson(symbol, PERIOD_H4, InpBarsPerTimeframe) + ",";
   body += "\"D1\":" + RatesJson(symbol, PERIOD_D1, InpBarsPerTimeframe);
   body += "}}";
   PostPayload(body);
}

int OnInit()
{
   EventSetMillisecondTimer(MathMax(500, InpTimerMs));
   Print("Khan XAU MT5 bridge started in READ-ONLY mode.");
   Print("Resolved symbol will be auto-detected; configure endpoint/key before data can be sent.");
   return INIT_SUCCEEDED;
}
void OnDeinit(const int reason) { EventKillTimer(); }
void OnTimer() { SendSnapshot(); }
void OnTick() { /* Timer controls rate; no order functions are used. */ }

# XAU AI — MT5 read-only bridge

This bridge is deliberately read-only. It sends the live XAUUSD quote and recent OHLC bars from an MT5 terminal to a small HTTP server. No order execution is implemented.

## Architecture

`Alpari MT5 terminal → XAU_AI_Bridge.mq5 → HTTPS POST /mt5/update → server.py → GET /xau/latest → Android app`

## MT5 setup

1. Open the demo account in the desktop MT5 terminal.
2. Put `XAU_AI_Bridge.mq5` in `MQL5/Experts/` and compile it.
3. Set `InpSymbol` to the exact gold symbol shown by the broker. It is often `XAUUSD`, but broker suffixes are possible.
4. Set `InpEndpoint` to the deployed bridge URL ending in `/mt5/update`.
5. Set `InpToken` to the same secret used by the server's `MT5_BRIDGE_TOKEN` environment variable.
6. In MT5, add the bridge URL to **Tools → Options → Expert Advisors → Allow WebRequest for listed URL**.
7. Attach the EA to any chart and keep the MT5 terminal running.

## Server

Run:

```bash
export MT5_BRIDGE_TOKEN='replace-with-a-random-secret'
python3 server.py
```

Health: `/health`

Latest feed: `/xau/latest`

## Important

The Android APK must not contain the MT5 account password. The EA authenticates to the broker locally; only market data leaves the MT5 terminal.

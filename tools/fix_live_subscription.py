from pathlib import Path

p = Path("app/src/main/java/com/xauai/gold/MainActivity.kt")
s = p.read_text(encoding="utf-8")

old = '\\"arguments\\":[[\\"XAUUSD\\"]]'
new = '\\"arguments\\":[\\"XAUUSD\\"]'

if old in s:
    p.write_text(s.replace(old, new, 1), encoding="utf-8")
    print("fixed Biquote SignalR Subscribe arguments")
else:
    print("Biquote Subscribe payload already correct")

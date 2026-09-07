package com.xauai.gold

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var result: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28,36,28,28); setBackgroundColor(Color.rgb(17,17,17)) }
        val title = TextView(this).apply { text="XAU AI • GOLD ANALYZER"; textSize=24f; setTextColor(Color.WHITE); gravity=Gravity.CENTER; setPadding(0,0,0,24) }
        val sub = TextView(this).apply { text="Paper Trading • BUY / SELL / WAIT"; textSize=15f; setTextColor(Color.LTGRAY); gravity=Gravity.CENTER; setPadding(0,0,0,30) }
        val button = Button(this).apply { text="تحلیل بازار"; textSize=18f }
        result = TextView(this).apply { text="برای دریافت تحلیل، دکمه را بزنید."; textSize=17f; setTextColor(Color.WHITE); setPadding(0,35,0,0) }
        button.setOnClickListener { result.text="در حال آماده‌سازی تحلیل…\n\nنسخه APK اولیه نصب‌پذیر است.\nسیگنال واقعی پس از اتصال موتور داده بازار و خبر فعال می‌شود.\n\nحالت: WAIT" }
        root.addView(title); root.addView(sub); root.addView(button); root.addView(result)
        setContentView(root)
    }
}

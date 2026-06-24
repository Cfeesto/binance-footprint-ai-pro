package com.footprintai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

// ponytail: keep-alive only — ViewModel coroutines survive as long as process lives.
// Upgrade: run own BinanceRepository+engine inside here if cold-start background signals are needed.
class TradingService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("signals", "Trade Signals", NotificationManager.IMPORTANCE_HIGH)
        )
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(999, Notification.Builder(this, "signals")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("FootprintAI")
            .setContentText("Monitoring ETH/USDT · 5m")
            .setContentIntent(tap)
            .setOngoing(true)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}

package com.example.wifibooster

import android.app.*
import android.content.*
import android.net.*
import android.net.wifi.*
import android.os.*

/**
 * Scans periodically, finds the strongest access point broadcasting [ssid],
 * and moves to it if it is clearly better than the current one.
 */
class WifiSwitchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wifi: WifiManager
    private lateinit var cm: ConnectivityManager
    private var ssid = ""
    private var pass = ""
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val SCAN_INTERVAL_MS = 20_000L   // Android throttles scans (~4 per 2 min)
    private val HYSTERESIS_DB = 8            // only switch if new AP is 8 dB stronger

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = evaluate()
    }

    private val scanLoop = object : Runnable {
        override fun run() {
            @Suppress("DEPRECATION") wifi.startScan()
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ssid = intent?.getStringExtra("ssid") ?: ""
        pass = intent?.getStringExtra("pass") ?: ""
        startForeground(1, buildNotification())
        handler.removeCallbacks(scanLoop)
        handler.post(scanLoop)
        return START_STICKY
    }

    private fun evaluate() {
        if (ssid.isEmpty()) return
        val candidates = try { wifi.scanResults } catch (e: SecurityException) { return }
            .filter { it.SSID == ssid }
        val best = candidates.maxByOrNull { it.level } ?: return

        @Suppress("DEPRECATION") val cur = wifi.connectionInfo
        val connectedHere = cur != null && cur.ssid.trim('"') == ssid
        if (connectedHere) {
            if (cur.bssid.equals(best.BSSID, true)) return
            if (best.level < cur.rssi + HYSTERESIS_DB) return
        }
        connect(best)
    }

    @Suppress("DEPRECATION")
    private fun connect(ap: ScanResult) {
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+: apps can't force-switch saved networks. Request a
            // specific BSSID; the system may show a one-time approval dialog.
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setBssid(MacAddress.fromString(ap.BSSID))
                .apply { if (pass.isNotEmpty()) setWpa2Passphrase(pass) }
                .build()
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(spec)
                .build()
            netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            netCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                }
            }
            cm.requestNetwork(req, netCallback!!)
        } else {
            // Android 9 and below: reconfigure the saved network to the chosen BSSID.
            val cfg = wifi.configuredNetworks?.firstOrNull { it.SSID == "\"$ssid\"" } ?: return
            cfg.BSSID = ap.BSSID
            wifi.updateNetwork(cfg)
            wifi.disconnect()
            wifi.enableNetwork(cfg.networkId, true)
            wifi.reconnect()
        }
    }

    private fun buildNotification(): Notification {
        val chId = "wifi_switch"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(chId, "WiFi switching", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, chId)
            .setContentTitle("WiFi Booster running")
            .setContentText("Looking for the strongest access point")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(scanLoop)
        unregisterReceiver(scanReceiver)
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}

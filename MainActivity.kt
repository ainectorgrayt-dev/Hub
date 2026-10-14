package com.buloba.wifihotspot

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class Net(val ssid: String, val security: String, val percent: Int)

class MainActivity : AppCompatActivity() {
    private lateinit var wifi: WifiManager
    private lateinit var status: TextView
    private val nets = mutableListOf<Net>()
    private val adapter = NetAdapter(nets) { askAndConnect(it) }
    private var localHotspot: WifiManager.LocalOnlyHotspotReservation? = null

    private val scanDone = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = showResults()
    }
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) scan() else status.text = "Allow location and nearby devices access so Android can list Wi-Fi networks."
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        status = findViewById(R.id.status)
        findViewById<RecyclerView>(R.id.list).apply { layoutManager = LinearLayoutManager(context); adapter = this@MainActivity.adapter }
        findViewById<Button>(R.id.scan).setOnClickListener { scan() }
        findViewById<Button>(R.id.share).setOnClickListener { shareDialog() }
    }

    override fun onStart() { super.onStart()
        ContextCompat.registerReceiver(this, scanDone, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED) }
    override fun onStop() { super.onStop(); unregisterReceiver(scanDone) }
    override fun onDestroy() { super.onDestroy(); localHotspot?.close() }

    private fun needed() = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    private fun scan() {
        val missing = needed().filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) return perms.launch(missing.toTypedArray())
        if (!wifi.isWifiEnabled) { status.text = "Turn Wi-Fi on, then scan again."; startActivity(Intent(Settings.Panel.ACTION_WIFI)); return }
        if (!LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))) {
            status.text = "Turn on Location in quick settings. Android needs it to list Wi-Fi."; return }
        status.text = "Scanning..."
        @Suppress("DEPRECATION")
        if (!wifi.startScan()) { status.text = "Android limits scans. Showing the latest results."; showResults() }
    }

    @SuppressLint("MissingPermission")
    private fun showResults() {
        val raw = try { wifi.scanResults } catch (e: SecurityException) { emptyList<ScanResult>() }
        val best = raw.filter { it.SSID.isNotBlank() }.groupBy { it.SSID }.map { (_, g) -> g.maxBy { it.level } }.sortedByDescending { it.level }
        nets.clear()
        best.forEach { nets.add(Net(it.SSID, securityOf(it.capabilities), ((it.level + 100) * 2).coerceIn(0, 100))) }
        adapter.notifyDataSetChanged()
        status.text = if (nets.isEmpty()) "No networks found. Move closer to a router and scan again." else "${nets.size} networks found. Tap one to connect."
    }

    private fun securityOf(c: String) = when {
        c.contains("SAE") -> "WPA3"; c.contains("WPA2") || c.contains("RSN") -> "WPA2"
        c.contains("WPA") -> "WPA"; c.contains("WEP") -> "WEP"; else -> "Open" }

    private fun askAndConnect(n: Net) {
        if (n.security == "WEP" || n.security == "WPA") { toast("${n.security} networks are not supported by Android's connect API."); return }
        if (n.security == "Open") return connect(n, "")
        val pw = EditText(this).apply { hint = "Network password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("Connect to ${n.ssid}").setMessage("Enter the password you were given for this network.").setView(pw)
            .setPositiveButton("Connect") { _, _ -> connect(n, pw.text.toString()) }.setNegativeButton("Cancel", null).show()
    }

    private fun connect(n: Net, password: String) {
        val b = WifiNetworkSuggestion.Builder().setSsid(n.ssid)
        if (n.security == "WPA3") b.setWpa3Passphrase(password) else if (n.security != "Open") b.setWpa2Passphrase(password)
        wifi.removeNetworkSuggestions(emptyList()) // clear our earlier suggestion
        val code = wifi.addNetworkSuggestions(listOf(b.build()))
        if (code == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS)
            status.text = "Request sent for ${n.ssid}. Approve the Android prompt, then wait a few seconds for it to connect."
        else status.text = "Android refused the request (code $code). Check Settings > Apps > this app > Wi-Fi permissions."
    }

    private fun shareDialog() {
        AlertDialog.Builder(this).setTitle("Share as hotspot")
            .setMessage("1. Connect to a network first.\n2. Open Hotspot settings and switch Hotspot on.\n\nAndroid does not let apps switch the full hotspot on, so step 2 is done in system settings. Many phones cannot share Wi-Fi while using Wi-Fi; if yours cannot, use mobile data or a hotspot-capable phone.\n\nThe Local hotspot option starts a private hotspot for nearby devices, without internet.")
            .setPositiveButton("Hotspot settings") { _, _ -> openTethering() }
            .setNeutralButton(if (localHotspot == null) "Local hotspot" else "Stop local hotspot") { _, _ -> toggleLocal() }
            .setNegativeButton("Close", null).show()
    }

    private fun openTethering() {
        try { startActivity(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")) }
        catch (e: Exception) { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
    }

    @SuppressLint("MissingPermission")
    private fun toggleLocal() {
        if (localHotspot != null) { localHotspot?.close(); localHotspot = null; status.text = "Local hotspot stopped."; return }
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    localHotspot = r
                    val (s, p) = if (Build.VERSION.SDK_INT >= 30) r.softApConfiguration.let { it.ssid to it.passphrase }
                        else @Suppress("DEPRECATION") r.wifiConfiguration.let { it?.SSID to it?.preSharedKey }
                    status.text = "Local hotspot on: $s"
                    AlertDialog.Builder(this@MainActivity).setTitle("Local hotspot is on").setMessage("Name: $s\nPassword: $p\n\nOther phones can join it, but it has no internet.").setPositiveButton("OK", null).show()
                }
                override fun onStopped() { localHotspot = null }
                override fun onFailed(reason: Int) { status.text = "Could not start the local hotspot (reason $reason). Turn Wi-Fi on and try again." }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) { perms.launch(needed().toTypedArray()) }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}

class NetAdapter(private val items: List<Net>, private val click: (Net) -> Unit) : RecyclerView.Adapter<NetAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name); val detail: TextView = v.findViewById(R.id.detail); val bar: ProgressBar = v.findViewById(R.id.bar)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_network, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val n = items[i]; h.name.text = n.ssid; h.detail.text = "${n.security}  -  ${n.percent}% signal"; h.bar.progress = n.percent
        h.itemView.setOnClickListener { click(n) }
    }
}

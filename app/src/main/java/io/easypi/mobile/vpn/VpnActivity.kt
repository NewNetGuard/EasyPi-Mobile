package io.easypi.mobile.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.easypi.mobile.EasyPiLinkStore
import io.easypi.mobile.VpnRoutingMode
import io.easypi.mobile.backButton
import io.easypi.mobile.prism.PrismDnsController
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VpnActivity : Activity() {
    private lateinit var statusValue: TextView
    private lateinit var serverValue: TextView
    private lateinit var durationValue: TextView
    private lateinit var trafficValue: TextView
    private lateinit var connectButton: Button
    private lateinit var connectionProgress: ProgressBar
    private lateinit var selectedAppsButton: Button
    private lateinit var routingGroup: RadioGroup
    @Volatile private var preparing = false
    @Volatile private var disconnecting = false
    @Volatile private var lastTrafficStats = ChameleonStats(false, 0, 0)
    private val trafficStatsLoading = AtomicBoolean(false)
    private val trafficStatsExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "EasyPi-VPN-stats") }

    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            window.decorView.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        setContentView(buildScreen())
    }

    override fun onResume() {
        super.onResume()
        window.decorView.removeCallbacks(refreshTask)
        refreshTask.run()
    }

    override fun onPause() {
        window.decorView.removeCallbacks(refreshTask)
        super.onPause()
    }

    override fun onDestroy() {
        trafficStatsExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val store = EasyPiLinkStore(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(38), dp(22), dp(36))
            setBackgroundColor(BACKGROUND)
            addView(LinearLayout(this@VpnActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(backButton())
                addView(LinearLayout(this@VpnActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("VPN", 32f, Color.WHITE, Typeface.BOLD))
                    addView(text("Chameleon VPN", 15f, ACCENT))
                })
                setPadding(0, 0, 0, dp(22))
            })
            addView(card().apply {
                statusValue = value("Disconnected")
                addView(label("STATUS"))
                addView(statusValue)
                serverValue = value("Not configured")
                addView(label("SERVER").spaced())
                addView(serverValue)
                durationValue = value("—")
                addView(label("CONNECTED TIME").spaced())
                addView(durationValue)
                trafficValue = value("Received 0 B · Sent 0 B")
                addView(label("TRAFFIC").spaced())
                addView(trafficValue)
            })
            addView(card().apply {
                addView(label("ROUTING MODE"))
                routingGroup = RadioGroup(this@VpnActivity).apply {
                    orientation = RadioGroup.VERTICAL
                    val all = RadioButton(this@VpnActivity).apply {
                        id = View.generateViewId()
                        text = "All internet traffic through EasyPi"
                        setTextColor(Color.WHITE)
                        tag = VpnRoutingMode.ALL_TRAFFIC
                    }
                    val selected = RadioButton(this@VpnActivity).apply {
                        id = View.generateViewId()
                        text = "Only selected applications"
                        setTextColor(Color.WHITE)
                        tag = VpnRoutingMode.SELECTED_APPS
                    }
                    addView(all)
                    addView(selected)
                    check(if (store.vpnRoutingMode() == VpnRoutingMode.ALL_TRAFFIC) all.id else selected.id)
                    setOnCheckedChangeListener { group, checkedId ->
                        val mode = group.findViewById<RadioButton>(checkedId).tag as VpnRoutingMode
                        store.saveVpnRouting(mode, store.selectedVpnApps())
                        selectedAppsButton.visibility = if (mode == VpnRoutingMode.SELECTED_APPS) View.VISIBLE else View.GONE
                    }
                }
                addView(routingGroup)
                selectedAppsButton = Button(this@VpnActivity).apply {
                    text = "Choose applications"
                    isAllCaps = false
                    setOnClickListener { chooseApplications() }
                    visibility = if (store.vpnRoutingMode() == VpnRoutingMode.SELECTED_APPS) View.VISIBLE else View.GONE
                }
                addView(selectedAppsButton)
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14) })
            connectButton = Button(this@VpnActivity).apply {
                text = "Connect"
                isAllCaps = false
                textSize = 17f
                setTextColor(Color.WHITE)
                background = rounded(BLUE, dp(14).toFloat())
                setOnClickListener { toggleConnection() }
            }
            addView(FrameLayout(this@VpnActivity).apply {
                addView(connectButton, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                connectionProgress = ProgressBar(this@VpnActivity).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
                    visibility = View.GONE
                    contentDescription = "VPN operation in progress"
                }
                addView(connectionProgress, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    marginEnd = dp(20)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
                topMargin = dp(18)
            })
            addView(text(
                "EasyPi Mobile itself stays on Mobile Link, so server controls remain available even when this VPN is disconnected or reconnecting.",
                12.5f,
                MUTED,
            ).apply { setPadding(dp(3), dp(16), dp(3), 0) })
        }
        return ScrollView(this).apply { isFillViewport = true; addView(content) }
    }

    private fun toggleConnection() {
        if (preparing || disconnecting) return
        if (ChameleonController.isRunning()) {
            disconnecting = true
            refresh()
            ChameleonController.stop(this)
            return
        }
        val store = EasyPiLinkStore(this)
        val link = store.linkedEasyPi() ?: run {
            Toast.makeText(this, "Connect this phone to EasyPi first", Toast.LENGTH_LONG).show()
            return
        }
        if (store.vpnRoutingMode() == VpnRoutingMode.SELECTED_APPS && store.selectedVpnApps().isEmpty()) {
            Toast.makeText(this, "Choose at least one application", Toast.LENGTH_LONG).show()
            return
        }
        // The VPN server is independent of the home EasyPi. Connect with the
        // saved profile at once and refresh it in the background, so the VPN
        // still starts while the home EasyPi is unreachable.
        val saved = store.chameleonProfile()
        if (saved != null) {
            startWithPermission()
            Thread {
                runCatching { ChameleonController.prepareProfile(applicationContext, link) }
                    .onSuccess { fresh ->
                        if (fresh != saved && ChameleonController.isRunning()) {
                            ChameleonController.restart(applicationContext)
                        }
                    }
            }.start()
            return
        }
        preparing = true
        refresh()
        Thread {
            val result = runCatching { ChameleonController.prepareProfile(this, link) }
            runOnUiThread {
                preparing = false
                result.onSuccess { startWithPermission() }
                    .onFailure { Toast.makeText(this, it.message ?: "Chameleon setup failed", Toast.LENGTH_LONG).show() }
                refresh()
            }
        }.start()
    }

    private fun startWithPermission() {
        val permission = ChameleonController.needsVpnPermission(this)
        if (permission == null) ChameleonController.start(this)
        else startActivityForResult(permission, VPN_PERMISSION_REQUEST)
    }

    @Deprecated("Android VPN consent still uses an activity result on supported API levels")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) ChameleonController.start(this)
    }

    private fun chooseApplications() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase(Locale.getDefault()) }
        val store = EasyPiLinkStore(this)
        val selected = store.selectedVpnApps().toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in selected }
        AlertDialog.Builder(this)
            .setTitle("Applications through VPN")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, index, enabled ->
                if (enabled) selected.add(apps[index].first) else selected.remove(apps[index].first)
            }
            .setPositiveButton("Save") { _, _ ->
                store.saveVpnRouting(VpnRoutingMode.SELECTED_APPS, selected)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refresh() {
        if (!::statusValue.isInitialized) return
        val profile = EasyPiLinkStore(this).chameleonProfile()
        val running = ChameleonController.isRunning()
        if (disconnecting && !running) disconnecting = false
        val busy = preparing || disconnecting
        statusValue.text = when {
            preparing -> "Preparing secure profile…"
            disconnecting -> "Disconnecting…"
            running && PrismDnsController.isRunning() -> "Connected · PRISM active"
            running -> "Connected"
            else -> "Disconnected"
        }
        statusValue.setTextColor(if (running && !disconnecting) SUCCESS else if (busy) WARNING else MUTED)
        serverValue.text = profile?.serverAddress ?: "Not configured"
        val connectedAt = ChameleonController.connectedAtMillis()
        durationValue.text = if (running && connectedAt > 0) formatDuration(System.currentTimeMillis() - connectedAt) else "—"
        requestTrafficStats()
        val stats = lastTrafficStats
        trafficValue.text = "Received ${formatBytes(stats.bytesReceived)} · Sent ${formatBytes(stats.bytesSent)}"
        connectButton.text = when {
            preparing -> "Preparing…"
            disconnecting -> "Disconnecting…"
            running -> "Disconnect"
            else -> "Connect"
        }
        connectButton.isEnabled = !busy
        connectButton.alpha = if (busy) 0.68f else 1f
        connectionProgress.visibility = if (busy) View.VISIBLE else View.GONE
        routingGroup.isEnabled = !busy && !running
        selectedAppsButton.isEnabled = !busy && !running
    }

    private fun requestTrafficStats() {
        if (!trafficStatsLoading.compareAndSet(false, true)) return
        runCatching {
            trafficStatsExecutor.execute {
                lastTrafficStats = ChameleonController.stats()
                trafficStatsLoading.set(false)
            }
        }.onFailure { trafficStatsLoading.set(false) }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(19), dp(20), dp(19))
        background = rounded(CARD, dp(20).toFloat(), BORDER, dp(1))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun label(value: String) = text(value, 11f, ACCENT, Typeface.BOLD)
    private fun value(value: String) = text(value, 17f, Color.WHITE, Typeface.BOLD).apply { setPadding(0, dp(6), 0, 0) }
    private fun TextView.spaced() = apply { setPadding(0, dp(18), 0, 0) }
    private fun text(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setTypeface(typeface, style)
    }
    private fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke != null) setStroke(strokeWidth, stroke)
    }
    private fun formatDuration(milliseconds: Long): String {
        val total = (milliseconds / 1_000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
    }
    private fun formatBytes(value: Long): String = when {
        value >= 1_073_741_824 -> String.format(Locale.US, "%.1f GB", value / 1_073_741_824.0)
        value >= 1_048_576 -> String.format(Locale.US, "%.1f MB", value / 1_048_576.0)
        value >= 1_024 -> String.format(Locale.US, "%.1f KB", value / 1_024.0)
        else -> "$value B"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val VPN_PERMISSION_REQUEST = 7102
        val BACKGROUND = Color.rgb(5, 15, 27)
        val CARD = Color.rgb(18, 34, 53)
        val BORDER = Color.rgb(39, 66, 92)
        val BLUE = Color.rgb(41, 137, 245)
        val ACCENT = Color.rgb(82, 191, 255)
        val MUTED = Color.rgb(164, 183, 204)
        val SUCCESS = Color.rgb(55, 220, 143)
        val WARNING = Color.rgb(255, 179, 74)
    }
}

package io.easypi.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.easypi.mobile.R
import io.easypi.mobile.photos.PhotosActivity
import io.easypi.mobile.photos.PhotoSyncWorker
import io.easypi.mobile.cameras.CamerasActivity
import io.easypi.mobile.dashboard.ProtectionOrbitView
import io.easypi.mobile.dashboard.ProtectionStatsClient
import io.easypi.mobile.dashboard.TrafficPulseView
import io.easypi.mobile.prism.PrismDnsVpnService
import io.easypi.mobile.vpn.ChameleonController
import io.easypi.mobile.vpn.VpnActivity

class HomeActivity : Activity() {
    private lateinit var trafficChart: TrafficPulseView
    private lateinit var protectionChart: ProtectionOrbitView
    private lateinit var vpnCard: View
    private lateinit var protectionCard: View
    private val statusRefresh = object : Runnable {
        override fun run() {
            val protectionActive = PrismDnsVpnService.isProtectionRunning &&
                !PrismDnsVpnService.trafficBlocked && !PrismDnsVpnService.policyUnavailable
            val vpnActive = ChameleonController.isRunning() && ChameleonController.stats().connected
            updateCard(vpnCard, "VPN", vpnActive)
            updateCard(protectionCard, "Protection", protectionActive)
            window.decorView.postDelayed(this, 1_000L)
        }
    }
    @Volatile private var loadingDashboard = false
    private val dashboardRefresh = object : Runnable {
        override fun run() {
            refreshDashboard()
            window.decorView.postDelayed(this, DASHBOARD_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        val link = EasyPiLinkStore(this).linkedEasyPi()
        if (!link?.photoFolder.isNullOrBlank()) {
            PhotoSyncWorker.schedule(this)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(42), dp(24), dp(32))
            setBackgroundColor(BACKGROUND)
            addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@HomeActivity).apply {
                    setImageResource(R.drawable.easypi_web_logo)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) })
                addView(text("EasyPi Mobile", 32f, Color.WHITE, Typeface.BOLD), LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ))
                addView(ImageButton(this@HomeActivity).apply {
                    setImageResource(R.drawable.ic_settings)
                    setColorFilter(Color.WHITE)
                    contentDescription = "Settings"
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(14).toFloat()
                        setColor(CARD)
                        setStroke(dp(1), BORDER)
                    }
                    setPadding(dp(13), dp(13), dp(13), dp(13))
                    setOnClickListener { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) }
                }, LinearLayout.LayoutParams(dp(52), dp(52)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(text("This phone today", 16f, MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(20))
            })
            trafficChart = TrafficPulseView(this@HomeActivity)
            addView(trafficChart, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220),
            ))
            protectionChart = ProtectionOrbitView(this@HomeActivity)
            addView(protectionChart, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(210),
            ).apply { topMargin = dp(14) })
            addView(text("Choose a section", 16f, MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            })
            vpnCard = destination(
                title = "VPN",
                subtitle = "Chameleon connection and routing",
            ) { startActivity(Intent(this@HomeActivity, VpnActivity::class.java)) }.spaced()
            addView(vpnCard)
            protectionCard = destination(
                title = "Protection",
                subtitle = "Your home EasyPi rules on this phone",
            ) { startActivity(Intent(this@HomeActivity, MainActivity::class.java)) }.spaced()
            addView(protectionCard)
            addView(destination(
                title = "Gallery",
                subtitle = "Photos on this phone and your home EasyPi",
            ) { startActivity(Intent(this@HomeActivity, PhotosActivity::class.java)) }.spaced())
            addView(destination(
                title = "Cameras",
                subtitle = "View cameras connected to your home EasyPi",
            ) { startActivity(Intent(this@HomeActivity, CamerasActivity::class.java)) }.spaced())
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
            addView(content)
        })
        if (link != null && link.photoFolder.isNullOrBlank()) {
            PhotoFolderSetup.show(this, required = true) {
                PhotoSyncWorker.onBackupSettingsChanged(this)
                PhotoSyncWorker.requestMediaAccessIfNeeded(this)
            }
        } else {
            PhotoSyncWorker.requestMediaAccessIfNeeded(this)
        }
    }

    @Deprecated("Activity permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PhotoSyncWorker.onMediaAccessResult(this, requestCode)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.removeCallbacks(dashboardRefresh)
        dashboardRefresh.run()
        statusRefresh.run()
    }

    override fun onPause() {
        window.decorView.removeCallbacks(dashboardRefresh)
        window.decorView.removeCallbacks(statusRefresh)
        super.onPause()
    }

    private fun refreshDashboard() {
        if (loadingDashboard || !::trafficChart.isInitialized) return
        val stored = EasyPiLinkStore(this).linkedEasyPi()
        if (stored == null) {
            trafficChart.showMessage("Connect this phone to load statistics")
            protectionChart.showMessage("Protection data will appear after pairing")
            return
        }
        loadingDashboard = true
        Thread {
            val result = runCatching { ProtectionStatsClient.load(this) }
            runOnUiThread {
                loadingDashboard = false
                result.onSuccess {
                    trafficChart.showStats(it)
                    protectionChart.showStats(it)
                }.onFailure {
                    trafficChart.showMessage("Traffic statistics are temporarily unavailable")
                    protectionChart.showMessage("Protection statistics are temporarily unavailable")
                }
            }
        }.start()
    }

    private fun destination(title: String, subtitle: String, open: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(14), dp(22), dp(14))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(CARD)
            setStroke(dp(1), BORDER)
        }
        setOnClickListener { open() }
        isClickable = true
        isFocusable = true
        contentDescription = title
        addView(LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 21f, Color.WHITE, Typeface.BOLD))
            addView(text(subtitle, 14f, MUTED).apply { setPadding(0, dp(6), dp(10), 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(112),
        )
    }

    private fun updateCard(card: View, title: String, active: Boolean) {
        val background = card.background as GradientDrawable
        background.setColor(if (active) ACTIVE_CARD else CARD)
        background.setStroke(dp(1), if (active) ACTIVE_BORDER else BORDER)
        card.contentDescription = if (active) "$title, active" else title
    }

    private fun View.spaced() = apply {
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
    }

    private fun text(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setTypeface(typeface, style)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = Color.rgb(5, 15, 27)
        private val CARD = Color.rgb(18, 34, 53)
        private val BORDER = Color.rgb(39, 66, 92)
        private val ACTIVE_CARD = Color.rgb(13, 59, 42)
        private val ACTIVE_BORDER = Color.rgb(71, 206, 136)
        private val MUTED = Color.rgb(164, 183, 204)
        private const val DASHBOARD_REFRESH_MS = 10_000L
    }
}

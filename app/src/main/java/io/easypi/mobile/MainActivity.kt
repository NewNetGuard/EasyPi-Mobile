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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.easypi.mobile.prism.PrismDnsController
import io.easypi.mobile.sharing.EasyPiUploader
import java.io.File

class MainActivity : Activity() {
    private lateinit var protectionIcon: TextView
    private lateinit var protectionTitle: TextView
    private lateinit var protectionDetail: TextView
    private lateinit var protectionButton: Button
    private lateinit var modeSection: View
    private lateinit var normalModeButton: LinearLayout
    private lateinit var strictModeButton: LinearLayout
    private lateinit var modeDetail: TextView
    private lateinit var connectionValue: TextView
    private lateinit var queueValue: TextView
    private var waitingForVpnPermission = false
    @Volatile private var uploadingQueuedFiles = false
    @Volatile private var changingProtection = false
    private var prismStopChecks = 0
    @Volatile private var checkingMobileApi = false
    @Volatile private var mobileApiReachable: Boolean? = null
    private var prismStartChecks = 0
    private val policyRefresh = object : Runnable {
        override fun run() { refresh(); window.decorView.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        setContentView(buildScreen())
    }

    override fun onResume() {
        super.onResume()
        refresh()
        refreshMobileApiStatus()
        retryQueuedUploads()
        window.decorView.removeCallbacks(policyRefresh)
        window.decorView.postDelayed(policyRefresh, 1000)
    }

    override fun onPause() {
        window.decorView.removeCallbacks(policyRefresh)
        super.onPause()
    }

    private fun buildScreen(): View {
        val body = vertical().apply {
            setPadding(dp(20), dp(20), dp(20), dp(36))
            setBackgroundColor(BACKGROUND)
        }
        body.addView(header())
        body.addView(protectionCard().marginTop(24))
        body.addView(connectionCard().marginTop(14))
        body.addView(filesCard().marginTop(14))
        body.addView(label(
            "PRISM checks network metadata, not message content. Gallery and sharing access the media you choose or enable for backup. Filtering on this phone blocks connections that hide their destination.",
            12.5f,
            MUTED,
        ).apply { setPadding(dp(4), dp(20), dp(4), 0) })
        return ScrollView(this).apply { isFillViewport = true; addView(body) }
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(backButton())
        addView(vertical().apply {
            addView(label("EasyPi", 29f, Color.WHITE, Typeface.BOLD))
            addView(label("Mobile protection", 13f, ACCENT).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun protectionCard(): View = vertical().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(22), dp(25), dp(22), dp(22))
        background = rounded(CARD, dp(24).toFloat(), BLUE_BORDER, dp(1))
        protectionIcon = label("◇", 38f, ACCENT, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(ICON_BACKGROUND, dp(36).toFloat())
        }
        addView(protectionIcon, LinearLayout.LayoutParams(dp(72), dp(72)))
        protectionTitle = label("Checking protection…", 23f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(17), 0, 0)
        }
        addView(protectionTitle)
        protectionDetail = label("", 14f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        addView(protectionDetail)
        protectionButton = primaryButton("Please wait") { toggleProtection() }.apply { isEnabled = false }
        addView(protectionButton.marginTop(21))
        modeSection = vertical().apply {
            addView(label("PROTECTION MODE", 12f, ACCENT, Typeface.BOLD))
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            normalModeButton = modeButton("Normal", "Filtered on this phone") { selectProtectionMode(strict = false) }
            strictModeButton = modeButton("Strict", "All traffic via home") { selectProtectionMode(strict = true) }
            row.addView(normalModeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            row.addView(strictModeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
            addView(row.marginTop(10))
            modeDetail = label("", 13f, MUTED).apply { setPadding(dp(2), dp(10), dp(2), 0) }
            addView(modeDetail)
        }
        addView(modeSection.marginTop(22))
    }

    /** One of the two Protection modes; the selected one is drawn filled. */
    private fun modeButton(title: String, subtitle: String, action: () -> Unit) = vertical().apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(12), dp(10), dp(12))
        isClickable = true
        setOnClickListener { action() }
        addView(label(title, 16f, Color.WHITE, Typeface.BOLD).apply { gravity = Gravity.CENTER })
        addView(label(subtitle, 12f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
    }

    private fun selectProtectionMode(strict: Boolean) {
        if (PrismDnsController.isStrict(this) == strict) return
        PrismDnsController.setStrict(this, strict)
        refresh()
        Toast.makeText(
            this,
            if (strict) "Strict mode: all traffic goes through your home EasyPi" else "Normal mode: filtered on this phone",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun showProtectionMode(strict: Boolean) {
        normalModeButton.background = if (strict) rounded(SECONDARY, dp(13).toFloat()) else rounded(BLUE, dp(13).toFloat(), ACCENT, dp(2))
        strictModeButton.background = if (strict) rounded(BLUE, dp(13).toFloat(), ACCENT, dp(2)) else rounded(SECONDARY, dp(13).toFloat())
        // The subtitle stays readable on the filled (selected) button.
        (normalModeButton.getChildAt(1) as TextView).setTextColor(if (strict) MUTED else Color.WHITE)
        (strictModeButton.getChildAt(1) as TextView).setTextColor(if (strict) Color.WHITE else MUTED)
        modeDetail.text = if (strict) {
            "All of this phone's traffic goes through your home EasyPi. EasyPi sees and controls everything; speed depends on the connection to home."
        } else {
            "This phone filters its own traffic with your EasyPi's rules and connects straight to the internet. Fastest on every network."
        }
    }

    private fun connectionCard(): View = vertical().apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(SURFACE, dp(18).toFloat())
        addView(sectionHeader("HOME EASYPI", "Mobile access is independent from full-traffic protection"))
        connectionValue = label("Checking…", 17f, Color.WHITE, Typeface.BOLD).apply { setPadding(0, dp(15), 0, 0) }
        addView(connectionValue)
        addView(label("Mobile Link carries PRISM policy, statistics, and EasyPi controls over HTTPS. It does not depend on the VPN switch.", 13f, MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun filesCard(): View = vertical().apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(SURFACE, dp(18).toFloat())
        addView(sectionHeader("SEND TO EASYPI", "Share directly from Photos or Files"))
        queueValue = label("Checking queue…", 16f, Color.WHITE, Typeface.BOLD).apply { setPadding(0, dp(15), 0, 0) }
        addView(queueValue)
        addView(label("Choose Share, then Send to EasyPi. The original file name is preserved.", 13f, MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun sectionHeader(title: String, subtitle: String): View = vertical().apply {
        addView(label(title, 12f, ACCENT, Typeface.BOLD))
        addView(label(subtitle, 13f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
    }

    private fun refresh() {
        val store = EasyPiLinkStore(this)
        val linked = store.isLinked()
        val prism = PrismDnsController.isRunning()
        val prismStarting = PrismDnsController.isStarting()
        val prismStopping = PrismDnsController.isStopping()
        when {
            io.easypi.mobile.prism.PrismDnsVpnService.trafficBlocked -> showProtection("!", "Traffic is blocked", "The protected connection could not start. Reconnect or explicitly stop protection to resume traffic.", "Connection options", false)
            !linked -> showProtection("!", "Connect your EasyPi", "Link this phone once to activate protection and private file transfer.", "How to connect", false)
            prismStopping -> showProtection("◌", "Stopping protection…", "Closing the PRISM traffic tunnel safely.", "Please wait", false)
            prism && io.easypi.mobile.prism.PrismDnsVpnService.policyUnavailable -> showProtection("!", "Protection: restricted", "Policy has not been verified or is unavailable. Unverified domains are blocked until EasyPi responds.", "Turn off protection", true)
            prism -> showProtection("✓", "Protection is on", "PRISM checks this phone's domains through authenticated Mobile Link. Unverified domains are blocked if the server becomes unavailable.", "Turn off protection", true)
            prismStarting -> showProtection("◌", "Starting protection…", "Preparing the secure PRISM traffic tunnel.", "Please wait", false)
            else -> showProtection("◇", "Protection is off", "Mobile Link stays online. Turn on PRISM to analyse and filter this phone's traffic.", "Turn on protection", false)
        }
        showProtectionMode(PrismDnsController.isStrict(this))
        modeSection.visibility = if (linked) View.VISIBLE else View.GONE
        connectionValue.text = when {
            !linked -> "○  Not connected"
            checkingMobileApi -> "◌  Checking ${store.displayName()}…"
            mobileApiReachable == true -> "●  ${store.displayName()} · Mobile Link online"
            mobileApiReachable == false -> "○  ${store.displayName()} · Mobile Link unavailable"
            else -> "●  ${store.displayName()} · Linked"
        }
        connectionValue.setTextColor(if (linked && mobileApiReachable != false) SUCCESS else WARNING)
        val queuedFiles = queuedFileCount()
        queueValue.text = when {
            uploadingQueuedFiles -> "Sending queued files…"
            queuedFiles == 0 -> "No files waiting"
            queuedFiles == 1 -> "1 file waiting to send"
            else -> "$queuedFiles files waiting to send"
        }
        queueValue.setTextColor(if (queuedFiles == 0) SUCCESS else WARNING)
    }

    private fun refreshMobileApiStatus() {
        if (checkingMobileApi) return
        val link = EasyPiLinkStore(this).linkedEasyPi() ?: run {
            mobileApiReachable = null
            return
        }
        checkingMobileApi = true
        refresh()
        Thread {
            val reachable = runCatching { MobileApiClient.ping(link) }.isSuccess
            runOnUiThread {
                mobileApiReachable = reachable
                checkingMobileApi = false
                refresh()
            }
        }.start()
    }

    private fun showProtection(icon: String, title: String, detail: String, button: String, active: Boolean) {
        protectionIcon.text = icon
        protectionIcon.setTextColor(if (active) SUCCESS else ACCENT)
        protectionTitle.text = title
        protectionDetail.text = detail
        protectionButton.text = button
        protectionButton.isEnabled = !changingProtection
        protectionButton.background = rounded(if (active) SECONDARY else BLUE, dp(13).toFloat())
    }

    private fun toggleProtection() {
        if (changingProtection) return
        if (io.easypi.mobile.prism.PrismDnsVpnService.trafficBlocked) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Protected connection unavailable")
                .setMessage("Traffic remains blocked while EasyPi reconnects.")
                .setPositiveButton("Retry") { _, _ -> requestProtectionPermission() }
                .setNegativeButton("Stop VPN and protection") { _, _ -> PrismDnsController.stopAll(this); window.decorView.postDelayed(::refresh, 300) }
                .setNeutralButton("Cancel", null).show()
            return
        }
        val store = EasyPiLinkStore(this)
        if (!store.isLinked()) {
            showLinkInstructions()
            return
        }
        if (PrismDnsController.isRunning()) {
            changingProtection = true
            prismStopChecks = 0
            PrismDnsController.stop(this)
            refresh()
            window.decorView.postDelayed(::checkPrismShutdown, 150)
        } else requestProtectionPermission()
    }

    private fun checkPrismShutdown() {
        if (!PrismDnsController.isRunning() && !PrismDnsController.isStopping()) {
            changingProtection = false
            Toast.makeText(this, "EasyPi protection is off", Toast.LENGTH_SHORT).show()
            refresh()
        } else if (prismStopChecks++ < PRISM_START_CHECK_LIMIT) {
            window.decorView.postDelayed(::checkPrismShutdown, PRISM_START_CHECK_DELAY_MS)
        } else {
            changingProtection = false
            refresh()
        }
    }

    private fun requestProtectionPermission() {
        val permission = PrismDnsController.needsVpnPermission(this)
        if (permission != null) {
            waitingForVpnPermission = true
            startActivityForResult(permission, VPN_PERMISSION_REQUEST)
        } else window.decorView.postDelayed(::startPrism, 250)
    }

    private fun startPrism() {
        changingProtection = true
        prismStartChecks = 0
        PrismDnsController.start(this)
        refresh()
        window.decorView.postDelayed(::checkPrismStartup, 150)
    }

    private fun checkPrismStartup() {
        when {
            PrismDnsController.isRunning() -> {
                changingProtection = false
                Toast.makeText(this, "PRISM Protection is active", Toast.LENGTH_SHORT).show()
                refresh()
            }
            !PrismDnsController.isStarting() -> {
                changingProtection = false
                val reason = PrismDnsController.lastStartError()
                    ?.let { "Protection could not start: $it" }
                    ?: "Protection could not start"
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                refresh()
            }
            prismStartChecks++ < PRISM_START_CHECK_LIMIT ->
                window.decorView.postDelayed(::checkPrismStartup, PRISM_START_CHECK_DELAY_MS)
            else -> {
                changingProtection = false
                PrismDnsController.stop(this)
                Toast.makeText(this, "Protection startup timed out", Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    @Deprecated("Android VPN consent still uses an activity result on supported API levels")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_PERMISSION_REQUEST) return
        val requested = waitingForVpnPermission
        waitingForVpnPermission = false
        if (resultCode == RESULT_OK && requested) startPrism() else {
            changingProtection = false
            refresh()
        }
    }

    private fun showLinkInstructions() {
        Toast.makeText(this, "Open Mobile EasyPi in your EasyPi dashboard, choose Add phone, and scan the QR code with this phone's camera.", Toast.LENGTH_LONG).show()
    }

    private fun retryQueuedUploads() {
        if (uploadingQueuedFiles) return
        val link = EasyPiLinkStore(this).linkedEasyPi() ?: return
        val directory = io.easypi.mobile.sharing.UploadQueue.directory(filesDir, link)
        if (directory.listFiles()?.any { it.isFile } != true) return
        uploadingQueuedFiles = true
        refresh()
        Thread {
            val result = EasyPiUploader.uploadPending(link, directory)
            runOnUiThread {
                uploadingQueuedFiles = false
                if (result.uploaded > 0) Toast.makeText(this, "Sent ${result.uploaded} file(s) to EasyPi", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }.start()
    }

    private fun queuedFileCount(): Int = EasyPiLinkStore(this).linkedEasyPi()?.let { link ->
        io.easypi.mobile.sharing.UploadQueue.directory(filesDir, link).listFiles()?.count { it.isFile && !it.name.endsWith(".partial") }
    } ?: 0
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun primaryButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rounded(BLUE, dp(13).toFloat())
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
    }
    private fun label(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setTypeface(typeface, style); setLineSpacing(0f, 1.15f)
    }
    private fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke != null) setStroke(strokeWidth, stroke)
    }
    private fun <T : View> T.marginTop(value: Int): T {
        val params = (layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(value); layoutParams = params; return this
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val BACKGROUND = Color.rgb(6, 14, 26)
        val CARD = Color.rgb(14, 31, 51)
        val SURFACE = Color.rgb(22, 37, 56)
        val ICON_BACKGROUND = Color.rgb(21, 58, 88)
        val BLUE = Color.rgb(41, 137, 245)
        val BLUE_BORDER = Color.rgb(35, 93, 139)
        val SECONDARY = Color.rgb(47, 63, 83)
        val ACCENT = Color.rgb(82, 191, 255)
        val MUTED = Color.rgb(164, 183, 204)
        val SUCCESS = Color.rgb(55, 220, 143)
        val WARNING = Color.rgb(255, 179, 74)
        const val VPN_PERMISSION_REQUEST = 4102
        const val PRISM_START_CHECK_DELAY_MS = 150L
        const val PRISM_START_CHECK_LIMIT = 66
        const val PENDING_UPLOADS = "pending-uploads"
    }
}

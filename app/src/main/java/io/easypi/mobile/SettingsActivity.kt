package io.easypi.mobile

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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

class SettingsActivity : Activity() {
    private lateinit var connectionStatus: TextView
    private lateinit var connectionAddress: TextView
    private lateinit var photoFolder: TextView
    private lateinit var photoFolderButton: Button
    private lateinit var replaceButton: Button
    private lateinit var removeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        setContentView(buildScreen())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildScreen(): View {
        val body = vertical().apply {
            setPadding(dp(20), dp(18), dp(20), dp(36))
            setBackgroundColor(BACKGROUND)
        }
        body.addView(header())
        body.addView(connectionCard().marginTop(24))
        body.addView(protectionCard().marginTop(14))
        body.addView(privacyCard().marginTop(14))
        body.addView(aboutCard().marginTop(14))
        return ScrollView(this).apply { isFillViewport = true; addView(body) }
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(backButton())
        addView(vertical().apply {
            addView(label("Settings", 27f, Color.WHITE, Typeface.BOLD))
            addView(label("EasyPi Mobile", 13f, ACCENT))
        })
    }

    @Deprecated("Activity permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        io.easypi.mobile.photos.PhotoSyncWorker.onMediaAccessResult(this, requestCode)
    }

    private fun connectionCard(): View = card("EASYPI CONNECTION", "Secure pairing with your home EasyPi") {
        connectionStatus = label("Checking…", 18f, Color.WHITE, Typeface.BOLD).apply { setPadding(0, dp(16), 0, 0) }
        addView(connectionStatus)
        connectionAddress = label("", 13f, MUTED).apply { setPadding(0, dp(5), 0, 0) }
        addView(connectionAddress)
        photoFolder = label("", 13f, MUTED).apply { setPadding(0, dp(10), 0, 0) }
        addView(photoFolder)
        photoFolderButton = outlineButton("Photo & video backup") {
            PhotoFolderSetup.show(this@SettingsActivity, required = false) {
                refresh()
                io.easypi.mobile.photos.PhotoSyncWorker.onBackupSettingsChanged(this@SettingsActivity)
                io.easypi.mobile.photos.PhotoSyncWorker.requestMediaAccessIfNeeded(this@SettingsActivity)
            }
        }
        addView(photoFolderButton.marginTop(12))
        replaceButton = primaryButton("Connect a different EasyPi", ::showLinkInstructions)
        addView(replaceButton.marginTop(18))
        removeButton = outlineButton("Remove this connection", ::confirmRemoveConnection)
        addView(removeButton.marginTop(9))
        addView(outlineButton("Review retained uploads", ::reviewRetainedUploads).marginTop(9))
    }

    private fun protectionCard(): View = card("PROTECTION", "Traffic filtering and VPN routing") {
        addView(settingRow(
            "Prism Protection",
            "Applies rules from EasyPi. A domain can be blocked while its policy check is pending or unavailable.",
            "",
        ).marginTop(15))
        addView(bullet(
            "Protection routes all phone traffic through your home EasyPi for PRISM enforcement. Chameleon routing is controlled separately.",
        ).marginTop(10))
        addView(divider().marginTop(14))
        addView(settingRow(
            "Chameleon",
            "When enabled, traffic covered by the VPN uses the Chameleon server. If Protection is also on, Prism applies EasyPi rules on this phone.",
            "VPN",
        ).marginTop(14))
    }

    private fun privacyCard(): View = card("PRIVACY", "What EasyPi Mobile can access") {
        addView(bullet("Uses connection metadata and DNS names").marginTop(15))
        addView(bullet("Does not read the screen or use Accessibility control").marginTop(10))
        addView(bullet("Pairing credentials are encrypted by Android Keystore").marginTop(10))
        addView(bullet("Shared files remain private while waiting to upload").marginTop(10))
    }

    private fun aboutCard(): View = card("ABOUT", "Application information") {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "Unknown"
        addView(settingRow("EasyPi Mobile", "Traffic protection and secure access", "v$version").marginTop(15))
    }

    private fun refresh() {
        val link = EasyPiLinkStore(this).linkedEasyPi()
        if (link == null) {
            connectionStatus.text = "Not connected"
            connectionStatus.setTextColor(WARNING)
            connectionAddress.text = "Scan a QR code from Mobile EasyPi in your dashboard."
            replaceButton.text = "Connect EasyPi"
            photoFolder.visibility = View.GONE
            photoFolderButton.visibility = View.GONE
            removeButton.visibility = View.GONE
        } else {
            connectionStatus.text = "●  ${link.displayName}"
            connectionStatus.setTextColor(SUCCESS)
            val host = runCatching { Uri.parse(link.serverUrl).host }.getOrNull().orEmpty()
            connectionAddress.text = if (host.isBlank()) "Securely linked" else "Paired with $host"
            val videoStatus = if (link.backupVideos) "Videos: separate folder" else "Videos: off"
            photoFolder.text = "Media backup folder: ${link.photoFolder ?: "Not set"} · $videoStatus"
            photoFolder.visibility = View.VISIBLE
            photoFolderButton.visibility = View.VISIBLE
            replaceButton.text = "Connect a different EasyPi"
            removeButton.visibility = View.VISIBLE
        }
    }

    private fun showLinkInstructions() {
        Toast.makeText(this, "Open Mobile EasyPi in your EasyPi dashboard, choose Add phone, and scan the QR code with this phone's camera.", Toast.LENGTH_LONG).show()
    }

    private fun reviewRetainedUploads() {
        val link = EasyPiLinkStore(this).linkedEasyPi() ?: return
        val files = io.easypi.mobile.sharing.UploadQueue.retainedFiles(filesDir, link)
        if (files.isEmpty()) {
            Toast.makeText(this, "No retained files from earlier connections", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = BooleanArray(files.size)
        AlertDialog.Builder(this)
            .setTitle("Retained files — not sent")
            .setMultiChoiceItems(files.map { it.name }.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose destination") { _, _ ->
                val chosen = files.filterIndexed { index, _ -> selected[index] }
                if (chosen.isEmpty()) return@setPositiveButton
                AlertDialog.Builder(this)
                    .setTitle("Send selected files here?")
                    .setMessage("Move ${chosen.size} selected file(s) to ${link.displayName} at ${Uri.parse(link.serverUrl).authority}? They will upload when you open Protection or share another file.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Queue for this connection") { _, _ ->
                        val result = runCatching {
                            check(EasyPiLinkStore(this).linkedEasyPi() == link) { "Connection changed" }
                            io.easypi.mobile.sharing.UploadQueue.reassign(filesDir, link, chosen)
                        }
                        Toast.makeText(this, if (result.isSuccess) "Selected files queued for this connection" else "Could not queue files; limits are 16 files and 256 MiB per file", Toast.LENGTH_LONG).show()
                    }.show()
            }.show()
    }

    private fun confirmRemoveConnection() {
        AlertDialog.Builder(this)
            .setTitle("Remove EasyPi connection?")
            .setMessage("Protection will stop and this phone will need a new QR code to connect again. Waiting files stay on this phone and will not be sent to a different connection.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> removeConnection() }
            .show()
    }

    private fun removeConnection() {
        PrismDnsController.stopAll(this)
        io.easypi.mobile.photos.PhotoSyncWorker.cancel(this)
        EasyPiLinkStore(this).clear()
        Toast.makeText(this, "EasyPi connection removed", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun card(title: String, subtitle: String, content: LinearLayout.() -> Unit): View = vertical().apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(SURFACE, dp(18).toFloat())
        addView(label(title, 12f, ACCENT, Typeface.BOLD))
        addView(label(subtitle, 13f, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        content()
    }

    private fun settingRow(title: String, detail: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(vertical().apply {
            addView(label(title, 16f, Color.WHITE, Typeface.BOLD))
            addView(label(detail, 12.5f, MUTED).apply { setPadding(0, dp(3), dp(12), 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label(value, 12f, SUCCESS, Typeface.BOLD).apply { gravity = Gravity.END })
    }

    private fun bullet(value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        addView(label("✓", 15f, SUCCESS, Typeface.BOLD), LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(label(value, 13.5f, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }
    private fun primaryButton(value: String, action: () -> Unit) = button(value, BLUE, Color.WHITE, action)
    private fun outlineButton(value: String, action: () -> Unit) = button(value, CARD, DANGER, action).apply {
        background = rounded(CARD, dp(12).toFloat(), DANGER_BORDER, dp(1))
    }
    private fun button(value: String, color: Int, textColor: Int, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(textColor)
        gravity = Gravity.CENTER; background = rounded(color, dp(12).toFloat()); setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
    }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
        val SURFACE = Color.rgb(22, 37, 56)
        val CARD = Color.rgb(18, 31, 47)
        val BLUE = Color.rgb(41, 137, 245)
        val ACCENT = Color.rgb(82, 191, 255)
        val MUTED = Color.rgb(164, 183, 204)
        val SUCCESS = Color.rgb(55, 220, 143)
        val WARNING = Color.rgb(255, 179, 74)
        val DANGER = Color.rgb(255, 125, 125)
        val DANGER_BORDER = Color.rgb(123, 60, 67)
        val DIVIDER = Color.rgb(49, 66, 87)
    }
}

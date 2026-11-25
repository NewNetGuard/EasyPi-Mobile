package io.easypi.mobile.pairing

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.easypi.mobile.EasyPiLinkStore
import io.easypi.mobile.HomeActivity
import io.easypi.mobile.PhotoFolderSetup

class PairingActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var confirm: Button
    private lateinit var progress: ProgressBar
    private var server = ""
    private var homeServer: String? = null
    private var code = ""
    private var certificateSha256: String? = null
    private var pendingPairing: PairingClient.PairingResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent.data
        server = data?.getQueryParameter("server").orEmpty()
        homeServer = data?.getQueryParameter("home")
        code = data?.getQueryParameter("code").orEmpty()
        certificateSha256 = data?.getQueryParameter("cert")
        val validated = runCatching {
            PairingClient.validateServerUrl(server).also {
                homeServer?.takeIf { value -> value.isNotBlank() }?.let(PairingClient::validateServerUrl)
                certificateSha256?.let(PinnedHttps::normalizeFingerprint)
            }
        }
        setContentView(buildScreen(validated.getOrNull()))
        if (validated.isFailure || code.length !in 32..128) {
            status.text = validated.exceptionOrNull()?.message ?: "This pairing code is invalid"
            status.setTextColor(Color.rgb(255, 121, 121))
            confirm.isEnabled = false
        }
    }

    private fun buildScreen(validServer: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(48), dp(24), dp(32))
        setBackgroundColor(Color.rgb(7, 17, 31))
        addView(text("Link this phone", 27f, Color.WHITE, Typeface.BOLD))
        addView(text("Confirm the EasyPi address before linking.", 15f, Color.rgb(169, 187, 207)).marginTop(8))
        addView(text(validServer ?: server.ifBlank { "Unknown EasyPi" }, 17f, Color.rgb(86, 190, 255), Typeface.BOLD).marginTop(28))
        addView(text("A restricted mobile token will be stored in Android Keystore. Your admin password is never sent to the phone.", 14f, Color.rgb(169, 187, 207)).marginTop(18))
        status = text("Ready to link", 14f, Color.rgb(65, 221, 145), Typeface.BOLD).marginTop(22)
        addView(status)
        progress = ProgressBar(this@PairingActivity).apply { visibility = View.GONE }
        addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply { topMargin = dp(16) })
        confirm = button("Link to this EasyPi") { pair() }
        addView(confirm.marginTop(18))
        addView(button("Cancel") { finish() }.apply { background = rounded(Color.rgb(45, 61, 82), dp(10).toFloat()) }.marginTop(10))
    }

    private fun pair() {
        confirm.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Linking securely…"
        Thread {
            val result = runCatching {
                PairingClient.complete(server, code, Build.MODEL, certificateSha256, homeServer)
            }
            runOnUiThread {
                progress.visibility = View.GONE
                result.onSuccess { pairing ->
                    io.easypi.mobile.prism.PrismDnsController.stopAll(this)
                    io.easypi.mobile.photos.PhotoSyncWorker.cancel(this)
                    EasyPiLinkStore(this).save(pairing.link)
                    pendingPairing = pairing
                    requestPhotoFolder()
                }.onFailure { error ->
                    status.text = when {
                        error.message?.contains("certificate", ignoreCase = true) == true -> "EasyPi certificate is not trusted by this phone"
                        else -> error.message ?: "Could not link this phone"
                    }
                    status.setTextColor(Color.rgb(255, 121, 121))
                    confirm.isEnabled = true
                }
            }
        }.start()
    }

    private fun requestPhotoFolder() {
        PhotoFolderSetup.show(this, required = true) { completeEnrollment() }
    }

    private fun completeEnrollment() {
        val pairing = pendingPairing ?: return
        val configured = EasyPiLinkStore(this).linkedEasyPi()
        EasyPiLinkStore(this).save(pairing.link.copy(
            photoFolder = configured?.photoFolder,
            backupVideos = configured?.backupVideos ?: false,
        ))
        finishPairing("Phone linked to EasyPi")
    }

    private fun finishPairing(message: String) {
        pendingPairing = null
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun text(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setTypeface(typeface, style)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.16f)
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(39, 132, 238), dp(10).toFloat())
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun <T : View> T.marginTop(value: Int): T {
        val params = (layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(value)
        layoutParams = params
        return this
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}

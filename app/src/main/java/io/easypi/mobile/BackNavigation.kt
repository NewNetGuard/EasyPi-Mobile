package io.easypi.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

internal fun Activity.backButton() = ImageButton(this).apply {
    val density = resources.displayMetrics.density
    val size = (48 * density).roundToInt()
    layoutParams = LinearLayout.LayoutParams(size, size).apply {
        marginEnd = (12 * density).roundToInt()
    }
    background = GradientDrawable().apply {
        cornerRadius = 14 * density
        setColor(Color.rgb(18, 34, 53))
    }
    setPadding(0, 0, 0, 0)
    scaleType = ImageView.ScaleType.CENTER
    setImageResource(R.drawable.ic_back_arrow)
    contentDescription = "Back"
    setOnClickListener { this@backButton.finish() }
}

@Composable
internal fun BackNavigation(activity: Activity) {
    AndroidView(
        factory = { activity.backButton() },
        modifier = Modifier.padding(end = 12.dp).size(48.dp),
    )
}

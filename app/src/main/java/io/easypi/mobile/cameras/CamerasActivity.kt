package io.easypi.mobile.cameras

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.easypi.mobile.EasyPiEndpointResolver
import io.easypi.mobile.BackNavigation
import io.easypi.mobile.EasyPiLinkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class CamerasActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CamerasScreen() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CamerasScreen() {
        val background = Color(0xFF06101D)
        val surface = Color(0xFF122235)
        val accent = Color(0xFF52BFFF)
        var cameras by remember { mutableStateOf<List<EasyPiCamera>>(emptyList()) }
        var frames by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var selected by remember { mutableStateOf<EasyPiCamera?>(null) }

        LaunchedEffect(Unit) {
            while (true) {
                val storedLink = EasyPiLinkStore(this@CamerasActivity).linkedEasyPi()
                if (storedLink == null) {
                    error = "Connect this phone to EasyPi first"
                    loading = false
                    break
                }
                runCatching {
                    withContext(Dispatchers.IO) {
                        val link = EasyPiEndpointResolver.resolve(this@CamerasActivity, storedLink)
                        val available = CameraClient.list(link)
                        val refreshed = available.filter { camera ->
                            camera.status == "online" || camera.status == "recording"
                        }.mapNotNull { camera ->
                            runCatching { camera.id to CameraClient.snapshot(link, camera.id) }.getOrNull()
                        }.toMap()
                        available to refreshed
                    }
                }.onSuccess { result ->
                    cameras = result.first
                    frames = result.second
                    error = null
                }.onFailure { failure ->
                    error = failure.message ?: "Could not load EasyPi cameras"
                }
                loading = false
                delay(10_000)
            }
        }

        MaterialTheme {
            Scaffold(
                containerColor = background,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Cameras", color = Color.White, fontWeight = FontWeight.Bold)
                                Text("Live snapshots from your EasyPi", color = accent, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        navigationIcon = { BackNavigation(this@CamerasActivity) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
                    )
                },
            ) { padding ->
                when {
                    loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent)
                    }
                    cameras.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(error ?: "No cameras have been added to EasyPi", color = Color(0xFFA4B7CC))
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (error != null) item {
                            Text(error.orEmpty(), color = Color(0xFFFF7979), modifier = Modifier.padding(vertical = 8.dp))
                        }
                        items(cameras, key = { it.id }) { camera ->
                            CameraCard(camera, frames[camera.id], surface) { selected = camera }
                        }
                        item { Box(Modifier.size(1.dp).padding(bottom = 20.dp)) }
                    }
                }
            }
            selected?.let { camera ->
                CameraDialog(camera, frames[camera.id]) { selected = null }
            }
        }
    }

    @Composable
    private fun CameraCard(camera: EasyPiCamera, frame: ByteArray?, surface: Color, open: () -> Unit) {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(onClick = open),
            color = surface,
            shape = RoundedCornerShape(18.dp),
        ) {
            Column {
                CameraFrame(
                    frame,
                    if (camera.status == "online" || camera.status == "recording") "Preview unavailable" else "Camera offline",
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(camera.name, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(camera.location ?: "EasyPi camera", color = Color(0xFFA4B7CC))
                    }
                    Box(
                        Modifier.size(10.dp).background(
                            if (camera.status == "online" || camera.status == "recording") Color(0xFF37DC8F) else Color(0xFFFF7979),
                            CircleShape,
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun CameraFrame(frame: ByteArray?, unavailableLabel: String, modifier: Modifier = Modifier) {
        val bitmap = remember(frame) {
            frame?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Camera preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(unavailableLabel, color = Color(0xFFA4B7CC))
            }
        }
    }

    @Composable
    private fun CameraDialog(camera: EasyPiCamera, frame: ByteArray?, close: () -> Unit) {
        AlertDialog(
            onDismissRequest = close,
            title = { Text(camera.name) },
            text = {
                CameraFrame(
                    frame,
                    if (camera.status == "online" || camera.status == "recording") "Preview unavailable" else "Camera offline",
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            },
            confirmButton = { Button(onClick = close) { Text("Close") } },
        )
    }
}

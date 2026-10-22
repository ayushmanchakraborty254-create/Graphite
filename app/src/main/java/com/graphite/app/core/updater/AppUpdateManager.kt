package com.graphite.app.core.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.graphite.app.BuildConfig
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.theme.GraphiteSurfaceVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,
    val commitSha: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val publishedAt: String,
    val isNewer: Boolean
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(
        val progressPercent: Int,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
        val speedKbps: Long = 0L
    ) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class AppUpdateManager(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
) {
    var lastDownloadUrl: String? = null
        private set

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val githubRepo = "ayushmanchakraborty254-create/Graphite"
    private val apiUrl = "https://api.github.com/repos/$githubRepo/releases"

    /**
     * Checks GitHub for newly published APK builds.
     * Compares release commit SHA with the running build's GIT_COMMIT_SHA.
     */
    fun checkForUpdates(context: Context? = null, onComplete: (Boolean) -> Unit = {}) {
        _updateState.value = UpdateState.Checking
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .addHeader("User-Agent", "Graphite-Android-App")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    _updateState.value = UpdateState.Idle
                    withContext(Dispatchers.Main) { onComplete(false) }
                    return@launch
                }

                val responseBody = response.body?.string() ?: ""
                val releases = JSONArray(responseBody)
                if (releases.length() == 0) {
                    _updateState.value = UpdateState.Idle
                    withContext(Dispatchers.Main) { onComplete(false) }
                    return@launch
                }

                // First release is the latest
                val latest = releases.getJSONObject(0)
                val tagName = latest.optString("tag_name", "latest")
                val releaseName = latest.optString("name", "New Graphite Build")
                val body = latest.optString("body", "Bug fixes and improvements.")
                val publishedAt = latest.optString("published_at", "")

                // Find APK asset download URL and accurate asset upload timestamp
                var apkUrl = "https://github.com/$githubRepo/releases/latest/download/Graphite-v1.0.0.apk"
                var assetTimestamp = publishedAt
                val assets = latest.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", apkUrl)
                            val upd = asset.optString("updated_at", "")
                            if (upd.isNotBlank()) assetTimestamp = upd
                            break
                        }
                    }
                }

                // Extract commit SHA from release name if present: "Graphite Auto-Build (7b3f91a)"
                val shaRegex = Regex("\\(([a-f0-9]{7,10})\\)", RegexOption.IGNORE_CASE)
                val releaseSha = shaRegex.find(releaseName)?.groupValues?.get(1)
                    ?: shaRegex.find(body)?.groupValues?.get(1)
                    ?: tagName

                val localSha = BuildConfig.GIT_COMMIT_SHA
                val localBuildTime = BuildConfig.BUILD_TIME_MILLIS

                // Check snooze duration in SharedPreferences (user clicked Later)
                if (context != null) {
                    val prefs = context.getSharedPreferences("graphite_updater", Context.MODE_PRIVATE)
                    val snoozedUntil = prefs.getLong("snoozed_until_${releaseSha}", 0L)
                    if (System.currentTimeMillis() < snoozedUntil) {
                        _updateState.value = UpdateState.Idle
                        withContext(Dispatchers.Main) { onComplete(false) }
                        return@launch
                    }
                }

                // Check if the update is genuinely newer
                var isNewer = false
                if (releaseSha.isNotBlank() && !releaseSha.equals("latest", ignoreCase = true)) {
                    if (localSha.isNotBlank() && !localSha.equals("dev", ignoreCase = true)) {
                        // Exact commit SHA comparison: newer if remote SHA does NOT match installed SHA
                        isNewer = !releaseSha.equals(localSha, ignoreCase = true)
                    } else {
                        // Local build has no commit sha or is dev, so a published release is newer
                        isNewer = true
                    }
                } else if (assetTimestamp.isNotBlank()) {
                    try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        val remoteTime = sdf.parse(assetTimestamp)?.time ?: 0L
                        if (remoteTime > localBuildTime + 60_000) {
                            isNewer = true
                        }
                    } catch (_: Exception) {}
                }

                if (isNewer) {
                    val updateInfo = UpdateInfo(
                        versionName = releaseName,
                        commitSha = releaseSha,
                        releaseNotes = body,
                        downloadUrl = apkUrl,
                        publishedAt = publishedAt,
                        isNewer = true
                    )
                    _updateState.value = UpdateState.Available(updateInfo)
                    withContext(Dispatchers.Main) { onComplete(true) }
                } else {
                    _updateState.value = UpdateState.Idle
                    withContext(Dispatchers.Main) { onComplete(false) }
                }
            } catch (e: Exception) {
                _updateState.value = UpdateState.Idle
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    private var downloadJob: kotlinx.coroutines.Job? = null

    /**
     * Downloads the latest APK and launches the system installer.
     */
    fun downloadAndInstall(context: Context, downloadUrl: String) {
        lastDownloadUrl = downloadUrl
        _updateState.value = UpdateState.Downloading(0, 0L, 0L, 0L)
        downloadJob?.cancel()
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(downloadUrl)
                    .addHeader("User-Agent", "Graphite-Android-App")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    _updateState.value = UpdateState.Error("Failed to download update: HTTP ${response.code}")
                    return@launch
                }

                val body = response.body ?: throw IllegalStateException("Empty response body")
                val contentLength = body.contentLength()

                val apkFile = File(context.cacheDir, "Graphite-update.apk")
                if (apkFile.exists()) apkFile.delete()

                val inputStream = java.io.BufferedInputStream(body.byteStream(), 262144)
                val outputStream = java.io.BufferedOutputStream(FileOutputStream(apkFile), 262144)

                val buffer = ByteArray(131072) // 128 KB buffer for high socket throughput
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastUpdateTime = System.currentTimeMillis()
                var bytesSinceLastUpdate = 0L
                var currentSpeed = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    bytesSinceLastUpdate += bytesRead

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastUpdateTime
                    if (elapsed >= 120L) { // Update every 120ms so user sees smooth progress
                        currentSpeed = if (elapsed > 0) (bytesSinceLastUpdate * 1000L) / (elapsed * 1024L) else 0L
                        lastUpdateTime = now
                        bytesSinceLastUpdate = 0L

                        val progress = if (contentLength > 0) {
                            ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                        } else 0

                        _updateState.value = UpdateState.Downloading(
                            progressPercent = progress,
                            bytesDownloaded = totalBytesRead,
                            totalBytes = contentLength,
                            speedKbps = currentSpeed
                        )
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                _updateState.value = UpdateState.ReadyToInstall(apkFile)
                withContext(Dispatchers.Main) {
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _updateState.value = UpdateState.Error("Download failed: ${e.message}")
                }
            }
        }
    }

    fun cancelDownload(context: Context? = null, sha: String? = null) {
        downloadJob?.cancel()
        downloadJob = null
        if (context != null) {
            try {
                val apkFile = File(context.cacheDir, "Graphite-update.apk")
                if (apkFile.exists()) apkFile.delete()
            } catch (_: Exception) {}
        }
        dismissUpdate(context, sha)
    }

    fun dismissUpdate(context: Context? = null, sha: String? = null) {
        val targetSha = sha ?: (_updateState.value as? UpdateState.Available)?.info?.commitSha
        if (context != null && !targetSha.isNullOrBlank()) {
            context.getSharedPreferences("graphite_updater", Context.MODE_PRIVATE)
                .edit()
                .putLong("snoozed_until_${targetSha}", System.currentTimeMillis() + 30 * 60 * 1000L) // snooze 30 mins
                .remove("dismissed_sha") // clear legacy permanent blacklist
                .apply()
        }
        _updateState.value = UpdateState.Idle
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(
                        context,
                        "Please allow Graphite to install updates, then tap Install",
                        Toast.LENGTH_LONG
                    ).show()
                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(permissionIntent)
                    return
                }
            }

            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error("Could not launch package installer: ${e.message}")
        }
    }
}

/**
 * Clean In-App Auto-Update Notification Dialog.
 */
@Composable
fun AppUpdateModalDialog(
    updateManager: AppUpdateManager,
    context: Context
) {
    val updateState by updateManager.updateState.collectAsState()

    when (val state = updateState) {
        is UpdateState.Available -> {
            AlertDialog(
                onDismissRequest = { updateManager.dismissUpdate(context, state.info.commitSha) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = "Update Available",
                        tint = GraphiteOrange,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🚀 New Update Available!",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { updateManager.dismissUpdate(context, state.info.commitSha) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.LightGray
                            )
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.info.versionName,
                            color = GraphiteOrange,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = "A new version of Graphite was pushed to GitHub. Update now to get the latest features and bug fixes.",
                            color = Color(0xFFCCCCCC),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            updateManager.downloadAndInstall(context, state.info.downloadUrl)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Update Now", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateManager.dismissUpdate(context, state.info.commitSha) }) {
                        Text("Later", color = Color.Gray)
                    }
                },
                containerColor = GraphiteSurfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
        }
        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = { updateManager.cancelDownload(context) },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Downloading Update...",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { updateManager.cancelDownload(context) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel Download",
                                tint = Color.LightGray
                            )
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { if (state.totalBytes > 0) (state.bytesDownloaded.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = GraphiteOrange,
                            trackColor = Color(0xFF333333)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val currMb = state.bytesDownloaded / 1048576f
                            val totalMb = if (state.totalBytes > 0) state.totalBytes / 1048576f else 0f
                            Text(
                                text = if (totalMb > 0f) String.format(Locale.US, "%.1f / %.1f MB", currMb, totalMb) else String.format(Locale.US, "%.1f MB", currMb),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            val speedText = if (state.speedKbps >= 1024) {
                                String.format(Locale.US, "%.1f MB/s", state.speedKbps / 1024f)
                            } else {
                                "${state.speedKbps} KB/s"
                            }
                            Text(
                                text = if (state.speedKbps > 0) speedText else "Downloading...",
                                color = GraphiteOrange,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val dlUrl = updateManager.lastDownloadUrl
                        if (!dlUrl.isNullOrBlank()) {
                            TextButton(
                                onClick = {
                                    updateManager.cancelDownload(context)
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(dlUrl)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                }
                            ) {
                                Text("Open in Browser", color = Color(0xFF64B5F6), fontSize = 12.sp)
                            }
                        }
                        TextButton(onClick = { updateManager.cancelDownload(context) }) {
                            Text("Cancel", color = Color(0xFFFF5252), fontSize = 12.sp)
                        }
                    }
                },
                containerColor = GraphiteSurfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
        }
        is UpdateState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = { updateManager.dismissUpdate(context) },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Update Ready to Install",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { updateManager.dismissUpdate(context) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.LightGray
                            )
                        }
                    }
                },
                text = {
                    Text(
                        text = "Download finished. Tap Install to apply update.",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { updateManager.installApk(context, state.apkFile) },
                        colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Install", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateManager.dismissUpdate(context) }) {
                        Text("Later", color = Color.Gray)
                    }
                },
                containerColor = GraphiteSurfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
        }
        is UpdateState.Error -> {
            AlertDialog(
                onDismissRequest = { updateManager.dismissUpdate(context) },
                title = { Text("Update Error", color = Color.White) },
                text = { Text(state.message, color = Color.LightGray) },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        updateManager.lastDownloadUrl?.let { retryUrl ->
                            Button(
                                onClick = { updateManager.downloadAndInstall(context, retryUrl) },
                                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
                            ) {
                                Text("Retry")
                            }
                        }
                        TextButton(onClick = { updateManager.dismissUpdate(context) }) {
                            Text("Dismiss", color = Color.Gray)
                        }
                    }
                },
                containerColor = GraphiteSurfaceVariant
            )
        }
        else -> {}
    }
}

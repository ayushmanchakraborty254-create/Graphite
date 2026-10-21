package com.graphite.app.core.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
    data class Downloading(val progressPercent: Int) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class AppUpdateManager(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val githubRepo = "ayushmanchakraborty254-create/Graphite"
    private val apiUrl = "https://api.github.com/repos/$githubRepo/releases"

    /**
     * Checks GitHub for newly published APK builds.
     * Compares release commit SHA, tag, or publish timestamp with the running build.
     */
    fun checkForUpdates(onComplete: (Boolean) -> Unit = {}) {
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

                // Find APK asset download URL
                var apkUrl = "https://github.com/$githubRepo/releases/latest/download/Graphite-latest.apk"
                val assets = latest.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", apkUrl)
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

                // Parse published timestamp
                var isNewer = false
                if (localSha != "dev" && releaseSha.isNotBlank() && !releaseSha.equals(localSha, ignoreCase = true)) {
                    isNewer = true
                } else if (publishedAt.isNotBlank()) {
                    try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        val remoteTime = sdf.parse(publishedAt)?.time ?: 0L
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

    /**
     * Downloads the latest APK and launches the system installer.
     */
    fun downloadAndInstall(context: Context, downloadUrl: String) {
        _updateState.value = UpdateState.Downloading(0)
        CoroutineScope(Dispatchers.IO).launch {
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

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(apkFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (contentLength > 0) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                        _updateState.value = UpdateState.Downloading(progress)
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
                _updateState.value = UpdateState.Error("Download failed: ${e.message}")
            }
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateState.Idle
    }

    fun installApk(context: Context, apkFile: File) {
        try {
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
                onDismissRequest = { updateManager.dismissUpdate() },
                icon = {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = "Update Available",
                        tint = GraphiteOrange,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = "🚀 New Update Available!",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
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
                    TextButton(onClick = { updateManager.dismissUpdate() }) {
                        Text("Later", color = Color.Gray)
                    }
                },
                containerColor = GraphiteSurfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
        }
        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Text(
                        text = "Downloading Update...",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { state.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = GraphiteOrange,
                            trackColor = Color(0xFF333333)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "${state.progressPercent}% downloaded",
                            color = Color.LightGray,
                            fontSize = 13.sp
                        )
                    }
                },
                confirmButton = {},
                containerColor = GraphiteSurfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
        }
        is UpdateState.ReadyToInstall -> {
            AlertDialog(
                onDismissRequest = { updateManager.dismissUpdate() },
                title = {
                    Text(
                        text = "Update Ready to Install",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
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
                        colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
                    ) {
                        Text("Install", color = Color.White)
                    }
                },
                containerColor = GraphiteSurfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
        }
        else -> {}
    }
}

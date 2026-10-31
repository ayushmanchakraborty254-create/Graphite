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
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String,
    val commitSha: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val publishedAt: String,
    val isNewer: Boolean,
    val apkSize: Long = 0L,
    val versionCode: Int = 0
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

    private val _latestAvailableUpdate = MutableStateFlow<UpdateInfo?>(null)
    val latestAvailableUpdate: StateFlow<UpdateInfo?> = _latestAvailableUpdate.asStateFlow()

    private val githubRepo = "ayushmanchakraborty254-create/Graphite"
    private val rawVersionUrl = "https://raw.githubusercontent.com/$githubRepo/main/version.json"
    private val apiUrl = "https://api.github.com/repos/$githubRepo/releases"

    private var downloadJob: kotlinx.coroutines.Job? = null

    /**
     * Checks for newly published APK builds using a multi-layer strategy:
     * 1. Ultra-fast, rate-limit-free raw metadata via GitHub CDN (`version.json`)
     * 2. Full GitHub Releases API fallback if CDN is not reachable
     */
    fun checkForUpdates(
        context: Context? = null,
        isManualCheck: Boolean = false,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val currentState = _updateState.value
        // NEVER interrupt an active download or a build ready to install
        if (currentState is UpdateState.Downloading || currentState is UpdateState.ReadyToInstall) {
            onComplete(false)
            return
        }
        if (!isManualCheck && currentState is UpdateState.Checking) {
            return
        }

        // Clean up old temporary update files on check
        if (context != null) {
            try {
                val updateDir = File(context.cacheDir, "updates")
                if (updateDir.exists()) {
                    updateDir.listFiles()?.forEach { file ->
                        if (System.currentTimeMillis() - file.lastModified() > 3_600_000L) {
                            file.delete()
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        _updateState.value = UpdateState.Checking
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var updateInfo: UpdateInfo? = null

                // Strategy 1: Fetch version.json from raw CDN (Unlimited rate limits, instant)
                try {
                    val rawRequest = Request.Builder()
                        .url(rawVersionUrl)
                        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                        .addHeader("Cache-Control", "no-cache")
                        .build()
                    val rawResponse = httpClient.newCall(rawRequest).execute()
                    if (rawResponse.isSuccessful) {
                        val body = rawResponse.body?.string() ?: ""
                        if (body.isNotBlank()) {
                            val json = JSONObject(body)
                            val releaseSha = json.optString("commitSha", "").trim()
                            val vCode = json.optInt("versionCode", 0)
                            val vName = json.optString("versionName", "1.0.0")
                            val dlUrl = json.optString("downloadUrl", "https://github.com/$githubRepo/releases/download/latest/Graphite-v1.0.0.apk")
                            val notes = json.optString("commitMessage", "Latest updates and enhancements.")
                            val buildDate = json.optString("buildDate", "")

                            if (releaseSha.isNotBlank()) {
                                updateInfo = UpdateInfo(
                                    versionName = "Graphite Auto-Build ($releaseSha)",
                                    commitSha = releaseSha,
                                    releaseNotes = notes,
                                    downloadUrl = dlUrl,
                                    publishedAt = buildDate,
                                    isNewer = false, // evaluated below
                                    versionCode = vCode
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Fall back to GitHub Releases API below
                }

                // Strategy 2: Query GitHub Releases API if Strategy 1 was empty
                if (updateInfo == null) {
                    val apiRequest = Request.Builder()
                        .url(apiUrl)
                        .addHeader("Accept", "application/vnd.github.v3+json")
                        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                        .build()

                    val apiResponse = httpClient.newCall(apiRequest).execute()
                    if (apiResponse.isSuccessful) {
                        val responseBody = apiResponse.body?.string() ?: ""
                        val releases = JSONArray(responseBody)
                        if (releases.length() > 0) {
                            val latest = releases.getJSONObject(0)
                            val tagName = latest.optString("tag_name", "latest")
                            val releaseName = latest.optString("name", "New Graphite Build")
                            val body = latest.optString("body", "Bug fixes and improvements.")
                            val publishedAt = latest.optString("published_at", "")

                            var apkUrl = "https://github.com/$githubRepo/releases/latest/download/Graphite-v1.0.0.apk"
                            var apkSize = 0L
                            val assets = latest.optJSONArray("assets")
                            if (assets != null) {
                                for (i in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(i)
                                    val name = asset.optString("name", "")
                                    if (name.endsWith(".apk", ignoreCase = true)) {
                                        apkUrl = asset.optString("browser_download_url", apkUrl)
                                        apkSize = asset.optLong("size", 0L)
                                        break
                                    }
                                }
                            }

                            val shaRegex = Regex("\\(([a-f0-9]{7,10})\\)", RegexOption.IGNORE_CASE)
                            val releaseSha = shaRegex.find(releaseName)?.groupValues?.get(1)
                                ?: shaRegex.find(body)?.groupValues?.get(1)
                                ?: tagName

                            updateInfo = UpdateInfo(
                                versionName = releaseName,
                                commitSha = releaseSha,
                                releaseNotes = body,
                                downloadUrl = apkUrl,
                                publishedAt = publishedAt,
                                isNewer = false,
                                apkSize = apkSize
                            )
                        }
                    }
                }

                if (updateInfo == null) {
                    _updateState.value = UpdateState.Idle
                    withContext(Dispatchers.Main) { onComplete(false) }
                    return@launch
                }

                val releaseSha = updateInfo.commitSha.trim()
                val localSha = BuildConfig.GIT_COMMIT_SHA.trim()
                val localVersionCode = BuildConfig.VERSION_CODE
                val isDevBuild = localSha.isBlank() || localSha.equals("dev", ignoreCase = true)

                // Check whether this release was already installed on this device
                val prefs = context?.getSharedPreferences("graphite_updater", Context.MODE_PRIVATE)
                val lastInstalledSha = prefs?.getString("last_installed_sha", "") ?: ""
                val lastInstalledTime = prefs?.getLong("last_installed_time", 0L) ?: 0L
                val wasRecentlyInstalled = lastInstalledSha.isNotBlank() &&
                        (lastInstalledSha.equals(releaseSha, ignoreCase = true) ||
                                (releaseSha.length >= 7 && lastInstalledSha.startsWith(releaseSha, ignoreCase = true))) &&
                        (System.currentTimeMillis() - lastInstalledTime < 24 * 3600 * 1000L)

                var isNewer = false
                if (wasRecentlyInstalled) {
                    isNewer = false
                } else if (isDevBuild) {
                    // Dev builds should never pop up automatically; only show on explicit manual check
                    isNewer = isManualCheck && !releaseSha.equals("latest", ignoreCase = true)
                } else if (updateInfo.versionCode > 0 && localVersionCode > 0) {
                    // Strictly monotonic version code comparison
                    isNewer = updateInfo.versionCode > localVersionCode
                } else if (releaseSha.isNotBlank() && !releaseSha.equals("latest", ignoreCase = true)) {
                    val isSameCommit = releaseSha.equals(localSha, ignoreCase = true) ||
                            (releaseSha.length >= 7 && localSha.length >= 7 &&
                                    (releaseSha.startsWith(localSha, ignoreCase = true) || localSha.startsWith(releaseSha, ignoreCase = true)))
                    isNewer = !isSameCommit
                } else if (updateInfo.publishedAt.isNotBlank()) {
                    try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        val remoteTime = sdf.parse(updateInfo.publishedAt)?.time ?: 0L
                        if (remoteTime > BuildConfig.BUILD_TIME_MILLIS + 120_000L) {
                            isNewer = true
                        }
                    } catch (_: Exception) {}
                }

                if (isNewer) {
                    val finalInfo = updateInfo.copy(isNewer = true)
                    _latestAvailableUpdate.value = finalInfo

                    // Check snooze duration only on automatic checks, never on manual checks
                    val snoozedUntil = if (!isManualCheck && prefs != null) {
                        prefs.getLong("snoozed_until_${releaseSha}", 0L)
                    } else 0L

                    if (System.currentTimeMillis() < snoozedUntil) {
                        _updateState.value = UpdateState.Idle
                        withContext(Dispatchers.Main) { onComplete(true) }
                        return@launch
                    }

                    _updateState.value = UpdateState.Available(finalInfo)
                    withContext(Dispatchers.Main) { onComplete(true) }
                } else {
                    _latestAvailableUpdate.value = null
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
     * Downloads the APK with chunked, resumable streaming into internal cache directory,
     * verifies package archive integrity, and launches installation.
     */
    fun downloadAndInstall(context: Context, downloadUrl: String) {
        lastDownloadUrl = downloadUrl
        downloadJob?.cancel()
        downloadJob = null

        val knownTotalBytes = _latestAvailableUpdate.value?.apkSize ?: 0L
        _updateState.value = UpdateState.Downloading(0, 0L, knownTotalBytes, 0L)

        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updateDir, "Graphite-update.apk")

                var attempts = 0
                var success = false
                var lastException: Exception? = null
                var totalBytesRead = 0L
                var totalExpectedBytes = knownTotalBytes

                while (attempts < 3 && !success && isActive) {
                    attempts++
                    try {
                        val existingLength = if (apkFile.exists()) apkFile.length() else 0L
                        val requestBuilder = Request.Builder()
                            .url(downloadUrl)
                            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                            .addHeader("Accept", "*/*")

                        val shouldResume = existingLength > 0L && (knownTotalBytes == 0L || existingLength < knownTotalBytes)
                        if (shouldResume) {
                            requestBuilder.addHeader("Range", "bytes=$existingLength-")
                        } else if (apkFile.exists()) {
                            apkFile.delete()
                        }

                        val response = httpClient.newCall(requestBuilder.build()).execute()
                        val isPartial = response.code == 206
                        val isOk = response.code == 200

                        if (!isPartial && !isOk) {
                            if (response.code == 416) {
                                // Range invalid or server file changed: delete and restart
                                apkFile.delete()
                                continue
                            }
                            throw IOException("HTTP ${response.code} ${response.message}")
                        }

                        val body = response.body ?: throw IOException("Empty response body")
                        val append = isPartial && existingLength > 0L
                        if (!append && apkFile.exists()) {
                            apkFile.delete()
                        }

                        val startBytes = if (append) existingLength else 0L
                        totalBytesRead = startBytes

                        val bodyLen = body.contentLength()
                        totalExpectedBytes = if (isPartial) {
                            val contentRange = response.header("Content-Range")
                            val totalFromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
                            totalFromRange ?: if (bodyLen > 0L) startBytes + bodyLen else knownTotalBytes
                        } else {
                            if (bodyLen > 0L) bodyLen else knownTotalBytes
                        }

                        val inputStream = body.byteStream()
                        val outputStream = FileOutputStream(apkFile, append)

                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        var lastUpdateTime = System.currentTimeMillis()
                        var bytesSinceLastUpdate = 0L
                        var smoothedSpeedKbps = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            bytesSinceLastUpdate += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastUpdateTime
                            if (elapsed >= 250L || (totalExpectedBytes > 0L && totalBytesRead >= totalExpectedBytes)) {
                                val instantSpeed = if (elapsed > 0L) (bytesSinceLastUpdate * 1000L) / (elapsed * 1024L) else 0L
                                smoothedSpeedKbps = if (smoothedSpeedKbps == 0L) instantSpeed else ((smoothedSpeedKbps * 3 + instantSpeed) / 4)
                                lastUpdateTime = now
                                bytesSinceLastUpdate = 0L

                                val progress = if (totalExpectedBytes > 0L) {
                                    ((totalBytesRead * 100L) / totalExpectedBytes).toInt().coerceIn(0, 100)
                                } else 0

                                _updateState.value = UpdateState.Downloading(
                                    progressPercent = progress,
                                    bytesDownloaded = totalBytesRead,
                                    totalBytes = totalExpectedBytes,
                                    speedKbps = smoothedSpeedKbps
                                )
                            }
                        }

                        outputStream.flush()
                        outputStream.close()
                        inputStream.close()

                        if (apkFile.length() > 0L && (totalExpectedBytes == 0L || totalBytesRead >= totalExpectedBytes)) {
                            success = true
                        } else if (attempts < 3 && isActive) {
                            delay(1000L * attempts)
                        }
                    } catch (e: Exception) {
                        lastException = e
                        if (!isActive) return@launch
                        if (attempts < 3) {
                            delay(1000L * attempts)
                        }
                    }
                }

                if (!success) {
                    val errMsg = lastException?.message ?: "Incomplete download ($totalBytesRead of $totalExpectedBytes bytes)"
                    _updateState.value = UpdateState.Error("Download failed: $errMsg")
                    return@launch
                }

                // Verify downloaded APK package integrity before presenting to installer
                val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                if (archiveInfo == null) {
                    apkFile.delete()
                    _updateState.value = UpdateState.Error("Downloaded APK package is corrupted or incomplete. Please retry.")
                    return@launch
                }

                _updateState.value = UpdateState.ReadyToInstall(apkFile)
                withContext(Dispatchers.Main) {
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _updateState.value = UpdateState.Error("Download failed: ${e.message ?: "Network error"}")
                }
            }
        }
    }

    fun cancelDownload(context: Context? = null, sha: String? = null) {
        downloadJob?.cancel()
        downloadJob = null
        if (context != null) {
            try {
                val updateDir = File(context.cacheDir, "updates")
                if (updateDir.exists()) updateDir.listFiles()?.forEach { it.delete() }
            } catch (_: Exception) {}
        }
        dismissUpdate(context, sha)
    }

    fun dismissUpdate(context: Context? = null, sha: String? = null) {
        val targetSha = sha ?: (_updateState.value as? UpdateState.Available)?.info?.commitSha
        if (context != null && !targetSha.isNullOrBlank()) {
            context.getSharedPreferences("graphite_updater", Context.MODE_PRIVATE)
                .edit()
                .putLong("snoozed_until_${targetSha}", System.currentTimeMillis() + 60 * 60 * 1000L) // snooze 1 hour
                .apply()
        }
        _updateState.value = UpdateState.Idle
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                _updateState.value = UpdateState.Error("APK file not found or empty.")
                return
            }

            // Verify integrity
            val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (archiveInfo == null) {
                apkFile.delete()
                _updateState.value = UpdateState.Error("APK package is corrupted or invalid. Please re-download.")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(
                        context,
                        "Please allow Graphite to install unknown apps, then return here to install",
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
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            // Grant read permissions to all matched installer activities
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    installIntent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(installIntent, 0)
            }

            for (resolveInfo in resolved) {
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            // Explicitly grant permissions to system package installers
            listOf(
                "com.google.android.packageinstaller",
                "com.android.packageinstaller",
                "com.samsung.android.packageinstaller"
            ).forEach { pkg ->
                try {
                    context.grantUriPermission(pkg, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
            }

            // Record this release SHA as installed so Graphite will not loop upon app restart
            val targetSha = _latestAvailableUpdate.value?.commitSha
            if (!targetSha.isNullOrBlank()) {
                context.getSharedPreferences("graphite_updater", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_installed_sha", targetSha)
                    .putLong("last_installed_time", System.currentTimeMillis())
                    .apply()
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
                            text = "A new build of Graphite is available. Update now to receive the latest features, performance improvements, and fixes.",
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.info.downloadUrl)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                                updateManager.dismissUpdate(context, state.info.commitSha)
                            }
                        ) {
                            Text("Open in Browser", color = Color(0xFF64B5F6))
                        }
                        TextButton(onClick = { updateManager.dismissUpdate(context, state.info.commitSha) }) {
                            Text("Later", color = Color.Gray)
                        }
                    }
                },
                containerColor = GraphiteSurfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
        }
        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = { /* Do not abort download on accidental tap outside */ },
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
                        if (state.totalBytes > 0L) {
                            val fraction = (state.bytesDownloaded.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = GraphiteOrange,
                                trackColor = Color(0xFF333333)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = GraphiteOrange,
                                trackColor = Color(0xFF333333)
                            )
                        }
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
                        text = "Download complete and verified. Tap Install to apply the update.",
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        updateManager.lastDownloadUrl?.let { retryUrl ->
                            TextButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(retryUrl)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                    updateManager.dismissUpdate(context)
                                }
                            ) {
                                Text("Open in Browser", color = Color(0xFF64B5F6), fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Button(
                                onClick = { updateManager.downloadAndInstall(context, retryUrl) },
                                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Retry", fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = { updateManager.dismissUpdate(context) }) {
                            Text("Dismiss", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                },
                containerColor = GraphiteSurfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
        }
        else -> {}
    }
}

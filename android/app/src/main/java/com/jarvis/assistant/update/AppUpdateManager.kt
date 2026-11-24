package com.jarvis.assistant.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.jarvis.assistant.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val VERSION_CHECK_URL = "https://raw.githubusercontent.com/NikhilSaharan/Jarvis/main/version.json"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val updateScope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Checks for updates from GitHub repository.
     * Can be invoked automatically on app launch, or manually via "Check for Updates" button.
     */
    fun checkForUpdates(activity: Activity, isManual: Boolean = false) {
        updateScope.launch {
            try {
                val request = Request.Builder()
                    .url(VERSION_CHECK_URL)
                    .header("Cache-Control", "no-cache")
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()

                if (!response.isSuccessful || body.isNullOrBlank()) {
                    if (isManual) {
                        mainHandler.post {
                            Toast.makeText(activity, "Server se update info nahi mil saki.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }

                val json = JSONObject(body)
                val remoteVersionCode = json.optInt("versionCode", 0)
                val remoteVersionName = json.optString("versionName", "")
                val apkUrl = json.optString("apkUrl", "")
                val changelog = json.optString("changelog", "Bug fixes and performance improvements.")

                val currentVersionCode = BuildConfig.VERSION_CODE

                if (remoteVersionCode > currentVersionCode && apkUrl.isNotBlank()) {
                    mainHandler.post {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            showUpdatePromptDialog(activity, remoteVersionName, changelog, apkUrl)
                        }
                    }
                } else if (isManual) {
                    mainHandler.post {
                        Toast.makeText(
                            activity,
                            "Sir, J.A.R.V.I.S. pehle se latest version (v${BuildConfig.VERSION_NAME}) par hai!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                if (isManual) {
                    mainHandler.post {
                        Toast.makeText(activity, "Update check me error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUpdatePromptDialog(
        activity: Activity,
        versionName: String,
        changelog: String,
        apkUrl: String
    ) {
        AlertDialog.Builder(activity)
            .setTitle("⚡ Naya Update Available (v$versionName)")
            .setMessage("Sir, J.A.R.V.I.S. ka naya version release ho chuka hai!\n\nNaye Features:\n$changelog\n\nKya aap abhi update karna chahte hain?")
            .setPositiveButton("Update Now") { _, _ ->
                checkPermissionAndDownload(activity, apkUrl)
            }
            .setNegativeButton("Baad Me", null)
            .show()
    }

    private fun checkPermissionAndDownload(activity: Activity, apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    activity,
                    "Kripya J.A.R.V.I.S. ko 'Install Unknown Apps' permission allow karein.",
                    Toast.LENGTH_LONG
                ).show()
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(settingsIntent)
            }
        }

        downloadAndInstallApk(activity, apkUrl)
    }

    private fun downloadAndInstallApk(activity: Activity, apkUrl: String) {
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("Downloading Update...")
            .setMessage("Downloading J.A.R.V.I.S. update file...\n0%")
            .setCancelable(false)
            .create()

        progressDialog.show()

        updateScope.launch {
            var downloadSuccess = false
            val destDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
            val apkFile = File(destDir, "jarvis_update.apk")

            try {
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                val request = Request.Builder().url(apkUrl).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }

                val responseBody = response.body ?: throw Exception("Empty response body")
                val totalBytes = responseBody.contentLength()
                var downloadedBytes = 0L

                responseBody.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var lastProgress = 0

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    mainHandler.post {
                                        if (progressDialog.isShowing) {
                                            progressDialog.setMessage("Downloading J.A.R.V.I.S. update file...\n$progress%")
                                        }
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                downloadSuccess = apkFile.exists() && apkFile.length() > 0
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}", e)
                mainHandler.post {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    Toast.makeText(activity, "Download fail ho gaya: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            mainHandler.post {
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (downloadSuccess) {
                    launchPackageInstaller(activity, apkFile)
                }
            }
        }
    }

    private fun launchPackageInstaller(activity: Activity, apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer: ${e.message}", e)
            Toast.makeText(activity, "Installer open karne me error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

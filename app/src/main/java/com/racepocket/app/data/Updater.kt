package com.racepocket.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.racepocket.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Updates from the GitHub repository that builds the app: every build publishes a Release
 * ("v<version>", e.g. v2.1, from version.txt) with the APK. The app compares that version with its own and installs the new APK.
 * The repository must be public (the GitHub API does not show releases of private repositories without a token).
 */
object Updater {
    data class Release(val version: String, val apkUrl: String, val notes: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val reason: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** "v2.1" -> [2, 1]; null when the tag is not a version. */
    private fun parts(v: String): List<Int>? {
        val t = v.trim().removePrefix("v")
        if (!Regex("""\d+(\.\d+)*""").matches(t)) return null
        return t.split('.').map { it.toInt() }
    }

    /** True when version a is newer than b (2.10 > 2.9 > 2.1 > 2.0). */
    fun isNewer(a: String, b: String): Boolean {
        val x = parts(a) ?: return false
        val y = parts(b) ?: return true
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c > 0
        }
        return false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun repo(settings: AppSettings): String = settings.updateRepo.trim().ifEmpty { BuildConfig.UPDATE_REPO }

    fun check(settings: AppSettings, silent: Boolean = false) {
        val repo = repo(settings)
        if (repo.isBlank()) {
            if (!silent) state.value = State.Failed("no_repo")
            return
        }
        val s = state.value
        if (s is State.Checking || s is State.Downloading) return
        if (silent && (s is State.Available || s is State.Ready)) return
        scope.launch {
            if (!silent) state.value = State.Checking
            state.value = try {
                val r = latest(repo)
                when {
                    r != null && isNewer(r.version, currentVersion) -> State.Available(r)
                    silent -> State.Idle
                    else -> State.UpToDate
                }
            } catch (e: Exception) {
                if (silent) State.Idle else State.Failed(describe(e))
            }
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is HttpException -> "HTTP ${e.code}"
        else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
    }

    /** Never from cache: always asks GitHub. */
    private val noCache by lazy { Http.client.newBuilder().cache(null).followRedirects(false).build() }

    private suspend fun latest(repo: String): Release? {
        // 1) the release page redirects to ".../releases/tag/v<N>"; no API, no rate limit
        val fromPage = runCatching {
            withContext(Dispatchers.IO) {
                noCache.newCall(Request.Builder().url("https://github.com/$repo/releases/latest").header("User-Agent", Http.UA).build()).execute().use { r ->
                    val loc = r.header("Location") ?: return@use null
                    val tag = loc.substringAfterLast("/tag/", "")
                    val v = tag.removePrefix("v").takeIf { parts(it) != null } ?: return@use null
                    Release(v, "https://github.com/$repo/releases/download/$tag/RacePocket.apk", "")
                }
            }
        }.getOrNull()
        if (fromPage != null) return fromPage
        // 2) the GitHub API
        val text = withContext(Dispatchers.IO) {
            noCache.newCall(
                Request.Builder().url("https://api.github.com/repos/$repo/releases/latest")
                    .header("User-Agent", Http.UA).header("Accept", "application/vnd.github+json").build()
            ).execute().use { r ->
                if (!r.isSuccessful) throw HttpException(r.code)
                r.body?.string() ?: ""
            }
        }
        val j = Http.json.parseToJsonElement(text)
        val tag = j.child("tag_name").str() ?: return null
        val version = tag.removePrefix("v").takeIf { parts(it) != null } ?: return null
        val apk = j.child("assets").arr().firstOrNull { it.child("name").str()?.endsWith(".apk") == true }
            ?.child("browser_download_url").str() ?: return null
        return Release(version, apk, j.child("body").str() ?: "")
    }

    fun download(context: Context, release: Release) {
        if (state.value is State.Downloading) return
        val app = context.applicationContext
        scope.launch {
            state.value = State.Downloading(release, 0)
            state.value = try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "update").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val out = File(dir, "RacePocket-${release.version}.apk")
                    val client = Http.client.newBuilder().cache(null).followRedirects(true).build()
                    client.newCall(Request.Builder().url(release.apkUrl).header("User-Agent", Http.UA).build()).execute().use { r ->
                        if (!r.isSuccessful) throw HttpException(r.code)
                        val body = r.body ?: throw IllegalStateException("empty")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            out.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                var read = 0L
                                var last = -1
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    read += n
                                    val pct = if (total > 0) (read * 100 / total).toInt() else 0
                                    if (pct != last) {
                                        last = pct
                                        state.value = State.Downloading(release, pct)
                                    }
                                }
                            }
                        }
                    }
                    out
                }
                State.Ready(release, file)
            } catch (e: Exception) {
                State.Failed(describe(e))
            }
            (state.value as? State.Ready)?.let { install(app, it.file) }
        }
    }

    /** Opens Android's installer. The first time, Android asks to allow installs from RacePocket. */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}

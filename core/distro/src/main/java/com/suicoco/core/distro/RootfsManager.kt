package com.suicoco.core.distro

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** rootfs 下载与解压 — 官方源，app 私有存储，无 host root */
class RootfsManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun rootfsDir(): File = File(context.filesDir, "distro/ubuntu-24.04")

    fun isInstalled(): Boolean {
        val dir = rootfsDir()
        return dir.exists() && File(dir, "bin/bash").exists()
    }

    suspend fun download(
        profile: DistroProfile = DistroProfile.UBUNTU_2404,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "rootfs.tar.gz")
        val request = Request.Builder().url(profile.downloadUrl).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("下载失败: HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("响应体为空")
            val total = body.contentLength()
            var downloaded = 0L
            FileOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (body.byteStream().read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read); downloaded += read; onProgress(downloaded, total)
                }
            }
        }
        target
    }

    /** 解压到 app 私有目录（真实实现用 bundled tar 或 commons-compress，见 ARCHITECTURE.md） */
    suspend fun extract(archive: File): File = withContext(Dispatchers.IO) {
        val dest = rootfsDir(); dest.mkdirs(); dest
    }
}

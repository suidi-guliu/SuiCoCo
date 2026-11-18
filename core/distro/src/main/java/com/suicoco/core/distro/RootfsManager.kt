package com.suicoco.core.distro

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** rootfs 下载与解压 — 官方源，app 私有存储，无 host root */
class RootfsManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

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
        // SHA-256 校验（官方 SHA256SUMS 值内置于 DistroProfile）
        profile.sha256?.let { expected ->
            val actual = target.inputStream().use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (stream.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                target.delete()
                throw RuntimeException("rootfs SHA-256 校验失败: expected=$expected actual=$actual")
            }
        }
        target
    }

    /** 解压 rootfs tar.gz 到 app 私有目录（纯 Kotlin 解析 ustar/PAX/GNU tar，无需外部 tar 二进制） */
    suspend fun extract(archive: File): File = withContext(Dispatchers.IO) {
        val dest = rootfsDir()
        if (dest.exists()) dest.deleteRecursively()
        if (!dest.mkdirs()) throw IOException("无法创建目标目录: $dest")
        TarExtractor().extract(FileInputStream(archive), dest)
        archive.delete()
        dest
    }
}

/**
 * 最小化 tar.gz 解压器：
 * - 支持 POSIX ustar、PAX 扩展头（path/linkpath）、GNU longname/longlink
 * - 支持目录 / 普通文件 / 符号链接 / 硬链接；设备与 FIFO 跳过
 * - 防路径穿越：所有成员路径必须落在目标目录内
 */
private class TarExtractor {
    fun extract(input: InputStream, dest: File) {
        GZIPInputStream(BufferedInputStream(input, 128 * 1024), 64 * 1024).use { gz ->
            readEntries(gz, dest)
        }
    }

    private fun readEntries(gz: InputStream, dest: File) {
        val header = ByteArray(512)
        var paxPath: String? = null
        var paxLink: String? = null
        while (true) {
            if (!readFully(gz, header)) return            // 流结束
            if (header.all { it.toInt() == 0 }) continue  // 结束块或填充
            val size = readOctal(header, 124, 12)
            val type = (header[156].toInt() and 0xFF).toChar()
            when (type) {
                'x', 'g' -> {                              // PAX 扩展头（x 为后续条目，g 为全局）
                    val body = ByteArray(size.toInt())
                    if (!readFully(gz, body)) return
                    if (type == 'x') {
                        val kv = parsePax(body)
                        kv["path"]?.let { paxPath = it }
                        kv["linkpath"]?.let { paxLink = it }
                    }
                    skipPad(gz, size)
                }
                'L', 'K' -> {                              // GNU longname / longlink
                    val body = ByteArray(size.toInt())
                    if (!readFully(gz, body)) return
                    val s = String(body, Charsets.UTF_8).trimEnd('\u0000', '\n')
                    if (type == 'L') paxPath = s else paxLink = s
                    skipPad(gz, size)
                }
                else -> {
                    val rawName = parseString(header, 0, 100)
                    val prefix = if (header[345].toInt() != 0) parseString(header, 345, 155) else ""
                    val name = paxPath ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
                    val linkTarget = paxLink ?: parseString(header, 157, 100)
                    val mode = readOctal(header, 100, 8).toInt()
                    when (type) {
                        '5' -> safeFile(dest, name).mkdirs()
                        '0', '\u0000' -> {
                            val f = safeFile(dest, name)
                            f.parentFile?.mkdirs()
                            FileOutputStream(f).use { out -> copyN(gz, out, size) }
                            applyMode(f, mode)
                        }
                        '2' -> {
                            skipN(gz, size)
                            val f = safeFile(dest, name)
                            f.parentFile?.mkdirs()
                            if (f.exists()) f.delete()
                            try {
                                Files.createSymbolicLink(f.toPath(), Paths.get(linkTarget))
                            } catch (_: IOException) {
                                // 个别文件系统不支持符号链接时忽略，不影响主体解压
                            }
                        }
                        '1' -> {
                            skipN(gz, size)
                            val f = safeFile(dest, name)
                            val src = safeFile(dest, linkTarget)
                            f.parentFile?.mkdirs()
                            try {
                                if (f.exists()) f.delete()
                                Files.createLink(f.toPath(), src.toPath())
                            } catch (_: IOException) {
                                try {
                                    Files.copy(src.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
                                } catch (_: IOException) {
                                    // 硬链接降级复制失败时跳过该成员
                                }
                            }
                        }
                        else -> skipN(gz, size)           // 设备 / FIFO 等跳过
                    }
                    skipPad(gz, size)
                    paxPath = null
                    paxLink = null
                }
            }
        }
    }

    /** 跳过条目末尾的 512 对齐填充 */
    private fun skipPad(gz: InputStream, size: Long) {
        val pad = ((size + 511) / 512 * 512) - size
        if (pad > 0) skipN(gz, pad)
    }

    private fun parsePax(body: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        val s = String(body, Charsets.UTF_8)
        var i = 0
        while (i < s.length) {
            val sp = s.indexOf(' ', i)
            if (sp < 0) break
            val len = s.substring(i, sp).toIntOrNull() ?: break
            val eq = s.indexOf('=', sp)
            if (eq < 0 || eq >= i + len) break
            val key = s.substring(sp + 1, eq)
            val valueEnd = i + len - 1   // 末尾 \n 不计入值
            if (valueEnd > eq) out[key] = s.substring(eq + 1, valueEnd)
            i += len
        }
        return out
    }

    private fun safeFile(dest: File, name: String): File {
        val resolved = File(dest, name).canonicalFile
        val root = dest.canonicalFile
        if (resolved != root && !resolved.path.startsWith(root.path + File.separator)) {
            throw IOException("tar 条目路径越界: $name")
        }
        return resolved
    }

    private fun applyMode(f: File, mode: Int) {
        if (mode == 0) return
        val r = if (mode and 0b100_000_000 != 0) "r" else "-"
        val w = if (mode and 0b010_000_000 != 0) "w" else "-"
        val x = if (mode and 0b001_000_000 != 0) "x" else "-"
        val g = if (mode and 0b000_100_000 != 0) "r" else "-"
        val gw = if (mode and 0b000_010_000 != 0) "w" else "-"
        val gx = if (mode and 0b000_001_000 != 0) "x" else "-"
        val o = if (mode and 0b000_000_100 != 0) "r" else "-"
        val ow = if (mode and 0b000_000_010 != 0) "w" else "-"
        val ox = if (mode and 0b000_000_001 != 0) "x" else "-"
        try {
            Files.setPosixFilePermissions(
                f.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("$r$w$x$g$gw$gx$o$ow$ox")
            )
        } catch (_: IOException) {
            // 权限设置失败不阻断解压
        }
    }

    private fun parseString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val max = offset + length
        while (end < max && header[end].toInt() != 0) end++
        return String(header, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Long {
        // base-256（GNU 扩展，首字节高位为 1）优先
        if (header[offset].toInt() and 0x80 != 0) {
            var v = (header[offset].toInt() and 0x7F).toLong()
            for (i in 1 until length) v = (v shl 8) or (header[offset + i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        for (i in 0 until length) {
            val b = header[offset + i].toInt()
            if (b == 0 || b == ' '.code) break
            if (b < '0'.code || b > '7'.code) continue
            v = v shl 3 or (b - '0'.code).toLong()
        }
        return v
    }

    private fun readFully(gz: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = gz.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skipN(gz: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val n = gz.skip(remaining)
            if (n <= 0) {
                if (gz.read() < 0) return   // 流提前结束
                remaining -= 1
            } else {
                remaining -= n
            }
        }
    }

    private fun copyN(gz: InputStream, out: FileOutputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = gz.read(buf, 0, want)
            if (n < 0) throw IOException("tar 流提前结束")
            out.write(buf, 0, n)
            remaining -= n
        }
    }
}

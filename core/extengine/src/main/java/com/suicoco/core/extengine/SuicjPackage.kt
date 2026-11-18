package com.suicoco.core.extengine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.lingala.zip4j.ZipFile
import java.io.File

/** manifest.json 强类型 */
@Serializable
data class SuicjManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val main: String = "main.js",
    val publisher: String = "",
    val engines: Map<String, String> = emptyMap(),
    val categories: List<String> = emptyList(),
    val activationEvents: List<String> = emptyList(),
    val requiresEnv: String? = null,
    val contributes: SuicjContributes? = null
)

@Serializable
data class SuicjContributes(
    val languages: List<SuicjLanguage> = emptyList(),
    val commands: List<SuicjCommand> = emptyList(),
    val snippets: List<SuicjSnippet> = emptyList()
)

@Serializable
data class SuicjLanguage(val id: String, val extensions: List<String> = emptyList(), val aliases: List<String> = emptyList())
@Serializable
data class SuicjCommand(val command: String, val title: String)
@Serializable
data class SuicjSnippet(val language: String, val prefix: String, val body: List<String>, val description: String = "")

data class UnpackedPlugin(val manifest: SuicjManifest, val files: Map<String, String>, val dir: File)
class SuicjException(message: String) : Exception(message)

/**
 * .suicj 解包与校验器 — zip 容器 + manifest.json + main.js。
 * 严格拒绝其他插件格式（.vsix/.jext 等）。
 */
object SuicjPackage {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*\\.[a-zA-Z0-9][a-zA-Z0-9._-]*$")
    private val VERSION_REGEX = Regex("^\\d+\\.\\d+\\.\\d+([-+].+)?$")

    fun unpack(file: File, workDir: File): UnpackedPlugin {
        if (!file.name.endsWith(".suicj", ignoreCase = true)) {
            throw SuicjException("不支持的插件格式：仅接受 .suicj（不兼容 .vsix/.jext 等其他格式）")
        }
        if (!file.exists() || file.length() == 0L) throw SuicjException("插件文件不存在或为空")

        val zip = ZipFile(file)
        // 关键安全修复：在解压落盘「之前」审查所有条目（原实现在 extractAll 之后才 walk 校验，
        // 此时 zip-slip 恶意文件已写出到目标目录之外，校验形同虚设）。
        val headers = try {
            zip.fileHeaders
        } catch (e: Exception) {
            throw SuicjException("插件包 ZIP 结构非法：${e.message}")
        }
        val infos = headers.map { h ->
            SuicjImportPolicy.EntryInfo(
                name = h.fileName,
                uncompressedSize = h.uncompressedSize,
                compressedSize = h.compressedSize,
                isDirectory = h.isDirectory
            )
        }
        when (val verdict = SuicjImportPolicy.review(infos)) {
            is SuicjImportPolicy.Verdict.Reject -> throw SuicjException("插件包未通过安全校验：${verdict.reason}")
            SuicjImportPolicy.Verdict.Pass -> Unit
        }

        val extractDir = File(workDir, file.nameWithoutExtension + "_" + System.currentTimeMillis())
        extractDir.mkdirs()
        return try {
            zip.extractAll(extractDir.absolutePath)
            parseAndValidate(extractDir)
        } catch (e: SuicjException) {
            extractDir.deleteRecursively(); throw e
        } catch (e: Exception) {
            extractDir.deleteRecursively(); throw SuicjException("解包失败：${e.message}")
        }
    }

    private fun parseAndValidate(dir: File): UnpackedPlugin {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.exists()) throw SuicjException("缺少 manifest.json")
        val manifest = try {
            json.decodeFromString<SuicjManifest>(manifestFile.readText())
        } catch (e: Exception) {
            throw SuicjException("manifest.json 解析失败：${e.message}")
        }

        if (manifest.id.isBlank()) throw SuicjException("manifest 缺少必填字段 id")
        if (!ID_REGEX.matches(manifest.id)) throw SuicjException("id 格式非法：需形如 publisher.name")
        if (manifest.name.isBlank()) throw SuicjException("manifest 缺少必填字段 name")
        if (!VERSION_REGEX.matches(manifest.version)) throw SuicjException("version 格式非法：需 x.y.z")
        if (manifest.main.isBlank()) throw SuicjException("manifest 缺少必填字段 main")
        if (manifest.engines.isEmpty() || !manifest.engines.containsKey("suicoco")) {
            throw SuicjException("engines 缺少 suicoco 字段（本插件仅适用于 suicoco）")
        }
        if (!SuicjImportPolicy.isEngineCompatible(manifest.engines["suicoco"] ?: "")) {
            throw SuicjException("引擎版本不兼容：需要 suicoco ${manifest.engines["suicoco"]}（当前 ${SuicjImportPolicy.HOST_VERSION}）")
        }
        if (!SuicjImportPolicy.isSafeMain(manifest.main)) {
            throw SuicjException("main 入口非法：必须为安全的 .js 路径（${manifest.main}）")
        }
        val mainFile = File(dir, manifest.main)
        if (!mainFile.exists() || !mainFile.isFile) {
            throw SuicjException("main 指向的文件不存在或不是普通文件：${manifest.main}")
        }

        // 二次防御：解压后再次核对落盘路径未越界，且拒绝符号链接（防止 latent zip-slip）。
        val rootCanonical = dir.canonicalPath
        dir.walkTopDown().forEach { f ->
            if (!f.canonicalPath.startsWith(rootCanonical + File.separator) && f.canonicalPath != rootCanonical) {
                throw SuicjException("插件包含非法路径（目录穿越）：${f.relativeTo(dir)}")
            }
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                throw SuicjException("插件包含符号链接，出于安全考虑拒绝：${f.relativeTo(dir)}")
            }
        }

        val files = dir.walkTopDown().filter { it.isFile }
            .associate { f -> f.relativeTo(dir).path.replace(File.separatorChar, '/') to f.readText() }
        return UnpackedPlugin(manifest, files, dir)
    }
}

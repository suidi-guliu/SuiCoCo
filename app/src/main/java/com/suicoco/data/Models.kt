package com.suicoco.data

/** 文件树节点 */
data class FileNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val children: List<FileNode> = emptyList(),
    val language: String? = null
)

/** 打开的编辑器标签 */
data class EditorTab(
    val path: String,
    val name: String,
    val content: String,
    val language: String?,
    val dirty: Boolean = false
)

/** 终端行 */
data class TerminalLine(val text: String, val kind: Kind = Kind.OUT) {
    enum class Kind { COMMAND, OUT, ERR, INFO, SUCCESS }
}

/** 诊断项 */
data class Diagnostic(
    val line: Int,
    val column: Int,
    val message: String,
    val severity: Severity = Severity.ERROR
) {
    enum class Severity { ERROR, WARNING, INFO }
}

/** 补全项 */
data class CompletionItem(
    val label: String,
    val kind: String,
    val detail: String = "",
    val insertText: String = label
)

/** 安装步骤状态 */
enum class StepStatus { PENDING, RUNNING, DONE, FAILED }

/** 安装向导步骤 */
data class InstallStep(
    val id: String,
    val title: String,
    val subtitle: String = "",
    var status: StepStatus = StepStatus.PENDING
)

/** 插件信息（对应 manifest.json） */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val main: String = "main.js",
    val publisher: String = "",
    val engines: Map<String, String> = emptyMap(),
    val categories: List<String> = emptyList(),
    val activationEvents: List<String> = emptyList(),
    val contributes: Contributes = Contributes()
)

data class Contributes(
    val languages: List<LanguageContribution> = emptyList(),
    val commands: List<CommandContribution> = emptyList(),
    val snippets: List<SnippetContribution> = emptyList()
)

data class LanguageContribution(
    val id: String,
    val extensions: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val grammar: String? = null
)

data class CommandContribution(val command: String, val title: String)

data class SnippetContribution(
    val language: String,
    val prefix: String,
    val body: List<String>,
    val description: String = ""
)

/** 已安装插件 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val source: Source,
    val status: PluginStatus = PluginStatus.INSTALLED
) {
    enum class Source { OFFICIAL, THIRD_PARTY }
    enum class PluginStatus { INSTALLED, ACTIVATED, DISABLED }
}

/** 运行任务 */
data class TaskDefinition(
    val id: String,
    val label: String,
    val command: String,
    val language: String
)

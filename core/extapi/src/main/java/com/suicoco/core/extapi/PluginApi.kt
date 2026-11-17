package com.suicoco.core.extapi

/**
 * 插件宿主 API 契约 — .suicj 插件通过 activate(ctx) 的 ctx 参数访问。
 * 与 docs/PLUGIN_SPEC.md 保持一致。第三方插件只能使用此处暴露的能力。
 * 对应 JCode 的 ExtensionHost，但专用于自有 .suicj 格式。
 */
interface PluginContext {
    val version: String
    val languages: LanguageApi
    val commands: CommandApi
    val tasks: TaskApi
    val window: WindowApi
    val workspace: WorkspaceApi
    val diagnostics: DiagnosticsApi
}

interface LanguageApi {
    fun registerLanguage(def: Map<String, Any?>)
    fun registerCompletionItemProvider(languageId: String, provider: Map<String, Any?>)
}

interface CommandApi {
    fun registerCommand(id: String, title: String, handler: () -> Unit)
}

interface TaskApi {
    fun registerTasks(tasks: List<Map<String, Any?>>)
}

interface WindowApi {
    fun showMessage(message: String)
    fun showInformationMessage(message: String) = showMessage(message)
    fun showErrorMessage(message: String)
    fun setStatusBarMessage(message: String)
}

interface WorkspaceApi {
    fun onDidSave(handler: (String, String) -> Unit)
}

interface DiagnosticsApi {
    fun setProblems(problems: List<Map<String, Any?>>)
}

/** 插件生命周期 */
interface PluginLifecycle {
    fun activate(ctx: PluginContext)
    fun deactivate() {}
}

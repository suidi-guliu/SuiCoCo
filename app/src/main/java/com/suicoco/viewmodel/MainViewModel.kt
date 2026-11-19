package com.suicoco.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suicoco.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

/**
 * 工作台主状态机 — 对标 JCode 的 MainViewModel，管理编辑器/终端/面板/插件全部交互状态。
 */
class MainViewModel : ViewModel() {

    // ---- 活动栏 ----
    enum class ActivityView { EXPLORER, SEARCH, SCM, DEBUG, EXTENSIONS, SETTINGS }
    private val _activityView = MutableStateFlow(ActivityView.EXPLORER)
    val activityView: StateFlow<ActivityView> = _activityView.asStateFlow()

    // ---- 侧边栏可见性 ----
    private val _sideBarVisible = MutableStateFlow(true)
    val sideBarVisible: StateFlow<Boolean> = _sideBarVisible.asStateFlow()

    // ---- 文件树 ----
    private val _fileTree = MutableStateFlow(defaultFileTree())
    val fileTree: StateFlow<List<FileNode>> = _fileTree.asStateFlow()

    // ---- 打开的标签 ----
    private val _tabs = MutableStateFlow<List<EditorTab>>(emptyList())
    val tabs: StateFlow<List<EditorTab>> = _tabs.asStateFlow()
    private val _activeTabPath = MutableStateFlow<String?>(null)
    val activeTabPath: StateFlow<String?> = _activeTabPath.asStateFlow()

    val activeTab: StateFlow<EditorTab?> = combine(_tabs, _activeTabPath) { list, path ->
        list.find { it.path == path }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ---- 终端 ----
    private val _terminalLines = MutableStateFlow<List<TerminalLine>>(initialTerminal())
    val terminalLines: StateFlow<List<TerminalLine>> = _terminalLines.asStateFlow()
    private val _panelVisible = MutableStateFlow(false)
    val panelVisible: StateFlow<Boolean> = _panelVisible.asStateFlow()
    private val _panelTab = MutableStateFlow("终端")
    val panelTab: StateFlow<String> = _panelTab.asStateFlow()

    // ---- 命令面板 / 快速输入 ----
    enum class QuickMode { COMMAND, FILE, NONE }
    private val _quickMode = MutableStateFlow<QuickMode>(QuickMode.NONE)
    val quickMode: StateFlow<QuickMode> = _quickMode.asStateFlow()
    private val _quickQuery = MutableStateFlow("")
    val quickQuery: StateFlow<String> = _quickQuery.asStateFlow()

    // ---- 补全弹层 ----
    private val _completions = MutableStateFlow<List<CompletionItem>>(emptyList())
    val completions: StateFlow<List<CompletionItem>> = _completions.asStateFlow()
    private val _completionAnchor = MutableStateFlow<Pair<Int, Int>>(0 to 0) // line, column
    val completionAnchor: StateFlow<Pair<Int, Int>> = _completionAnchor.asStateFlow()

    // ---- 诊断 ----
    private val _diagnostics = MutableStateFlow<List<Diagnostic>>(emptyList())
    val diagnostics: StateFlow<List<Diagnostic>> = _diagnostics.asStateFlow()

    // ---- 插件 ----
    private val _plugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val plugins: StateFlow<List<InstalledPlugin>> = _plugins.asStateFlow()
    private val _langExts = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val langExts: StateFlow<Map<String, List<String>>> = _langExts.asStateFlow()

    // ---- 语言环境是否已安装（环境守卫）----
    private val _envInstalled = MutableStateFlow(setOf<String>())
    val envInstalled: StateFlow<Set<String>> = _envInstalled.asStateFlow()

    // ---- 安装向导 ----
    private val _wizardVisible = MutableStateFlow(false)
    val wizardVisible: StateFlow<Boolean> = _wizardVisible.asStateFlow()

    // ---- 插件市场 / 第三方导入浮层 ----
    private val _marketplaceVisible = MutableStateFlow(false)
    val marketplaceVisible: StateFlow<Boolean> = _marketplaceVisible.asStateFlow()
    private val _installSteps = MutableStateFlow(
        listOf(
            InstallStep("rootfs", "下载 Ubuntu 24.04 LTS (ARM64)", "官方源 · proot 运行环境"),
            InstallStep("toolchain-c", "C/C++ 工具链", "gcc · g++ · gdb · cmake"),
            InstallStep("toolchain-python", "Python 环境", "python3 · pip · debugpy"),
            InstallStep("toolchain-java", "Java 环境", "openjdk-17 · javac · maven")
        )
    )
    val installSteps: StateFlow<List<InstallStep>> = _installSteps.asStateFlow()

    // ---- 通知 ----
    private val _notification = MutableStateFlow<Pair<String, String>?>(null) // type, message
    val notification: StateFlow<Pair<String, String>?> = _notification.asStateFlow()

    // ================= 交互方法 =================

    fun setActivityView(view: ActivityView) {
        if (_activityView.value == view) {
            _sideBarVisible.value = !_sideBarVisible.value
        } else {
            _activityView.value = view
            _sideBarVisible.value = true
        }
    }

    fun toggleSideBar() { _sideBarVisible.value = !_sideBarVisible.value }

    fun openFile(path: String) {
        _tabs.value.find { it.path == path }?.let {
            _activeTabPath.value = path
            return
        }
        val node = findNode(_fileTree.value, path)
        val name = node?.name ?: path.substringAfterLast('/')
        val lang = languageFor(path)
        val content = DEMO_CONTENT[path] ?: "// $name\n// 在 suicoco 中打开文件\n"
        val tab = EditorTab(path, name, content, lang)
        _tabs.value = _tabs.value + tab
        _activeTabPath.value = path
        val current = _envInstalled.value
        // 环境守卫：语言插件未装时提示（不拦截编辑，只拦截运行）
        if (lang != null && lang !in current && lang in setOf("c", "cpp", "python", "java")) {
            postNotification("warn", "检测到 $lang 源文件，语言环境尚未安装。运行前请先完成环境安装。")
        }
    }

    fun closeTab(path: String) {
        val list = _tabs.value.filter { it.path != path }
        _tabs.value = list
        if (_activeTabPath.value == path) {
            _activeTabPath.value = list.lastOrNull()?.path
        }
    }

    fun updateContent(path: String, content: String) {
        _tabs.value = _tabs.value.map { if (it.path == path) it.copy(content = content, dirty = true) else it }
        updateCompletions(path, content)
    }

    private fun updateCompletions(path: String, content: String) {
        val lang = languageFor(path) ?: run { _completions.value = emptyList(); return }
        val prefix = content.substringAfterLast('\n').trim().substringAfterLast(' ')
        if (prefix.length < 1) { _completions.value = emptyList(); return }
        val pool = SNIPPETS[lang].orEmpty() + COMMON_WORDS
        val matches = pool.filter { it.label.startsWith(prefix, ignoreCase = true) }.take(8)
        _completions.value = matches
        val line = content.count { it == '\n' }
        _completionAnchor.value = line to prefix.length
    }

    fun dismissCompletions() { _completions.value = emptyList() }

    fun acceptCompletion(item: CompletionItem) {
        val path = _activeTabPath.value ?: return
        val tab = _tabs.value.find { it.path == path } ?: return
        val lines = tab.content.split('\n').toMutableList()
        val (lineIdx, col) = _completionAnchor.value
        if (lineIdx in lines.indices) {
            val line = lines[lineIdx]
            val before = line.substring(0, minOf(col, line.length))
            val after = line.substring(minOf(col, line.length))
            lines[lineIdx] = before + item.insertText + after
            updateContent(path, lines.joinToString("\n"))
        }
        _completions.value = emptyList()
    }

    fun runTask(task: TaskDefinition) {
        val lang = task.language
        if (lang !in _envInstalled.value) {
            appendTerminal("环境未安装：请先安装 $lang 语言环境（左侧 ⚙ 安装向导）", TerminalLine.Kind.ERR)
            postNotification("error", "无法运行：$lang 语言环境未安装")
            togglePanel(true)
            return
        }
        appendTerminal("$ ${task.command}", TerminalLine.Kind.COMMAND)
        appendTerminal("[suicoco] 在 proot Ubuntu 中执行任务：${task.label}", TerminalLine.Kind.INFO)
        appendTerminal("[suicoco] 任务已提交（桌面原型模拟执行，真实执行需 proot 运行时）", TerminalLine.Kind.SUCCESS)
        togglePanel(true)
    }

    fun execTerminalCommand(cmd: String) {
        appendTerminal("$ $cmd", TerminalLine.Kind.COMMAND)
        when (cmd.trim()) {
            "help" -> appendTerminal("可用命令: help, clear, ls, pwd, gcc --version, python3 --version, java -version", TerminalLine.Kind.OUT)
            "clear" -> _terminalLines.value = emptyList()
            "pwd" -> appendTerminal("/workspace", TerminalLine.Kind.OUT)
            "ls" -> appendTerminal("main.cpp  app.py  Main.java  README.md", TerminalLine.Kind.OUT)
            else -> appendTerminal("[suicoco] 命令需 proot 运行时支持：$cmd", TerminalLine.Kind.INFO)
        }
    }

    private fun appendTerminal(text: String, kind: TerminalLine.Kind) {
        _terminalLines.value = _terminalLines.value + TerminalLine(text, kind)
    }

    fun togglePanel(show: Boolean? = null) {
        _panelVisible.value = show ?: !_panelVisible.value
    }

    fun setPanelTab(tab: String) { _panelTab.value = tab; _panelVisible.value = true }

    fun openQuick(mode: QuickMode) {
        _quickMode.value = mode
        _quickQuery.value = ""
    }
    fun closeQuick() { _quickMode.value = QuickMode.NONE }
    fun setQuickQuery(q: String) { _quickQuery.value = q }

    fun openWizard() { _wizardVisible.value = true }
    fun closeWizard() { _wizardVisible.value = false }

    fun openMarketplace() { _marketplaceVisible.value = true }
    fun closeMarketplace() { _marketplaceVisible.value = false }

    fun completeStep(stepId: String) {
        _installSteps.value = _installSteps.value.map {
            if (it.id == stepId) it.copy(status = StepStatus.DONE) else it
        }
        if (stepId.startsWith("toolchain-")) {
            val lang = stepId.removePrefix("toolchain-")
            _envInstalled.value = _envInstalled.value + lang
        }
        postNotification("success", "安装完成：$stepId")
    }

    fun setStepRunning(stepId: String) {
        _installSteps.value = _installSteps.value.map {
            if (it.id == stepId) it.copy(status = StepStatus.RUNNING) else it
        }
    }

    fun installPlugin(manifest: PluginManifest, source: InstalledPlugin.Source) {
        if (_plugins.value.any { it.manifest.id == manifest.id }) {
            postNotification("warn", "插件已安装：${manifest.name}")
            return
        }
        _plugins.value = _plugins.value + InstalledPlugin(manifest, source)
        manifest.contributes.languages.forEach { lang ->
            val extMap = _langExts.value.toMutableMap()
            lang.extensions.forEach { ext -> extMap[ext] = listOf(lang.id) }
            _langExts.value = extMap
        }
        postNotification("success", "插件已安装：${manifest.name} v${manifest.version}")
    }

    fun uninstallPlugin(id: String) {
        _plugins.value = _plugins.value.filter { it.manifest.id != id }
        postNotification("info", "插件已卸载：$id")
    }

    fun postNotification(type: String, message: String) {
        _notification.value = type to message
    }
    fun clearNotification() { _notification.value = null }

    // ================= 辅助数据 =================

    companion object {
        private fun languageFor(path: String): String? = when (path.substringAfterLast('.').lowercase()) {
            "c" -> "c"
            "h" -> "c"
            "cpp", "cc", "cxx", "hpp" -> "cpp"
            "py" -> "python"
            "java" -> "java"
            "js", "json", "ts" -> "javascript"
            "md" -> "markdown"
            else -> null
        }

        private fun findNode(nodes: List<FileNode>, path: String): FileNode? {
            for (n in nodes) {
                if (n.path == path) return n
                if (n.isDir) findNode(n.children, path)?.let { return it }
            }
            return null
        }

        private val COMMON_WORDS = listOf(
            CompletionItem("include", "keyword"), CompletionItem("return", "keyword"),
            CompletionItem("if", "keyword"), CompletionItem("else", "keyword"),
            CompletionItem("for", "keyword"), CompletionItem("while", "keyword"),
            CompletionItem("class", "keyword"), CompletionItem("import", "keyword"),
            CompletionItem("def", "keyword"), CompletionItem("print", "function"),
            CompletionItem("public", "keyword"), CompletionItem("static", "keyword")
        )

        private val SNIPPETS = mapOf(
            "c" to listOf(
                CompletionItem("main", "snippet", "main 函数", "int main(int argc, char** argv) {\n    return 0;\n}"),
                CompletionItem("printf", "function", "格式化输出", "printf(\"\");")
            ),
            "cpp" to listOf(
                CompletionItem("main", "snippet", "main 函数", "int main() {\n    return 0;\n}"),
                CompletionItem("cout", "snippet", "标准输出", "std::cout << \"\";")
            ),
            "python" to listOf(
                CompletionItem("main", "snippet", "主入口", "if __name__ == \"__main__\":\n    pass"),
                CompletionItem("print", "function", "输出", "print()")
            ),
            "java" to listOf(
                CompletionItem("main", "snippet", "main 方法", "public static void main(String[] args) {\n    \n}"),
                CompletionItem("sout", "snippet", "输出", "System.out.println();")
            )
        )

        private fun defaultFileTree() = listOf(
            FileNode("SUICOCO-DEMO", "/workspace", true, listOf(
                FileNode("main.cpp", "/workspace/main.cpp", false, language = "cpp"),
                FileNode("app.py", "/workspace/app.py", false, language = "python"),
                FileNode("Main.java", "/workspace/Main.java", false, language = "java"),
                FileNode("README.md", "/workspace/README.md", false, language = "markdown")
            ))
        )

        private val DEMO_CONTENT = mapOf(
            "/workspace/main.cpp" to """#include <iostream>

int main() {
    std::cout << "Hello, suicoco!" << std::endl;
    return 0;
}
""",
            "/workspace/app.py" to """def main():
    print("Hello, suicoco!")

if __name__ == "__main__":
    main()
""",
            "/workspace/Main.java" to """public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, suicoco!");
    }
}
""",
            "/workspace/README.md" to """# suicoco 演示工作区

这是一个演示项目，包含 C++、Python、Java 三种语言的示例文件。
点击文件打开编辑器，体验语法高亮与代码补全。
"""
        )

        private fun initialTerminal() = listOf(
            TerminalLine("suicoco terminal — proot Ubuntu 24.04 LTS (arm64)", TerminalLine.Kind.INFO),
            TerminalLine("输入 'help' 查看可用命令。", TerminalLine.Kind.OUT)
        )
    }
}

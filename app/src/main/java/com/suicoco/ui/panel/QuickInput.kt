package com.suicoco.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.suicoco.core.design.VscodeTheme
import com.suicoco.data.*
import com.suicoco.viewmodel.MainViewModel

/** 命令面板 / 快速打开（VSCode Ctrl+Shift+P / Ctrl+P 复刻） */
@Composable
fun QuickInputOverlay(vm: MainViewModel) {
    val mode by vm.quickMode.collectAsState()
    if (mode == MainViewModel.QuickMode.NONE) return

    val query by vm.quickQuery.collectAsState()
    val tabs by vm.tabs.collectAsState()

    Dialog(onDismissRequest = { vm.closeQuick() }) {
        Column(
            modifier = Modifier.fillMaxWidth().wrapContentHeight()
                .background(VscodeTheme.WidgetBg, RoundedCornerShape(6.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(38.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (mode == MainViewModel.QuickMode.FILE) ">" else ">",
                    color = VscodeTheme.Fg, fontSize = 18.sp, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { vm.setQuickQuery(it) },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(color = VscodeTheme.Fg, fontSize = 15.sp),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(
                            if (mode == MainViewModel.QuickMode.FILE) "按文件名搜索..."
                            else "输入命令名称或搜索文件",
                            color = VscodeTheme.FgDim, fontSize = 14.sp
                        )
                        inner()
                    }
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(VscodeTheme.Border))

            val items = buildQuickItems(vm, mode, query)
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(items) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(30.dp)
                            .padding(horizontal = 12.dp)
                            .clickable { item.run(); vm.closeQuick() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.icon, fontSize = 14.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(item.label, color = VscodeTheme.Fg, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        if (item.detail.isNotEmpty()) {
                            Text(item.detail, color = VscodeTheme.FgDim, fontSize = 11.sp)
                        }
                    }
                }
                if (items.isEmpty()) {
                    item {
                        Text("无匹配结果", color = VscodeTheme.FgDim, fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

private data class QuickItem(val icon: String, val label: String, val detail: String = "", val run: () -> Unit)

private fun buildQuickItems(vm: MainViewModel, mode: MainViewModel.QuickMode, query: String): List<QuickItem> {
    val q = query.lowercase().removePrefix(">").trim()
    val all = mutableListOf<QuickItem>()

    if (mode == MainViewModel.QuickMode.FILE) {
        listOf("main.cpp", "app.py", "Main.java", "README.md").forEach { name ->
            all += QuickItem("📄", name) { vm.openFile("/workspace/$name") }
        }
    } else {
        // 内置命令
        all += QuickItem("⚙", "安装向导: 打开环境安装向导") { vm.openWizard() }
        all += QuickItem("▤", "视图: 切换终端面板") { vm.togglePanel() }
        all += QuickItem("📁", "文件: 快速打开文件") { vm.openQuick(MainViewModel.QuickMode.FILE) }

        // 运行任务（每个语言）
        listOf("c" to "运行 C 文件", "cpp" to "运行 C++ 文件", "python" to "运行 Python 文件", "java" to "运行 Java 文件")
            .forEach { (lang, label) ->
                all += QuickItem("▶", "任务: $label", lang) {
                    vm.runTask(TaskDefinition("run-$lang", label, "run $lang", lang))
                }
            }

        // 插件相关
        all += QuickItem("🧩", "插件: 安装官方语言插件 (C/C++)") {
            installLangPlugin(vm, "c-cpp")
        }
        all += QuickItem("🧩", "插件: 安装官方语言插件 (Python)") {
            installLangPlugin(vm, "python")
        }
        all += QuickItem("🧩", "插件: 安装官方语言插件 (Java)") {
            installLangPlugin(vm, "java")
        }
        all += QuickItem("📥", "插件: 导入第三方 .suicj") {
            vm.openMarketplace()
        }
    }

    return if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) || it.detail.lowercase().contains(q) }
}

private fun installLangPlugin(vm: MainViewModel, which: String) {
    val manifest = when (which) {
        "c-cpp" -> PluginManifest(
            id = "suicj.lang.c-cpp", name = "C/C++ Language", version = "0.1.0",
            description = "C/C++ 语法高亮、补全与运行任务",
            contributes = Contributes(languages = listOf(
                LanguageContribution("cpp", listOf(".c", ".h", ".cpp", ".cc", ".hpp"), listOf("C++", "cpp"))
            ))
        )
        "python" -> PluginManifest(
            id = "suicj.lang.python", name = "Python Language", version = "0.1.0",
            description = "Python 语法高亮、补全与运行任务",
            contributes = Contributes(languages = listOf(
                LanguageContribution("python", listOf(".py"), listOf("Python"))
            ))
        )
        else -> PluginManifest(
            id = "suicj.lang.java", name = "Java Language", version = "0.1.0",
            description = "Java 语法高亮、补全与运行任务",
            contributes = Contributes(languages = listOf(
                LanguageContribution("java", listOf(".java"), listOf("Java"))
            ))
        )
    }
    vm.installPlugin(manifest, InstalledPlugin.Source.OFFICIAL)
}

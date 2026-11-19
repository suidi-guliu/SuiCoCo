package com.suicoco.ui.panel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.suicoco.core.design.VscodeTheme
import com.suicoco.data.InstalledPlugin
import com.suicoco.data.PluginManifest
import com.suicoco.feature.marketplace.ImportController
import com.suicoco.feature.marketplace.MarketplaceScreen
import com.suicoco.viewmodel.MainViewModel
import java.io.File

/**
 * 插件市场浮层 —— 宿主 app 侧的“导入 .suicj”文件选择器接线。
 *
 * 文件选择器使用 activity-compose 的 GetContent（app 已具备该依赖），选中文件后复制到
 * app 私有 cache，交由 feature:marketplace 的 [ImportController] 复用 core:extengine 的
 * 完整安全校验；成功则把插件摘要登记到 [MainViewModel] 的已安装列表并提示。
 * 业务校验规则不在此重复，保持单一真源（SuicjImportPolicy / SuicjPackage）。
 */
@Composable
fun PluginMarketplaceOverlay(vm: MainViewModel) {
    val visible by vm.marketplaceVisible.collectAsState()
    if (!visible) return

    val context = LocalContext.current
    var importResult by remember { mutableStateOf<ImportController.ImportResult?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importResult = try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                ImportController.ImportResult.Rejected("无法读取所选文件")
            } else {
                val tmp = File(context.cacheDir, "import-${System.currentTimeMillis()}.suicj")
                tmp.writeBytes(bytes)
                val workDir = File(context.filesDir, "third-party-plugins")
                workDir.mkdirs()
                val result = ImportController.import(tmp, workDir)
                if (result is ImportController.ImportResult.Success) {
                    val info = result.plugin
                    val manifest = PluginManifest(
                        id = info.id,
                        name = info.name,
                        version = info.version,
                        description = info.description,
                        main = info.main,
                        publisher = "third-party"
                    )
                    vm.installPlugin(manifest, InstalledPlugin.Source.THIRD_PARTY)
                }
                result
            }
        } catch (e: Exception) {
            ImportController.ImportResult.Rejected("读取文件失败：${e.message ?: "未知错误"}")
        }
    }

    Dialog(onDismissRequest = { vm.closeMarketplace() }) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = VscodeTheme.SideBarBg,
            modifier = Modifier.fillMaxWidth().height(520.dp)
        ) {
            Box(Modifier.fillMaxSize()) {
                MarketplaceScreen(
                    importState = importResult,
                    onPickFile = { pickLauncher.launch("*/*") },
                    onDismissResult = { importResult = null }
                )
                Icon(
                    Icons.Default.Close, "关闭", tint = VscodeTheme.FgMuted,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                        .clickable { vm.closeMarketplace() }
                )
            }
        }
    }
}

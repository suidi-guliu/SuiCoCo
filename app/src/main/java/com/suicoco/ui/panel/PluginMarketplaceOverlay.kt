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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.suicoco.feature.marketplace.OfficialInstallController
import com.suicoco.feature.settings.RepositoryConfigStore
import com.suicoco.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 插件市场浮层 —— 宿主 app 侧的接线层。
 *
 * 第三方导入：用 activity-compose 的 GetContent 选文件 → 复制到私有 cache → 交
 * [ImportController] 复用 core:extengine 的完整安全校验；成功登记为 THIRD_PARTY。
 *
 * 官方一键安装（2026-11-20 06:03 日报 P0 后续余项）：从 [RepositoryConfigStore] 读取跨会话
 * 持久化的仓库地址 → 后台加载官方清单 → 用户对兼容条目点「一键安装」→ 受控重定向下载到 raw
 * 二进制 → SHA256 校验 → 复用 [ImportController] 做 zip-slip/引擎兼容等完整安全校验 → 成功登记
 * 为 OFFICIAL。所有网络 I/O 与文件写入在 [Dispatchers.IO] 执行，状态更新回主线程。
 * 业务校验规则不在此重复，保持单一真源（OfficialInstallController / SuicjImportPolicy）。
 */
@Composable
fun PluginMarketplaceOverlay(vm: MainViewModel) {
    val visible by vm.marketplaceVisible.collectAsState()
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hostVersion = HOST_VERSION

    var importResult by remember { mutableStateOf<ImportController.ImportResult?>(null) }
    var catalogState by remember { mutableStateOf<OfficialInstallController.CatalogResult?>(null) }
    var installState by remember { mutableStateOf<OfficialInstallController.VerifyResult?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var busyInstalling by remember { mutableStateOf<String?>(null) }

    val repoStore = remember { RepositoryConfigStore(context) }

    fun refreshCatalog() {
        scope.launch {
            catalogState = withContext(Dispatchers.IO) {
                val catalogUrl = OfficialInstallController.resolveCatalogUrl(repoStore.load())
                    ?: return@withContext OfficialInstallController.CatalogResult.Failure("仓库地址非法或不在白名单，请到设置中检查")
                when (val dl = OfficialInstallController.fetchWithGuardedRedirects(
                    catalogUrl, OfficialInstallController::networkFetch
                )) {
                    is OfficialInstallController.DownloadResult.Fail ->
                        OfficialInstallController.CatalogResult.Failure(dl.reason)
                    is OfficialInstallController.DownloadResult.Ok ->
                        OfficialInstallController.loadCatalog(
                            dl.bytes.toString(Charsets.UTF_8), hostVersion
                        )
                }
            }
        }
    }

    // 每次打开浮层都重新拉取清单，确保摘要与线上产物同步。
    LaunchedEffect(Unit) { refreshCatalog() }

    // 后台安装的返回：校验态（供 UI 展示）+ 错误原因 + 成功时待登记的 manifest。
    data class InstallOutcome(
        val verify: OfficialInstallController.VerifyResult? = null,
        val error: String? = null,
        val manifest: PluginManifest? = null
    )

    fun installOfficial(item: OfficialInstallController.CatalogItem) {
        if (busyInstalling != null) return
        busyInstalling = item.file
        installState = null
        installError = null
        scope.launch {
            // 后台只做 I/O，不触碰 Compose 状态。
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val url = OfficialInstallController.resolveDownloadUrl(repoStore.load(), item.file)
                        ?: return@withContext InstallOutcome(error = "下载地址非法或不在白名单")
                    when (val dl = OfficialInstallController.fetchWithGuardedRedirects(
                        url, OfficialInstallController::networkFetch
                    )) {
                        is OfficialInstallController.DownloadResult.Fail ->
                            InstallOutcome(error = dl.reason)
                        is OfficialInstallController.DownloadResult.Ok ->
                            when (val verified = OfficialInstallController.verifyDownloaded(dl.bytes, item)) {
                                is OfficialInstallController.VerifyResult.Fail ->
                                    InstallOutcome(verify = verified)
                                is OfficialInstallController.VerifyResult.Ok -> {
                                    // SHA256 通过后写入私有 cache，复用第三方导入的完整安全链路。
                                    val tmp = File(context.cacheDir, "official-${item.file}")
                                    tmp.writeBytes(dl.bytes)
                                    val workDir = File(context.filesDir, "official-plugins")
                                    workDir.mkdirs()
                                    when (val ir = ImportController.import(tmp, workDir)) {
                                        is ImportController.ImportResult.Rejected ->
                                            InstallOutcome(verify = verified, error = ir.reason)
                                        is ImportController.ImportResult.Success -> {
                                            val info = ir.plugin
                                            InstallOutcome(
                                                verify = verified,
                                                manifest = PluginManifest(
                                                    id = info.id,
                                                    name = info.name,
                                                    version = info.version,
                                                    description = info.description,
                                                    main = info.main,
                                                    publisher = "suicoco-official",
                                                    engines = mapOf("suicoco" to item.engine)
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                    }
                } catch (e: Exception) {
                    InstallOutcome(error = "安装失败：${e.message ?: e::class.simpleName}")
                }
            }
            // 状态更新统一在主线程完成。
            outcome.verify?.let { installState = it }
            outcome.manifest?.let { vm.installPlugin(it, InstalledPlugin.Source.OFFICIAL) }
            installError = outcome.error
            busyInstalling = null
        }
    }

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
            modifier = Modifier.fillMaxWidth().height(560.dp)
        ) {
            Box(Modifier.fillMaxSize()) {
                MarketplaceScreen(
                    catalogState = catalogState,
                    installState = installState,
                    installError = installError,
                    busyInstalling = busyInstalling,
                    onInstall = { installOfficial(it) },
                    onRefreshCatalog = { refreshCatalog() },
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

private const val HOST_VERSION = "0.3.4"

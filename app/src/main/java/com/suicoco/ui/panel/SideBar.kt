package com.suicoco.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suicoco.core.design.VscodeTheme
import com.suicoco.data.FileNode
import com.suicoco.viewmodel.MainViewModel

@Composable
fun SideBar(vm: MainViewModel) {
    val view by vm.activityView.collectAsState()
    Column(
        modifier = Modifier.width(VscodeTheme.SIDE_BAR_W.dp).fillMaxHeight()
            .background(VscodeTheme.SideBarBg)
    ) {
        when (view) {
            MainViewModel.ActivityView.EXPLORER -> ExplorerView(vm)
            MainViewModel.ActivityView.SEARCH -> PlaceholderView("搜索", Icons.Default.Search)
            MainViewModel.ActivityView.SCM -> PlaceholderView("源代码管理", Icons.Default.Source)
            MainViewModel.ActivityView.DEBUG -> PlaceholderView("运行和调试", Icons.Default.PlayArrow)
            MainViewModel.ActivityView.EXTENSIONS -> ExtensionsView(vm)
            MainViewModel.ActivityView.SETTINGS -> PlaceholderView("设置", Icons.Default.Settings)
        }
    }
}

@Composable
private fun ViewHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().height(35.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title.uppercase(), color = VscodeTheme.FgMuted, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ExplorerView(vm: MainViewModel) {
    val tree by vm.fileTree.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        ViewHeader("资源管理器")
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 8.dp)
                .background(VscodeTheme.SideBarBg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.KeyboardArrowDown, "折叠", tint = VscodeTheme.FgMuted,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("SUICOCO-DEMO", color = VscodeTheme.Fg, fontSize = 11.sp,
                fontWeight = FontWeight.Bold)
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            tree.firstOrNull()?.children?.let { children ->
                items(children) { node -> FileRow(node, vm, indent = 1) }
            }
        }
    }
}

@Composable
private fun FileRow(node: FileNode, vm: MainViewModel, indent: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().height(22.dp)
            .padding(start = (8 + indent * 12).dp, end = 8.dp)
            .clickable { if (!node.isDir) vm.openFile(node.path) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (icon, tint) = fileIcon(node)
        Icon(icon, node.name, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(node.name, color = VscodeTheme.Fg, fontSize = 13.sp)
    }
}

private fun fileIcon(node: FileNode): Pair<androidx.compose.ui.graphics.vector.ImageVector, androidx.compose.ui.graphics.Color> {
    if (node.isDir) return Icons.Default.Folder to VscodeTheme.Yellow
    return when (node.name.substringAfterLast('.')) {
        "cpp", "c", "h", "hpp" -> Icons.Default.Code to VscodeTheme.Blue
        "py" -> Icons.Default.DataObject to VscodeTheme.Yellow
        "java" -> Icons.Default.Coffee to VscodeTheme.Orange
        "md" -> Icons.Default.Description to VscodeTheme.Blue
        else -> Icons.Default.InsertDriveFile to VscodeTheme.FgMuted
    }
}

@Composable
private fun ExtensionsView(vm: MainViewModel) {
    val plugins by vm.plugins.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        ViewHeader("扩展")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                .height(28.dp).background(VscodeTheme.InputBg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, "搜索", tint = VscodeTheme.FgMuted,
                modifier = Modifier.size(14.dp).padding(start = 6.dp))
            Text(" 搜索扩展", color = VscodeTheme.FgDim, fontSize = 12.sp)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                "官方插件下载 / 第三方 .suicj 导入 →",
                color = VscodeTheme.Link, fontSize = 12.sp,
                modifier = Modifier.clickable { vm.openQuick(MainViewModel.QuickMode.COMMAND) }
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(plugins) { p ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Extension, "插件", tint = VscodeTheme.Purple,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.manifest.name, color = VscodeTheme.Fg, fontSize = 12.sp)
                        Text("v${p.manifest.version} · ${p.source.name}",
                            color = VscodeTheme.FgMuted, fontSize = 10.sp)
                    }
                }
            }
            if (plugins.isEmpty()) {
                item {
                    Text("尚未安装插件。点击上方入口安装语言插件。",
                        color = VscodeTheme.FgDim, fontSize = 11.sp,
                        modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun PlaceholderView(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ViewHeader(title)
        Spacer(Modifier.height(48.dp))
        Icon(icon, title, tint = VscodeTheme.FgDim, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = VscodeTheme.FgMuted, fontSize = 14.sp)
    }
}

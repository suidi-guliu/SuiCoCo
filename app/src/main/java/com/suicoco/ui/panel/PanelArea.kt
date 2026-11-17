package com.suicoco.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suicoco.core.design.VscodeTheme
import com.suicoco.viewmodel.MainViewModel

@Composable
fun PanelArea(vm: MainViewModel) {
    val panelTab by vm.panelTab.collectAsState()
    val tabs = listOf("问题", "输出", "调试控制台", "终端")

    Column(
        modifier = Modifier.fillMaxWidth().height(VscodeTheme.PANEL_DEFAULT_H.dp)
            .background(VscodeTheme.PanelBg)
    ) {
        // 面板标签栏
        Row(
            modifier = Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { t ->
                Text(
                    t.uppercase(),
                    color = if (t == panelTab) VscodeTheme.Fg else VscodeTheme.FgMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { vm.setPanelTab(t) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text("✕", color = VscodeTheme.FgMuted, fontSize = 13.sp,
                modifier = Modifier.clickable { vm.togglePanel(false) }.padding(8.dp))
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (panelTab) {
                "终端" -> TerminalView(vm)
                else -> Text("$panelTab — 暂无内容", color = VscodeTheme.FgDim,
                    fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun TerminalView(vm: MainViewModel) {
    val lines by vm.terminalLines.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
        LazyColumn(modifier = Modifier.fillMaxSize().weight(1f)) {
            items(lines) { line ->
                Text(
                    line.text,
                    color = when (line.kind) {
                        com.suicoco.data.TerminalLine.Kind.COMMAND -> VscodeTheme.Fg
                        com.suicoco.data.TerminalLine.Kind.ERR -> VscodeTheme.Red
                        com.suicoco.data.TerminalLine.Kind.SUCCESS -> VscodeTheme.Green
                        com.suicoco.data.TerminalLine.Kind.INFO -> VscodeTheme.Blue
                        else -> VscodeTheme.Fg
                    },
                    fontSize = VscodeTheme.FONT_SIZE.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        // 命令行输入
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("$ ", color = VscodeTheme.Green, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            androidx.compose.foundation.text.BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle2(),
                singleLine = true,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (input.isNotBlank()) { vm.execTerminalCommand(input); input = "" } },
                    onSend = { if (input.isNotBlank()) { vm.execTerminalCommand(input); input = "" } }
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
                )
            )
        }
    }
}

@Composable
private fun TextStyle2() = androidx.compose.ui.text.TextStyle(
    color = VscodeTheme.Fg, fontSize = VscodeTheme.FONT_SIZE.sp,
    fontFamily = FontFamily.Monospace
)

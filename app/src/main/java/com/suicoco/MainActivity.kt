package com.suicoco

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import com.suicoco.core.design.VscodeTheme
import com.suicoco.ui.shell.SuicocoShell
import com.suicoco.viewmodel.MainViewModel

/** suicoco 安卓平板 IDE 入口 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = VscodeTheme.colors) {
                val vm = remember { MainViewModel() }
                SuicocoShell(vm)
            }
        }
    }
}

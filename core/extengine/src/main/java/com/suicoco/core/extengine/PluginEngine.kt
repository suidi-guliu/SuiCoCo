package com.suicoco.core.extengine

import android.content.Context
import com.suicoco.core.extapi.WindowApi
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File

/**
 * .suicj 插件引擎 — Rhino 执行 main.js，构造完整 ctx 宿主对象。
 * 第三方插件只能使用 ctx 暴露的能力。
 */
class PluginEngine(private val context: Context) {

    private val workDir: File by lazy { File(context.filesDir, "plugins") }
    private val installed = mutableMapOf<String, LoadedPlugin>()

    data class LoadedPlugin(
        val unpacked: UnpackedPlugin,
        var activated: Boolean = false,
        val source: String = "official"
    )

    /** 引擎中注册的语言/任务/命令（供 app 查询） */
    val registeredLanguages = mutableListOf<Map<String, Any?>>()
    val registeredTasks = mutableListOf<Map<String, Any?>>()
    val registeredCommands = mutableListOf<Map<String, Any?>>()
    var onStatusBarMessage: (String) -> Unit = {}
    var onSaveDiagnostics: (List<Map<String, Any?>>) -> Unit = {}

    init { workDir.mkdirs() }

    fun install(file: File, source: String = "official"): LoadedPlugin {
        val unpacked = SuicjPackage.unpack(file, workDir)
        if (installed.containsKey(unpacked.manifest.id)) throw SuicjException("插件已安装：${unpacked.manifest.id}")
        val plugin = LoadedPlugin(unpacked, source = source)
        installed[unpacked.manifest.id] = plugin
        return plugin
    }

    /** 仅注册 declared contributes（不执行 JS，供无 Rhino 环境使用） */
    fun registerContributes(pluginId: String) {
        val p = installed[pluginId] ?: return
        p.unpacked.manifest.contributes?.languages?.forEach {
            registeredLanguages += mapOf("id" to it.id, "extensions" to it.extensions, "aliases" to it.aliases)
        }
        p.unpacked.manifest.contributes?.commands?.forEach {
            registeredCommands += mapOf("command" to it.command, "title" to it.title)
        }
    }

    /** 激活：注入 ctx 并执行 main.js 的 activate(ctx) */
    fun activate(pluginId: String, windowApi: WindowApi) {
        val plugin = installed[pluginId] ?: throw SuicjException("插件未安装：$pluginId")
        if (plugin.activated) return
        registerContributes(pluginId)

        val code = plugin.unpacked.files[plugin.unpacked.manifest.main]
            ?: throw SuicjException("main 文件缺失：${plugin.unpacked.manifest.main}")
        val version = plugin.unpacked.manifest.version

        val rc = RhinoContext.enter()
        try {
            rc.languageVersion = RhinoContext.VERSION_ES6
            val scope = rc.initStandardObjects()
            val ctx = NativeObject()

            // ctx.version
            ctx.put("version", ctx, version)

            // ctx.languages
            val languages = NativeObject()
            languages.put("registerLanguage", languages, JsCallback { args ->
                val obj = args.getOrNull(0) as? NativeObject
                if (obj != null) {
                    val def = obj.toMap()
                    registeredLanguages += def
                }
                null
            })
            languages.put("registerCompletionItemProvider", languages, JsCallback { _ -> null })
            ctx.put("languages", ctx, languages)

            // ctx.tasks
            val tasks = NativeObject()
            tasks.put("registerTasks", tasks, JsCallback { args ->
                val arr = args.getOrNull(0) as? NativeArray
                if (arr != null) {
                    for (i in 0 until arr.length) {
                        (arr.get(i) as? NativeObject)?.let { registeredTasks += it.toMap() }
                    }
                }
                null
            })
            ctx.put("tasks", ctx, tasks)

            // ctx.commands
            val commands = NativeObject()
            commands.put("registerCommand", commands, JsCallback { args ->
                val id = args.getOrNull(0)?.toString() ?: ""
                val title = args.getOrNull(1)?.toString() ?: id
                registeredCommands += mapOf("command" to id, "title" to title)
                null
            })
            ctx.put("commands", ctx, commands)

            // ctx.window
            val window = NativeObject()
            val showMsg = JsCallback { args -> windowApi.showMessage(args.getOrNull(0)?.toString() ?: ""); null }
            window.put("showMessage", window, showMsg)
            window.put("showInformationMessage", window, showMsg)
            window.put("showErrorMessage", window, JsCallback { args -> windowApi.showErrorMessage(args.getOrNull(0)?.toString() ?: ""); null })
            window.put("setStatusBarMessage", window, JsCallback { args -> onStatusBarMessage(args.getOrNull(0)?.toString() ?: ""); null })
            ctx.put("window", ctx, window)

            // ctx.workspace
            val workspace = NativeObject()
            workspace.put("onDidSave", workspace, JsCallback { _ -> null })
            ctx.put("workspace", ctx, workspace)

            // ctx.diagnostics
            val diagnostics = NativeObject()
            diagnostics.put("setProblems", diagnostics, JsCallback { args ->
                val arr = args.getOrNull(0) as? NativeArray
                if (arr != null) {
                    val probs = (0 until arr.length).mapNotNull { (arr.get(it) as? NativeObject)?.toMap() }
                    onSaveDiagnostics(probs)
                }
                null
            })
            ctx.put("diagnostics", ctx, diagnostics)

            ScriptableObject.putProperty(scope, "__suicoco_ctx", ctx)
            val wrapped = """
                (function(ctx){
                  var module = { exports: {} };
                  var exports = module.exports;
                  $code
                  if (module.exports && typeof module.exports.activate === 'function') {
                    module.exports.activate(ctx);
                  } else if (typeof activate === 'function') {
                    activate(ctx);
                  }
                })(__suicoco_ctx);
            """.trimIndent()
            rc.evaluateString(scope, wrapped, plugin.unpacked.manifest.main, 1, null)
            plugin.activated = true
        } finally {
            RhinoContext.exit()
        }
    }

    /** NativeObject → Map<String, Any?>（递归展开 NativeArray） */
    private fun NativeObject.toMap(): Map<String, Any?> =
        keys.filterIsInstance<String>().associateWith { k -> convert(get(k)) }

    private fun convert(v: Any?): Any? = when (v) {
        is NativeObject -> v.toMap()
        is NativeArray -> (0 until v.length).map { convert(v.get(it)) }
        is Scriptable -> v.toString()
        else -> v
    }

    private class JsCallback(private val fn: (Array<Any?>) -> Any?) : BaseFunction() {
        override fun call(cx: RhinoContext?, scope: Scriptable?, thisObj: Scriptable?, args: Array<Any?>): Any? = fn(args)
    }

    fun uninstall(pluginId: String) {
        val p = installed.remove(pluginId) ?: return
        p.unpacked.dir.deleteRecursively()
    }

    fun getInstalled(): List<LoadedPlugin> = installed.values.toList()
}

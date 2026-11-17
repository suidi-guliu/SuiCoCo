/* suicj.example.hello — 最小示例插件
 * 用途：第三方开发者参考 .suicj 插件的最小结构。
 * 打包：将本目录打包为 zip 后重命名 .suicj（或用 scripts/build-plugin.sh）。
 */
"use strict";

function activate(ctx) {
  ctx.window.showInformationMessage("Hello suicoco！我是示例插件「" + ctx.id + "」v" + ctx.version);

  ctx.commands.registerCommand("suicj.example.hello.greet", "Hello: 打个招呼", () => {
    ctx.window.showInformationMessage("你好，来自示例插件 👋（软件本体 v" + ctx.app.version + "）");
  });

  ctx.commands.registerCommand("suicj.example.hello.env", "Hello: 查询环境安装状态", () => {
    const env = ["ubuntu", "cpp", "python", "java"].map(e => e + "=" + (ctx.env.isInstalled(e) ? "已安装" : "未安装")).join(", ");
    ctx.window.showInformationMessage("环境状态: " + env);
  });

  ctx.window.setStatusBarMessage("Hello 插件已激活");
}

module.exports = { activate };

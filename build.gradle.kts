// 根构建文件 — suicoco 安卓工程
// 借鉴 JCode 的多模块约定：
//   :core:*   纯逻辑，永不依赖 :feature:*
//   :feature:* 只依赖 :core:*
//   :native:* 叶子模块，产出 .so
//   :app      聚合一切
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

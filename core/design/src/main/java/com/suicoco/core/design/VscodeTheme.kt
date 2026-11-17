package com.suicoco.core.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** VSCode Dark Modern 配色与尺寸 — UI 复刻的单一真源 */
object VscodeTheme {
    val ActivityBarBg = Color(0xFF181818)
    val SideBarBg = Color(0xFF181818)
    val EditorBg = Color(0xFF1F1F1F)
    val PanelBg = Color(0xFF181818)
    val StatusBarBg = Color(0xFF007ACC)
    val TabActiveBg = Color(0xFF1F1F1F)
    val TabInactiveBg = Color(0xFF2D2D2D)
    val TitleBarBg = Color(0xFF181818)
    val WidgetBg = Color(0xFF313131)
    val InputBg = Color(0xFF313131)
    val HoverBg = Color(0xFF2A2D2E)
    val SelectionBg = Color(0xFF264F78)

    val Fg = Color(0xFFCCCCCC)
    val FgMuted = Color(0xFF9D9D9D)
    val FgDim = Color(0xFF6E7681)
    val Link = Color(0xFF4DA1FF)
    val StatusBarFg = Color(0xFFFFFFFF)
    val ButtonFg = Color(0xFFFFFFFF)

    val Border = Color(0xFF2B2B2B)
    val BorderStrong = Color(0xFF3C3C3C)
    val FocusBorder = Color(0xFF007FD4)

    val Accent = Color(0xFF007ACC)
    val Green = Color(0xFF4EC9B0)
    val Red = Color(0xFFF14C4C)
    val Yellow = Color(0xFFDCDCAA)
    val Orange = Color(0xFFCE9178)
    val Purple = Color(0xFFC586C0)
    val Blue = Color(0xFF569CD6)

    fun tokenColor(token: String): Color = when (token) {
        "keyword", "keyword.control", "storage" -> Purple
        "string", "string.quoted" -> Orange
        "comment", "comment.line", "comment.block" -> Color(0xFF6A9955)
        "constant.numeric", "number" -> Color(0xFFB5CEA8)
        "entity.name.type", "support.type" -> Green
        "entity.name.function", "support.function" -> Yellow
        "variable", "variable.other" -> Color(0xFF9CDCFE)
        else -> Fg
    }

    const val ACTIVITY_BAR_W = 48
    const val SIDE_BAR_W = 240
    const val TITLE_BAR_H = 35
    const val TAB_H = 35
    const val STATUS_BAR_H = 22
    const val BREADCRUMB_H = 22
    const val PANEL_DEFAULT_H = 220
    const val FONT_SIZE = 13
    const val LINE_HEIGHT = 20
    const val ICON_SIZE = 24

    val colors: ColorScheme = darkColorScheme(
        primary = Accent, onPrimary = ButtonFg, secondary = Purple,
        background = EditorBg, onBackground = Fg, surface = SideBarBg,
        onSurface = Fg, surfaceVariant = WidgetBg, onSurfaceVariant = FgMuted,
        outline = Border, error = Red
    )
}

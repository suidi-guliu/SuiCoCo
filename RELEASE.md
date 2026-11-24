# suicoco 版本记录

> **版本标记惯例（2026-11-22 明确，止住每轮重复提示）**：仅**对外发布里程碑**建 Git tag / GitHub Release（当前最新 v0.3.4，附 5 个 `.suicj` + APK 产物）；功能迭代 v0.2.5～v0.3.x 的纯源码/文档小版本只记入本文件、随 `main` 提交推进，不逐个建标。此为既有惯例的书面确认，非遗漏；如需改为逐版建标再单独立项。

## v0.3.20 — 括号匹配高亮与跳转：core:editor 配对内核 + 编辑器光标接线

> 响应 2026-11-24 12:10 巡检日报 P3「编辑器增强余项」。在线核对：main HEAD `50101b35`（源码 `a818b3be`，RELEASE v0.3.19），`Build suicoco`（run 45325016178）与 `build-plugins`（run 45325016222）双 job success，tags/Release 仍为里程碑 v0.3.4。日报 P1「查找替换」已由 v0.3.19 落地，按幂等原则跳过；P2「真实 arm64 `libproot.so` 随包」需 NDK27 交叉编译 + arm64 真机，沙箱无 SDK/NDK/真机，维持阻塞；P4「阶段4/5/6 todo 无 assignee」成员仅所有者一人，非仓库文件，维持现状。P3 中 BasicTextField 原生多段选区与 LSP/DAP 工程量大、非单轮可闭环，故从同一「编辑器增强余项」中挑出可离线/CI 安全、未重复的 VSCode 基础能力——括号匹配，沿用纯 Kotlin 内核 + 独立单测范式。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **新增 `core/editor/src/main/java/com/suicoco/core/editor/BracketMatchModel.kt`（纯 Kotlin 标准库，可独立编译单测）**：把光标旁括号的配对计算落地为独立内核。① 扫描文本识别代码区 `()[]{}`，跳过双引号字符串、字符字面量、`//` 行注释与 `/* */` 块注释，未闭合字符串在换行处容错恢复；② 数组栈配对，失配不强行匹配，结果确定可预测；③ `matchAt(text, offset)` 先看左侧 `offset-1` 再看右侧 `offset`，返回一对单字符半开区间，直接复用 `SelectionHighlightModel` 拆矩形；④ `jumpToMatch` 在左右括号间跳转，`unmatchedOf`/`isBalanced` 可供状态提示；⑤ 括号数上限 4096、偏移夹紧、空输入与非法上限失败安全幂等，不处理 `<>`（泛型/比较歧义），零 Android/Compose/三方依赖，core 不依赖 feature 的约束不变。
- **接线 `app/.../ui/editor/EditorArea.kt`**：`BasicTextField` 改用 `TextFieldValue` 重载以读取 `selection.end` 作为光标位置；外部内容变化（撤销/重做/查找替换/多光标回写）经 `LaunchedEffect` 同步文本并夹紧选区，编辑语义不变。光标左右相邻括号由内核求出配对后，在文本域叠加亮黄单字符高亮块（与查找高亮同口径，`Box+offset+background` 稳定 API，不拦截输入）；Ctrl+Shift+\ 跳转到配对括号。补全、撤销/重做、查找/替换、caret/多段选区、多光标、minimap 既有行为不变。
- **文档同步**：`docs/ARCHITECTURE.md` §7 追加 v0.3.20 括号匹配条目，标注仍为词法级视觉指示，LSP 语义级配对属后续。
- **验证**：`BracketMatchModel` 经 Kotlin 2.2.20（`kotlin-compiler-embeddable`）真实编译并运行 **35 项单测全通过**（简单/嵌套/混合括号、失配、`matchesOf` 顺序、字符串/字符/行注释/块注释/转义/未闭合字符串/CRLF 容错、光标左右边界优先级、`jumpToMatch` 四方向与幂等、偏移夹紧、中文 UTF-16、tab/换行、`<>` 忽略、容量上限、2000 层深嵌套与幂等）；`EditorArea.kt` 按词法排除注释/字符串后的括号平衡（花括号 193/193、圆括号 582/582）与新增符号引用静态核对通过；`bash scripts/verify-plugin.sh` 4 个官方 `.suicj` 全通过。本地无 JDK21/Android SDK，完整 Compose/APK 编译以在线 GitHub Actions 为准。
- **未闭环（仍阻塞/待后续）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机）、真机端到端验收、BasicTextField 原生多段文本选区、LSP（语义级配对/重命名/跳转）与 DAP、阶段4/5/6 todo 无 assignee（非仓库文件，成员仅所有者一人）。

## v0.3.19 — 文件内查找替换（Find & Replace）：core:editor 替换内核 + 编辑器替换行接线

> 响应 2026-11-24 06:01 巡检日报 P3「编辑器增强余项」中**可离线/CI 安全、未重复的查找替换闭环**——v0.3.18 已落地 P1 的「找得到、跳得动」，但查找栏只能跳转不能改写；本轮补齐 VSCode 最核心的后续动作「替换当前 / 全部替换」，延续纯 Kotlin 内核 + 独立单测范式，不触碰 BasicTextField 原生多选区与 LSP/DAP 等超出单轮范围的余项。在线核对：HEAD `81efff1`（源码 `4d37066`，v0.3.18），RELEASE 记至 v0.3.18，最终双 job success；`core/editor` 仅有 FindModel 无 ReplaceModel，查找栏无替换控件，确未落地。日报 P2（真实 arm64 `libproot.so` 随包，需 NDK27 交叉编译 + arm64 真机）与 P4（阶段4/5/6 todo 无 assignee，成员仅所有者一人）经核对维持阻塞/现状，不重复；P3 中 LSP/DAP 与 BasicTextField 原生多段选区工程量大，非单轮可闭环。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **新增 `core/editor/src/main/java/com/suicoco/core/editor/ReplaceModel.kt`（纯 Kotlin 标准库，可独立编译单测）**：把查找结果的改写落地为独立内核。① `replaceAt(text, start, end, replacement)` 替换单个半开区间（UTF-16 code unit 口径），start/end 夹紧、start > end 自动交换、空替换串等价于删除；② `replaceAll(text, matches, replacement)` 对 `FindModel.Match` 列表一次遍历批量替换，先按起点排序、夹紧、丢弃零宽与相互重叠项（保留先出现者），返回 `Result(text, replaced)`，从根本上避免逐项修改导致的偏移漂移；③ 替换串按**字面量**处理，不做正则/`$` 分组/转义解释，不引入命令或 shell 面；④ `caretAfterReplace` 给出替换后的光标位置；⑤ 全部公开方法失败安全（空文本/空列表/非法区间幂等返回原文本与 0）、纯函数无隐藏状态，零 Android/Compose/三方依赖，core 不依赖 feature 的架构约束不变。
- **接线 `app/.../ui/editor/EditorArea.kt`**：查找栏新增 `Ctrl+H` 展开/聚焦替换行——查找输入框按 Ctrl+H 也可展开；替换输入 Enter 或「替换」按钮替换当前匹配，「全部」按钮一次性替换全部匹配，显示「已替换 N 处 / 无可替换匹配」，Esc 关闭整栏；`Aa`/`ab|` 与 ↑↓/F3 选项继续参与匹配计算。内核结果经 `MainViewModel.updateContent` 回写，撤销树快照、补全刷新、minimap 重算与查找高亮自动同步，「全部替换」可被一次撤销整体回退；仅用 `BasicTextField`/`Row`/`ToolbarButton`/稳定 Key 常量（`Key.H`），无实验性 API。补全、撤销/重做、多光标、caret/选区叠加、minimap 原行为不变。
- **文档同步**：`docs/ARCHITECTURE.md` §7 追加 v0.3.19 查找替换条目，标注 LSP 级语义重命名仍属后续。
- **验证**：`ReplaceModel` 连同 `FindModel` 经 Kotlin 2.2.20（`kotlin-compiler-embeddable`）真实编译并运行 **39 项单测全通过**（单区间替换/前后缀/整体/删除/中文与字面量 `$`、空文本/负与超界/反向区间等失败安全；全部替换的单/多/相邻/扩展/空替换串/换行与 CRLF/中文、乱序、重叠去重、越界夹紧、反向匹配、空列表与空文本幂等、与 FindModel 忽略大小写端到端串联、替换后光标位置）；`bash scripts/verify-plugin.sh` 4 个官方 `.suicj` 全通过；`EditorArea.kt` 按词法排除注释/字符串后的括号平衡（花括号 185/185、圆括号 550/550、方括号 9/9）与新增符号引用/调用点静态核对通过。本地无 JDK21/Android SDK，完整 Compose/APK 编译以在线 GitHub Actions 为准。
- **未闭环（仍阻塞/待后续）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机）、真机端到端验收、BasicTextField 原生多段文本选区、LSP（语义级重命名/跳转）与 DAP、阶段4/5/6 todo 无 assignee（非仓库文件，成员仅所有者一人）。

## v0.3.18 — 文件内查找（Find-in-file）：core:editor 匹配内核 + 编辑器查找栏接线

> 响应 2026-11-24 06:01 巡检日报 P1（编辑器文件内查找内核 + UI 接线）。在线核对：HEAD `86a5366`（源码 `b260a88`，v0.3.17），RELEASE 记至 v0.3.17，双 job success；`core/editor` 无 FindModel、RELEASE 查找关键词 0 命中，P1 确未落地。日报 P2（真实 arm64 `libproot.so` 随包，需 NDK27 交叉编译 + arm64 真机）与 P4（阶段4/5/6 todo 无 assignee，成员仅所有者一人）经核对维持阻塞/现状，无 SDK/NDK/真机不可完成、非仓库文件，不重复；P3（BasicTextField 原生多段选区、LSP/DAP）工程量大，v0.3.17 已落地选区高亮视觉，LSP/DAP 非单轮可闭环。故本轮落地 P1，沿用纯 Kotlin 内核 + 独立单测范式，保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **新增 `core/editor/src/main/java/com/suicoco/core/editor/FindModel.kt`（纯 Kotlin 标准库，可独立编译单测）**：把文件内查找落地为真实内核。① `findAll(text, query, options)` 返回全部互不重叠、按 start 升序的匹配区间 `[start,end)`（UTF-16 code unit 口径），支持忽略大小写（`Options.ignoreCase`）与全词匹配（`Options.wholeWord`，词字符口径与 `SelectionHighlightModel` token 一致：字母/数字/`_`），命中上限默认 2048（非法上限回退默认）；② `nextIndex` / `previousIndex` 在匹配列表上循环（空列表返回 -1、-1 起点语义明确、超界夹紧），`indexAtOrAfter` / `indexAtOrBefore` 供按光标位置定位查找起点，`matchAt` 安全取当前项；③ `asSelectionPairs` 把匹配映射为 `(start,end)` 对，直接复用 `SelectionHighlightModel` 的高亮矩形内核，不重复实现行列/像素换算；④ 全部公开方法失败安全（空查询/空文本幂等、越界索引安全回退）、纯函数无隐藏状态，零 Android/Compose/三方依赖，core 不依赖 feature 的约束不变。
- **接线 `app/.../ui/editor/EditorArea.kt`**：新增 `FindBar` 查找栏——Ctrl+F 打开并聚焦输入框，输入即时重算并高亮全部匹配，↑/↓ 或 F3/Shift+F3 或回车循环跳转，『Aa』切换忽略大小写、『ab|』切换全词匹配并即时重算，计数字段显示「当前/总数」，Esc 关闭；文本域 `onKeyEvent` 用稳定 Key 常量接入 Ctrl+F/F3/Shift+F3/Esc，与既有 Ctrl+Z/Ctrl+Shift+Z/Ctrl+Y 撤销重做并存不冲突。查找状态提升到 `EditorArea` 顶层供查找栏与代码区共享，切文件自动重算。高亮层把所有匹配画为淡黄底、当前项叠加橙底（复用 `SelectionHighlightModel.highlightOf` 与文本同口径的逐行矩形，仅视觉指示不拦截输入）；跳转经 `CursorLayoutModel.lineColumnOf` 求匹配起点行号后 `animateScrollTo` 平滑定位；仅用 `BasicTextField`/`Box`/`offset`/`background`/`FocusRequester`/`onKeyEvent` 等稳定 API，无实验性接口。补全、撤销/重做、多光标/caret/选区、minimap 既有行为不变。
- **文档同步**：`docs/ARCHITECTURE.md` §7 更新为 v0.3.18 文件内查找已落地（纯内核 + UI 叠加 + 查找栏接线），标注 LSP 级语义查找仍属后续。
- **验证**：`FindModel` 经 kotlinc 2.2.20 独立编译（连带 `SelectionHighlightModel`/`CursorLayoutModel` 一并编译确保符号一致）+ **41 项单测全通过**（子串/多字符/重叠不重叠/中文 code unit；大小写敏感默认与忽略大小写；全词边界与下划线、全词+忽略大小写组合；匹配上限与非法上限回退；next/prev 从 -1、推进、循环回绕、超界夹紧、空列表、单匹配；atOrAfter/atOrBefore 起点与回绕；matchAt 越界 null；asSelectionPairs 与复用高亮矩形；findAll 幂等）。`EditorArea.kt` 括号平衡（花括号 163/163、圆括号 494/494、方括号 9/9）与符号引用静态校验通过（修正一处 `highlightRects`→`highlightOf` 的 Pair/Selection 类型匹配）；Compose BOM 2025.08.00 支持 Key.F3/Escape/Enter。完整 Compose/APK 编译以 GitHub Actions 为准。
- **未闭环（仍阻塞/待后续）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机）、真机端到端验收、BasicTextField 原生多段文本选区（本轮查找为纯内核高亮叠加视觉）、LSP（语义级查找/跳转）与 DAP、阶段4/5/6 todo 无 assignee（非仓库文件，成员仅所有者一人）。

## v0.3.17 — 逐光标多段选区高亮：core:editor 选区拆分内核 + 编辑器选区块接线

> 响应 2026-11-23 18:00 巡检日报 P3（编辑器增强余项「BasicTextField 原生逐光标多段选区高亮」）。在线核对：HEAD `1dd4921`（源码 HEAD `28fa2f9`），RELEASE 记至 v0.3.16，双 job success。日报 P1（minimap 拖拽连续滑动）已由 v0.3.16 完成，跳过；P2（真实 arm64 `libproot.so` 随包，需 NDK 交叉编译 + arm64 真机）与 P4（阶段4/5/6 todo 无 assignee，成员仅所有者一人）经核对维持阻塞/现状，非仓库文件或无 SDK/NDK/真机不可完成，不重复；P3 中 LSP/DAP 工程量巨大，非单轮可闭环。故本轮落地 P3 中可离线、CI 安全、未重复的「多段选区高亮」，沿用纯 Kotlin 内核 + 独立单测范式，保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **新增 `core/editor/src/main/java/com/suicoco/core/editor/SelectionHighlightModel.kt`（纯 Kotlin 标准库，可独立编译单测）**：把一组选区换算为逐行高亮矩形。① `normalize(text, pairs)` 把 (anchor,active) 偏移对交换/夹紧到 `[0,len]`、丢零宽、升序、合并相邻与重叠区间，避免重复绘制；② `highlightRects` / `highlightOf` 把每个 `[start,end)` 选区拆成逐行 `HighlightRect`（单行一段；跨行=首行 start→行尾 + 中间整行 + 末行行首→end），列按 UTF-16 code unit、tab 加权（默认 4）、CRLF 视为单换行且 offset 停 `\r`/`\n` 之间归本行行尾（与 `CursorLayoutModel` 同口径），像素 `x=startCol*cw`/`width=(endCol-startCol)*cw`/`y=line*lh`，矩形上限 256；③ `tokenRangeOf(text, offset)` 取光标处标识符 token、`occurrencesOf(text, token)` 返回全词匹配的全部出现（供 VSCode「选中所有匹配项」）；④ 全部公开方法失败安全（NaN/负像素回退 0、非法 tab 回退、空输入幂等）、纯函数无隐藏状态，零 Android/Compose/三方依赖，core 不依赖 feature 的约束不变。
- **接线 `app/.../ui/editor/EditorArea.kt`**：`CodeEditor` 在多光标模式且有光标时，取主光标下 token，经 `SelectionHighlightModel` 求全文出现→逐行矩形，在 `decorationBox` 文本层叠加多条半透明紫色选区块（VSCode「选中所有匹配项」视觉）；仅用 `Box`/`offset`/`width`/`background` 稳定 API、无 clickable/pointerInput 不拦截输入，与 caret 竖线、批量编辑、撤销/重做、补全、minimap 既有行为互不干扰；仅按 `cursorTick` 重组。
- **文档同步**：`docs/ARCHITECTURE.md` §7 更新为 caret 竖线 + 多段选区高亮均已落地（并注明仍非 BasicTextField 原生多选区、真正原生选区编辑属后续）。
- **验证**：`SelectionHighlightModel` 经 kotlinc 2.2.20 独立编译 + **96 项单测全通过**（normalize 交换/夹紧/零宽/排序/合并重叠与相邻；与 `CursorLayoutModel.lineColumnOf` 在 6 组文本×全偏移上逐点一致；单行/跨行/全文档/CRLF 跨行/tab 加权/像素换算；NaN 与负尺寸回退、非法 tab 回退、夹紧幂等、矩形上限、高亮幂等；token 边界与全词匹配、端到端同名词三段高亮）。`EditorArea.kt` 括号平衡与符号引用静态校验通过。完整 Compose/APK 编译以 GitHub Actions 为准。
- **未闭环（仍阻塞/待后续）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机）、真机端到端验收、BasicTextField 原生多段文本选区（本轮为纯内核高亮叠加的视觉指示）、LSP/DAP、阶段4/5/6 todo 无 assignee（非仓库文件，成员仅所有者一人）。

## v0.3.16 — minimap 拖拽连续滑动：core:editor 浮点连续锚点内核 + 编辑器拖拽接线

> 响应 2026-11-23 12:00 巡检日报 P1（minimap 拖拽连续滑动，v0.3.14 RELEASE 明确留待后续、唯一可离线/CI 安全且未重复项）。在线核对：HEAD `c44f598`，RELEASE 记至 v0.3.15，源码 HEAD `0036b4d`（v0.3.15）双 job success。日报 P2（真实 arm64 `libproot.so` 随包，需 NDK27 交叉编译 + arm64 真机）与 P4（阶段4/5/6 todo 无 assignee，成员仅所有者一人）经核对维持阻塞/现状，非仓库文件或无 SDK/NDK/真机不可完成，不重复；P3（BasicTextField 原生逐光标多段选区、LSP/DAP）工程量大，v0.3.15 已落地 caret 竖线定位，剩余部分逐项排期，本轮不重复。故本轮落地 P1。沿用 `MinimapModel` 的纯 Kotlin 内核 + 独立单测范式，保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **增强 `core/editor/src/main/java/com/suicoco/core/editor/MinimapModel.kt`（纯 Kotlin 标准库）**：新增 `scrollAnchorFromDrag(layout, yInLines, visibleLines)`——把缩略列上的**连续浮点 y**（以解析行为单位）换算为编辑器应滚动到的首个可见行，使手指所在位置尽量落在视口中央。与点击跳转 `lineAt + scrollAnchorLine` 的整数行不同，这里保留小数、避免拖拽时跳格；截断布局按比例还原回源行号，锚点夹紧到 `[0, sourceLineCount - visibleLines]`；失败安全（空布局/NaN/负数 y/非法可见数均安全回退）、纯函数幂等。既有 `lineAt`/`scrollAnchorLine`/`viewportRect`/`build` 行为不变。
- **接线 `app/.../ui/editor/EditorArea.kt`**：`MinimapRail` 的 Box 挂 `pointerInput` + `detectVerticalDragGestures`（**Compose Foundation 稳定 API，非实验性接口**），`onDragStart`/`onVerticalDrag` 均用指针绝对 y（扣除顶部 padding、按单行像素高换算为连续解析行）调用 `scrollAnchorFromDrag → scrollTo` 即时跟手滚动；pointerInput 置于 `padding` 之前覆盖整条 rail、更易抓取；每行 `clickable` 点击跳转（v0.3.14）保留，父级拖拽仅消费超过 touch slop 的纵向手势，二者互不干扰；键盘输入/撤销/补全/多光标/caret 叠加既有行为不变。
- **文档同步**：`docs/ARCHITECTURE.md` §7 更新 minimap 条目为「点击跳转 + 拖拽连续滑动均已接线」，标注 `scrollAnchorFromDrag` 归 `:core:editor`。
- **验证**：`MinimapModel` 经 kotlinc 2.2.20 独立编译 + **37 项单测全通过**（新增拖拽用例：浮点连续/居中/夹紧/NaN/负数/可见数边界/截断按比例还原/单调性/幂等/整数 y 与点击锚点一致，并回归 v0.3.14 点击锚点、lineAt、viewportRect、build、CRLF）；`EditorArea.kt` 括号平衡（花括号 103/103、圆括号 325/325、方括号 8/8）与符号引用静态校验通过。完整 Compose/APK 编译以 GitHub Actions 为准。
- **未闭环（仍阻塞/待后续）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机）、真机端到端验收、BasicTextField 原生逐光标多段选区高亮、LSP/DAP、阶段4/5/6 todo 无 assignee（非仓库文件，成员仅所有者一人）。

## v0.3.15 — 逐光标 caret 定位渲染：core:editor 偏移→行列内核 + 编辑器多光标竖线接线

> 响应 2026-11-23 05:59 巡检日报 P3（编辑器增强余项「BasicTextField 逐光标选区渲染的多光标文本域，建议先做前者纯 UI 增强」）。在线核对：HEAD `7b260861`，RELEASE 记至 v0.3.14。日报 P1（minimap 点击跳转）已由 v0.3.14 完成、`lineAt/scrollAnchorLine/animateScrollTo/视口高亮`全部落地，跳过；P0（真实 arm64 `libproot.so` 随包，需 NDK27 交叉编译 + arm64 真机）与 P4（阶段4/5/6 todo 无 assignee，成员仅所有者一人）经核对维持阻塞/现状，非仓库文件或无 SDK/NDK/真机不可完成，不重复。故本轮落地 P3 中可离线、CI 安全、未重复的「逐光标定位渲染」：把此前黑盒式的光标偏移集合变成屏幕上可见的 caret 竖线。沿用 `UndoTree`/`MinimapModel`/`MultiCursorModel` 的纯 Kotlin 内核 + 独立单测范式。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **新增 `core/editor/src/main/java/com/suicoco/core/editor/CursorLayoutModel.kt`（纯 Kotlin 标准库，可独立编译单测）**：把文本偏移映射为可见的光标位置。① `lineColumnOf(text, offset, tabWidth)` 返回 0-based `(line, column)`，列按 UTF-16 code unit 计、tab 按可配置宽度（默认 4，非法值回退）加权，CRLF 视为单换行且 offset 停在 `\r`/`\n` 之间归本行行尾、孤立 `\r` 视为换行；② `carets(text, offsets, lineHeightPx, charWidthPx)` 批量把一组偏移换算为像素坐标 `CaretPoint(offset,line,column,xPx,yPx)`，偏移夹紧去重升序、容量上限默认 64（超出取最小的 cap 个）、NaN/负像素尺寸回退 0、空输入幂等；③ 全部公开方法失败安全、纯函数幂等、无隐藏状态，零 Android/Compose/三方依赖，core 不依赖 feature 的约束不变。
- **接线 `app/.../ui/editor/EditorArea.kt`**：`CodeEditor` 接收 `cursorModel`/`multiMode`/`cursorTick`，在 `decorationBox` 文本层按 `CursorLayoutModel.carets` 的结果叠加多条 2dp×行高的 caret 竖线（等宽字体列宽按字号 0.6 倍近似，与 minimap 行高估算同口径，仅作视觉指示）；仅在多光标模式且有 >1 个光标时显示，按 `cursorTick` 重组，与批量编辑/撤销树/补全/minimap 既有行为互不干扰。仅用 `Box`/`Modifier.offset`/`background` 等稳定 API，未引入实验性接口。
- **文档同步**：`docs/ARCHITECTURE.md` §7 更新为「逐光标 caret 定位渲染已落地（偏移→行列→像素内核 + 竖线叠加），但非 BasicTextField 原生多选区，真正逐光标选区仍属后续」，并补注复用 `MultiCursorModel.cursors()` 的接线说明。
- **验证**：`CursorLayoutModel` 经 kotlinc 2.2.20 独立编译 + **33 项单测全通过**（空文本/负与超长夹紧/单行与多行行列/换行符边界/tab 展开与非法回退/CRLF 中间归行尾/孤立 CR/批量去重排序夹紧/像素换算/非法尺寸回退/容量上限/幂等/中文按 code unit 计列）；单测过程中修正的均为测试期望值（文本长度与容量样本），内核逻辑一次通过。`EditorArea.kt` 括号平衡（花括号 96/96、圆括号 309/309、方括号 6/6）与符号引用静态校验通过；`bash scripts/verify-plugin.sh` 4 个官方 `.suicj` 全通过。完整 Compose/APK 编译以 GitHub Actions 为准。
- **未闭环（仍阻塞/待后续）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机）、真机端到端验收、BasicTextField 原生逐光标多段选区高亮（本轮落地的是 caret 竖线定位）、LSP/DAP、阶段4/5/6 todo 无 assignee（非仓库文件，成员仅所有者一人）。

## v0.3.14 — minimap 点击跳转：core:editor 滚动锚点内核 + 视口高亮接线

> 响应 2026-11-22 23:59 巡检日报 P1（minimap 点击跳转滚动接线）。在线核对：HEAD `367fc90c`，RELEASE 记至 v0.3.13（多光标已由上一轮落地，日报 P2 多光标已完成故跳过），双 job success。P0（真实 arm64 `libproot.so` 随包，需 NDK 交叉编译 + arm64 真机）维持阻塞；日报三条优先级中仅 P1 可离线、CI 安全地落地，故本轮把已就绪的 `MinimapModel.lineAt` 内核接到编辑器滚动。沿用 `UndoTree`/`MinimapModel`/`MultiCursorModel` 的纯 Kotlin 内核 + 独立单测范式。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **增强 `core/editor/src/main/java/com/suicoco/core/editor/MinimapModel.kt`（纯 Kotlin 标准库）**：新增 `scrollAnchorLine(layout, targetLine, visibleLines)`——把「点击映射出的源行号」换算为编辑器应滚动到的首个可见行，使目标行尽量居中（上移半个视口）并夹紧到 `[0, sourceLineCount - visibleLines]`；失败安全（空布局返回 0、负数/超界夹紧、纯函数幂等）。`lineAt`（截断按比例还原源行号）与 `viewportRect` 既有能力复用，未改动既有行为。
- **接线 `app/.../ui/editor/EditorArea.kt`**：`CodeEditor` 的文本区改由外层 `Box.verticalScroll(scrollState)` 承担滚动（`BasicTextField` 用 `fillMaxWidth` 按内容高度铺开，避免其内部滚动使 `scrollState.maxValue` 为 0），滚动状态提升到编辑器级；`MinimapRail` 每行横杠点击经 `lineAt → scrollAnchorLine → ScrollState.animateScrollTo` 平滑滚动到目标行，并用 `viewportRect` 叠加半透明视口高亮框（当前可见行区间）；可见行数由代码区实测高度/行高估算。仅用 `clickable`/`Box` 等稳定 API，不引入实验性手势接口，键盘输入/撤销重做/补全/多光标/minimap 既有行为不变。
- **文档同步**：`docs/ARCHITECTURE.md` §7 minimap 条目更新为「点击跳转 + 视口高亮已接线」，滚动锚点内核归属 `:core:editor`。
- **验证**：`MinimapModel` 经 kotlinc 2.2.20 独立编译 + **32 项单测全通过**（新增 scrollAnchorLine 居中/夹紧/边界/视口大于文档/幂等等用例，并回归 lineAt 截断缩放、viewportRect、build 容量截断与 CRLF 归一）；`EditorArea.kt` 括号平衡与符号引用静态校验通过（花括号 88/88、圆括号 284/284）。完整 Compose/APK 编译以 GitHub Actions 为准。
- **未闭环（仍阻塞/待后续）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机）、真机端到端验收、minimap 拖拽连续滑动（本轮先落地点击跳转）、BasicTextField 逐光标选区渲染的多光标文本域、LSP/DAP 维持阻塞/待后续。

## v0.3.13 — 编辑器多光标：core:editor 批量编辑内核 + 列编辑操作条接线

> 响应 2026-11-22 17:59 巡检日报 P1（编辑器增强余项「再排多光标」）+ P2（RELEASE.md 构建 run 引用口径）。在线核对：HEAD `276bd02b`，RELEASE 记至 v0.3.12（minimap 已由上一轮落地），双 job success。P0（真实 arm64 `libproot.so` 随包，需 NDK 交叉编译 + arm64 真机）与 P3（阶段4/5/6 todo 因成员仅所有者一人无法指派）经核对维持阻塞/现状，不重复。故本轮落地日报 P1 的下一项「多光标」，沿用 `UndoTree`/`MinimapModel` 的纯 Kotlin 内核 + 独立单测范式，并补注 P2 的 run 引用口径。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **新增 `core/editor/src/main/java/com/suicoco/core/editor/MultiCursorModel.kt`（纯 Kotlin 标准库，可独立编译单测）**：把多光标编辑落地为真实内核。① 维护一组升序、去重、夹紧到 `[0, textLength]` 的光标偏移，容量上限默认 64（非法阈值回退到 1），避免长文档光标爆炸；② `insertAtAll` / `deleteBeforeAll`（退格）/ `deleteAfterAll`（删除）在**所有**光标处同时施加编辑，采用「一次遍历构建结果」算法避免逐光标修改导致的偏移漂移与重叠删除，并把结果光标写回内核支持连续输入；③ `columnDown`/`columnUp` 提供同列上/下放置光标（短行夹紧、不越行）；④ `reconcile` 供外部文本变化后夹紧去重；⑤ 全部公开方法失败安全（非法偏移夹紧、非法数量幂等、空输入原样返回），确定性无隐藏状态副作用。零 Android/Compose/三方依赖，core 不依赖 feature 的约束不变。
- **接线 `app/.../ui/editor/EditorArea.kt`**：工具栏新增「⋮ 多光标」开关（显示当前光标数），开启后展示操作条：`＋下行同列`/`＋上行同列` 放置光标，`插入`/`退格`/`删除` 把缓冲文本或删除操作批量应用到所有光标，`清空`/`退出` 收尾。批量结果经 `MainViewModel.updateContent` 回写，因此与撤销树快照、补全刷新、minimap 重算天然同步。补全弹层、撤销/重做、行号栏、minimap 等既有行为不变。
- **RELEASE.md P2 补注**：v0.3.11 条目所引 run `44955161878/44955161774` 系 v0.3.10 撤销树提交的构建记录，补注「功能小版本不逐个绑定单一 run、以最近一次双 job success 为准」，消除追溯歧义（见 v0.3.11 条目）。
- **文档同步**：`docs/ARCHITECTURE.md` §7 更新为多光标内核已落地 + 操作条接线说明，标注 BasicTextField 多光标文本域（逐光标选区渲染）仍属后续阶段。
- **验证**：`MultiCursorModel` 经 kotlinc 2.2.20 独立编译 + **48 项单测全通过**（单光标/夹紧/去重/容量/主光标/批量插入偏移稳定/开头与末尾光标/退格与删除语义/重叠删除/非法数量幂等/列编辑上下移动含短行夹紧/reconcile/移除保底/确定性幂等/连续插入推进）；单测中发现并修复两处真实缺陷（`deleteAfterAll` 光标左移误算、`columnDown` 短行越界）。改动文件括号平衡与符号引用静态校验通过。完整 Compose/APK 编译以 GitHub Actions 为准。
- **未闭环（仍阻塞/待后续）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机）、真机端到端验收、minimap 点击跳转滚动接线（`lineAt` 内核已就绪）、BasicTextField 逐光标选区渲染的多光标文本域（当前以「缓冲 + 批量应用」交互落地）、LSP/DAP 维持阻塞/待后续。

## v0.3.12 — 编辑器 minimap：core:editor 代码缩略图内核 + 右侧密度条接线

> 响应 2026-11-22 12:00 巡检日报 P3「编辑器增强余项（minimap 排期）」。在线核对：HEAD `9f372220`，RELEASE 记至 v0.3.11，双 job success；P0（补 tag v0.3.10）已由 v0.3.11 的「仅里程碑建标」惯例书面收口、P1（架构文档第 93 行）在线已是目标文案、P2（arm64 proot 随包）需 NDK+真机维持阻塞。故本轮落地 12:20 轮次明确「留待后续」的 minimap，沿用 `UndoTree` 的纯 Kotlin 内核 + 独立单测范式。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **新增 `core/editor/.../MinimapModel.kt`（纯 Kotlin 标准库，可独立编译单测）**：把整篇文档压成一列缩略信息。① 逐行解析前导空白（tab 记 4）与有效长度，按 `// /* * # <!--` 判定注释/空行/代码；② 代码密度按长度分 1..4 级、深缩进（≥12 列）再进一级并夹紧，供 UI 着色；③ **容量上限**默认 2000 行，超大文件只解析前 N 行并置 `truncated`，避免长文件卡顿；④ 提供 `viewportRect`（视口高亮矩形）与 `lineAt`（点击位置→源行号，截断时按比例放大回源文档），供后续滚动定位接线；⑤ 全部公开方法失败安全（非法阈值回退默认、负数/NaN 夹紧）、纯函数幂等。零 Android/Compose/三方依赖，core 不依赖 feature 的约束不变。
- **接线 `app/.../ui/editor/EditorArea.kt`**：编辑器主体改为 `Row`，右侧新增 `MinimapRail`（48.dp 竖条）——每行渲染为 2.dp 细横杠，注释偏黄、代码灰阶随密度增亮、空行留白、缩进做左侧 margin；计算全部由内核 `remember(content)` 驱动，UI 无状态可幂等重组。补全弹层、撤销/重做、行号栏等既有行为不变。
- **验证**：`MinimapModel` 经 kotlinc 2.2.20 独立编译 + **49 项单测全通过**（空文档/CRLF 归一/缩进与 tab/类别判定/密度分级/容量截断与非法阈值回退/视口矩形边界/点击映射含截断缩放/像素换算/幂等）；`bash scripts/verify-plugin.sh` 4 个官方 `.suicj` 全通过，全部 `scripts/*.sh` `bash -n` 通过，改动文件括号平衡与符号引用静态校验通过。完整 Compose/APK 编译以 GitHub Actions 为准。
- **未闭环（仍阻塞）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机）、真机端到端验收、minimap 点击跳转滚动接线（`lineAt` 内核已就绪）、多光标/LSP/DAP 维持阻塞/待后续。

## v0.3.11 — PTY 会话内核统一：向导与终端复用同一套 fail-closed 会话 + 版本惯例说明

> 响应 2026-11-22 05:59 巡检日报 P0（PTY 会话内核统一复用到安装向导，消除两套执行实现）+ P1（版本追溯断层收口）。在线核对：HEAD `b0f6154d`，RELEASE 记至 v0.3.10，双 job success（run 44955161878/44955161774）；P2（minimap）本轮按优先级让位 P0/P1，留待后续。保持三条不变量（无 host root / 官方源 / 仅 arm64）。本轮把向导从独立的 ProcessBuilder 执行路径收敛到与终端共享的 PTY 会话内核，core/feature 不依赖 native 叶子模块的架构约束不变。
>
> **构建 run 引用口径补注（v0.3.13，回应 2026-11-22 17:59 巡检日报 P2）**：上句所引 run `44955161878/44955161774` 实为 v0.3.10 撤销树提交（`c280469`…`9c1ca6a`）的 Actions 记录，非本 v0.3.11 源码 HEAD 的独立构建号。v0.3.11 及后续功能小版本按「仅里程碑建 tag/release、功能小版本随 main 提交」惯例，不逐个绑定单一 run，统一以 `.github/workflows/build.yml` 最近一次双 job success 为准；本补注用于消除追溯歧义，非功能变更。

- **新增 `core/term/src/main/java/com/suicoco/core/term/CommandExecutor.kt`（纯 Kotlin 契约）**：定义统一的命令执行接口 `CommandExecutor`（`run` / `cancel` / `cancelAll`）与值对象 `CommandResult(exitCode)`，作为"一套执行、两处复用"的抽象基点。core:term 仅持有契约，不依赖 native；消除了向导（`ProcessRunner`/ProcessBuilder）与终端（`PtySessionController`）各一套、互不复用的历史分裂。
- **`ProcessRunner` 实现 `CommandExecutor`**：保留 ProcessBuilder 路径作为默认纯 JVM 后端，`Result` 统一为 `CommandResult`；feature/onboarding 零 native 依赖、可独立单测的属性不变。
- **新增 `app/src/main/java/com/suicoco/terminal/PtyCommandExecutor.kt`（app 层适配器）**：把 `PtySessionController` 会话内核包装为 `CommandExecutor`，native 优先 + 进程降级 + fail-closed + 幂等 close 全继承；按 tag 维护句柄映射支持向导取消；逐行读取按 `0x0A` 切行并做 UTF-8 跨块保护、读取总量封顶（32 MiB）、30 分钟保护性上限。env 参数因会话内核已继承进程环境而忽略（SUICOCO_PROOT 来自 System.getenv），避免 shell 拼接注入面。
- **接线 `MainViewModel` + `Overlays.kt`**：创建**共享** `PtySessionController` 实例，同时注入 `TerminalCommandExecutor` 与 `PtyCommandExecutor`，终端与向导共用同一会话池；`onCleared` 统一回收。`InstallerController` 改为接受可注入 `CommandExecutor`（默认仍 ProcessRunner），`Overlays` 注入 `vm.installerExecutor`。
- **P1 版本惯例收口**：RELEASE.md 顶部补一句版本标记惯例说明（仅对外里程碑建 tag/release，功能小版本随 main 提交），止住历轮重复的"版本追溯断层"提示；不补建历史 tag，维持既有事实。
- **文档同步**：`docs/ARCHITECTURE.md` §7 更新向导执行路径为 `CommandExecutor` 契约注入 + 会话统一；修正"无撤销树"为 v0.3.10 已落地。
- **验证**：`CommandExecutor` / `ProcessRunner` / `PtySessionController` / `PtyCommandExecutor` / `TerminalCommandExecutor` 经 kotlinc 2.2.20 独立编译 + 单测（详见回复），`bash scripts/verify-plugin.sh` 4 个官方 `.suicj` 全通过，全部 `scripts/*.sh` `bash -n` 通过。完整 Compose/APK 编译以 GitHub Actions 为准。
- **未闭环（仍阻塞）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机）、真机端到端验收、minimap/多光标/LSP/DAP 等编辑器增强维持阻塞/待后续。

## v0.3.10 — 编辑器撤销树：core:editor 从占位落地为分支撤销内核 + 终端接线

> 响应 2026-11-22 00:13 巡检日报中历轮延续的 P2「编辑器增强余项（撤销树/多光标等）」。本轮日报 P0「收敛 v0.3.9 CI」与 P1「补齐 RELEASE.md v0.3.9」经在线核对均已闭环（双 job success、条目完整），P2「版本标记约定」维持既有惯例（纯文档/运维小版本不逐个建 tag/release），P3「阶段5 核心」需 NDK 交叉编译 + arm64 真机维持阻塞。故本轮落地唯一可离线、CI 安全、未重复的 P2 增强：**分支撤销树**。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **新增 `core/editor/src/main/java/com/suicoco/core/editor/UndoTree.kt`（纯 Kotlin 标准库，可独立编译单测）**：把此前的空壳 `core:editor` 模块落地为真实撤销内核。① **分支树语义**（对标 VSCode 撤销树）：撤销到历史节点后继续编辑会形成新分支，旧分支完整保留，`history()` 返回全部快照供 UI 绘制树，`jumpTo(id)` 可跳任意节点；② **线性 undo/redo**：沿最近访问分支回退/前进，分叉处 redo 自动回到上次所在分支；③ **连续输入合并** `recordTyped()`：同一 key、800ms 窗口内的逐字输入合并为单一撤销步骤；④ **容量上限**：默认 500 节点，超出后优先裁剪最老的非当前路径叶子，纯线性链则提升最老祖先为新根，避免内存无限增长；⑤ **失败安全**：所有公开方法对非法 id/空值返回 null/false/-1，绝不抛异常。零 Android/Compose/三方依赖，core 不依赖 feature 的架构约束不变。
- **接入 `app/src/main/java/com/suicoco/viewmodel/MainViewModel.kt`**：每个标签页按 path 维护一棵独立撤销树；`openFile()` 以初始内容建根（幂等，重开不重建）、`closeTab()` 清理、`updateContent()` 记录快照并刷新状态；新增 `undo()`/`redo()` 回填内容并同步补全；新增 `undoState`（canUndo/canRedo）StateFlow 驱动 UI 按钮启用态。
- **接入 `app/src/main/java/com/suicoco/ui/editor/EditorArea.kt`**：新增撤销/重做工具按钮（随 `undoState` 置灰）与键盘快捷键（`Ctrl+Z` 撤销 / `Ctrl+Shift+Z` 或 `Ctrl+Y` 重做）；新增 `ToolbarButton` 组件（disabled 不响应点击）。
- **验证**：`UndoTree` 经 kotlinc 2.2.20 独立编译 + **53 项单测全通过**（线性撤销重做/分支保留/分叉 redo/任意跳转/幂等/连续输入合并/容量裁剪/clear/边界与失败安全/快照结构）；模拟 MainViewModel 包装流程（app 层调用同构）**15 项全通过**（逐字合并/撤销禁用态/分支/多标签隔离/关闭清理）；单测中发现并修复两处真实缺陷（`recordTyped` 合并状态被 `record` 重置、纯线性链容量裁剪失效）。`bash scripts/verify-plugin.sh` 4 个官方 `.suicj` 全通过。完整 Compose/APK 编译以 GitHub Actions 为准。
- **未闭环（仍阻塞）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机）、终端向导复用会话内核与真机端到端验收、多光标/LSP/DAP 等其余编辑器增强维持阻塞/待后续。

## v0.3.9 — 构建脚本现代化：jvmTarget 从废弃 kotlinOptions 迁移到 compilerOptions DSL

> 响应 2026-11-21 17:59 巡检日报 P2「各模块 build.gradle.kts 的 jvmTarget 迁至 compilerOptions DSL」。KGP 2.2.20 / AGP 8.13.0 下 `android { kotlinOptions { jvmTarget = "21" } }` 已废弃，本轮统一迁移到官方推荐的 `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }`。语义等价、零功能变更，保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **20 个模块构建脚本统一迁移**：`app`、`core:*`（11 个）、`feature:*`（6 个）、`native/proot`、`native/pty`，共 20 个含 `jvmTarget` 的 `build.gradle.kts`，把 `android { kotlinOptions { jvmTarget = "21" } }` 迁移为顶层 `kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }`。使用 fully-qualified 类名避免 import 顺序问题；迁移前后 `{}`/`()` 括号平衡校验通过，未改动任何 dependencies / namespace / compileOptions / native 配置。
- **语义等价性**：原 `jvmTarget = "21"` 与新 `JvmTarget.JVM_21` 均为 Java 21 目标字节码，与 `compileOptions` 的 `VERSION_21` 保持一致；新 DSL 为 KGP 1.9.20+ 稳定 API，对 Android 全部 compilation 生效，消除废弃 API 告警。
- **验证**：20 个文件替换计数与残留检查通过（`kotlinOptions` 全部清除）、括号平衡校验通过；完整 Compose/APK 编译以 GitHub Actions 为准。
- **未闭环（仍阻塞）**：真实 arm64 proot 二进制随包、终端向导复用与真机端到端验收维持阻塞（需 NDK 交叉编译 + arm64 平板），与历轮一致。

## v0.3.8 — PTY 会话内核接入终端执行层：模拟响应升级为真实命令执行

> 响应 2026-11-21 12:00 巡检日报 P0「PTY 会话链接线到进程执行层」+ P1「同步设计文档滞后状态」。v0.3.7 已落地会话编排内核但终端 UI 仍是模拟响应，本轮把内核接到命令执行层，属阶段5 可离线落地部分。保持三条不变量（无 host root / 官方源 / 仅 arm64）。接线位于 app 层，native 叶子模块定位与 core 不依赖 feature 的约束均不变。

- **新增 `app/src/main/java/com/suicoco/terminal/TerminalCommandExecutor.kt`（app 层适配器，纯 Kotlin/JDK 标准库）**：把 `PtySessionController` 会话内核包装为终端可用的命令执行器。① **安全分词** `tokenize()`：shell-like 单/双引号 + 反斜杠转义，不展开变量、不做 glob、不解释元字符，不经 shell、不拼接命令字符串，引号未闭合/含 NUL/空输入一律 fail-closed；② **no-root 前置校验** `isAllowed()`：与 `ProotManager.assertNoHostRoot` 同语义，拒绝裸 su/sudo 与 shizuku/--host-root/libsu 等危险标记；③ **真实执行** `execute()`：命令字符串→argv 交给会话内核，native 可用走 forkpty、不可用自动降级直接进程，非阻塞轮询读 + `\n` 分行逐行回调，超时上限 + `shouldContinue` 协程取消钩子，finally 幂等 close 回收会话。
- **改造 `app/src/main/java/com/suicoco/viewmodel/MainViewModel.kt`**：`execTerminalCommand` 从模拟响应（help/pwd/ls 硬编码）改为经执行器真实执行；内置 help/clear 仍本地处理，其余命令分词后走会话内核，IO 线程执行、逐行回传真实输出，并显示当前会话模式（native PTY / 进程降级）；`onCleared()` 统一 `closeAll()` 释放会话资源。
- **`docs/ARCHITECTURE.md` §7**：修正 native/pty 滞后描述——从「仅 /dev/ptmx 探测、完整 forkpty 未完成」更新为「v0.3.6 完整 forkpty 会话链 + v0.3.7 会话编排内核 + v0.3.8 终端接线」，剩余 proot 二进制随包与向导复用维持阻塞标注。
- **验证**：`TerminalCommandExecutor` + `PtySessionController` + `PtyBridge` 经 kotlinc 2.2.20 独立编译并运行单测全通过（分词/引号转义/no-root 拒绝/native 委托/降级执行/逐行回传/幂等 close/超时与取消）；`bash scripts/verify-plugin.sh` 4 个官方 `.suicj` 全通过；全部 `scripts/*.sh` `bash -n` 通过。完整 Compose/APK 编译以 GitHub Actions 为准。
- **未闭环（仍阻塞）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH，需 NDK 交叉编译 + arm64 真机）；会话内核到向导 `ProcessRunner` 的统一复用与真机端到端验收维持阻塞。

## v0.3.7 — native/pty 会话编排内核：裸 JNI 桥升级为 fail-closed 会话 API + 进程降级

> 响应 2026-11-21 05:59 巡检日报 P0「native 完整实体化」历轮延续项。v0.3.6 已落地 forkpty 的 8 个 JNI 方法，但 PtyBridge 仍是「裸桥」——native 库加载失败时 `safeOpen` 直接返回 -1、上层无法真正执行命令，且无会话生命周期管理。本轮补齐会话编排内核，属阶段5 的可离线落地部分。保持三条不变量（无 host root / 官方源 / 仅 arm64）。真实 proot 二进制随包仍待 NDK 交叉编译 + 真机验证。

- **新增 `native/pty/.../PtySessionController.kt`（纯 Kotlin/JDK 标准库，可独立编译单测）**：把 PtyBridge 的 8 个 JNI 方法封装为生命周期安全的高层会话 API。① **统一句柄**：调用方拿到内核分配的单调递增 handle，内部映射到 native sid 或降级进程，永不暴露裸 sid，杜绝跨会话串扰与重复 close；② **native 优先 + 进程降级**：native 可用走 forkpty；不可用时降级到 `ProcessBuilder(argv)`（不经 shell、不拼接字符串、argv 逐元素传入，杜绝命令注入），使上层在纯 JVM / 未打包 .so 环境也能真实执行命令而非得到 -1；③ **fail-closed**：空 argv / 空元素 / 含 NUL 一律拒绝；每个 JNI 调用与 `available()` 均做异常兜底，JNI 抛异常时降级到进程路径而非崩溃；④ **幂等 close**：重复关闭返回 true、回收子进程、`closeAll()` 批量清理。降级路径 pump 线程非阻塞分块读 stdout/stderr、直写 stdin、waitFor 返回退出码。
- **依赖与架构**：新增类位于既有 `native:pty` 叶子模块（app 已依赖），零新依赖、无 Android/Compose/三方引用，core 不依赖 feature 的约束不变；只执行调用方 argv、不提权、不注入命令，no-root 仍由上层 `ProotManager.assertNoHostRoot` 保证。
- **验证**：PtySessionController + PtyBridge 经 kotlinc 2.2.20 独立编译（生成可执行 jar），**30 项单测全通过**（参数 fail-closed / native 委托读写-resize-isAlive-wait / 句柄与 sid 解耦 / 幂等 close / JNI 抛异常降级为真实进程且读到输出 / 纯 native 不可用降级 / 多会话隔离 / closeAll / 异常兜底）；单测过程中发现并修复两处真实健壮性缺口（`open`/`available` 未吞 JNI 异常，已补 try-catch 降级）。`pty.cpp` / `proot.cpp` 经 `g++ -std=c++17 -fsyntax-only -Wall`（补全 JNI mock）语法检查通过；`bash scripts/verify-plugin.sh` 4 个官方 `.suicj` 全通过；全部 `scripts/*.sh` `bash -n` 通过。完整 Compose/APK 编译以 GitHub Actions 为准。
- **未闭环（仍阻塞）**：会话编排内核到终端 UI（`MainViewModel.execTerminalCommand` 当前仍为模拟响应）与 `ProcessRunner` 的接线属阶段5 后续，待 proot 实体化后一并接通；真实 arm64 proot 二进制随包（SHA256 + RUNPATH 补丁）需 NDK 交叉编译 + arm64 真机，维持阻塞。

## v0.3.6 — native/pty 实体化：arm64 forkpty 伪终端会话链（8 JNI 方法）

> 响应 2026-11-20 23:59 巡检日报 P0「native 完整实体化」中唯一不依赖真机/NDK 运行时的可落地部分。保持三条不变量（无 host root / 官方源 / 仅 arm64）。属分阶段推进：本轮落地 PTY 会话链，真实 arm64 proot 二进制随包仍待 NDK 交叉编译 + 真机验证。

- **`native/pty/src/main/cpp/pty.cpp` 从「仅探测 /dev/ptmx」升级为真实 forkpty 会话链**，补齐 8 个 JNI 方法：① `nativeAvailable`（PTY 设备探测，保留）；② `nativeOpen(cols,rows,argv)` 以 `forkpty` 创建伪终端并在子进程 `execvp` 调用方传入的 argv；③ `nativeWrite` 向 master 写输入；④ `nativeRead` 用 `poll` 非阻塞读输出（短超时，避免 JNI 线程 hang，EIO 视为从端关闭）；⑤ `nativeResize` 走 `TIOCSWINSZ` 同步窗口尺寸；⑥ `nativeIsAlive` 以 `waitpid(WNOHANG)` 非阻塞探测并回收；⑦ `nativeWait` 阻塞等待并解析退出码；⑧ `nativeClose` 关闭 master + SIGHUP/SIGKILL 回收子进程，杜绝 fd 泄漏与僵尸进程。
- **安全不变量**：全局会话表 `unordered_map<id, shared_ptr<Session>>` 由 mutex 保护；argv 解析 fail-closed（任一元素异常整体拒绝）；本桥只执行调用方 argv、不自行提权、不注入命令，no-root 约束继续由上层 ProotManager 保证；master 设 `O_NONBLOCK`，输出读取由上层循环驱动。
- **`PtyBridge.kt` 补全 8 个 external 声明 + JVM 降级入口**：`safeAvailable()` / `safeOpen()` 在 native 库加载失败（纯 JVM 测试环境）时降级为 `/dev/ptmx` 探测与 `-1`，避免 `UnsatisfiedLinkError` 崩溃；`isNativeLoaded()` 供上层决定路径。此前该类仅有一个 `nativeAvailable`，`init{}` 直接 `loadLibrary` 会在 JVM 环境抛异常，本轮一并修复。
- **验证**：C++ 会话链经本地 `g++ -std=c++17 -fsyntax-only`（真实 jni.h + android/log.h mock）语法检查通过；Kotlin 侧经 kotlinc 2.2.20 独立编译通过；JNI 方法名/签名与 Kotlin external 声明逐一核对匹配。完整 Compose/APK 编译以 GitHub Actions 为准。**未做 arm64 真机端到端验证**（当前无真机、无 NDK 本地交叉编译环境）。
- **未闭环（仍阻塞）**：真实 arm64 proot 二进制随包（SHA256 + RUNPATH 补丁）需 NDK 交叉编译 + arm64 真机，维持阻塞；PTY 会话链到 `ProcessRunner` 的接线属阶段5，待 proot 实体化后一并接通。

## v0.3.5 — 设计文档同步：修正已知限制滞后状态（P1）

> 响应 2026-11-20 18:00 巡检日报 P1「同步设计文档滞后状态」。本轮为文档同步，无源码/功能变更，保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **`docs/ARCHITECTURE.md` §7 修正两处滞后描述**：①「安装向导原型为模拟执行」→ 更新为 v0.2.5 起 `ProcessRunner`+`InstallerController` 真实执行（proot 用户空间跑 debootstrap/apt，逐行日志、可取消）；②「补全为前缀过滤简版」→ 更新为 v0.3.1 起 `CompletionEngine` 智能模糊补全（三级打分/类型加权/文档词提取/token 区间计算），但仍未接入 LSP（P2 余项）。二者此前与已落地代码冲突，会误导开发者。
- **`docs/PLUGIN_SPEC.md` §8 补充一键安装闭环与清单 SHA 自洽机制**：新增 v0.3.4 官方插件「一键下载 + 自动安装」编排链路（含网页 URL→raw 直链、受控重定向 SSRF 防护、SHA256 校验、复用导入校验、登记 OFFICIAL）与 `scripts/sync-catalog-sha.py` 摘要回写机制说明，与 v0.3.4 实际代码对齐。
- **验证**：`bash scripts/verify-plugin.sh` 4 个官方 `.suicj` 全通过；文档为纯 Markdown 变更，无 APK/插件字节改动，构建以在线 Actions 为准。

## v0.3.4 — 阶段6 收官：官方插件「一键下载 + 自动安装」闭环 + 修复清单摘要失配

> 响应 2026-11-20 06:03 巡检日报 P0「基于清单的官方插件一键下载 + 自动安装 UI 交互（内核与 downloadUrl() 已就绪，UI 未接）」。这是 v0.3.2 RELEASE.md 明确留待后续的阶段6 最后余项，本轮首次真正接通。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **补挂 v0.3.4 APK asset（2026-11-20 22:10 轮次）**：v0.3.4 Release（id 452262006）此前仅附 4 个 `.suicj`，因沙箱到 GitHub blob 存储带宽不稳定未能在发布时随包。本轮通过断点续传从成功 CI run 44673088993 取回 `suicoco-debug-apk` 工件（zip 16,260,839 字节，`unzip -t` 无错误），解出 `app-debug.apk`（60,497,843 字节，SHA256 `e2e8aa5b221ffea3…`，含 `AndroidManifest.xml`、`classes.dex` 及 arm64-v8a 的 `libsuicoco_proot.so` / `libsuicoco_pty.so`），经 Release Asset API 补挂（HTTP 201）。至此 **v0.3.4 Release 具备 5 个可下载产物**（APK + 4 个 `.suicj`），与 v0.2.2~v0.3.3 完全一致，发布一致性缺口消除；三条不变量不变。

- **新增 `OfficialInstallController.kt`（feature:marketplace，可独立编译单测）**：编排内核——① `loadCatalog()` 把清单解析为展示条目并按宿主版本标注引擎兼容；② `resolveDownloadUrl()` / `resolveCatalogUrl()` 先复用 `PluginCatalog` 的文件名与仓库白名单校验，再把 github.com 网页 URL 安全转换为 raw.githubusercontent.com 二进制直链（**修复网页 URL 直接下载只得到 HTML 的真实缺陷**）；③ `fetchWithGuardedRedirects()` 受控重定向状态机，每次跟随前用 `isAllowedDownloadTarget()` 做白名单判定，杜绝「官方源 302 到内网/仿冒站」的 SSRF；④ `verifyDownloaded()` 复用内核常量时间 SHA-256 比对；⑤ `networkFetch()` 基于标准库 HttpURLConnection、关闭自动重定向。全程 fail-closed，不抛异常。
- **UI 接线 `MarketplaceScreen` + `PluginMarketplaceOverlay`**：市场面板新增官方插件清单（名称/版本/描述/依赖环境/引擎兼容标记）与「一键安装」按钮、清单刷新与安装反馈；浮层读取跨会话持久化的仓库地址，在 IO 线程执行「加载清单 → 点安装 → 受控下载 → SHA256 校验 → 复用 `ImportController` 完整安全校验 → 登记为 OFFICIAL」。第三方导入链路保持不变，业务规则不重复实现（单一真源 SuicjImportPolicy / OfficialInstallController）。
- **修复真实 bug：清单 SHA256 失配导致一键安装必然全被拒**。根因：`build-plugin.sh` 用 `zip -r` 打包会写入 mtime，CI 每次 `[skip ci]` 重打包后 `.suicj` 字节/SHA256 都变，而清单摘要写死不变。本轮按仓库真实产物回写 `release/suicj-catalog.json` 四个摘要；并新增 `scripts/sync-catalog-sha.py`，在 `build-plugins.yml` 打包后自动按真实产物回写清单摘要——从此「清单摘要 == 用户实际下载字节」长期自洽，不再随重打包漂移。
- **依赖**：`feature:marketplace` 增加 `implementation(project(":core:config"))`（feature→core，无架构回退；core 不依赖 feature）。
- **验证**：`OfficialInstallController` 经 kotlinc 2.2.20 独立编译并运行 **45 项单测全通过**（清单解析/引擎过滤/网页→raw 转换/路径穿越与非法文件名拒绝/SSRF 重定向拦截/重定向环上限/取数异常 fail-closed/SHA256 真实插件字节与篡改样本）；发现并修复 raw URL 漏 `release/` 段的真实 bug；`bash scripts/verify-plugin.sh` 4 个官方 `.suicj` 全通过（用在线真实字节）；全部安装脚本 `bash -n` 通过。Compose/APK 编译以 GitHub Actions 为准。

## v0.3.3 — 发布一致性补齐：v0.3.1 / v0.3.2 补建 tag+Release，v0.3.0 补挂 APK

> 响应 2026-11-19 23:59 巡检日报 P0「补建 v0.3.1 tag/release」与「补挂 v0.3.0 APK asset」。本轮为发布运维，无源码变更，保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **补建 tag / Release**：为 `6df8436`（v0.3.1 编辑器增强）创建 tag `v0.3.1` + Release（id 451986193），为 `6a362aeb`（v0.3.2 阶段6 后续）创建 tag `v0.3.2` + Release（id 451986266）；两者各附 5 个产物（`app-debug.apk` + hello/C-C++/Java/Python 四个 `.suicj`）。
- **补挂历史 APK**：为 v0.3.0 Release（id 450880483）补挂 `app-debug.apk`，此前其仅含 4 个 `.suicj`。至此 **v0.3.0 / v0.3.1 / v0.3.2 三个 Release 均具备 5 个可下载产物**，与 v0.2.2~v0.2.9 一致。
- **产物取证**：三个 APK 分别取自对应 success 的 CI run（v0.3.0 RUN 44458174373 / v0.3.1 RUN 44502837159 / v0.3.2 RUN 44563216331），逐一 `unzip -t` 通过、含 `AndroidManifest.xml` 与 `classes.dex`，大小递增（60,394,411 / 60,411,287 / 60,435,999 字节）与各自源码提交对应；四个 `.suicj` 经 `verify-plugin.sh` 全部通过。
- **版本一致性**：RELEASE.md / tag / Release 三处对齐至 v0.3.2 源码 + v0.3.3 发布运维记录，消除日报头号阻塞项「RELEASE.md 记 v0.3.x 但 tag/release 停在 v0.3.0」。
- **未闭环项**：native 完整实体化（真实 arm64 proot 二进制随包 + 完整 forkpty 会话链）需 NDK 交叉编译 + arm64 真机，本地无 SDK/NDK/真机且 seccomp 限制嵌套 ptrace，阻塞；阶段4/5/6 assignee 因项目成员仅所有者一人，无法指派；proot 端到端真机验证无设备；编辑器 LSP/DAP、minimap、多光标、撤销树及 `jvmTarget` DSL 迁移工程量大 / 无法本地编译验证，留待后续。

## v0.3.2 — 阶段6 后续：官方插件仓库清单内核 + 仓库地址跨会话持久化

> 响应 18:00 巡检日报 P0「阶段6 后续功能」。把 v0.3.0 的「输入框实时校验 / 保存按钮占位」升级为「清单可解析、可下载、可校验 + 地址真正落盘」。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **新增 `release/suicj-catalog.json`（官方插件清单）**：列出 4 个官方 `.suicj`（hello / C-C++ / Java / Python）的 id、名称、版本、描述、文件名 `file`、真实 SHA256、`engine` 版本范围、`requiresEnv` 依赖环境。清单 URL 由 `PluginRepositoryConfig.catalogUrl()` 构造（`<仓库根>/release/suicj-catalog.json`）。
- **新增 `PluginCatalog.kt`（core:config，可独立编译单测）**：纯 Kotlin/Java 标准库、无 Android/网络/JSON 三方依赖的清单安全内核。① 自带极简 JSON 解析（递归下降）；② fail-closed 校验：清单大小上限 512 KiB、条目 ≤128、结构版本=1、id 格式/唯一、version 语义化、`file` 必须安全 `.suicj`（拒绝路径穿越/斜杠/反斜杠/控制字符）、SHA256 必须 64 位 hex、engine 范围合法、`requiresEnv` 白名单、file 唯一；③ `downloadUrl()` 复用 `PluginRepositoryConfig` 白名单构造下载地址，`catalogUrl()` 提供清单地址；④ `verifyChecksum()` 常量时间比对 SHA256，`isEngineCompatible()` 支持 `*`/精确/`^`/`>=`。任何非法输入返回 Failure、不抛异常。
- **仓库地址跨会话持久化（feature:settings）**：`RepositoryConfigController` 增 `normalizeOrNull()`（保存前规范化）与 `sanitizeLoaded()`（加载后再次白名单净化，防篡改存储值注入恶意源）；新增 `RepositoryConfigStore`（SharedPreferences，零新依赖，Android framework API）；`SettingsScreen` 用 `LocalContext` 读取已保存地址回填输入框，「保存」按钮真正落盘并显示「已保存，将在重启后生效」，输入变更清除提示。
- **验证**：内核经 kotlinc 2.2.20 独立编译 **50 项单测全通过**（解析合法/畸形/超限/重复 id/路径穿越/非法 sha/engine/env、下载 URL 白名单绕过拦截、SHA256 已知向量、引擎兼容矩阵、持久化净化）；真实 `suicj-catalog.json` 经内核解析成功、4 条目 SHA256 与引擎兼容逐一核对一致；`bash scripts/verify-plugin.sh` 4 个官方 .suicj 全通过；两安装脚本 `bash -n` 通过。Compose/APK 编译以 GitHub Actions 为准。
- **范围说明**：本轮落地清单内核、清单文件与地址持久化；基于清单的「官方插件一键下载 + 自动安装」UI 交互留待后续轮次（内核与下载 URL 已就绪）。

## v0.3.1 — 编辑器增强：补全从前缀过滤升级为智能模糊补全 + 修复插入错位 bug

> 响应 12:00 巡检日报 P2「补全从前缀过滤升级」。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **新增 `CompletionEngine.kt`（core:syntax）**：此前 `core:syntax` 为 Placeholder。纯 Kotlin 标准库、无 Android/Compose 依赖、可独立编译单测的补全内核：① 三级模糊打分（精确 > 前缀 > 子串 > 子序列 fuzzy，大小写不敏感，短标签与高价值类型优先）；② 从当前文档已输入标识符动态提取「文档词」作为补全来源（对标 VSCode word-based suggestions）；③ 提供当前 token 提取与替换区间计算。
- **app 接线**：`MainViewModel.updateCompletions()` 由原 `label.startsWith(prefix)` 纯前缀过滤改为调用内核 `suggest()`（静态词库 + 文档词 + 三级打分）；`SNIPPETS`/`COMMON_WORDS` 词库保持不变，映射为内核候选。
- **修复真实 bug**：原 `acceptCompletion()` 把 `token.length` 当作光标列位置，在缩进或行中触发补全时插入错位（如 `    pri` + `print` 会变成 `   print pri`）。改为由内核计算 `[token 起始列, token 结束列)` 区间精确替换当前 token，缩进与行尾其它字符均正确保留。
- **架构**：未改任何依赖，`core:syntax` 仍为叶子 core 模块（app 原已依赖），无回退。
- **验证**：内核经 kotlinc 2.2.20 独立编译并跑多组单测（精确/前缀/子串/子序列/大小写/文档词/去重/插入区间/缩进 bug 回归）全通过；`bash scripts/verify-plugin.sh` 4 个官方 .suicj 全通过；Compose/APK 编译以 GitHub Actions 为准。

## v0.3.0 — 阶段6 收尾：第三方导入入口 UI + 仓库地址配置 UI

> 响应 06:00 巡检日报 P0「阶段6 收尾（可本地验证 / CI 安全）」：v0.2.9 已落地校验内核与仓库地址配置内核，但入口 UI 仍为 Placeholder。本轮把第三方 `.suicj` 导入入口与官方仓库地址配置落到真实 UI 并完成 app 层接线，消除死代码。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **第三方导入入口 `feature:marketplace`**：新增 `ImportController`（纯逻辑，复用 `SuicjPackage`/`SuicjImportPolicy` 的完整安全校验，异常一律 fail-closed 映射为拒绝原因）与 `MarketplaceScreen`（VSCode Dark Modern 风格：导入按钮 + 成功摘要 + 拒绝原因展示）。app 层 `PluginMarketplaceOverlay` 用 `ActivityResultContracts.GetContent()` 选文件 → 复制到私有 cache → 交 `ImportController` 校验 → 成功登记到已安装列表（来源标记 `THIRD_PARTY`）。
- **仓库地址配置 UI `feature:settings`**：新增 `RepositoryConfigController`（纯逻辑，复用 `core:config` 的 `PluginRepositoryConfig`）与 `SettingsScreen`（实时 fail-closed 校验、合法地址显示规范化结果与 `release/suicj-catalog.json` 清单路径）。设置页内保留环境安装向导入口。
- **app 层接线**：活动栏齿轮 → 设置视图；扩展视图「官方插件下载 / 第三方 .suicj 导入」入口与命令面板「导入第三方 .suicj」→ 插件市场浮层；替换原模拟提示。所有 UI 均可达，无 Placeholder 死代码。
- **验证**：两个 controller 经 kotlinc 2.2.20 独立编译并跑单测——`RepositoryConfigController` 11 项（合法/仿冒域名/IP/异端口/凭证注入/fragment/ftp/路径穿越）、`ImportController` 6 项（成功映射/非 .suicj/空文件/SuicjException 原因透传/通用异常 fail-closed）全通过；`bash scripts/verify-plugin.sh` 4 个官方 .suicj 全通过；两安装脚本 `bash -n` 通过。Compose 编译以 GitHub Actions 为准。
- **范围说明**：本轮仅完成 UI 呈现与接线，仓库地址暂未做跨会话持久化（输入框实时校验、合法时按钮可用），如后续需要可接入 DataStore；官方仓库清单下载逻辑留待后续轮次。

## v0.2.9 — 历史 Release 产物补齐 + 官方插件仓库地址配置（阶段6 前置）

> 响应 23:59 巡检日报 P1「补齐 v0.2.5~v0.2.7 Release assets」与 P2「阶段6 官方插件仓库地址配置」。补齐四个 Release 的可下载产物，并为设置页仓库地址配置落地可 fail-closed 的安全校验内核。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **补齐历史 Release assets**：为 v0.2.5 / v0.2.6 / v0.2.7 / v0.2.8 四个 GitHub Release 各补挂 5 个产物（`app-debug.apk` + hello / C-C++ / Java / Python 四个 `.suicj`），共 20 个。APK 取自各版本对应的 success Actions run（44235715828 / 44283667575 / 44320825401 / 44344585859），逐一确认含 `AndroidManifest.xml`；插件包经 `verify-plugin.sh` 全部校验通过。至此 v0.2.2~v0.2.8 全部 Release 均具备可下载产物。
- **新增 `PluginRepositoryConfig.kt`（core/config）**：纯标准库、可独立编译单测的官方插件仓库地址校验对象，对应 `docs/PLUGIN_SPEC.md` §8「设置中可配置仓库地址」。强制 https、域名白名单（github.com / raw / objects.githubusercontent.com）、拒绝 IP 字面量与私网/环回（SSRF）、拒绝 user:pass 凭证注入、端口仅允许缺省或 443、拒绝 fragment 与路径穿越，非法一律 fail-closed；`catalogUrl()` 由合法根地址构造 `release/suicj-catalog.json` 清单路径，为 feature:settings 配置 UI 与 feature:marketplace 官方下载提供基础。
- **验证**：`PluginRepositoryConfig` 经 kotlinc 2.2.20 真实编译，32 项单测（含 http/ftp/file 协议、仿冒域名、IPv4/IPv6/内网/元数据地址、凭证注入、异端口、fragment、路径穿越等样本）全通过；`bash scripts/verify-plugin.sh` 4 个官方 .suicj 全通过。core:config 无新增依赖，CI 编译安全。

## v0.2.8 — 第三方插件导入沙箱校验（阶段6 前置）

> 响应 18:00 巡检日报 P2「第三方 .suicj 导入沙箱校验（严格限定自有格式）」。第三方来源的 .suicj 是不可信输入，本轮把校验从「解压后补救」升级为「解压前拦截」，并修复引擎版本兼容判断的硬编码缺陷。保持三条不变量（无 host root / 官方源 / 仅 arm64）。

- **新增 `SuicjImportPolicy.kt`（core/extengine）**：纯标准库、可独立编译单测的安全判定对象，不依赖 Android/zip4j。提供 `review()` 解压前条目审查（空包 / 条目数 ≤512 / 单文件 ≤16 MiB / 解压总量 ≤32 MiB / 压缩比 >200:1 判为解压炸弹）、`unsafeNameReason()`（拒绝绝对路径、盘符、反斜杠、`..`/`.` 段、双斜杠、NUL/控制字符）、`isSafeMain()`（安全路径且必须 `.js`）、语义化版本比较 `compareVersion()` 与 `isEngineCompatible()`（支持 `*`/精确/`^x.y.z`/`>=x.y.z`，无法解析一律 fail-closed）。
- **`SuicjPackage.kt` 关键安全修复**：原实现在 `extractAll()` **之后**才 `walkTopDown` 校验路径，此时 zip-slip 恶意文件已写出到目标目录之外、校验形同虚设。改为解压前用 `ZipFile.getFileHeaders()` 映射为 `EntryInfo` 并调用 `review()` 预检，不安全即拒绝、不落盘；解压后再做一次 canonical 路径越界 + 符号链接拒绝的二次防御。
- **修复真实版本判断 bug**：原 `isVersionSupported()` 硬编码宿主版本 `0.1.0`、`^` 只比 major 导致高 minor 不兼容判断错误；改为接入策略的 `isEngineCompatible()`，宿主版本常量 `HOST_VERSION=0.2.8`，官方插件 `>=0.1.0` 仍兼容、声明 `>=9.9.9`/`^9.x` 的恶意或不兼容插件被拒。
- **main 入口强化**：从「存在即可」改为「安全路径 + `.js` 后缀 + 必须是普通文件（非目录/非符号链接）」。
- **`verify-plugin.sh` 对齐**：内嵌 Python 校验补齐与 Kotlin 策略一致的沙箱项（路径穿越/绝对路径/反斜杠/条目数/单文件/总量/压缩比/入口类型/id·version 格式/引擎兼容），CI 与每晚巡检复用同一套判定。
- **验证**：`SuicjImportPolicy` 经 kotlinc 2.2.20 真实编译并跑 37 项单测全通过；`SuicjPackage`+策略用依赖 stub 完整语义编译零错误（13 class），zip4j `getFileHeaders/getFileName/getUncompressedSize/getCompressedSize/isDirectory` API 经 javap 逐一核对；4 个官方 .suicj 全部通过，6 类自制恶意样本（zip-slip/绝对路径/反斜杠/伪装入口/不兼容引擎/解压炸弹）全部被拒；`bash -n` 通过。APK 编译以 GitHub Actions 为准。

## v0.2.7 — native 层实体化（ELF 校验 + PTY 能力探测）

> 阶段5 native 去骨架化的第一步：把 `:native:proot` / `:native:pty` 从返回固定字符串的 JNI 骨架，实体化为具备真实能力的实现。仅用标准 C/POSIX 调用，保持 native 叶子模块定位与三条不变量（无 host root / 官方源 / 仅 arm64）。

- **`:native:proot` 实体化**：`proot.cpp` 新增真实 ELF64 头校验——以二进制读取文件头，校验 ELF magic、`ELFCLASS64`、小端字节序、`e_machine == EM_AARCH64(183)`，用于注入的 proot/libproot.so 随包自检；`nativeProotVersion()` 不再伪造二进制版本，改为返回桥实现版本标识。
- **`ProotBridge.kt` 接口增强**：新增 `nativeValidateElf(path)` / `safeValidateArm64Elf(path)` / `safeVersion()`；native 库不可用时（纯 JVM 测试环境）自动降级为 JVM 侧等价校验（ByteBuffer 解析前 20 字节），避免 `UnsatisfiedLinkError` 崩溃。保留原 `nativeProotVersion()` public API 向后兼容。
- **`:native:pty` 实体化**：`pty.cpp.nativeAvailable()` 由硬编码 `JNI_TRUE` 改为真实探测 `/dev/ptmx`（`open(O_RDWR|O_CLOEXEC)` + `close`），设备不可用/权限受限时返回 false，供上层决定是否回退到 JVM 进程执行路径；保持 `nativeAvailable(): Boolean` API 不变。
- **验证**：两个 C++ 文件经 `g++ -std=c++17 -Wall -Wextra -Werror` 严格编译零错误零警告；JNI 签名经 `javac -h` 逐一核对一致；ELF 校验算法用真实 arm64 ELF（termux proot 5.1.107.102）、伪造 x86-64 ELF、垃圾文件、不存在路径四类样本测试，判定全部符合预期，并与 `readelf -h` 交叉验证一致；`bash scripts/verify-plugin.sh` 4 个 .suicj 全部通过；两个安装脚本 `bash -n` 语法通过。
- **范围说明**：本轮为 native 桥的能力实体化，未随包真实 proot 二进制、未实现完整 forkpty 会话链（需 NDK 交叉编译 + arm64 真机验证，受当前环境限制，留待后续真机阶段）；APK 编译以 GitHub Actions 为准。

## v0.2.6 — CI 依赖升级（消除 Node 20 deprecated 与 runner 迁移风险）

- **P2 CI 依赖升级**（2026-11-18 12:00 巡检日报新发现项）：
  1. `actions/checkout@v4` → `@v5`、`actions/setup-java@v4` → `@v5`、`actions/upload-artifact@v4` → `@v5`，统一迁移到 Node 24 运行时，消除 GitHub Actions 对 Node 20 的 deprecated 告警与被强制跑在 Node 24 的警告；
  2. 两个 job 的 `runs-on: ubuntu-latest` 固定为 `ubuntu-24.04`，避免 ubuntu-latest 迁移至 Ubuntu 26 带来的工具链（NDK 27 / CMake 3.31）兼容不确定性；
  3. `build-plugins.yml` 同步升级 `checkout@v5` + `ubuntu-24.04`，并新增 `concurrency` 组串行化产物提交——修复本轮暴露的真实问题：多个 commit 紧邻推送时，两次产物提交 job 并发 `git push` 导致非快进冲突（其中一次失败）；加并发组后改为排队，消除该竞态；
  4. 升级前已通过 GitHub REST API 确认 checkout@v5 / setup-java@v5 / upload-artifact@v5 三个 major tag 均存在（HTTP 200），升级后由 GitHub Actions 实跑验证。
- 本轮为纯 CI 配置改动，无 Kotlin/Compose 源码变更，APK 构建逻辑与 4 个插件产物不变。

## v0.2.5 — 阶段4 收尾：安装向导对接真实 PTY 执行

- **P0 阶段4 收尾（向导对接真实进程执行）**：将 `:feature:onboarding` 从占位实现为真实的 Compose 安装向导，取代早期"点击即完成"模拟：
  1. `core/term` 新增 `ProcessRunner`：用 ProcessBuilder 真实启动 proot 命令、合并 stdout/stderr 并**逐行回传日志**，支持按 tag 取消子进程；不依赖需 NDK 交叉编译的 native/pty JNI 骨架，CI 可编译验证。
  2. `feature/onboarding` 新增 `InstallerController`（下载→解压→apt 更新→工具链安装状态机，幂等锁、Mutex 保护、取消支持、日志上限 500 行）与 `OnboardingWizard`（VSCode Dark Modern 风格 Compose UI：步骤/进度条/逐行日志自动滚动/开始·取消按钮）。
  3. `ProotManager` 支持 `SUICOCO_PROOT` 环境变量注入 libproot.so 绝对路径（阶段5 实体化路径），否则回退 app nativeLibraryDir。
  4. **修复真实 bug**：`assertNoHostRoot` 旧版对整条 argv 做 `"su"` 子串匹配，会把 proot 路径 `com.suicoco` 内的 "su" 误判为提权、导致向导必然失败；改为跳过 argv[0] 并对裸 su/sudo 整词匹配、危险标记子串匹配。
- 本轮为纯 Kotlin/Compose 改动，native 真实 arm64 libproot.so 实体化仍属阶段5（需 NDK 交叉编译，本地无 Android SDK，留待后续）。
- 验证：`bash scripts/verify-plugin.sh` 4 个 .suicj 全部通过；Compose/Kotlin API 静态核对与括号配平通过；APK 编译以 GitHub Actions 为准。

## v0.2.4 — 阶段4 第一步：安装脚本 proot 化 + rootfs 真实解压

- **P0 阶段4 真实安装落地（第一步）**：
  1. `scripts/install-ubuntu-minimal.sh` 重写为 **proot 版**：弃用 debootstrap + sudo chroot（需 host root，违反不变量），改为下载 Ubuntu 官方 base rootfs（cdimage.ubuntu.com，arm64）+ 官方 SHA256SUMS 校验 + proot fake-root（`-0`）执行 apt-get update，全程无 su/sudo；
  2. `scripts/install-lang-env.sh` 同步重写为 proot 版：在容器内以 apt 官方源安装语言工具链，无 chroot；
  3. `core/distro` `RootfsManager.extract` 从空壳 stub 实现为真实解压：纯 Kotlin 解析 ustar/PAX/GNU tar（符号链接/硬链接/权限位），含路径穿越防护，无需外部 tar 二进制；
  4. **修复失效下载源**：`DistroProfile.UBUNTU_2404` 原 `partner-images.canonical.com` 地址已 404，改用 cdimage.ubuntu.com 官方 ubuntu-base 24.04.5 arm64 并内置 SHA-256 校验值（`download()` 下载后强制校验）；
  5. `docs/INSTALLER_DESIGN.md` 同步更新脚本迁移与解压说明。
- 本轮未替换 `:feature:onboarding` 模拟执行（Compose 向导对接 PTY 为下一步），native proot/pty 实体化仍为待办。

## v0.2.3 — v0.2.2 正式 Release 发布，阶段5 定义修订

- **P0 落地**：创建 `v0.2.2` tag 与 GitHub Release，附 `app-debug.apk` 与 4 个官方 `.suicj` 插件包（hello / C-C++ / Java / Python），产物取自 Actions RUN 44207560406 / 44207560401（双 job success）。
- **P1 阶段5 定义修订**：随 v0.2.x 弃用 Electron/Web 原型，阶段5 由「Electron 桌面壳」修订为「安卓 proot 运行时与真实环境安装对接」（native proot/pty 实体化、安装脚本 proot PTY 执行）；`docs/INSTALLER_DESIGN.md`、`docs/ARCHITECTURE.md` 同步更新。
- 本轮未改动源码，CI 保持双 job success。

## v0.2.2 — CI 全链路修复，APK 构建打通

- **CI 修复链**（连续 5 次失败的根因逐一排除）：
  1. 移除持续故障的第三方 action `android-actions/setup-android@v3`；
  2. 修复 `sdkmanager: command not found`——自包含下载 Android cmdline-tools 到 `$RUNNER_TEMP/android-sdk` 并显式加入 PATH；
  3. 补推缺失的 `gradle/wrapper/gradle-wrapper.jar`（此前二进制漏推，导致 `GradleWrapperMain` 找不到）；
  4. **补齐 37 个漏推文件**：全模块的 `AndroidManifest.xml`、`CMakeLists.txt`、`themes.xml`、`proguard-rules.pro` 及 `release/*.suicj`——这是 `processDebugMainManifest` 失败的真正根因，仓库此前从未推全。
- **验证**：GitHub Actions 两个 job 全部 success，`app-debug.apk` 成功产出，4 个 `.suicj` 校验通过。
- 后续建议（详见项目讨论区 2026-11-18 日报）：发布正式 tag 与 GitHub Release；阶段4 真实安装脚本对接；native proot 实体化。

## v0.2.1 — 修复 CI APK 构建

- 移除持续故障的第三方 action `android-actions/setup-android@v3`（连续 5 次构建失败），改用预装 SDK + 许可证接受（后因 sdkmanager 不在 PATH 由 v0.2.2 进一步完善）

## v0.2.0 — 安卓原生架构重建

> **重大方向修正**：从早期 Web/Electron 原型（已归档至 `legacy-web-prototype/`）重建为面向安卓平板的原生 APK，架构借鉴 JCode。

- **软件本体**：Kotlin + Jetpack Compose 多模块工程（20 个模块，沿用 JCode 依赖规则），VSCode Dark Modern 工作台 UI（活动栏/资源管理器/编辑器标签/面包屑/终端面板/命令面板/补全弹层/状态栏，无 AI 入口）
- **插件引擎**：`.suicj` 自有格式（zip + manifest + main.js），Rhino JS 引擎执行，manifest 强校验 + engines 兼容性 + 目录穿越防护；仅此一种格式，不兼容其他
- **语言插件**：C/C++、Python、Java + 示例，4 个 `.suicj` 产物全部通过校验
- **运行时**：proot 命令构建器（硬性无 host root）、Ubuntu 24.04 ARM64 官方 rootfs 下载器、apt 官方源工具链目录、图形化安装向导
- **native 层**：proot / pty JNI 桥骨架（arm64-v8a）
- **产物**：`app-debug.apk` + `release/*.suicj`

构建环境：JDK 21 / Android SDK platform 36 / NDK 27.0.12077973 / CMake 3.31.6 / Gradle 8.14.3。

### 已知限制（后续阶段）

- native 层为 JNI 骨架，真实 proot .so 需随包集成
- 安装向导原型为模拟执行，真实 proot/apt 脚本待对接
- 语法高亮为基础着色，补全为前缀过滤，未接入 LSP/DAP
- 编辑器为 Compose 文本域，无 minimap/多光标/撤销树

## v0.1.0 — Web 原型（已归档）

早期的 Electron/Web 原型，已移至 `legacy-web-prototype/`，仅作 UI 参考，非交付物。因方向错误（PC 桌面而非安卓平板 APK）弃用。

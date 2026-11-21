package com.suicoco.core.editor

import kotlin.math.max

/**
 * 撤销树内核（纯 Kotlin 标准库，无 Android / Compose 依赖，可独立编译单测）。
 *
 * 与「线性撤销栈」不同，本内核是一棵**分支树**：在撤销到某个历史状态后继续编辑，
 * 新内容会作为该节点的新分支保留，旧分支不会被丢弃——这正是 VSCode「撤销树」
 * 的核心语义。调用方既可用 [undo]/[redo] 走最近分支，也可用 [jumpTo] 跳到任意
 * 历史节点，还可用 [history] 拿到全部分支快照用于绘制撤销树 UI。
 *
 * 设计约束：
 *  - 每个节点保存**整份内容快照**（编辑器文本量可控，换取实现简单与语义确定）；
 *  - 所有公开方法失败安全：非法参数返回 null / false，绝不抛异常；
 *  - 容量有上限，超出后从最老的「非当前路径叶子」裁剪，避免无限增长；
 *  - 保持三条不变量（无 host root / 官方源 / 仅 arm64）：本类不涉及任何系统能力。
 */
class UndoTree(initialContent: String = "", private val maxNodes: Int = DEFAULT_MAX_NODES) {

    /** 历史节点：一个文档状态快照。 */
    data class Node(
        val id: Long,
        val content: String,
        val parentId: Long?,
        val children: MutableList<Long> = mutableListOf(),
        val createdAtMs: Long,
        /** 节点来源标记：如 "edit" / "type" / "completion"，供 UI 区分。 */
        val label: String = "edit"
    )

    /** 供 UI 展示的轻量快照（不含可变 children 引用）。 */
    data class Snapshot(
        val id: Long,
        val content: String,
        val parentId: Long?,
        val childIds: List<Long>,
        val label: String,
        val isCurrent: Boolean
    )

    private data class BranchPath(val ids: List<Long>, val merge: Boolean)

    private val nodes = LinkedHashMap<Long, Node>()
    private val lastVisitedChild = HashMap<Long, Long>()
    private var currentId: Long = ROOT_ID
    private var nextId: Long = ROOT_ID + 1

    // 连续输入合并状态
    private var lastTypeKey: String? = null
    private var lastTypeAtMs: Long = 0L
    private var lastTypedNodeId: Long = -1L

    init {
        val cap = if (maxNodes < MIN_NODES) MIN_NODES else maxNodes
        nodes[ROOT_ID] = Node(
            id = ROOT_ID,
            content = initialContent,
            parentId = null,
            createdAtMs = System.currentTimeMillis(),
            label = "origin"
        )
    }

    /** 当前内容。 */
    fun current(): String = nodes[currentId]?.content ?: ""

    /** 当前节点 id。 */
    fun currentId(): Long = currentId

    /** 是否有可撤销的父节点。 */
    fun canUndo(): Boolean = nodes[currentId]?.parentId != null

    /** 是否有可重做的子节点。 */
    fun canRedo(): Boolean = nodes[currentId]?.children?.isNotEmpty() == true

    /** 当前节点的子节点数（分叉数）。 */
    fun branchCount(): Int = nodes[currentId]?.children?.size ?: 0

    /** 历史节点总数。 */
    fun nodeCount(): Int = nodes.size

    /**
     * 记录一次编辑快照。
     *  - 内容与当前相同：幂等，直接返回当前 id；
     *  - 当前节点的某个子节点内容相同：视为重放到该分支，移动到它（不新建）；
     *  - 否则：新建当前节点的子节点（若当前已有子节点则自然形成新分支），成为新的当前节点。
     * @return 新当前节点 id；异常输入返回 -1。
     */
    fun record(content: String, label: String = "edit"): Long {
        val cur = nodes[currentId] ?: return -1L
        if (cur.content == content) return currentId
        val existingChild = cur.children.firstOrNull { nodes[it]?.content == content }
        if (existingChild != null) {
            moveTo(existingChild)
            return currentId
        }
        val id = nextId++
        nodes[id] = Node(
            id = id,
            content = content,
            parentId = cur.id,
            createdAtMs = System.currentTimeMillis(),
            label = if (label.isEmpty()) "edit" else label
        )
        cur.children.add(id)
        lastVisitedChild[cur.id] = id
        currentId = id
        enforceCapacity()
        // 记录一次 edit 会打断「连续输入合并」序列
        lastTypeKey = null
        lastTypedNodeId = -1L
        return id
    }

    /**
     * 记录一次「连续输入」：相同 [key] 且在 [windowMs] 时间窗内的后续输入合并到同一节点，
     * 使一次连续打字只产生一个撤销步骤（对标编辑器 coalescing）。
     * 合并仅在「当前节点无子节点（是叶子）」时发生；若当前节点已分叉，则强制新建分支。
     * @return 当前节点 id；异常输入返回 -1。
     */
    fun recordTyped(
        content: String,
        key: String = "type",
        windowMs: Long = 800L,
        label: String = "type"
    ): Long {
        val cur = nodes[currentId] ?: return -1L
        val now = System.currentTimeMillis()
        val canMerge = key.isNotEmpty() &&
            key == lastTypeKey &&
            lastTypedNodeId == currentId &&
            cur.children.isEmpty() &&
            now - lastTypeAtMs >= 0 &&
            now - lastTypeAtMs <= max(0L, windowMs)
        if (canMerge) {
            nodes[currentId] = cur.copy(content = content)
            lastTypeAtMs = now
            return currentId
        }
        // record() 内部会重置合并状态，故在它之后再写入本次合并键
        val id = record(content, label)
        if (id >= 0L) {
            lastTypeKey = key
            lastTypeAtMs = now
            lastTypedNodeId = currentId
        }
        return id
    }

    /** 撤销：移动到父节点并返回其内容；无父节点返回 null。 */
    fun undo(): String? {
        val cur = nodes[currentId] ?: return null
        val parentId = cur.parentId ?: return null
        if (!nodes.containsKey(parentId)) return null
        // 记住从哪个子节点离开，redo 时回到同一分支
        lastVisitedChild[parentId] = cur.id
        return moveTo(parentId)
    }

    /** 重做：移动到最近访问的子节点（无则第一个）并返回其内容；无子节点返回 null。 */
    fun redo(): String? {
        val cur = nodes[currentId] ?: return null
        if (cur.children.isEmpty()) return null
        val target = lastVisitedChild[cur.id]
            ?.takeIf { cur.children.contains(it) && nodes.containsKey(it) }
            ?: cur.children.firstOrNull { nodes.containsKey(it) }
            ?: return null
        return moveTo(target)
    }

    /** 跳到任意历史节点并返回其内容；id 非法返回 null。 */
    fun jumpTo(id: Long): String? {
        if (!nodes.containsKey(id)) return null
        // 记录路径上每个父节点的最后访问分支，保证 jump 后 redo 语义连贯
        var node = nodes[id]
        var child = id
        while (node?.parentId != null) {
            lastVisitedChild[node.parentId!!] = child
            child = node.parentId!!
            node = nodes[child]
        }
        return moveTo(id)
    }

    /** 全部历史快照（按节点创建顺序），供 UI 绘制撤销树。 */
    fun history(): List<Snapshot> = nodes.values.map { n ->
        Snapshot(
            id = n.id,
            content = n.content,
            parentId = n.parentId,
            childIds = n.children.toList(),
            label = n.label,
            isCurrent = n.id == currentId
        )
    }

    /** 从当前节点到根的路径（根在前），供高亮当前分支。 */
    fun pathToRoot(): List<Long> {
        val path = ArrayList<Long>()
        var id: Long? = currentId
        val guard = nodes.size + 1
        var i = 0
        while (id != null && nodes.containsKey(id) && i < guard) {
            path.add(id)
            id = nodes[id]?.parentId
            i++
        }
        return path.asReversed()
    }

    /** 重置整棵树到单个根节点。 */
    fun clear(content: String = "") {
        nodes.clear()
        lastVisitedChild.clear()
        currentId = ROOT_ID
        nextId = ROOT_ID + 1
        nodes[ROOT_ID] = Node(
            id = ROOT_ID,
            content = content,
            parentId = null,
            createdAtMs = System.currentTimeMillis(),
            label = "origin"
        )
        lastTypeKey = null
        lastTypeAtMs = 0L
        lastTypedNodeId = -1L
    }

    private fun moveTo(id: Long): String? {
        val node = nodes[id] ?: return null
        currentId = id
        // moveTo 打断连续输入合并：位置已改变，不应再合并到新位置的节点
        lastTypeKey = null
        lastTypedNodeId = -1L
        return node.content
    }

    /** 容量裁剪：优先删最老的非当前路径叶子；纯线性链超限时把最老祖先提升为新根。 */
    private fun enforceCapacity() {
        val limit = maxOf(MIN_NODES, maxNodes)
        while (nodes.size > limit) {
            // 当前节点到根的路径（受保护：删除会破坏 current 的连通性）
            val protected = HashSet<Long>()
            var id: Long? = currentId
            var guard = 0
            while (id != null && nodes.containsKey(id) && guard <= nodes.size) {
                protected.add(id)
                id = nodes[id]?.parentId
                guard++
            }
            // 1) 优先删除最老的非当前路径叶子（LinkedHashMap 保序）
            val victim = nodes.values.firstOrNull { n ->
                n.id !in protected && n.children.isEmpty()
            }
            if (victim != null) {
                victim.parentId?.let { nodes[it]?.children?.remove(victim.id) }
                nodes.remove(victim.id)
                lastVisitedChild.remove(victim.id)
                continue
            }
            // 2) 纯线性链：整条路径都受保护，把最老祖先（根）摘除，其子节点升为新根
            val path = pathToRoot() // 根在前
            if (path.size <= MIN_NODES) break
            val oldRoot = path[0]
            val newRootId = path[1]
            nodes[oldRoot]?.children?.remove(newRootId)
            nodes.remove(oldRoot)
            lastVisitedChild.remove(oldRoot)
            val nr = nodes[newRootId] ?: break
            nodes[newRootId] = nr.copy(parentId = null)
        }
    }

    companion object {
        const val ROOT_ID: Long = 0L
        const val DEFAULT_MAX_NODES: Int = 500
        const val MIN_NODES: Int = 2
    }
}

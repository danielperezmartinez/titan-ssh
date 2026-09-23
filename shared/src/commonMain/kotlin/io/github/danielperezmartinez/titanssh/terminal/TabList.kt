package io.github.danielperezmartinez.titanssh.terminal

/**
 * Pure, immutable ordering model for the open terminal tabs: which tabs exist,
 * in what order, and which one is active. It holds only tab ids so the
 * open/switch/close/reorder logic can be unit-tested without any live session.
 * The [SessionManager] pairs it with the actual [SessionTab] runtime objects.
 */
data class TabList(
    val order: List<String> = emptyList(),
    val activeId: String? = null,
) {
    val isEmpty: Boolean get() = order.isEmpty()

    val activeIndex: Int get() = activeId?.let { order.indexOf(it) } ?: -1

    /** Appends [id] and makes it active. A duplicate id just becomes active. */
    fun add(id: String): TabList =
        if (id in order) copy(activeId = id)
        else copy(order = order + id, activeId = id)

    /** Makes [id] active if it exists; otherwise unchanged. */
    fun activate(id: String): TabList =
        if (id in order) copy(activeId = id) else this

    /**
     * Removes [id]. If it was active, the neighbour to its right becomes active
     * (or the new last tab, or `null` when none remain), which matches how tabbed
     * UIs pick the next focus on close.
     */
    fun remove(id: String): TabList {
        val index = order.indexOf(id)
        if (index < 0) return this
        val nextOrder = order.toMutableList().apply { removeAt(index) }
        val nextActive = when {
            nextOrder.isEmpty() -> null
            id != activeId -> activeId
            else -> nextOrder[index.coerceAtMost(nextOrder.size - 1)]
        }
        return TabList(nextOrder, nextActive)
    }

    /**
     * Moves the tab at [from] to index [to] (clamped), keeping the same tab
     * active. Out-of-range [from] is a no-op.
     */
    fun move(from: Int, to: Int): TabList {
        if (from !in order.indices) return this
        val target = to.coerceIn(0, order.size - 1)
        if (from == target) return this
        val next = order.toMutableList()
        val moved = next.removeAt(from)
        next.add(target, moved)
        return copy(order = next)
    }

    /** Moves [id] one position towards the front. */
    fun moveLeft(id: String): TabList {
        val index = order.indexOf(id)
        return if (index <= 0) this else move(index, index - 1)
    }

    /** Moves [id] one position towards the back. */
    fun moveRight(id: String): TabList {
        val index = order.indexOf(id)
        return if (index < 0 || index >= order.size - 1) this else move(index, index + 1)
    }
}

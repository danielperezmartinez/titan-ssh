package io.github.danielperezmartinez.titanssh.config

/** A row of a list shown in folders, as [GroupTree.rows] lays it out. */
sealed interface GroupedRow<out T> {
    /** How many folders deep the row is; 0 at the top of the list. */
    val depth: Int

    /** A group's folder; [itemCount] counts its items, subfolders included. */
    data class Folder(val group: Group, override val depth: Int, val itemCount: Int) : GroupedRow<Nothing>

    data class Item<T>(val item: T, override val depth: Int) : GroupedRow<T>
}

/**
 * The folder structure of one list's groups (the hosts' or the sessions').
 * It tolerates a damaged document: a group whose parent is missing, or that is
 * caught in a parent cycle, goes to the top; an item whose group is missing is
 * shown loose.
 */
class GroupTree(groups: List<Group>) {

    private val byId: Map<String, Group> = groups.associateBy { it.id }

    /** Each group's effective parent, `null` at the top. */
    private val parentOf: Map<String, String?> = groups.associate { it.id to effectiveParent(it) }

    /** Children of each parent (`null` = top), folders sorted by name. */
    private val childrenOf: Map<String?, List<Group>> = groups
        .groupBy { parentOf[it.id] }
        .mapValues { (_, list) -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) }

    private fun effectiveParent(group: Group): String? {
        val parent = group.parentId ?: return null
        if (parent !in byId) return null
        val seen = mutableSetOf(group.id)
        var current: String? = parent
        while (current != null) {
            if (!seen.add(current)) return null
            current = byId[current]?.parentId?.takeIf { it in byId }
        }
        return parent
    }

    fun contains(groupId: String?): Boolean = groupId != null && groupId in byId

    /** The group's name preceded by its ancestors', e.g. `cliente-x / web`. */
    fun path(groupId: String): String = ancestry(groupId).joinToString(" / ") { it.name }

    /** [groupId] and its ancestors, from the top down. */
    fun ancestry(groupId: String): List<Group> {
        val chain = mutableListOf<Group>()
        var current: String? = groupId
        while (current != null) {
            val group = byId[current] ?: break
            chain += group
            current = parentOf[current]
        }
        return chain.asReversed()
    }

    /** The ids of every group nested inside [groupId], at any depth. */
    fun descendants(groupId: String): Set<String> {
        val result = mutableSetOf<String>()
        val pending = ArrayDeque(childrenOf[groupId].orEmpty())
        while (pending.isNotEmpty()) {
            val group = pending.removeFirst()
            if (result.add(group.id)) pending += childrenOf[group.id].orEmpty()
        }
        return result
    }

    /** Every group in folder order (parents before their children). */
    fun ordered(): List<Group> {
        val result = mutableListOf<Group>()
        fun visit(parent: String?) {
            childrenOf[parent].orEmpty().forEach { result += it; visit(it.id) }
        }
        visit(null)
        return result
    }

    /** The groups [groupId] may move into: none of its own subfolders, nor itself. */
    fun moveTargets(groupId: String): List<Group> {
        val excluded = descendants(groupId) + groupId
        return ordered().filterNot { it.id in excluded }
    }

    /**
     * The rows of a list of [items] shown in folders. At each level the folders
     * come first, sorted by name, and then the loose items in their own order.
     * A collapsed folder hides what it holds. With [hideEmpty], folders without
     * any item (at any depth) are left out. Without groups this is the flat
     * list of [items].
     */
    fun <T> rows(items: List<T>, groupOf: (T) -> String?, hideEmpty: Boolean = false): List<GroupedRow<T>> {
        val itemsIn: Map<String?, List<T>> = items.groupBy { item -> groupOf(item)?.takeIf { it in byId } }
        val counts = mutableMapOf<String, Int>()
        fun count(groupId: String): Int = counts.getOrPut(groupId) {
            itemsIn[groupId].orEmpty().size + childrenOf[groupId].orEmpty().sumOf { count(it.id) }
        }

        val rows = mutableListOf<GroupedRow<T>>()
        fun visit(parent: String?, depth: Int) {
            childrenOf[parent].orEmpty().forEach { group ->
                val itemCount = count(group.id)
                if (hideEmpty && itemCount == 0) return@forEach
                rows += GroupedRow.Folder(group, depth, itemCount)
                if (!group.collapsed) visit(group.id, depth + 1)
            }
            itemsIn[parent].orEmpty().forEach { rows += GroupedRow.Item(it, depth) }
        }
        visit(null, 0)
        return rows
    }
}

package io.github.danielperezmartinez.titanssh.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Hosts and sessions groups as folders ([[Grupos de hosts y de sesiones como
 * carpetas]]): the folder layout, the controller's group operations and the
 * version 2 → 3 migration that split the shared groups.
 */
class GroupsTest {

    private fun session(id: String, groupId: String? = null) = Session(id = id, name = id, hostId = "h", groupId = groupId)

    /** A row as text: `#name(count)` for a folder, the id for an item, indented by depth. */
    private fun List<GroupedRow<Session>>.render(): List<String> = map { row ->
        "  ".repeat(row.depth) + when (row) {
            is GroupedRow.Folder -> "#${row.group.name}(${row.itemCount})"
            is GroupedRow.Item -> row.item.id
        }
    }

    // --- Folder layout --------------------------------------------------------

    @Test
    fun without_groups_the_list_is_flat_and_in_its_order() {
        val sessions = listOf(session("b"), session("a"))

        assertEquals(listOf("b", "a"), GroupTree(emptyList()).rows(sessions, { it.groupId }).render())
    }

    @Test
    fun folders_come_first_sorted_by_name_then_loose_items_at_each_level() {
        val groups = listOf(
            Group("web", "web", parentId = "cx"),
            Group("cx", "cliente-x"),
            Group("api", "Api", parentId = "cx"),
            Group("casa", "casa"),
        )
        val sessions = listOf(
            session("loose"),
            session("front", "web"),
            session("deploy", "cx"),
            session("nas", "casa"),
            session("gw", "api"),
        )

        assertEquals(
            listOf(
                "#casa(1)",
                "  nas",
                "#cliente-x(3)",
                "  #Api(1)",
                "    gw",
                "  #web(1)",
                "    front",
                "  deploy",
                "loose",
            ),
            GroupTree(groups).rows(sessions, { it.groupId }).render(),
        )
    }

    @Test
    fun a_collapsed_folder_hides_what_it_holds_but_still_counts_it() {
        val groups = listOf(Group("cx", "cliente-x", collapsed = true), Group("web", "web", parentId = "cx"))
        val sessions = listOf(session("front", "web"), session("deploy", "cx"))

        assertEquals(listOf("#cliente-x(2)"), GroupTree(groups).rows(sessions, { it.groupId }).render())
    }

    @Test
    fun empty_folders_can_be_left_out() {
        val groups = listOf(Group("cx", "cliente-x"), Group("empty", "vacío", parentId = "cx"), Group("none", "nada"))
        val sessions = listOf(session("deploy", "cx"))
        val tree = GroupTree(groups)

        assertEquals(listOf("#cliente-x(1)", "  deploy"), tree.rows(sessions, { it.groupId }, hideEmpty = true).render())
        assertEquals(
            listOf("#cliente-x(1)", "  #vacío(0)", "  deploy", "#nada(0)"),
            tree.rows(sessions, { it.groupId }).render(),
        )
    }

    @Test
    fun a_damaged_document_still_shows_everything() {
        // A missing parent, a parent cycle and an item pointing at no group.
        val groups = listOf(
            Group("orphan", "huérfano", parentId = "gone"),
            Group("a", "a", parentId = "b"),
            Group("b", "b", parentId = "a"),
        )
        val sessions = listOf(session("x", "orphan"), session("y", "gone"), session("z", "a"))

        assertEquals(
            listOf("#a(1)", "  z", "#b(0)", "#huérfano(1)", "  x", "y"),
            GroupTree(groups).rows(sessions, { it.groupId }).render(),
        )
    }

    @Test
    fun paths_order_and_move_targets() {
        val groups = listOf(Group("cx", "cliente-x"), Group("web", "web", parentId = "cx"), Group("casa", "casa"))
        val tree = GroupTree(groups)

        assertEquals("cliente-x / web", tree.path("web"))
        assertEquals(listOf("casa", "cx", "web"), tree.ordered().map { it.id })
        assertEquals(setOf("web"), tree.descendants("cx"))
        // A group cannot move into itself or into its own subfolders.
        assertEquals(listOf("casa"), tree.moveTargets("cx").map { it.id })
    }

    // --- Controller -------------------------------------------------------------

    private fun controller(cfg: TitanConfig) = ConfigController(FakeConfigStore(cfg), CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun create_rename_collapse_in_one_list_only() {
        val controller = controller(TitanConfig())

        val parent = controller.createGroup(GroupScope.SESSIONS, "  cliente-x ")
        val child = controller.createGroup(GroupScope.SESSIONS, "web", parentId = parent.id)
        controller.renameGroup(GroupScope.SESSIONS, child.id, "front")
        controller.setGroupCollapsed(GroupScope.SESSIONS, parent.id, true)

        val cfg = controller.state.value
        assertTrue(cfg.hostGroups.isEmpty())
        assertEquals(
            listOf(Group(parent.id, "cliente-x", collapsed = true), Group(child.id, "front", parentId = parent.id)),
            cfg.sessionGroups,
        )
    }

    @Test
    fun moving_a_group_into_its_own_subfolder_is_refused() {
        val groups = listOf(Group("cx", "cliente-x"), Group("web", "web", parentId = "cx"), Group("casa", "casa"))
        val controller = controller(TitanConfig(hostGroups = groups))

        assertFalse(controller.moveGroup(GroupScope.HOSTS, "cx", "web"))
        assertFalse(controller.moveGroup(GroupScope.HOSTS, "cx", "cx"))
        assertFalse(controller.moveGroup(GroupScope.HOSTS, "cx", "missing"))
        assertEquals(groups, controller.state.value.hostGroups)

        assertTrue(controller.moveGroup(GroupScope.HOSTS, "cx", "casa"))
        assertEquals("casa", controller.state.value.hostGroups.single { it.id == "cx" }.parentId)
        assertTrue(controller.moveGroup(GroupScope.HOSTS, "cx", null))
        assertNull(controller.state.value.hostGroups.single { it.id == "cx" }.parentId)
    }

    @Test
    fun deleting_a_group_moves_its_contents_up_to_its_parent() {
        val groups = listOf(
            Group("cx", "cliente-x"),
            Group("web", "web", parentId = "cx"),
            Group("front", "front", parentId = "web"),
        )
        val sessions = listOf(session("a", "web"), session("b", "front"), session("c", "cx"))
        val controller = controller(TitanConfig(sessions = sessions, sessionGroups = groups))

        controller.deleteGroup(GroupScope.SESSIONS, "web")

        var cfg = controller.state.value
        assertEquals(listOf("cx", "front"), cfg.sessionGroups.map { it.id })
        assertEquals("cx", cfg.sessionGroups.single { it.id == "front" }.parentId)
        assertEquals(listOf("cx", "front", "cx"), cfg.sessions.map { it.groupId })

        // A top-level group leaves its contents loose.
        controller.deleteGroup(GroupScope.SESSIONS, "cx")

        cfg = controller.state.value
        assertEquals(listOf(Group("front", "front")), cfg.sessionGroups)
        assertEquals(listOf(null, "front", null), cfg.sessions.map { it.groupId })
        assertEquals(3, cfg.sessions.size)
    }

    // --- Migration 2 → 3 ----------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }

    @Test
    fun the_shared_groups_split_by_what_they_hold_and_keep_their_ids() {
        val v2 = """
            {
              "version": 2,
              "hosts": [
                { "id": "h1", "alias": "a", "hostname": "a.example.net", "username": "u", "groupId": "both",
                  "auth": { "kind": "io.github.danielperezmartinez.titanssh.config.HostAuth.HardwareKey", "alias": "hw" } },
                { "id": "h2", "alias": "b", "hostname": "b.example.net", "username": "u", "groupId": "child",
                  "auth": { "kind": "io.github.danielperezmartinez.titanssh.config.HostAuth.HardwareKey", "alias": "hw" } }
              ],
              "sessions": [
                { "id": "s1", "name": "s1", "hostId": "h1", "groupId": "both" },
                { "id": "s2", "name": "s2", "hostId": "h1", "groupId": "sessionsOnly" }
              ],
              "groups": [
                { "id": "both", "name": "compartido" },
                { "id": "sessionsOnly", "name": "solo sesiones" },
                { "id": "parent", "name": "padre" },
                { "id": "child", "name": "hijo", "parentId": "parent" },
                { "id": "unused", "name": "sin usar" }
              ]
            }
        """.trimIndent()

        val document = ConfigMigration.migrate(json.parseToJsonElement(v2).jsonObject)
        val cfg = json.decodeFromJsonElement(TitanConfig.serializer(), document)

        assertEquals(3, cfg.version)
        assertTrue("groups" !in document)
        // A group held through a subgroup comes along with it.
        assertEquals(listOf("both", "parent", "child", "unused"), cfg.hostGroups.map { it.id })
        assertEquals(listOf("both", "sessionsOnly", "unused"), cfg.sessionGroups.map { it.id })
        assertEquals("parent", cfg.hostGroups.single { it.id == "child" }.parentId)
        // No host or session changes group.
        assertEquals(listOf("both", "child"), cfg.hosts.map { it.groupId })
        assertEquals(listOf("both", "sessionsOnly"), cfg.sessions.map { it.groupId })
    }
}

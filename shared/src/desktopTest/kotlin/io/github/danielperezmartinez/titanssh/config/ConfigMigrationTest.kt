package io.github.danielperezmartinez.titanssh.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The version 1 → 2 migration (ADR-0013): snippets become the script library,
 * and a session script inserted from a snippet becomes a reference only when
 * that does not change what runs.
 */
class ConfigMigrationTest {

    /** A version-1 document as the previous app wrote it (encodeDefaults = true). */
    private val v1 = """
        {
          "version": 1,
          "hosts": [{ "id": "h1", "alias": "casa", "hostname": "casa.example.net", "port": 22,
                      "username": "user", "auth": { "kind": "io.github.danielperezmartinez.titanssh.config.HostAuth.HardwareKey", "keyType": "ECDSA_P256", "alias": "hw" } }],
          "sessions": [{
            "id": "s1", "name": "deploy", "hostId": "h1",
            "scripts": [
              { "id": "same", "label": "mi reinicio", "enabled": true, "phase": "ON_DEMAND",
                "body": "sudo systemctl restart app", "snippetId": "sn1",
                "behavior": { "silent": false, "waitForCompletion": true, "timeoutSeconds": null,
                              "onFailure": "CONTINUE", "delaySeconds": null, "expectPattern": null },
                "reconnectBehavior": "RERUN_ALL", "envVars": {}, "secretRefs": [] },
              { "id": "edited", "label": "editado", "phase": "POST_INIT",
                "body": "sudo systemctl restart app --now", "snippetId": "sn1" },
              { "id": "tuned", "label": "con timeout", "phase": "POST_INIT",
                "body": "sudo systemctl restart app", "snippetId": "sn1",
                "behavior": { "timeoutSeconds": 5 } },
              { "id": "gone", "label": "de uno borrado", "body": "echo hi", "snippetId": "deleted" },
              { "id": "own", "label": "propio", "body": "cd /srv" }
            ]
          }],
          "groups": [],
          "snippets": [{ "id": "sn1", "name": "restart", "body": "sudo systemctl restart app", "tags": ["ops"] }]
        }
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }

    private fun migrated(): TitanConfig {
        val document = ConfigMigration.migrate(json.parseToJsonElement(v1).jsonObject)
        return json.decodeFromJsonElement(TitanConfig.serializer(), document)
    }

    @Test
    fun snippets_become_library_scripts_with_the_same_id() {
        val cfg = migrated()

        assertEquals(2, cfg.version)
        assertEquals(listOf(LibraryScript(id = "sn1", name = "restart", body = "sudo systemctl restart app", tags = listOf("ops"))), cfg.scripts)
    }

    @Test
    fun only_identical_copies_become_references() {
        val scripts = migrated().sessions.single().scripts.associateBy { it.id }

        assertEquals("sn1", scripts.getValue("same").libraryScriptId)
        // Edited body, non-default behavior, or a snippet that no longer exists:
        // they stay the session's own scripts.
        assertNull(scripts.getValue("edited").libraryScriptId)
        assertNull(scripts.getValue("tuned").libraryScriptId)
        assertNull(scripts.getValue("gone").libraryScriptId)
        assertNull(scripts.getValue("own").libraryScriptId)
    }

    @Test
    fun migration_does_not_change_what_runs() {
        val cfg = migrated()
        val runs = cfg.resolve(cfg.sessions.single()).session.scripts

        assertEquals(
            listOf(
                "same" to "sudo systemctl restart app",
                "edited" to "sudo systemctl restart app --now",
                "tuned" to "sudo systemctl restart app",
                "gone" to "echo hi",
                "own" to "cd /srv",
            ),
            runs.map { it.id to it.body },
        )
        assertEquals(5, runs.single { it.id == "tuned" }.behavior.timeoutSeconds)
        assertEquals(ScriptPhase.ON_DEMAND, runs.single { it.id == "same" }.phase)
    }

    @Test
    fun a_current_document_is_left_as_is() {
        val document = json.parseToJsonElement("""{ "version": 2, "scripts": [{ "id": "l", "name": "n" }] }""").jsonObject

        assertEquals(document, ConfigMigration.migrate(document))
    }

    @Test
    fun the_store_migrates_on_load_and_keeps_a_backup_of_the_old_file() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "titan-migration-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(dir, JsonFileConfigStore.CONFIG_FILE).writeText(v1)
            val store = JsonFileConfigStore(dir)

            val loaded = store.load()
            store.save(loaded)

            assertEquals(migrated(), loaded)
            assertEquals(v1, File(dir, "${JsonFileConfigStore.CONFIG_FILE}.v1.bak").readText())
            val saved = File(dir, JsonFileConfigStore.CONFIG_FILE).readText()
            assertTrue(saved.contains("\"version\": 2"))
            assertTrue(!saved.contains("snippet"), "no legacy fields are written back: $saved")
            assertEquals(loaded, JsonFileConfigStore(dir).load())
        } finally {
            dir.deleteRecursively()
        }
    }
}

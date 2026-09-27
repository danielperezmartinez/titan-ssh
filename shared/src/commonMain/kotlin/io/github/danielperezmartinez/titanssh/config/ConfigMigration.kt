package io.github.danielperezmartinez.titanssh.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Upgrades a persisted config document to [TitanConfig.CURRENT_VERSION] before it
 * is decoded. It works on the raw JSON, so the model carries no legacy fields.
 * A migration never changes what a session runs.
 */
object ConfigMigration {

    private val json = Json { ignoreUnknownKeys = true }

    /** The document's version; a document without one is version 1. */
    fun versionOf(document: JsonObject): Int =
        (document["version"] as? JsonPrimitive)?.intOrNull ?: 1

    /** [document] migrated step by step up to [TitanConfig.CURRENT_VERSION]. */
    fun migrate(document: JsonObject): JsonObject {
        var current = document
        if (versionOf(current) < 2) current = v1ToV2(current)
        return current
    }

    /**
     * 1 → 2 (ADR-0013): each snippet becomes a library script with the same id.
     * A session script inserted from a snippet becomes a reference only when the
     * library script would run exactly the same thing (same body, no env vars,
     * no secrets, default behavior); otherwise it stays the session's own
     * script. Either way `snippetId` goes away.
     */
    private fun v1ToV2(document: JsonObject): JsonObject {
        val snippets = document["snippets"]?.jsonArray.orEmpty().map { it.jsonObject }
        val bodies = snippets.associate { it.string("id") to it.string("body") }

        fun migrateScript(script: JsonObject): JsonObject {
            val snippetId = script.string("snippetId")
            val linked = snippetId != null &&
                bodies.containsKey(snippetId) &&
                (script.string("body") ?: "") == (bodies[snippetId] ?: "") &&
                script.isEmptyOrAbsent("envVars") &&
                script.isEmptyOrAbsent("secretRefs") &&
                script.hasDefaultBehavior()
            val fields = script.toMutableMap()
            fields.remove("snippetId")
            if (linked) fields["libraryScriptId"] = JsonPrimitive(snippetId)
            return JsonObject(fields)
        }

        val sessions = document["sessions"]?.jsonArray.orEmpty().map { element ->
            val session = element.jsonObject
            val scripts = session["scripts"]?.jsonArray ?: return@map session
            JsonObject(session + ("scripts" to JsonArray(scripts.map { migrateScript(it.jsonObject) })))
        }

        val fields = document.toMutableMap()
        fields.remove("snippets")
        fields["scripts"] = JsonArray(snippets)
        fields["sessions"] = JsonArray(sessions)
        fields["version"] = JsonPrimitive(2)
        return JsonObject(fields)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.isEmptyOrAbsent(key: String): Boolean =
        when (val value = this[key]) {
            null -> true
            is JsonArray -> value.isEmpty()
            is JsonObject -> value.isEmpty()
            else -> value is JsonPrimitive && value.contentOrNull == null
        }

    private fun JsonObject.hasDefaultBehavior(): Boolean {
        val behavior: JsonElement = this["behavior"] ?: return true
        return runCatching {
            json.decodeFromJsonElement(ScriptBehavior.serializer(), behavior) == ScriptBehavior()
        }.getOrDefault(false)
    }
}

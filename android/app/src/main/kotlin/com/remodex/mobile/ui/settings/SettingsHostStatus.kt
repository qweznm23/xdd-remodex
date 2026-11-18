package com.remodex.mobile.ui.settings

import com.remodex.mobile.core.model.JSONValue

/** Only explicit, non-secret fields from the bridge's account/status/read response. */
internal data class SettingsHostStatus(
    val status: String?,
    val email: String?,
    val plan: String?,
    val authMethod: String?,
    val bridgeVersion: String?,
    val hostPlatform: String?,
    val transport: String?,
    val authFile: SettingsAuthFileInfo? = null,
) {
    companion object {
        fun decode(result: JSONValue?): SettingsHostStatus {
            val fields = result?.objectValue ?: throw IllegalArgumentException("account/status/read: expected an object")
            fun text(key: String): String? = when (val value = fields[key]) {
                null, JSONValue.Null -> null
                is JSONValue.Str -> value.value.trim().takeIf { it.isNotEmpty() }
                else -> throw IllegalArgumentException("account/status/read: $key must be a string or null")
            }
            return SettingsHostStatus(text("status"), text("email"), text("planType"), text("authMethod"),
                text("bridgeVersion"), text("hostPlatform"), text("codexTransportMode"),
                SettingsAuthFileInfo.decode(fields["authFile"]))
        }
    }
}

internal data class SettingsAuthFileInfo(
    val status: String, val storage: String?, val path: String?, val resolvedPath: String?,
    val sizeBytes: Long?, val modifiedAt: java.time.Instant?, val lastRefresh: java.time.Instant?,
    val authMode: String?, val reason: String?, val errorDetails: String?,
) {
    companion object {
        fun decode(value: JSONValue?): SettingsAuthFileInfo? {
            if (value == null || value == JSONValue.Null) return null // Older bridge compatibility.
            val fields = value.objectValue ?: throw IllegalArgumentException("account/status/read: authFile must be an object")
            fun text(key: String): String? = when (val v = fields[key]) {
                null, JSONValue.Null -> null
                is JSONValue.Str -> v.value
                else -> throw IllegalArgumentException("account/status/read: authFile.$key must be a string")
            }
            fun instant(key: String) = text(key)?.let {
                try { java.time.Instant.parse(it) }
                catch (cause: java.time.format.DateTimeParseException) { throw IllegalArgumentException("account/status/read: authFile.$key is invalid", cause) }
            }
            val size = when (val v = fields["sizeBytes"]) {
                null, JSONValue.Null -> null
                is JSONValue.NumLong -> v.value.also { require(it >= 0) { "authFile.sizeBytes must be non-negative" } }
                else -> throw IllegalArgumentException("authFile.sizeBytes must be an integer")
            }
            val error = fields["error"]?.takeUnless { it == JSONValue.Null }?.let {
                val obj = it.objectValue ?: throw IllegalArgumentException("authFile.error must be an object")
                listOf("stage", "code", "type", "message").mapNotNull { key ->
                    when (val v = obj[key]) {
                        null, JSONValue.Null -> null
                        is JSONValue.Str -> "$key=${v.value}"
                        else -> throw IllegalArgumentException("authFile.error.$key must be a string")
                    }
                }.joinToString("\n")
            }
            return SettingsAuthFileInfo(text("status") ?: throw IllegalArgumentException("authFile.status is missing"),
                text("storage"), text("path"), text("resolvedPath"), size, instant("modifiedAt"), instant("lastRefresh"),
                text("authMode"), text("reason"), error)
        }
    }
}

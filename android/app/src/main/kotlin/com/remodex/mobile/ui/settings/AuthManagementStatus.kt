package com.remodex.mobile.ui.settings

import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.services.FileTransferFailure
import java.io.InputStream
import java.time.Instant

internal data class AuthManagedEntry(val profile: AuthProfile, val active: Boolean, val result: String?, val diagnostic: String?)
internal data class AuthManagementStatus(val current: AuthProfile, val entries: List<AuthManagedEntry>,
    val lastCheck: Instant?, val nextCheck: Instant?, val lastRun: Instant?,
    val activeState: String?, val activeDiagnostic: String?) {
    companion object {
        fun decode(data: Map<String, JSONValue>): AuthManagementStatus {
            require(data["managedBy"]?.stringValue == "remodex") { "stage=auth_status_decode: unknown owner" }
            val scheduler = data["scheduler"]?.objectValue.orEmpty()
            val schedule = data["schedule"]?.objectValue.orEmpty()
            val active = data["activeMaintenance"]?.objectValue.orEmpty()
            fun date(value: JSONValue?) = value?.takeUnless { it == JSONValue.Null }?.let {
                Instant.parse(it.stringValue ?: error("stage=auth_status_decode: invalid date"))
            }
            val entries = (data["profiles"]?.arrayValue ?: error("stage=auth_status_decode: missing profiles")).map { row ->
                val fields = row.objectValue ?: error("stage=auth_status_decode: invalid profile")
                val result = fields["lastMaintenance"]?.objectValue.orEmpty()
                AuthManagedEntry(AuthProfile.decode(row), fields["active"]?.boolValue
                    ?: error("stage=auth_status_decode: missing active flag"), result["status"]?.stringValue,
                    result["diagnostic"]?.takeUnless { it == JSONValue.Null }?.let { JSONValue.toJsonElement(it).toString() })
            }
            require(entries.map { it.profile.id }.distinct().size == entries.size) { "stage=auth_status_decode: duplicate slot" }
            return AuthManagementStatus(AuthProfile.decode(data["currentProfile"] ?: error("Missing current profile")), entries,
                date(scheduler["lastCheckAt"]), date(scheduler["nextCheck"] ?: schedule["nextCheck"]), date(schedule["checkedAt"]),
                active["status"]?.stringValue, (active["diagnostic"] ?: active["lastResult"]?.objectValue?.get("diagnostic"))
                    ?.takeUnless { it == JSONValue.Null }?.let { JSONValue.toJsonElement(it).toString() })
        }
    }
}

internal fun readAuthImport(input: InputStream): ByteArray {
    val bytes = ByteArray(65537)
    var size = 0
    try {
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            if (count == 0) throw FileTransferFailure("auth_import_read", "read_stalled", "Authorization input made no progress")
            size += count
        }
        if (size == 0 || size > 65536) throw FileTransferFailure("auth_import_read", "invalid_size", "Authorization must be between 1 byte and 64 KiB")
        return bytes.copyOf(size)
    } finally { bytes.fill(0) }
}

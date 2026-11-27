package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.data.CodexRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
internal data class CoreLoad(val id: Int, val percent: Int?)

@Serializable
internal data class HostCpu(val percent: Int?, val cores: List<CoreLoad>)

@Serializable
internal data class HostVolume(val directory: String, val available: Long? = null, val total: Long? = null, val error: JsonObject? = null, val used: Long? = null)

@Serializable
internal data class HostSystemInfo(
    val hostname: String, val system: String, val release: String, val arch: String, val bridgeVersion: String,
    val sampledAt: Long, val cpu: HostCpu, val memoryFree: Long, val memoryTotal: Long, val disk: HostVolume,
)

private val systemInfoJson = Json { ignoreUnknownKeys = true }

internal suspend fun CodexRepository.readHostSystemInfo(cwd: String?): HostSystemInfo {
    val params = cwd?.let { JSONValue.Obj(mapOf("cwd" to JSONValue.Str(it))) }
    val response = sendRequest("remodex/host/systemInfo", params)
    response.error?.let { throw CodexServiceError.RpcFailure(it) }
    val value = response.result ?: throw CodexServiceError.InvalidResponse("Missing host system information")
    return systemInfoJson.decodeFromJsonElement(JSONValue.toJsonElement(value))
}

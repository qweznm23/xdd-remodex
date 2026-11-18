package com.remodex.mobile.core.model

/** Live server state. Missing/unknown status and notLoaded are not evidence of completion. */
enum class ThreadRuntimeStatus {
    Active, Idle, SystemError, NotLoaded;

    companion object {
        fun decode(value: JSONValue?): ThreadRuntimeStatus? = when (value?.objectValue?.get("type")?.stringValue) {
            "active" -> Active
            "idle" -> Idle
            "systemError" -> SystemError
            "notLoaded" -> NotLoaded
            else -> null
        }
    }
}

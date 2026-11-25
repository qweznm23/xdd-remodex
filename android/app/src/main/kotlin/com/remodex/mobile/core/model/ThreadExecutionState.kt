package com.remodex.mobile.core.model

/** Owner-reported fields. Null environments means unavailable, an empty list means none. */
data class ThreadExecutionState(
    val cwd: String? = null,
    val environments: List<String>? = null,
    val primaryEnvironmentDirectory: String? = null,
    val profile: String? = null,
    val sandbox: String? = null,
    val approval: String? = null,
) {
    val preset: CodexAccessMode? get() = when {
        profile != null && !profile.startsWith(":") -> null
        sandbox == "dangerFullAccess" && approval == "never" -> CodexAccessMode.fullAccess
        sandbox == "workspaceWrite" && approval in listOf("on-request", "onRequest") -> CodexAccessMode.onRequest
        else -> null
    }

    fun merge(owner: Map<String, JSONValue>): ThreadExecutionState {
        val fields = owner["remodexExecution"]?.objectValue.orEmpty() + owner
        val policyKey = if ("sandboxPolicy" in fields) "sandboxPolicy" else "sandbox"
        val policy = fields[policyKey]
        val approvals = fields["approvalPolicy"]
        return copy(
            cwd = if ("cwd" in fields) fields["cwd"]?.stringValue else cwd,
            environments = if ("environments" in fields) fields["environments"]?.arrayValue?.map {
                it.objectValue?.get("environmentId")?.stringValue.orEmpty()
            } else environments,
            primaryEnvironmentDirectory = if ("environments" in fields) {
                fields["environments"]?.arrayValue?.firstOrNull()?.objectValue?.get("cwd")?.stringValue
            } else primaryEnvironmentDirectory,
            profile = if ("activePermissionProfile" in fields) fields["activePermissionProfile"]?.objectValue?.get("id")?.stringValue else profile,
            sandbox = if (policyKey in fields) policy?.objectValue?.get("type")?.stringValue ?: policy?.stringValue else sandbox,
            approval = if ("approvalPolicy" in fields) approvals?.stringValue ?: approvals?.objectValue?.keys?.singleOrNull() else approval,
        )
    }
}

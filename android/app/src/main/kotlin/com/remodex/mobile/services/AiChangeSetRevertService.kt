package com.remodex.mobile.services

import com.remodex.mobile.core.model.AIChangeSet
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RevertApplyResult
import com.remodex.mobile.core.model.WorkspaceCheckpointRestoreApplyResult
import com.remodex.mobile.data.CodexRepository

class AiChangeSetRevertService(
    private val repository: CodexRepository,
) {
    suspend fun applyTracked(change: AIChangeSet, cwd: String, ledger: com.remodex.mobile.core.persistence.AIChangeSetPersistence,
        device: String?): RevertApplyResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Persist the claim before the host can mutate files. An interrupted or ambiguous attempt requires inspection.
        ledger.update(device) { rows ->
            check(rows.any { it == change && it.status == com.remodex.mobile.core.model.AIChangeSetStatus.ready }) {
                "Change set has changed. Refresh before attempting a revert."
            }
            rows.map { if (it.id == change.id) it.copy(status = com.remodex.mobile.core.model.AIChangeSetStatus.collecting) else it }
        }
        val result = try {
            apply(change, cwd)
        } catch (failure: Exception) {
            // The host might already have applied the revert before the connection failed.
            try {
                ledger.update(device) { rows ->
                    val failed = rows.map { row ->
                        if (row.id == change.id) row.copy(status = com.remodex.mobile.core.model.AIChangeSetStatus.failed) else row
                    }
                    com.remodex.mobile.core.model.TurnUsageSheetLogic.recordChangeSetRevertError(
                        failed, change.id, "workspace revert: ${failure.stackTraceToString()}", java.time.Instant.now())
                }
            } catch (recording: Exception) {
                failure.addSuppressed(recording)
            }
            throw failure
        }
        ledger.update(device) { rows ->
            if (result.success) com.remodex.mobile.core.model.TurnUsageSheetLogic.markChangeSetReverted(rows, change.id, java.time.Instant.now())
            else {
                val message = (result.unsupportedReasons + result.conflicts.map { it.message }).joinToString("\n").ifBlank { "Revert rejected" }
                com.remodex.mobile.core.model.TurnUsageSheetLogic.recordChangeSetRevertError(
                    rows.map { if (it.id == change.id) it.copy(status = com.remodex.mobile.core.model.AIChangeSetStatus.ready) else it },
                    change.id, message, java.time.Instant.now())
            }
        }
        result
    }

    suspend fun previewSinglePatch(change: AIChangeSet, cwd: String): com.remodex.mobile.core.model.RevertPreviewResult {
        require(change.forwardUnifiedPatch.isNotBlank() && cwd.isNotBlank())
        val params = JSONValue.Obj(mapOf("cwd" to JSONValue.Str(cwd), "forwardPatch" to JSONValue.Str(change.forwardUnifiedPatch)))
        val result = repository.sendRequest("workspace/revertPatchPreview", params).result?.objectValue
            ?: error("workspace/revertPatchPreview missing result")
        check(result["canRevert"]?.boolValue != null) { "workspace/revertPatchPreview missing canRevert" }
        return com.remodex.mobile.core.model.RevertPreviewResult.fromJson(result)
    }

    suspend fun apply(
        changeSet: AIChangeSet,
        workingDirectory: String,
    ): RevertApplyResult {
        val checkpointRef = changeSet.workspaceCheckpoint?.restoreCheckpointRef
        if (!checkpointRef.isNullOrBlank()) {
            return applyWorkspaceCheckpointRestore(changeSet, workingDirectory, checkpointRef)
        }

        if (changeSet.source == com.remodex.mobile.core.model.AIChangeSetSource.fileChangeFallback && changeSet.fallbackPatchBatches.isNotEmpty()) {
            val params = JSONValue.Obj(mapOf("cwd" to JSONValue.Str(workingDirectory), "patches" to JSONValue.Arr(
                changeSet.fallbackPatchBatches.asReversed().map { batch ->
                    JSONValue.Obj(mapOf("id" to JSONValue.Str(batch.id), "forwardPatch" to JSONValue.Str(batch.forwardUnifiedPatch)))
                })))
            val preview = repository.sendRequest("workspace/revertPatchBatchPreview", params).result?.objectValue
                ?: error("Invalid workspace/revertPatchBatchPreview response")
            if (preview["canRevert"]?.boolValue != true) {
                return RevertApplyResult.fromJson(preview + ("success" to JSONValue.Bool(false)))
            }
            val applied = repository.sendRequest("workspace/revertPatchBatchApply", params).result?.objectValue
                ?: error("Invalid workspace/revertPatchBatchApply response")
            return RevertApplyResult.fromJson(applied)
        }
        val patch = changeSet.forwardUnifiedPatch.trim()
        require(patch.isNotEmpty()) {
            "This response cannot be auto-reverted because no exact patch was captured."
        }
        val response =
            repository.sendRequest(
                method = "workspace/revertPatchApply",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "cwd" to JSONValue.Str(workingDirectory),
                            "forwardPatch" to JSONValue.Str(changeSet.forwardUnifiedPatch),
                        ),
                    ),
            )
        val result =
            response.result?.objectValue
                ?: error("Invalid response from bridge.")
        return RevertApplyResult.fromJson(result)
    }

    private suspend fun applyWorkspaceCheckpointRestore(
        changeSet: AIChangeSet,
        workingDirectory: String,
        checkpointRef: String,
    ): RevertApplyResult {
        val params = linkedMapOf<String, JSONValue>(
            "cwd" to JSONValue.Str(workingDirectory),
            "threadId" to JSONValue.Str(changeSet.threadId),
            "targetCheckpointRef" to JSONValue.Str(checkpointRef),
            "confirmDestructiveRestore" to JSONValue.Bool(true),
        )
        changeSet.turnId.trim().takeIf { it.isNotEmpty() }?.let { params["targetTurnId"] = JSONValue.Str(it) }
        changeSet.workspaceCheckpoint?.restoreExpectedCommit
            ?.let { params["expectedTargetCommit"] = JSONValue.Str(it) }

        val response =
            repository.sendRequest(
                method = "workspace/checkpointRestoreApply",
                params = JSONValue.Obj(params),
            )
        val result =
            response.result?.objectValue
                ?: error("Invalid response from bridge.")
        val restored =
            WorkspaceCheckpointRestoreApplyResult.fromJson(result)
        return RevertApplyResult(
            success = restored.success,
            revertedFiles = restored.restoredFiles,
            conflicts = restored.conflicts,
            unsupportedReasons = restored.unsupportedReasons,
            stagedFiles = restored.stagedFiles,
            status = restored.status,
        )
    }
}


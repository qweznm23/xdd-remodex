package com.remodex.mobile.services

import com.remodex.mobile.core.model.toRpcObject
import com.remodex.mobile.core.model.CodexModelOption
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.CodexReasoningEffortOption
import com.remodex.mobile.core.persistence.MacScopedSessionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadRuntimeSelectionTest {
    private val models = listOf(
        CodexModelOption("sol-id", "gpt-6.1-sol", "Sol", "", true, emptyList(), null),
        CodexModelOption("astra-id", "gpt-6-astra", "Astra", "", false, emptyList(), null),
    )
    private val threads = listOf(CodexThread("a", model = "gpt-6-astra"), CodexThread("b", model = "gpt-6.1-sol"))
    private fun selection() = ThreadRuntimeSelection(ThreadModelSelection("sol-id", "medium"))

    @Test fun openingDesktopThreadUsesItsModelBeforeAndAfterModelListArrives() {
        val state = selection()
        assertEquals("gpt-6-astra", state.resolve("a", threads, emptyList()).modelId)
        assertEquals("gpt-6-astra", state.resolve("a", threads, models).modelId)
    }

    @Test fun historyRefreshUpdatesActiveConversationWithoutBorrowingAnotherThreadSelection() {
        val state = selection()
        assertEquals("gpt-6.1-sol", state.resolve("a", emptyList(), models).modelId)
        assertEquals("gpt-6-astra", state.resolve("a", threads, models).modelId)
        assertEquals("gpt-6.1-sol", state.resolve("b", threads, models).modelId)
    }

    @Test fun explicitChoiceSurvivesStaleHistoryAndSwitchingAwayAndBack() {
        val state = selection()
        state.select("a", ThreadModelSelection("sol-id", "high"))
        assertEquals("gpt-6.1-sol", state.resolve("a", threads, models).modelId)
        state.select("b", ThreadModelSelection("astra-id", "low"))
        assertEquals("gpt-6.1-sol", state.resolve("a", threads, models).modelId)
        assertEquals("high", state.resolve("a", threads, models).reasoningEffort)
    }

    @Test fun unknownThreadAndExplicitModelsAreNeverReplacedByAccountDefault() {
        val state = selection()
        assertEquals("custom-model", state.resolve("a", listOf(CodexThread("a", model = "custom-model")), models).modelId)
        state.select("a", ThreadModelSelection("custom-choice", "ultra"))
        assertEquals("custom-choice", state.resolve("a", threads, models).modelId)
        assertEquals("ultra", state.resolve("a", threads, models).reasoningEffort)
    }

    @Test fun deviceScopeResetDropsConversationOverrides() {
        val state = selection()
        state.select("a", ThreadModelSelection("custom-choice", "ultra"))
        state.reset(ThreadModelSelection("sol-id", null))
        assertEquals("gpt-6-astra", state.resolve("a", threads, models).modelId)
    }

    @Test fun draftRetainsExplicitDefaultWhenConversationMetadataArrives() {
        val state = selection()
        state.select(null, ThreadModelSelection("custom-draft", "high"))
        state.resolve("a", threads, models)
        assertEquals("custom-draft", state.resolve(null, threads, models).modelId)
    }
    @Test fun desktopReasoningSurvivesDecodeCacheAndMissingFieldsWithoutInheritingDraftEffort() {
        val desktop = CodexThread.fromJsonObject(Json.parseToJsonElement(
            """{"id":"a","model":"gpt-6-astra","reasoningEffort":"high"}""",
        ).jsonObject)
        val cached = MacScopedSessionStore.decodeCachedThreadSnapshot(
            MacScopedSessionStore.encodeCachedThreadSnapshot(listOf(desktop)),
        ).single()
        val merged = CodexThread("a").withMissingDisplayFieldsFrom(cached)
        val options = models.map { model -> model.copy(
            supportedReasoningEfforts = listOf(CodexReasoningEffortOption("medium", ""), CodexReasoningEffortOption("high", "")),
            defaultReasoningEffort = "medium",
        ) }
        assertEquals("high", selection().resolve("a", listOf(merged), options).reasoningEffort)
        assertEquals("medium", selection().resolve("b", threads, options).reasoningEffort)
    }

    @Test fun confirmedOwnerEffortSurvivesAnOlderCatalogAndAPartialSpeedUpdate() {
        val state = selection()
        val choices = models.map { it.copy(supportedReasoningEfforts = listOf(CodexReasoningEffortOption("high", "")),
            defaultReasoningEffort = "high") }
        fun update(json: String) = Json.parseToJsonElement(json).jsonObject.toRpcObject()
        val first = update("""{"epoch":"owner","revision":1,"updatedAt":1,"model":"gpt-6-astra","reasoningEffort":"ultra"}""")
        val staleRow = ThreadModelSelection("sol-id", "high")
        state.confirm("a", staleRow, first)
        state.confirm("a", staleRow, update("""{"epoch":"owner","revision":2,"updatedAt":2,"serviceTier":"priority"}"""))
        state.confirm("a", staleRow, first)
        assertEquals(ThreadModelSelection("gpt-6-astra","ultra"), state.resolve("a", threads, choices))
        assertEquals(true, state.fastTier("a"))
        assertEquals(false, state.fastTier("b"))
        state.confirm("a", staleRow, update("""{"epoch":"owner","revision":3,"updatedAt":3,"reasoningEffort":null}"""))
        assertEquals(null, state.resolve("a", threads, choices).reasoningEffort)
    }

    @Test fun knownModelIdsResolveToWireModelNamesAndCompatibleEffort() {
        val state = selection()
        state.select("a", ThreadModelSelection("astra-id", "ultra"))
        val options = models.map { it.copy(
            supportedReasoningEfforts = listOf(CodexReasoningEffortOption("high", "")),
            defaultReasoningEffort = "high",
        ) }
        val outgoing = state.resolve("a", threads, options)
        assertEquals("gpt-6-astra", outgoing.modelId)
        assertEquals("high", outgoing.reasoningEffort)
    }

}

package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.CodexAccessMode
import com.remodex.mobile.core.model.CodexModelOption
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeModelControlsTest {
    @Test fun sliderUsesOnlySupportedLevelsAndPreservesUnfamiliarProviderOrder() {
        fun levels(vararg ids: String): List<String> {
            val options = ids.map { TurnComposerRuntimeOption(it, it) }
            return sliderEfforts(TurnComposerRuntimeSelectorState(options.first(), options)).map { it.id }
        }
        assertEquals(listOf("low", "mid", "high", "extra-high"),
            levels("high", TURN_COMPOSER_RUNTIME_AUTO_ID, "extra-high", "low", "mid", "mid"))
        assertEquals(listOf("custom-fast", "low", "custom-deep"), levels("custom-fast", "low", "custom-deep"))
        assertEquals(emptyList<String>(), levels(TURN_COMPOSER_RUNTIME_AUTO_ID))
    }

    @Test fun unknownConversationModelIsDisplayedInsteadOfAccountDefault() {
        val state = buildRuntimeControlsState(
            models = listOf(CodexModelOption("default", "gpt-6.1-sol", "GPT Sol", "", true, emptyList(), null)),
            isLoadingModels = false,
            selectedModelId = "custom-current-model",
            selectedReasoningEffort = "high",
            selectedAccessMode = CodexAccessMode.entries.first(),
            selectedServiceTier = null,
            loadingLabel = "Loading", modelFallbackLabel = "Model", noModelsLabel = "None",
            autoLabel = "Auto", normalTierLabel = "Normal",
            reasoningEffortTitles = ReasoningEffortTitleStrings("Low", "Medium", "High", "Extra high"),
        )
        assertEquals("custom-current-model", state.model.selected.id)
        assertEquals("custom-current-model", state.model.selected.label)
        assertEquals("high", state.reasoningEffort.selected.id)
        // A historical selection remains visible on the chip, but cannot become
        // an invented selectable entry in the server-backed model menu.
        assertEquals(listOf("default"), composerModelPopupOptions(state.model).map { it.id })
    }

    @Test fun popupShowsAllServerModelsWithTheirNamesAndOrder() {
        val names = listOf(
            "gpt-6.1-sol" to "GPT-6.1-Sol", "gpt-6-astra" to "GPT-6-Astra",
            "gpt-6.1-terra" to "GPT-6.1 Terra", "gpt-6-sol" to "GPT-6-Sol",
            "gpt-6-luna" to "GPT-6-Luna", "gpt-5.6-sol" to "GPT-5.6-Sol",
            "gpt-5.6-terra" to "GPT-5.6-Terra", "gpt-5.6-luna" to "GPT-5.6-Luna",
            "provider/new-model" to "New provider model",
        )
        val models = names.mapIndexed { i, (id, label) ->
            CodexModelOption(id, id, label, "Server description $i", i == 0, emptyList(), null)
        }
        val state = buildRuntimeControlsState(
            models = models, isLoadingModels = false, selectedModelId = names[2].first,
            selectedReasoningEffort = null, selectedAccessMode = CodexAccessMode.entries.first(),
            selectedServiceTier = null, loadingLabel = "Loading", modelFallbackLabel = "Model",
            noModelsLabel = "None", autoLabel = "Auto", normalTierLabel = "Normal",
            reasoningEffortTitles = ReasoningEffortTitleStrings("Low", "Medium", "High", "Extra high"),
        )
        val options = composerModelPopupOptions(state.model)
        assertEquals(names, options.map { it.id to it.label })
        org.junit.Assert.assertTrue(options.all { it.enabled })
        assertEquals(models.map { it.description }, options.map { it.detail })
        assertEquals(names[2].first, state.model.selected.id)
    }

    @Test fun disabledOrLoadingSelectorDoesNotOfferStaleSelectableModels() {
        val selected = TurnComposerRuntimeOption("server-model", "Server model")
        val state = TurnComposerRuntimeSelectorState(selected, listOf(selected), enabled = false)
        org.junit.Assert.assertFalse(composerModelPopupOptions(state).single().enabled)
    }
}

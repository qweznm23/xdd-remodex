package com.remodex.mobile.ui.turn

import androidx.compose.runtime.*
import com.remodex.mobile.core.model.AIChangeSet
import com.remodex.mobile.core.model.TurnUsageSheetLogic
import com.remodex.mobile.core.persistence.AIChangeSetPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun threadChangeLedger(store: AIChangeSetPersistence, device: String?, thread: String, limit: Int = 8): State<List<AIChangeSet>> =
    key(store, device, thread, limit) {
        produceState<List<AIChangeSet>>(emptyList()) {
            store.revision.collect {
                value = withContext(Dispatchers.IO) { TurnUsageSheetLogic.recentChangeSetsForThread(thread, store.load(device), limit) }
            }
        }
    }

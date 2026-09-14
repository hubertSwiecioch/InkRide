package com.speedevand.inkride.dashboard.presentation

import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.presentation.UiText

data class DestinationSearchState(
    val query: String = "",
    val results: List<PlaceResult> = emptyList(),
    val isSearching: Boolean = false,
    val isRouting: Boolean = false,
)

sealed interface DestinationSearchAction {
    data class OnQueryChanged(
        val query: String,
    ) : DestinationSearchAction

    data class OnResultSelected(
        val result: PlaceResult,
    ) : DestinationSearchAction
}

sealed interface DestinationSearchEvent {
    data class ShowError(
        val message: UiText,
    ) : DestinationSearchEvent

    data object NavigateBackToDashboard : DestinationSearchEvent
}

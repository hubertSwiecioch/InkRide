package com.speedevand.inkride.dashboard.presentation

/**
 * Stable identifiers for the destination-search screen. Result rows are keyed
 * by index because two places can share a display name.
 */
object DestinationSearchTestTags {
    const val QUERY_FIELD = "destination_search_query"
    const val RESULT_LIST = "destination_search_results"
    const val EMPTY_STATE = "destination_search_empty"
    const val SEARCH_PROGRESS = "destination_search_progress"
    const val ROUTING_PROGRESS = "destination_search_routing_progress"
    const val BACK_BUTTON = "destination_search_back"

    fun resultRow(index: Int): String = "destination_search_result_$index"
}

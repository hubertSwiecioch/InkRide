package com.speedevand.inkride.history.presentation

/**
 * Stable identifiers for the ride-history list. Rows are keyed by ride id
 * because every row renders the same delete icon and the same value layout;
 * a text selector could not tell two rides apart.
 */
object HistoryTestTags {
    const val LIST = "history_list"
    const val EMPTY_STATE = "history_empty"
    const val DELETE_ALL_BUTTON = "history_delete_all"
    const val LIFETIME_STATS_BUTTON = "history_lifetime_stats"
    const val CONFIRM_DELETE_ALL_DIALOG = "history_confirm_delete_all"
    const val CONFIRM_DELETE_ALL_ACCEPT = "history_confirm_delete_all_accept"
    const val CONFIRM_DELETE_ALL_CANCEL = "history_confirm_delete_all_cancel"

    fun row(id: Long): String = "history_row_$id"

    fun rowDelete(id: Long): String = "history_row_delete_$id"

    fun rowDate(id: Long): String = "history_row_date_$id"

    /**
     * Distance, moving time and average speed share one `TextMMD` in the row,
     * so they share one tag; assertions match on its parts.
     */
    fun rowMetrics(id: Long): String = "history_row_metrics_$id"
}

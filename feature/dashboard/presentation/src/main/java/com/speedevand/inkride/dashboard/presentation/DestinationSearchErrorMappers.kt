package com.speedevand.inkride.dashboard.presentation

import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.presentation.UiText

fun PlaceSearchError.toUiText(): UiText =
    when (this) {
        PlaceSearchError.NETWORK_FAILED -> UiText.StringResource(R.string.destination_search_error_network)
        PlaceSearchError.NO_RESULTS -> UiText.StringResource(R.string.destination_search_error_no_results)
    }

fun LocationError.toUiText(): UiText =
    when (this) {
        LocationError.PERMISSION_DENIED -> UiText.StringResource(R.string.destination_search_error_location_permission)
        LocationError.TIMED_OUT -> UiText.StringResource(R.string.destination_search_error_location_timeout)
        LocationError.PROVIDER_UNAVAILABLE -> UiText.StringResource(R.string.destination_search_error_location_unavailable)
    }

fun RoutingError.toUiText(): UiText =
    when (this) {
        RoutingError.NETWORK_FAILED -> UiText.StringResource(R.string.destination_search_error_network)
        RoutingError.NO_ROUTE_FOUND -> UiText.StringResource(R.string.destination_search_error_no_route)
    }

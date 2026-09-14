package com.speedevand.inkride.dashboard.presentation

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.presentation.UiText
import org.junit.jupiter.api.Test

class DestinationSearchErrorMappersTest {
    @Test
    fun `PlaceSearchError NETWORK_FAILED maps to correct string resource`() {
        val result = PlaceSearchError.NETWORK_FAILED.toUiText()
        assertThat(result).isEqualTo(UiText.StringResource(R.string.destination_search_error_network))
    }

    @Test
    fun `PlaceSearchError NO_RESULTS maps to correct string resource`() {
        val result = PlaceSearchError.NO_RESULTS.toUiText()
        assertThat(result).isEqualTo(UiText.StringResource(R.string.destination_search_error_no_results))
    }

    @Test
    fun `LocationError PERMISSION_DENIED maps to correct string resource`() {
        val result = LocationError.PERMISSION_DENIED.toUiText()
        assertThat(result).isEqualTo(UiText.StringResource(R.string.destination_search_error_location_permission))
    }

    @Test
    fun `LocationError TIMED_OUT maps to correct string resource`() {
        val result = LocationError.TIMED_OUT.toUiText()
        assertThat(result).isEqualTo(UiText.StringResource(R.string.destination_search_error_location_timeout))
    }

    @Test
    fun `LocationError PROVIDER_UNAVAILABLE maps to correct string resource`() {
        val result = LocationError.PROVIDER_UNAVAILABLE.toUiText()
        assertThat(result).isEqualTo(UiText.StringResource(R.string.destination_search_error_location_unavailable))
    }

    @Test
    fun `RoutingError NETWORK_FAILED maps to correct string resource`() {
        val result = RoutingError.NETWORK_FAILED.toUiText()
        assertThat(result).isEqualTo(UiText.StringResource(R.string.destination_search_error_network))
    }

    @Test
    fun `RoutingError NO_ROUTE_FOUND maps to correct string resource`() {
        val result = RoutingError.NO_ROUTE_FOUND.toUiText()
        assertThat(result).isEqualTo(UiText.StringResource(R.string.destination_search_error_no_route))
    }
}

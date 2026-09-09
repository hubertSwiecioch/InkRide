package com.speedevand.inkride.dashboard.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.bottom_sheet.ModalBottomSheetMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.domain.tracking.TurnDirection
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.dashboard.presentation.model.RouteProgressUi

/**
 * Compact, read-only route readout: the live next-turn / off-route line plus the
 * loaded route's name. Renders nothing when no route is active. Loading and
 * clearing a route are driven from the top bar ([DashboardTopBar]) so the
 * dashboard body stays free of bulky buttons. Static text only — no map view on
 * the dashboard (E-Ink refresh cost).
 */
@Composable
fun RouteStatus(
    route: RouteProgressUi?,
    modifier: Modifier = Modifier,
) {
    if (route == null) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_TINY),
    ) {
        RoutePrimaryLine(route)
        route.routeName?.let { name ->
            TextMMD(
                text = stringResource(R.string.dashboard_route_following, name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * Confirmation sheet shown before clearing an active route — clearing is
 * irreversible from the dashboard (the GPX has to be re-picked from disk), so
 * it gets the same confirm-before-destructive-action treatment as deleting a
 * ride in history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClearRouteConfirmationSheet(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalBottomSheetMMD(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(DesignConstants.PADDING_LARGE),
            verticalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_MEDIUM),
        ) {
            TextMMD(
                text = stringResource(R.string.dashboard_route_clear_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            TextMMD(
                text = stringResource(R.string.dashboard_route_clear_message),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_MEDIUM),
            ) {
                OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    TextMMD(text = stringResource(R.string.dashboard_goal_cancel))
                }
                ButtonMMD(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                    TextMMD(text = stringResource(R.string.dashboard_route_clear))
                }
            }
        }
    }
}

@Composable
private fun RoutePrimaryLine(route: RouteProgressUi) {
    when {
        route.offRoute -> {
            TextMMD(
                text = stringResource(R.string.dashboard_route_off, route.offRouteDistance),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        route.nextTurn != null -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_TINY),
            ) {
                route.nextTurnDirection?.let { direction ->
                    TurnDirectionIcon(direction)
                }
                TextMMD(
                    text =
                        route.nextTurnName?.let { name ->
                            stringResource(R.string.dashboard_route_next_turn_named, route.nextTurn, name)
                        } ?: stringResource(R.string.dashboard_route_next_turn, route.nextTurn),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(DashboardTestTags.ROUTE_NEXT_TURN_TEXT),
                )
            }
        }

        else -> {
            TextMMD(
                text = stringResource(R.string.dashboard_route_active),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Direction arrow for the next turn, derived from the route's geometry (not the
 * waypoint's name — see [TurnDirection]). No dedicated MMD icon exists for
 * turn-by-turn direction, so this uses the same raw [Icon] + Material Icons
 * pattern already used throughout the app (e.g. [DashboardTopBar]).
 */
@Composable
private fun TurnDirectionIcon(direction: TurnDirection) {
    val (icon, description) =
        when (direction) {
            TurnDirection.LEFT -> Icons.Filled.TurnLeft to R.string.dashboard_route_turn_left
            TurnDirection.RIGHT -> Icons.Filled.TurnRight to R.string.dashboard_route_turn_right
            TurnDirection.STRAIGHT -> Icons.Filled.Straight to R.string.dashboard_route_turn_straight
        }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(description),
        tint = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .size(20.dp)
                .testTag(DashboardTestTags.ROUTE_NEXT_TURN_ICON),
    )
}

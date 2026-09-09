package com.speedevand.inkride.dashboard.presentation

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.core.presentation.ObserveAsEvents
import com.speedevand.inkride.core.presentation.verticalScrollbar
import org.koin.androidx.compose.koinViewModel

@Composable
fun DestinationSearchRoot(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: DestinationSearchViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is DestinationSearchEvent.ShowError -> {
                Toast.makeText(context, event.message.asString(context), Toast.LENGTH_LONG).show()
            }

            DestinationSearchEvent.NavigateBackToDashboard -> {
                onNavigateBack()
            }
        }
    }

    DestinationSearchScreen(
        state = state,
        onAction = viewModel::onAction,
        onNavigateBack = onNavigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationSearchScreen(
    state: DestinationSearchState,
    onAction: (DestinationSearchAction) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.destination_search_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.destination_search_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(DesignConstants.PADDING_MEDIUM),
            verticalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_MEDIUM),
        ) {
            TextFieldMMD(
                value = state.query,
                onValueChange = { onAction(DestinationSearchAction.OnQueryChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { TextMMD(text = stringResource(R.string.destination_search_hint)) },
            )

            when {
                state.isSearching -> {
                    TextMMD(
                        text = stringResource(R.string.destination_search_searching),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                state.isRouting -> {
                    TextMMD(
                        text = stringResource(R.string.destination_search_routing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                state.query.length >= 3 && state.results.isEmpty() -> {
                    TextMMD(
                        text = stringResource(R.string.destination_search_error_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            val scrollState = rememberScrollState()
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .verticalScrollbar(scrollState),
                verticalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_SMALL),
            ) {
                state.results.forEach { result ->
                    DestinationResultRow(
                        result = result,
                        onClick = { onAction(DestinationSearchAction.OnResultSelected(result)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationResultRow(
    result: PlaceResult,
    onClick: () -> Unit,
) {
    TextMMD(
        text = result.displayName,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = DesignConstants.PADDING_SMALL),
    )
}

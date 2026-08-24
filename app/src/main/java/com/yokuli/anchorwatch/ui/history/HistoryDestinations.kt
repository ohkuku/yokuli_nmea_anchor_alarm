package com.yokuli.anchorwatch

import androidx.compose.runtime.Composable

/** Explicit product destinations keep Anchor sessions, the anchorage library,
 * and Trip recordings from drifting back into one ambiguous History screen. */
@Composable internal fun AnchorHistoryPage(state:MainUiState,vm:MainViewModel)=HistoryPage(state,vm,fixedTab=0)
@Composable internal fun AnchorageLibraryPage(state:MainUiState,vm:MainViewModel)=HistoryPage(state,vm,fixedTab=1)
@Composable internal fun TripHistoryPage(state:MainUiState,vm:MainViewModel)=HistoryPage(state,vm,fixedTab=2)

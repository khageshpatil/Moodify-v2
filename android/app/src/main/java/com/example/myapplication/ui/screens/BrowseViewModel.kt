package com.moodify.app.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.navigation.toRoute
import com.moodify.app.AppGraph
import com.moodify.app.data.CatalogPage
import com.moodify.app.data.mergeCatalogPages
import com.moodify.app.ui.navigation.BrowseKind
import com.moodify.app.ui.navigation.BrowseRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val browseId: String = "",
    val title: String,
    val kind: BrowseKind,
    val page: CatalogPage? = null,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
)

/**
 * One instance per album/artist/playlist page on the back stack, so pages can
 * stack (artist → album → artist) and each keeps its own state on Back.
 */
@UnstableApi
class BrowseViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val route = savedStateHandle.toRoute<BrowseRoute>()
    private val kind = BrowseKind.of(route.browseId, route.pageType)
    private val repository = AppGraph.repository

    private val _state = MutableStateFlow(BrowseUiState(browseId = route.browseId, title = route.title, kind = kind))
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val page = repository.browseCatalog(
                    browseId = route.browseId,
                    params = route.params,
                    // Same surface names the catalog parser has always received.
                    surface = if (kind == BrowseKind.OTHER) "browse" else kind.name.lowercase(),
                    title = route.title.ifBlank { kind.label },
                )
                _state.update { it.copy(page = page, isLoading = false, title = page.title.ifBlank { it.title }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message?.takeIf(String::isNotBlank) ?: "Couldn't open this page.")
                }
            }
        }
    }

    fun loadMore() {
        val current = _state.value.page ?: return
        val cursor = current.nextCursor ?: return
        if (_state.value.isLoadingMore) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            try {
                val next = repository.browseCatalog(
                    browseId = route.browseId,
                    params = route.params,
                    surface = current.surface,
                    title = current.title,
                    continuation = cursor,
                )
                _state.update { it.copy(page = mergeCatalogPages(current, next), isLoadingMore = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoadingMore = false) }
            }
        }
    }
}

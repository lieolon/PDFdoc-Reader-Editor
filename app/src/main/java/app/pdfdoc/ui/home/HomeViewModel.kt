package app.pdfdoc.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pdfdoc.data.settings.SettingsRepository
import app.pdfdoc.domain.model.PdfDocument
import app.pdfdoc.domain.model.SortOrder
import app.pdfdoc.domain.repo.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val documents: List<PdfDocument> = emptyList(),
    val hasAnyDocuments: Boolean = false,
    val query: String = "",
    val sortOrder: SortOrder = SortOrder.DATE,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<HomeUiState> =
        combine(documents.observeRecent(), query, settings.sortOrder) { all, q, sort ->
            HomeUiState(
                documents = filterAndSort(all, q, sort),
                hasAnyDocuments = all.isNotEmpty(),
                query = q,
                sortOrder = sort,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onSortChange(order: SortOrder) {
        viewModelScope.launch { settings.setSortOrder(order) }
    }

    fun remove(id: Long) {
        viewModelScope.launch { documents.remove(id) }
    }
}

internal fun filterAndSort(all: List<PdfDocument>, query: String, sort: SortOrder): List<PdfDocument> {
    val q = query.trim()
    val filtered = if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }
    return when (sort) {
        SortOrder.DATE -> filtered.sortedByDescending { it.lastOpenedAt }
        SortOrder.NAME -> filtered.sortedBy { it.name.lowercase() }
        SortOrder.SIZE -> filtered.sortedByDescending { it.sizeBytes }
    }
}

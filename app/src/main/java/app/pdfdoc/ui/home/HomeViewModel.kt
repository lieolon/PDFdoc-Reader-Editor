package app.pdfdoc.ui.home

import android.net.Uri
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which documents the list shows: all, favorites, or one folder. */
sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object Favorites : LibraryFilter
    data class Folder(val id: Long) : LibraryFilter
    /** Files hidden behind the screen lock; shown only after unlocking. */
    data object Secret : LibraryFilter
}

data class HomeUiState(
    val documents: List<PdfDocument> = emptyList(),
    val hasAnyDocuments: Boolean = false,
    val query: String = "",
    val sortOrder: SortOrder = SortOrder.DATE,
    val filter: LibraryFilter = LibraryFilter.All,
    val folders: List<app.pdfdoc.data.db.CollectionEntity> = emptyList(),
    /** Folder ids of each document. */
    val membership: Map<Long, Set<Long>> = emptyMap(),
    /** Documents found by their content, with a snippet. */
    val textHits: List<Pair<PdfDocument, String>> = emptyList(),
    val secretCount: Int = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val settings: SettingsRepository,
    private val library: app.pdfdoc.data.db.LibraryDao,
    private val index: app.pdfdoc.data.library.LibraryIndex,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow<LibraryFilter>(LibraryFilter.All)

    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val textHits = query.debounce(300).mapLatest { q -> runCatching { index.search(q) }.getOrDefault(emptyList()) }

    private val membership = library.observeMemberships().map { list ->
        list.groupBy({ it.documentId }, { it.collectionId }).mapValues { it.value.toSet() }
    }

    private data class Inputs(val q: String, val sort: SortOrder, val filter: LibraryFilter)

    val state: StateFlow<HomeUiState> =
        combine(
            documents.observeRecent(),
            combine(query, settings.sortOrder, filter, ::Inputs),
            library.observeCollections(),
            membership,
            textHits,
        ) { all, inputs, folders, members, hits ->
            val open = all.filterNot { it.isSecret }
            val scoped = when (val f = inputs.filter) {
                LibraryFilter.All -> open
                LibraryFilter.Favorites -> open.filter { it.isFavorite }
                is LibraryFilter.Folder -> open.filter { f.id in members[it.id].orEmpty() }
                LibraryFilter.Secret -> all.filter { it.isSecret }
            }
            val byName = filterAndSort(scoped, inputs.q, inputs.sort)
            val byId = scoped.associateBy { it.id }
            HomeUiState(
                documents = byName,
                hasAnyDocuments = all.isNotEmpty(),
                secretCount = all.count { it.isSecret },
                query = inputs.q,
                sortOrder = inputs.sort,
                filter = inputs.filter,
                folders = folders,
                membership = members,
                textHits = hits.mapNotNull { h -> byId[h.documentId]?.takeIf { d -> byName.none { it.id == d.id } }?.let { it to h.snippet } },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setFilter(f: LibraryFilter) {
        filter.value = f
    }

    fun setSecret(doc: PdfDocument, secret: Boolean) {
        viewModelScope.launch { library.setSecret(doc.id, secret) }
    }

    fun toggleFavorite(doc: PdfDocument) {
        viewModelScope.launch { library.setFavorite(doc.id, !doc.isFavorite) }
    }

    fun createFolder(name: String, thenAdd: Long? = null) {
        viewModelScope.launch {
            val id = library.insertCollection(app.pdfdoc.data.db.CollectionEntity(name = name.trim()))
            thenAdd?.let { library.addToCollection(app.pdfdoc.data.db.DocumentCollectionEntity(it, id)) }
        }
    }

    fun renameFolder(id: Long, name: String) {
        viewModelScope.launch { library.renameCollection(id, name.trim()) }
    }

    fun deleteFolder(id: Long) {
        viewModelScope.launch {
            library.deleteCollection(id)
            if ((filter.value as? LibraryFilter.Folder)?.id == id) filter.value = LibraryFilter.All
        }
    }

    fun setInFolder(documentId: Long, folderId: Long, member: Boolean) {
        viewModelScope.launch {
            if (member) library.addToCollection(app.pdfdoc.data.db.DocumentCollectionEntity(documentId, folderId))
            else library.removeFromCollection(documentId, folderId)
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onSortChange(order: SortOrder) {
        viewModelScope.launch { settings.setSortOrder(order) }
    }

    /** Keeps access to a PDF picked for the tools screen and lists it in recent files. */
    fun prepareForTools(uri: Uri, then: (Uri) -> Unit) {
        viewModelScope.launch {
            runCatching { documents.recordOpened(uri, null) }
            then(uri)
        }
    }

    /** Keeps access to the PDFs picked for questions, then hands them on. */
    fun prepareForAsk(uris: List<Uri>, then: (List<Uri>) -> Unit) {
        viewModelScope.launch {
            uris.forEach { runCatching { documents.recordOpened(it, null) } }
            then(uris)
        }
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

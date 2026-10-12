package com.bennybar.mypapers.feature.documents

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bennybar.mypapers.ai.ReadAllWorker
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.DOCUMENT_TYPES
import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.ImportResult
import com.bennybar.mypapers.search.Filters
import com.bennybar.mypapers.search.FuzzySearch
import java.time.LocalDate
import com.bennybar.mypapers.ui.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DocumentsViewModel : ViewModel() {
    val query = MutableStateFlow("")
    val selectedTags = MutableStateFlow(emptySet<String>())
    val selectedTypes = MutableStateFlow(emptySet<String>())

    /** Papers picked with a long press (selection mode while not empty). */
    val selection = MutableStateFlow(emptySet<String>())

    /** Deleted, waiting out the Undo: hidden from the list until it's done or undone. */
    private val hidden = MutableStateFlow(emptySet<String>())

    /** The filter sheet's choices. */
    val filters = MutableStateFlow(Filters())

    private val all = app.repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Without the papers waiting out an Undo. Null while the database is first read. */
    private val visible = combine(all, hidden) { docs, gone -> docs?.filter { it.id !in gone } }

    private val filtered = combine(visible, selectedTypes, filters) { docs, types, f ->
        val today = LocalDate.now()
        docs?.filter { (types.isEmpty() || it.docType in types) && f.matches(it, today) }
    }

    val results: StateFlow<List<Document>?> = combine(filtered, query, selectedTags, filters) { docs, q, tags, f ->
        docs?.let { f.sorted(FuzzySearch.search(it, q, tags, Format.locale)) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Every tag in use, most used first, for the filter chips. */
    val tags: StateFlow<List<String>> = visible.map { docs ->
        docs.orEmpty().flatMap { it.tags }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The document types in use, in the usual order, for the filter chips. */
    val types: StateFlow<List<String>> = visible.map { docs ->
        val used = docs.orEmpty().map { it.docType }.toSet()
        DOCUMENT_TYPES.filter { it in used }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Vendors in use, most papers first, for the filter sheet. */
    val vendors: StateFlow<List<String>> = visible.map { docs ->
        docs.orEmpty().map { it.vendor }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Papers the AI hasn't read yet (and nobody filled in). */
    val unread = combine(app.repository.observeUnread(), hidden) { docs, gone -> docs.count { it.id !in gone } }
    val ai = app.ai.config

    val syncState = app.sync.state
    val session = app.server.session
    val gmailFound = app.gmail.observeNewCount()

    fun toggleTag(tag: String) = selectedTags.update { if (tag in it) it - tag else it + tag }
    fun toggleType(type: String) = selectedTypes.update { if (type in it) it - type else it + type }

    fun readUnread() = app.repository.readUnread()

    fun clearFilters() {
        filters.value = Filters()
        selectedTags.value = emptySet()
        selectedTypes.value = emptySet()
    }

    fun toggleSelected(id: String) = selection.update { if (id in it) it - id else it + id }
    fun clearSelection() { selection.value = emptySet() }
    fun selectAll(ids: Collection<String>) { selection.value = ids.toSet() }

    /** Hides these papers; returns the ones that weren't hidden already (a delete in progress is ignored). */
    fun hide(ids: Collection<String>): Set<String> {
        val new = ids.toSet() - hidden.value
        hidden.update { it + new }
        return new
    }
    /** After the Undo window: deletes (unless undone) in the app's scope, so leaving the screen can't stop it. */
    fun finishDelete(ids: Collection<String>, undone: Boolean) {
        app.scope.launch {
            if (!undone) ids.forEach { app.repository.delete(it) }
            hidden.update { it - ids.toSet() }
        }
    }

    suspend fun addTag(ids: Collection<String>, tag: String) = app.repository.addTag(ids, tag)
    suspend fun setType(ids: Collection<String>, type: String) = app.repository.setType(ids, type)
    suspend fun read(ids: Collection<String>) = ids.forEach { app.repository.requestReading(it) }

    /** The read-all job's (done, total) while it runs. */
    val readingProgress = ReadAllWorker.observeProgress(app)

    suspend fun import(uri: Uri, name: String? = null): ImportResult = app.repository.import(uri, name)

    suspend fun refresh() = app.sync.sync()
}

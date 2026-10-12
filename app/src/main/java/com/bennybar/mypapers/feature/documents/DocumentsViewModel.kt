package com.bennybar.mypapers.feature.documents

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.ImportResult
import com.bennybar.mypapers.search.FuzzySearch
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

class DocumentsViewModel : ViewModel() {
    val query = MutableStateFlow("")
    val selectedTags = MutableStateFlow(emptySet<String>())

    private val all = app.repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Null while the database is first read. */
    val results: StateFlow<List<Document>?> = combine(all, query, selectedTags) { docs, q, tags ->
        docs?.let { FuzzySearch.search(it, q, tags, Format.locale) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Every tag in use, most used first, for the filter chips. */
    val tags: StateFlow<List<String>> = all.map { docs ->
        docs.orEmpty().flatMap { it.tags }.groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncState = app.sync.state
    val session = app.server.session
    val gmailFound = app.gmail.observeNewCount()

    fun toggleTag(tag: String) = selectedTags.update { if (tag in it) it - tag else it + tag }

    suspend fun import(uri: Uri, name: String? = null): ImportResult = app.repository.import(uri, name)

    suspend fun refresh() = app.sync.sync()
}

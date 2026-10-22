package com.bennybar.mypapers.ai

import android.content.Context
import com.bennybar.mypapers.data.KeyDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Changes to a paper the AI proposes ("set the title to …", "add tag Car"); null/empty: leave it. */
@Serializable
data class PaperChanges(
    val title: String? = null,
    val vendor: String? = null,
    val docType: String? = null,
    /** yyyy-MM-dd. */
    val date: String? = null,
    val total: Double? = null,
    val currency: String? = null,
    val addTags: List<String> = emptyList(),
    val removeTags: List<String> = emptyList(),
    /** Labels of key details to remove. */
    val removeDetails: List<String> = emptyList(),
) {
    val isEmpty get() = listOf(title, vendor, docType, date, total, currency).all { it == null } &&
        addTags.isEmpty() && removeTags.isEmpty() && removeDetails.isEmpty()
}

/**
 * One question or instruction about a paper and what came back: a [reply], key [details] found
 * (saved only on request) and proposed [changes] (applied only on request). Pending while all are null.
 */
@Serializable
data class PaperTurn(
    val question: String,
    val reply: String? = null,
    val details: List<KeyDetail>? = null,
    val changes: PaperChanges? = null,
    val error: String? = null,
    val saved: Boolean = false,
    val applied: Boolean = false,
) {
    val done get() = reply != null || error != null
}

/** One answer in a conversation about all the papers; pending while [answer] and [error] are null. */
@Serializable
data class AllTurn(
    val question: String,
    val answer: String? = null,
    val paperIds: List<String> = emptyList(),
    val totals: Map<String, Long>? = null,
    val error: String? = null,
) {
    val done get() = answer != null || error != null
}

/**
 * Ask AI conversations: one per paper, and one about all the papers. The background jobs answer
 * their last turn; kept until closed, so an answer that came while the app was away is still there.
 */
class AiAnswers(context: Context) {
    private val prefs = context.getSharedPreferences("ai_threads", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _papers = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith(PAPER) }.mapNotNull { (k, v) ->
            runCatching { k.removePrefix(PAPER) to json.decodeFromString<List<PaperTurn>>(v as String) }.getOrNull()
        }.toMap(),
    )
    /** By paper id. */
    val papers: StateFlow<Map<String, List<PaperTurn>>> = _papers

    private val _all = MutableStateFlow(prefs.getString(ALL, null)?.let { runCatching { json.decodeFromString<List<AllTurn>>(it) }.getOrNull() })
    val all: StateFlow<List<AllTurn>?> = _all

    /** [turns] null or empty: the conversation is closed. */
    fun setPaper(id: String, turns: List<PaperTurn>?) {
        val t = turns?.takeIf { it.isNotEmpty() }
        prefs.edit().apply { if (t == null) remove(PAPER + id) else putString(PAPER + id, json.encodeToString(t)) }.apply()
        _papers.value = if (t == null) _papers.value - id else _papers.value + (id to t)
    }

    fun setAll(turns: List<AllTurn>?) {
        val t = turns?.takeIf { it.isNotEmpty() }
        prefs.edit().apply { if (t == null) remove(ALL) else putString(ALL, json.encodeToString(t)) }.apply()
        _all.value = t
    }

    private companion object {
        const val PAPER = "paper:"
        const val ALL = "all"
    }
}

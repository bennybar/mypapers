package com.bennybar.mypapers.ai

import android.content.Context
import com.bennybar.mypapers.data.KeyDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An Ask AI question about one paper; [details] null while it's asked, [error] when it failed. */
@Serializable
data class PaperAnswer(val question: String, val details: List<KeyDetail>? = null, val error: String? = null)

/** A question about all the papers; [answer] null while it's asked. Papers by id, totals per currency in cents. */
@Serializable
data class AllAnswer(
    val question: String,
    val answer: String? = null,
    val paperIds: List<String> = emptyList(),
    val totals: Map<String, Long>? = null,
    val error: String? = null,
)

/**
 * Answers the background jobs came back with, kept until they're seen (and closed), so one that
 * finished while the app was away is still there to read.
 */
class AiAnswers(context: Context) {
    private val prefs = context.getSharedPreferences("ai_answers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _papers = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith(PAPER) }.mapNotNull { (k, v) ->
            runCatching { k.removePrefix(PAPER) to json.decodeFromString<PaperAnswer>(v as String) }.getOrNull()
        }.toMap(),
    )
    /** By paper id. */
    val papers: StateFlow<Map<String, PaperAnswer>> = _papers

    private val _all = MutableStateFlow(prefs.getString(ALL, null)?.let { runCatching { json.decodeFromString<AllAnswer>(it) }.getOrNull() })
    val all: StateFlow<AllAnswer?> = _all

    fun setPaper(id: String, answer: PaperAnswer?) {
        prefs.edit().apply { if (answer == null) remove(PAPER + id) else putString(PAPER + id, json.encodeToString(answer)) }.apply()
        _papers.value = if (answer == null) _papers.value - id else _papers.value + (id to answer)
    }

    fun setAll(answer: AllAnswer?) {
        prefs.edit().apply { if (answer == null) remove(ALL) else putString(ALL, json.encodeToString(answer)) }.apply()
        _all.value = answer
    }

    private companion object {
        const val PAPER = "paper:"
        const val ALL = "all"
    }
}

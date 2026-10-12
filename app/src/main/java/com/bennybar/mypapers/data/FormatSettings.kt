package com.bennybar.mypapers.data

import android.content.Context
import com.bennybar.mypapers.ui.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Which region's date and number formats to use; "" = the phone's (the default). */
class FormatSettings(context: Context) {
    private val prefs = context.getSharedPreferences("formats", Context.MODE_PRIVATE)

    private val _region = MutableStateFlow(prefs.getString("region", "")!!)
    val region: StateFlow<String> = _region

    init { apply() }

    fun save(tag: String) {
        prefs.edit().putString("region", tag).apply()
        _region.value = tag
        apply()
    }

    /** Also when the phone's language changes (the activity is recreated then). */
    fun apply() {
        Format.locale = _region.value.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()
    }

    companion object {
        /** The regions offered besides the phone's own. */
        val REGIONS = listOf("he-IL", "en-IL", "en-US", "en-GB", "de-DE", "fr-FR", "es-ES", "ru-RU")
    }
}

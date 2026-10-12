package com.bennybar.mypapers.nav

import kotlinx.serialization.Serializable

/** Every destination (type-safe Navigation Compose routes). */
sealed interface Route {
    @Serializable data object Documents : Route
    @Serializable data class Document(val id: String) : Route
    @Serializable data class EditDocument(val id: String) : Route
    @Serializable data object Settings : Route
    @Serializable data object Gmail : Route
    @Serializable data object Trash : Route
}

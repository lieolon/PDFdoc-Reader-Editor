package app.pdfdoc.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data class ViewerRoute(val uri: String)

@Serializable
data class PagesRoute(val uri: String)

@Serializable
data object MergeRoute

@Serializable
data object SettingsRoute

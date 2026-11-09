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

@Serializable
data class EditorRoute(val uri: String)

@Serializable
data class FormRoute(val uri: String)

/** [fromImages]: build a PDF from gallery images instead of starting with the camera. */
@Serializable
data class ScanRoute(val fromImages: Boolean = false, val images: List<String> = emptyList())

@Serializable
data class ToolsRoute(val uri: String)

@Serializable
data class CompareRoute(val old: String, val new: String)

/** Reflowed text of a document, for comfortable reading on a phone. */
@Serializable
data class ReaderRoute(val uri: String, val page: Int = 0)

/** Full-screen slide show of a document. */
@Serializable
data class PresentRoute(val uri: String, val page: Int = 0)

/** Converts a text, office, e-book or image file to PDF before opening it in the viewer. */
@Serializable
data class ImportRoute(val uri: String)

@Serializable
data object BatchRoute

/** Creates a PDF from a text, Markdown or HTML file. */
@Serializable
data object TextToPdfRoute

/** Summary of and questions about the documents [uris], answered from their text. */
@Serializable
data class AskRoute(val uris: List<String>)

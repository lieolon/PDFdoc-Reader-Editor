package app.pdfdoc.data.pdf

import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.Pt
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination

sealed interface LinkTarget {
    data class Page(val index: Int) : LinkTarget
    data class Web(val uri: String) : LinkTarget
}

/** A clickable area of a page, in page display space. */
data class PageLink(val box: Box, val target: LinkTarget)

object PdfLinks {

    fun read(doc: PDDocument, pageIndex: Int): List<PageLink> {
        val page = doc.getPage(pageIndex)
        val geometry = PageGeometry.of(page)
        return runCatching { page.annotations }.getOrDefault(emptyList())
            .filterIsInstance<PDAnnotationLink>()
            .mapNotNull { link ->
                val rect = link.rectangle ?: return@mapNotNull null
                val target = targetOf(doc, link) ?: return@mapNotNull null
                PageLink(displayBox(geometry, rect), target)
            }
    }

    private fun targetOf(doc: PDDocument, link: PDAnnotationLink): LinkTarget? = runCatching {
        when (val action = link.action) {
            is PDActionURI -> action.uri?.takeIf { it.isNotBlank() }?.let { LinkTarget.Web(it.trim()) }
            is PDActionGoTo -> pageOf(doc, action.destination)
            else -> pageOf(doc, link.destination)
        }
    }.getOrNull()

    fun pageOf(doc: PDDocument, destination: PDDestination?): LinkTarget.Page? {
        val dest = when (destination) {
            is PDNamedDestination -> doc.documentCatalog.findNamedDestinationPage(destination)
            is PDPageDestination -> destination
            else -> null
        } ?: return null
        val index = dest.page?.let { doc.pages.indexOf(it) }?.takeIf { it >= 0 } ?: dest.pageNumber
        return index.takeIf { it in 0 until doc.numberOfPages }?.let { LinkTarget.Page(it) }
    }

    fun displayBox(geometry: PageGeometry, rect: PDRectangle): Box = Box.bounding(
        listOf(
            Pt(rect.lowerLeftX, rect.lowerLeftY), Pt(rect.upperRightX, rect.lowerLeftY),
            Pt(rect.upperRightX, rect.upperRightY), Pt(rect.lowerLeftX, rect.upperRightY),
        ).map(geometry::toDisplay),
    )!!
}

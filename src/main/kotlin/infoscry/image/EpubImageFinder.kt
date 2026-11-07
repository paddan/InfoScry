package infoscry.image

import infoscry.domain.SourceLocation
import infoscry.extract.ArchiveRefusedException
import infoscry.extract.DOCUMENT_TOO_LARGE_CODE
import infoscry.extract.EPUB_MEDIA_TYPE
import infoscry.extract.EpubPackage
import infoscry.extract.HtmlSanitizer
import infoscry.extract.HtmlSections
import infoscry.extract.IBOOKS_MEDIA_TYPE
import infoscry.extract.SafeArchive
import infoscry.storage.CaptionSource
import java.io.IOException
import java.nio.file.Path
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * The `<img>` elements of an EPUB, found under the section the extractor emitted for them.
 *
 * A chapter is the spine item named by the locator's spine index. Its images are assigned to sections with the
 * heading path in force where the `<img>` appears, tracked the way the extractor's section walk tracks it; an
 * image whose heading path has no section of its own, such as one before the chapter's first heading, belongs to
 * the chapter's first section. A chapter without text sections is a picture-only chapter, whose single unit has
 * an empty heading path and holds every image.
 *
 * `src` is resolved inside the archive through [SafeArchive], so a path that leaves the book, an external URL
 * and a missing entry are skipped without a failure: the book's markup and manifest disagree in real books, and
 * nothing was read. The caption is the `alt`, else the enclosing `<figure>`'s `<figcaption>`. An EPUB image has
 * no box.
 */
class EpubImageFinder : ImageFinder {

    override val mediaTypes: Set<String> = setOf(EPUB_MEDIA_TYPE, IBOOKS_MEDIA_TYPE)

    override fun open(managedPath: Path): OpenedSource {
        val archive = SafeArchive.open(managedPath)
        return try {
            Opened(archive, EpubPackage.read(archive))
        } catch (refused: ArchiveRefusedException) {
            archive.close()
            throw refused
        }
    }

    private class Opened(private val archive: SafeArchive, private val book: EpubPackage.Book) : OpenedSource {

        @Synchronized
        override fun imagesOf(parent: ParentUnit): ParentImages {
            val locator = parent.locator as? SourceLocation.EbookSection ?: return ParentImages.NONE
            val chapterPath = book.spine.getOrNull(locator.spineIndex)?.path ?: return ParentImages.NONE
            return try {
                find(parent, locator, chapterPath)
            } catch (unreadable: IOException) {
                ParentImages(emptyList(), listOf(ImageFindFailure.PARENT_UNREADABLE), scanned = false)
            } catch (unreadable: RuntimeException) {
                ParentImages(emptyList(), listOf(ImageFindFailure.PARENT_UNREADABLE), scanned = false)
            }
        }

        override fun close() = archive.close()

        private fun find(parent: ParentUnit, locator: SourceLocation.EbookSection, chapterPath: String): ParentImages {
            val entry = archive.entry(chapterPath) ?: return ParentImages.NONE
            val chapter = Jsoup.parse(archive.readText(entry))
            val sectionPaths = HtmlSections.sectionsOf(HtmlSanitizer.sanitize(chapter))
                .filter { it.text.isNotBlank() }
                .map { it.headingPath }
            val mine = imagesWithPaths(chapter).filter { (_, path) ->
                if (sectionPaths.isEmpty()) {
                    locator.headingPath.isEmpty()
                } else {
                    (if (path in sectionPaths) path else sectionPaths.first()) == locator.headingPath
                }
            }
            val images = mutableListOf<FoundImage>()
            val failures = mutableListOf<String>()
            for ((element, _) in mine) {
                if (images.size + failures.size >= ImageIdentity.MAX_PER_PARENT) {
                    failures += ImageFindFailure.TOO_MANY_IMAGES
                    break
                }
                val bytes = try {
                    bytesOf(chapterPath, element.attr("src")) ?: continue
                } catch (refused: ArchiveRefusedException) {
                    failures += if (refused.code == DOCUMENT_TOO_LARGE_CODE) {
                        ImageRejectedException.IMAGE_TOO_LARGE
                    } else {
                        ImageRejectedException.IMAGE_DECODE_FAILED
                    }
                    continue
                }
                val candidate = ImageProbe.candidateOf(bytes, box = null)
                if (candidate == null) {
                    failures += ImageRejectedException.IMAGE_DECODE_FAILED
                    continue
                }
                val alt = element.attr("alt").trim()
                val figure = element.closest("figure")?.selectFirst("figcaption")?.text()?.trim().orEmpty()
                val (caption, source) = when {
                    alt.isNotEmpty() -> alt to CaptionSource.ALT_TEXT
                    figure.isNotEmpty() -> figure to CaptionSource.FIGCAPTION
                    else -> null to null
                }
                images += FoundImage(parent, ImageSourceKind.EMBEDDED, candidate, caption, source)
            }
            return ParentImages(images, failures, scanned = false)
        }

        /** Every `<img src>` in document order with the heading path in force at it. */
        private fun imagesWithPaths(chapter: org.jsoup.nodes.Document): List<Pair<Element, List<String>>> {
            val stack = ArrayDeque<Pair<Int, String>>()
            var path: List<String> = emptyList()
            val found = mutableListOf<Pair<Element, List<String>>>()
            chapter.body().getAllElements().forEach { element ->
                val level = headingLevel(element.normalName())
                if (level != null) {
                    val title = element.text().trim()
                    if (title.isNotEmpty()) {
                        while (stack.isNotEmpty() && stack.last().first >= level) stack.removeLast()
                        stack.addLast(level to title)
                        path = stack.map { it.second }
                    }
                } else if (element.normalName() == "img" && element.attr("src").isNotBlank()) {
                    found += element to path
                }
            }
            return found
        }

        private fun headingLevel(tag: String): Int? =
            if (tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6') tag[1] - '0' else null

        /** The bytes `src` names inside the archive, or null when it names nothing the archive holds. */
        private fun bytesOf(chapterPath: String, src: String): ByteArray? {
            val reference = src.trim()
            if (reference.startsWith("data:") || "://" in reference) return null
            val path = SafeArchive.resolveHref(chapterPath, reference) ?: return null
            val entry = archive.entry(path) ?: return null
            return archive.readBytes(entry)
        }
    }
}

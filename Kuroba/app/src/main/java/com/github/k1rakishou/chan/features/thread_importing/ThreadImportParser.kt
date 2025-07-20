/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.k1rakishou.chan.features.thread_importing

import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.core.site.Site
import com.github.k1rakishou.chan.core.site.SiteResolver
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.fsaf.FileManager
import com.github.k1rakishou.fsaf.file.ExternalFile
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThreadImportParser @Inject constructor(
    private val fileManager: FileManager,
    private val siteManager: SiteManager,
    private val siteResolver: SiteResolver
) {
    
    fun parseFromClipboard(content: String): ModularResult<ChanDescriptor.ThreadDescriptor> {
        return ModularResult.Try {
            val trimmedContent = content.trim()
            
            // Try to parse as URL first
            val urlResult = parseUrlFromClipboard(trimmedContent)
            if (urlResult != null) {
                return@Try urlResult
            }
            
            // Try to parse as JSON thread data
            val jsonResult = parseJsonFromClipboard(trimmedContent)
            if (jsonResult != null) {
                return@Try jsonResult
            }
            
            // Try to parse as HTML content
            val htmlResult = parseHtmlFromClipboard(trimmedContent)
            if (htmlResult != null) {
                return@Try htmlResult
            }
            
            throw IllegalArgumentException("Unsupported clipboard content format")
        }
    }
    
    fun parseHtmlFromZip(zipFile: ExternalFile, filename: String? = null): ModularResult<ThreadImportData> {
        return ModularResult.Try {
            Logger.d(TAG, "Parsing HTML from ZIP file: ${zipFile.getFullPath()}")
            
            // First, scan the ZIP file structure to find the HTML file
            val zipInputStream = fileManager.getInputStream(zipFile)
                ?: throw IOException("Failed to open ZIP file: ${zipFile.getFullPath()}")
            
            val zipEntries = mutableMapOf<String, Long>() // Store entry names and sizes instead of data
            var threadDataHtml: String? = null
            
            zipInputStream.use { zis ->
                val zipInputStreamJava = java.util.zip.ZipInputStream(zis)
                var entry = zipInputStreamJava.nextEntry
                
                while (entry != null) {
                    val entryName = entry.name
                    Logger.d(TAG, "Processing ZIP entry: $entryName, size: ${entry.size}")
                    
                    if (!entry.isDirectory) {
                        zipEntries[entryName] = entry.size
                        
                        // Look for thread data HTML - read it immediately if found
                        if (entryName.endsWith(".html") && entryName.contains("thread")) {
                            Logger.d(TAG, "Found thread data HTML: $entryName")
                            
                            // Read HTML content using streaming approach
                            val htmlContent = StringBuilder()
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            
                            while (zipInputStreamJava.read(buffer).also { bytesRead = it } != -1) {
                                htmlContent.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                            }
                            
                            threadDataHtml = htmlContent.toString()
                            Logger.d(TAG, "Successfully read HTML content (${threadDataHtml?.length} characters)")
                        }
                    }
                    
                    entry = zipInputStreamJava.nextEntry
                }
            }
            
            if (threadDataHtml == null) {
                Logger.e(TAG, "No thread data HTML found in ZIP file. Available entries: ${zipEntries.keys}")
                throw IllegalArgumentException("No thread data HTML found in ZIP file")
            }
            
            // Parse the HTML content
            Logger.d(TAG, "Parsing HTML content with Jsoup...")
            val document = Jsoup.parse(threadDataHtml!!)
            
            Logger.d(TAG, "Extracting thread descriptor from HTML...")
            val threadDescriptor = parseThreadDescriptorFromHtml(document, filename)
            
            Logger.d(TAG, "Extracted thread descriptor: $threadDescriptor")
            
            Logger.d(TAG, "Parsing posts from HTML...")
            val posts = parsePostsFromHtml(document)
            
            Logger.d(TAG, "Parsed ${posts.size} posts")
            
            val mediaFiles = zipEntries.keys.filter { it.isMediaFile() }
            Logger.d(TAG, "Found ${mediaFiles.size} media files: $mediaFiles")
            
            ThreadImportData(
                threadDescriptor = threadDescriptor,
                posts = posts,
                mediaFiles = mediaFiles,
                zipFile = zipFile // Pass the zipFile reference for on-demand media loading
            )
        }
    }
    
    private fun parseUrlFromClipboard(content: String): ChanDescriptor.ThreadDescriptor? {
        return try {
            // Common URL patterns for imageboards
            val urlPatterns = listOf(
                // 4chan
                Pattern.compile("https?://boards\\.4chan(?:nel)?\\.org/(\\w+)/thread/(\\d+)(?:#p(\\d+))?"),
                // 8chan/8kun
                Pattern.compile("https?://8kun\\.top/(\\w+)/res/(\\d+)\\.html(?:#(\\d+))?"),
                // Others
                Pattern.compile("https?://([^/]+)/(\\w+)/(?:thread|res)/(\\d+)(?:\\.html)?(?:#(?:p)?(\\d+))?")
            )
            
            for (pattern in urlPatterns) {
                val matcher = pattern.matcher(content)
                if (matcher.find()) {
                    val boardCode = matcher.group(1)
                    val threadNo = matcher.group(2)?.toLongOrNull()
                    
                    if (boardCode != null && threadNo != null) {
                        // Try to resolve the site
                        val site = siteResolver.findSiteForUrl(content)
                        if (site != null) {
                            return ChanDescriptor.ThreadDescriptor.create(
                                siteName = site.name(),
                                boardCode = boardCode,
                                threadNo = threadNo
                            )
                        }
                    }
                }
            }
            
            null
        } catch (error: Throwable) {
            Logger.e(TAG, "Failed to parse URL from clipboard", error)
            null
        }
    }
    
    private fun parseJsonFromClipboard(content: String): ChanDescriptor.ThreadDescriptor? {
        return try {
            // Try to parse as JSON if it looks like JSON
            if (content.startsWith("{") || content.startsWith("[")) {
                // This would need proper JSON parsing implementation
                // For now, return null to indicate unsupported
                null
            } else {
                null
            }
        } catch (error: Throwable) {
            Logger.e(TAG, "Failed to parse JSON from clipboard", error)
            null
        }
    }
    
    private fun parseHtmlFromClipboard(content: String): ChanDescriptor.ThreadDescriptor? {
        return try {
            if (content.contains("<html") || content.contains("<!DOCTYPE")) {
                val document = Jsoup.parse(content)
                parseThreadDescriptorFromHtml(document, null)
            } else {
                null
            }
        } catch (error: Throwable) {
            Logger.e(TAG, "Failed to parse HTML from clipboard", error)
            null
        }
    }
    
    private fun parseThreadDescriptorFromHtml(document: Document, filename: String? = null): ChanDescriptor.ThreadDescriptor {
        // Look for thread metadata in various places
        
        // First, try to extract board info from filename if provided
        if (filename != null) {
            // Extract board from filename pattern like "4chan_gif_25512520.zip" -> "gif"
            // Strip file extension first
            val baseFilename = filename.substringBeforeLast(".")
            val filenameParts = baseFilename.split("_")
            if (filenameParts.size >= 3) {
                val site = filenameParts[0] // e.g., "4chan"
                val board = filenameParts[1] // e.g., "gif"
                
                // Try to find thread number from HTML first, then from filename
                val threadNoFromHtml = findThreadNumberFromHtml(document)
                val threadNoFromFilename = filenameParts[2].toLongOrNull()
                val threadNo = threadNoFromHtml ?: threadNoFromFilename
                
                if (threadNo != null) {
                    Logger.d(TAG, "Extracted thread info from filename: site=$site, board=$board, threadNo=$threadNo")
                    return ChanDescriptor.ThreadDescriptor.create(
                        siteName = site,
                        boardCode = board,
                        threadNo = threadNo
                    )
                } else {
                    Logger.w(TAG, "Could not parse thread number from filename: ${filenameParts[2]}")
                }
            } else {
                Logger.w(TAG, "Filename does not match expected pattern 'site_board_threadnum.zip': $filename")
            }
        }
        
        // Try to find thread info in title
        val title = document.title()
        val titlePattern = Pattern.compile("/(\\w+)/ - (.*?)(?:#(\\d+))?")
        val titleMatcher = titlePattern.matcher(title)
        
        if (titleMatcher.find()) {
            val boardCode = titleMatcher.group(1)
            // Thread number might be in the URL or as a data attribute
            val threadNo = findThreadNumberFromHtml(document)
            
            if (boardCode != null && threadNo != null) {
                // Default to 4chan if we can't determine the site
                return ChanDescriptor.ThreadDescriptor.create(
                    siteName = "4chan",
                    boardCode = boardCode,
                    threadNo = threadNo
                )
            }
        }
        
        // Try to find thread info in meta tags
        val metaElements = document.select("meta")
        for (meta in metaElements) {
            val name = meta.attr("name")
            val content = meta.attr("content")
            
            if (name.equals("board", ignoreCase = true) && content.isNotEmpty()) {
                val threadNo = findThreadNumberFromHtml(document)
                if (threadNo != null) {
                    return ChanDescriptor.ThreadDescriptor.create(
                        siteName = "4chan",
                        boardCode = content,
                        threadNo = threadNo
                    )
                }
            }
        }
        
        // Try to find thread info in post elements
        val postElements = document.select(".post, .postContainer, [data-thread-id]")
        for (post in postElements) {
            val threadId = post.attr("data-thread-id")
            if (threadId.isNotEmpty()) {
                val threadNo = threadId.toLongOrNull()
                if (threadNo != null) {
                    // Try to get board from URL or other attributes
                    val boardCode = findBoardCodeFromHtml(document)
                    if (boardCode != null) {
                        return ChanDescriptor.ThreadDescriptor.create(
                            siteName = "4chan",
                            boardCode = boardCode,
                            threadNo = threadNo
                        )
                    }
                }
            }
        }
        
        // Try to find thread number from OP post ID (e.g., id="p25512520")
        val opPost = document.select(".post.op, .postContainer.opContainer .post").first()
        if (opPost != null) {
                val postId = opPost.attr("id")
                if (postId.startsWith("p")) {
                    val threadNo = postId.substring(1).toLongOrNull()
                    if (threadNo != null) {
                        Logger.d(TAG, "Found thread number from OP post ID: $threadNo")
                        val boardCode = findBoardCodeFromHtml(document)
                        if (boardCode != null) {
                            return ChanDescriptor.ThreadDescriptor.create(
                                siteName = "4chan",
                                boardCode = boardCode,
                                threadNo = threadNo
                            )
                        }
                    }
                }
            }
        
        // If we still don't have a board code but we have a thread number, we need to get it from the user
        // or make an educated guess from the content
        val threadNo = findThreadNumberFromHtml(document)
        if (threadNo != null) {
            // As a last resort, try to infer board from content or ask user
            // For now, throw an exception to let the user know we need board information
            throw IllegalArgumentException("Could not determine board code from filename '$filename' or HTML content. Please ensure the ZIP filename follows the pattern 'site_board_threadnum.zip' (e.g., '4chan_gif_25512520.zip')")
        }
        
        throw IllegalArgumentException("Unable to extract thread information from HTML")
    }
    
    private fun findThreadNumberFromHtml(document: Document): Long? {
        // Try various selectors to find thread number
        val selectors = listOf(
            "[data-thread-id]",
            ".thread",
            ".op",
            ".post.op",
            ".post:first-child"
        )
        
        for (selector in selectors) {
            val elements = document.select(selector)
            for (element in elements) {
                val threadId = element.attr("data-thread-id")
                if (threadId.isNotEmpty()) {
                    return threadId.toLongOrNull()
                }
                
                val id = element.attr("id")
                if (id.startsWith("thread_")) {
                    return id.substring(7).toLongOrNull()
                }
            }
        }
        
        return null
    }
    
    private fun findBoardCodeFromHtml(document: Document): String? {
        // Try to find board code in various places
        val navElements = document.select(".boardTitle, .boardBanner, nav")
        for (nav in navElements) {
            val text = nav.text()
            val pattern = Pattern.compile("/(\\w+)/")
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        
        return null
    }
    
    private fun parsePostsFromHtml(document: Document): List<ParsedPost> {
        val posts = mutableListOf<ParsedPost>()
        
        // Try various post selectors
        val postSelectors = listOf(
            ".post",
            ".postContainer",
            ".thread > .post",
            "[data-post-id]"
        )
        
        for (selector in postSelectors) {
            val postElements = document.select(selector)
            if (postElements.isNotEmpty()) {
                for (element in postElements) {
                    val post = parsePostFromElement(element)
                    if (post != null) {
                        posts.add(post)
                    }
                }
                break // Use the first working selector
            }
        }
        
        return posts
    }
    
    private fun parsePostFromElement(element: Element): ParsedPost? {
        return try {
            // Try different ways to get post ID
            val postId = element.attr("data-post-id").toLongOrNull()
                ?: element.attr("id").removePrefix("post_").toLongOrNull()
                ?: element.attr("id").removePrefix("p").toLongOrNull()  // Handle "p25512520" format
                ?: return null
            
            val nameElement = element.select(".name, .postInfo .name").first()
            val name = nameElement?.text() ?: "Anonymous"
            
            val subjectElement = element.select(".subject, .postInfo .subject").first()
            val subject = subjectElement?.text() ?: ""
            
            val commentElement = element.select(".postMessage, .comment").first()
            val comment = commentElement?.html() ?: ""
            
            val timestampElement = element.select(".dateTime, .postInfo .dateTime").first()
            val timestamp = timestampElement?.attr("data-utc")?.toLongOrNull()
                ?: parseTimestampFromText(timestampElement?.text())
                ?: System.currentTimeMillis() / 1000
            
            // Extract media files from this post
            val mediaFiles = mutableListOf<String>()
            val fileElements = element.select(".file, .fileThumb")
            for (fileElement in fileElements) {
                // Look for links to media files
                val mediaLinks = fileElement.select("a[href]")
                for (link in mediaLinks) {
                    val href = link.attr("href")
                    if (href.isNotEmpty() && href.isMediaFile()) {
                        mediaFiles.add(href)
                        Logger.d(TAG, "Found media file for post $postId: $href")
                    }
                }
            }
            
            ParsedPost(
                postId = postId,
                name = name,
                subject = subject,
                comment = comment,
                timestamp = timestamp,
                mediaFiles = mediaFiles
            )
        } catch (error: Throwable) {
            Logger.e(TAG, "Failed to parse post from element", error)
            null
        }
    }
    
    private fun parseTimestampFromText(timestampText: String?): Long? {
        if (timestampText.isNullOrEmpty()) return null
        
        return try {
            // Parse format like "2023-07-15 06:04:45 No. 25512520"
            val dateTimePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})")
            val matcher = dateTimePattern.matcher(timestampText)
            
            if (matcher.find()) {
                val dateTimeString = matcher.group(1)
                if (dateTimeString != null) {
                    val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    val date = format.parse(dateTimeString)
                    date?.time?.div(1000) // Convert to seconds
                } else {
                    null
                }
            } else {
                null
            }
        } catch (error: Exception) {
            Logger.e(TAG, "Failed to parse timestamp from text: $timestampText", error)
            null
        }
    }
    
    private fun String.isMediaFile(): Boolean {
        val mediaExtensions = setOf("jpg", "jpeg", "png", "gif", "webm", "mp4", "webp")
        return mediaExtensions.any { this.endsWith(".$it", ignoreCase = true) }
    }
    
    data class ThreadImportData(
        val threadDescriptor: ChanDescriptor.ThreadDescriptor,
        val posts: List<ParsedPost>,
        val mediaFiles: List<String>,
        val zipFile: ExternalFile // Store reference to ZIP file for on-demand media loading
    )
    
    data class ParsedPost(
        val postId: Long,
        val name: String,
        val subject: String,
        val comment: String,
        val timestamp: Long,
        val mediaFiles: List<String> = emptyList() // Store media files associated with this post
    )
    
    companion object {
        private const val TAG = "ThreadImportParser"
    }
}

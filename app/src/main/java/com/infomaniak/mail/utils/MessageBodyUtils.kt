/*
 * Infomaniak Mail - Android
 * Copyright (C) 2023 Infomaniak Network SA
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
package com.infomaniak.mail.utils

import com.infomaniak.mail.data.models.message.Body
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object MessageBodyUtils {

    const val INFOMANIAK_SIGNATURE_HTML_CLASS_NAME = "editorUserSignature"
    const val INFOMANIAK_REPLY_QUOTE_HTML_CLASS_NAME = "ik_mail_quote"
    const val INFOMANIAK_FORWARD_QUOTE_HTML_CLASS_NAME = "forwardContentMessage"

    private const val BLOCKQUOTE = "blockquote"
    private const val QUOTE_DETECTION_TIMEOUT = 1_500L

    private val quoteDescriptors = arrayOf(
        // Do not detect this quote as long as we can't detect siblings quotes or else a single reply will be missing among the
        // many replies of an Outlook reply "chain", which is worst than simply ignoring it.
        // "#divRplyFwdMsg", // Microsoft Outlook
        "#isForwardContent",
        "#isReplyContent",
        "#mailcontent:not(table)",
        "#origbody",
        "#oriMsgHtmlSeperator",
        "#reply139content",
        anyCssClassContaining("gmail_extra"), // Gmail
        anyCssClassContaining("gmail_quote"), // Gmail
        anyCssClassContaining(INFOMANIAK_REPLY_QUOTE_HTML_CLASS_NAME), // That's us :3
        anyCssClassContaining("moz-cite-prefix"), // Mozilla Thunderbird
        anyCssClassContaining("protonmail_quote"), // Proton Mail
        anyCssClassContaining("yahoo_quoted"), // Yahoo! Mail
        anyCssClassContaining("zmail_extra"), // Zoho Mail
        "[name=\"quote\"]", // GMX
    )

    suspend fun splitContentAndQuote(body: Body): SplitBody {

        val bodyContent = body.value
        if (body.type == Utils.TEXT_PLAIN) return SplitBody(bodyContent)

        return executeWithTimeoutOrDefault(
            timeout = QUOTE_DETECTION_TIMEOUT,
            defaultValue = SplitBody(bodyContent),
            block = {
                val (content, quote) = splitContentAndQuote(htmlDocument = Jsoup.parse(bodyContent))
                if (quote.isNullOrBlank()) SplitBody(bodyContent) else SplitBody(content, bodyContent)
            },
            onTimeout = {
                Sentry.withScope { scope ->
                    scope.level = SentryLevel.WARNING
                    scope.setExtra("body size", "${bodyContent.toByteArray().size} bytes")
                    scope.setExtra("email", AccountUtils.currentMailboxEmail.toString())
                    Sentry.captureMessage("Timeout reached while displaying a Message's body")
                }
            },
        )
    }

    private suspend fun <T> executeWithTimeoutOrDefault(
        timeout: Long,
        defaultValue: T,
        block: CoroutineScope.() -> T,
        onTimeout: (() -> Unit)? = null,
    ): T = runCatching {
        coroutineWithTimeout(timeout, block)
    }.getOrElse {
        if (it is TimeoutCancellationException) onTimeout?.invoke() else Sentry.captureException(it)
        defaultValue
    }

    private suspend fun <T> coroutineWithTimeout(
        timeout: Long,
        block: CoroutineScope.() -> T,
    ): T = withTimeout(timeout) {
        var result: T? = null
        CoroutineScope(Dispatchers.Default).launch { result = block() }.join()
        result!!
    }

    private fun CoroutineScope.splitContentAndQuote(htmlDocument: Document): Pair<String, String?> {

        // Initiated to the original document and it'll be processed by Jsoup to remove quotes
        val htmlDocumentWithoutQuote = htmlDocument.clone()

        // Find the last parent blockquote and delete it in `htmlDocumentWithoutQuote`
        val blockquoteElement = findAndRemoveLastParentBlockquote(htmlDocumentWithoutQuote)
        // Find the first known parent quote in the HTML and delete all known quotes descriptor
        val currentQuoteDescriptor = findFirstKnownParentQuoteDescriptor(htmlDocumentWithoutQuote, scope = this).ifEmpty {
            if (blockquoteElement == null) "" else BLOCKQUOTE
        }

        val (content, quote) = splitContentAndQuote(htmlDocument, currentQuoteDescriptor, blockquoteElement)
        ensureActive()

        return content to quote
    }

    private fun findAndRemoveLastParentBlockquote(htmlDocumentWithoutQuote: Document): Element? {
        fun Document.selectLastParentBlockquote(): Element? {
            return selectFirst("$BLOCKQUOTE:not($BLOCKQUOTE $BLOCKQUOTE):last-of-type")
        }

        return htmlDocumentWithoutQuote.selectLastParentBlockquote()?.also { it.remove() }
    }

    private fun findFirstKnownParentQuoteDescriptor(htmlDocumentWithoutQuote: Document, scope: CoroutineScope): String {

        var currentQuoteDescriptor = ""

        for (quoteDescriptor in quoteDescriptors) {
            scope.ensureActive()

            val quotedContentElement = htmlDocumentWithoutQuote.select(quoteDescriptor)
            if (quotedContentElement.isNotEmpty()) {
                quotedContentElement.remove()
                currentQuoteDescriptor = quoteDescriptor
            }
        }

        return currentQuoteDescriptor
    }

    private fun splitContentAndQuote(
        htmlDocumentWithQuote: Document,
        currentQuoteDescriptor: String,
        blockquoteElement: Element?,
    ): Pair<String, String?> {
        return when {
            currentQuoteDescriptor == BLOCKQUOTE -> {
                for (quotedContentElement in htmlDocumentWithQuote.select(currentQuoteDescriptor)) {
                    if (quotedContentElement.toString() == blockquoteElement.toString()) {
                        quotedContentElement.remove()
                        break
                    }
                }
                htmlDocumentWithQuote.toString() to blockquoteElement.toString()
            }
            currentQuoteDescriptor.isNotEmpty() -> {
                val quotedContentElements = htmlDocumentWithQuote.select(currentQuoteDescriptor)
                quotedContentElements.remove()
                htmlDocumentWithQuote.toString() to quotedContentElements.toString()
            }
            else -> {
                htmlDocumentWithQuote.toString() to null
            }
        }
    }

    //region Utils
    /**
     * Some Email clients rename CSS classes to prefix them.
     * We match all the CSS classes that contain the quote, in case this one has been renamed.
     * @return a new CSS query
     */
    private fun anyCssClassContaining(cssClass: String) = "[class*=$cssClass]"
    //endregion

    data class SplitBody(
        val content: String,
        val quote: String? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            return other === this || (other is SplitBody && other.content == content && other.quote == quote)
        }

        override fun hashCode(): Int = 31 * content.hashCode() + quote.hashCode()
    }
}

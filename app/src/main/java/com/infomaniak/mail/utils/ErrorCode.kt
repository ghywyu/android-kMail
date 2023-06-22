/*
 * Infomaniak kMail - Android
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

import com.infomaniak.lib.core.utils.ApiErrorCode
import com.infomaniak.mail.R
import com.infomaniak.lib.core.R as RCore

@Suppress("MemberVisibilityCanBePrivate")
object ErrorCode {

    const val VALIDATION_FAILED = "validation_failed" // Do not translate, we don't want to show this to the user

    // Global
    const val INVALID_CREDENTIALS = "invalid_credentials"

    // Mailbox
    const val MAILBOX_LOCKED = "mailbox_locked"
    const val ERROR_WHILE_LINKING_MAILBOX = "error_while_linking_mailbox"

    // Folder
    const val FOLDER_ALREADY_EXISTS = "folder__destination_already_exists"
    const val FOLDER_DOES_NOT_EXIST = "folder__not_exists"
    const val FOLDER_NAME_TOO_LONG = "folder__name_too_long"
    const val FOLDER_UNABLE_TO_CREATE = "folder__unable_to_create"
    const val FOLDER_UNABLE_TO_FLUSH = "folder__unable_to_flush"

    // Draft
    const val DRAFT_DOES_NOT_EXIST = "draft__not_found"
    const val DRAFT_MESSAGE_NOT_FOUND = "draft__message_not_found"
    const val DRAFT_HAS_TOO_MANY_RECIPIENTS = "draft__to_many_recipients"
    const val DRAFT_NEED_AT_LEAST_ONE_RECIPIENT = "draft__need_at_least_one_recipient_to_be_sent"
    const val DRAFT_ALREADY_SCHEDULED_OR_SENT = "draft__cannot_modify_scheduled_or_already_sent_message"

    // Identity
    const val IDENTITY_NOT_FOUND = "identity__not_found"

    // Send
    const val SEND_RECIPIENTS_REFUSED = "send__server_refused_all_recipients"
    const val SEND_LIMIT_EXCEEDED = "send__server_rate_limit_exceeded"

    val apiErrorCodes = listOf(

        // Global
        ApiErrorCode(INVALID_CREDENTIALS, R.string.errorInvalidCredentials),
        // ApiErrorCode(IDENTITY_NOT_FOUND, R.string.), // Useless until we handle local drafts

        // Mailbox
        ApiErrorCode(MAILBOX_LOCKED, R.string.errorMailboxLocked),

        // Drafts
        ApiErrorCode(DRAFT_DOES_NOT_EXIST, R.string.errorDraftNotFound), // Should we show this technical info to the user ?
        ApiErrorCode(DRAFT_MESSAGE_NOT_FOUND, R.string.errorDraftNotFound), // Should we show this technical info to the user ?
        ApiErrorCode(DRAFT_HAS_TOO_MANY_RECIPIENTS, R.string.errorTooManyRecipients), // Useless until we handle local drafts
        ApiErrorCode(DRAFT_NEED_AT_LEAST_ONE_RECIPIENT, R.string.errorAtLeastOneRecipient), // Useless until local drafts
        ApiErrorCode(DRAFT_ALREADY_SCHEDULED_OR_SENT, R.string.errorEditScheduledMessage),


        // Send
        ApiErrorCode(SEND_RECIPIENTS_REFUSED, R.string.errorRefusedRecipients), // Useless until we handle local drafts
        ApiErrorCode(SEND_LIMIT_EXCEEDED, R.string.errorSendLimitExceeded),

        // Folder
        ApiErrorCode(FOLDER_ALREADY_EXISTS, R.string.errorNewFolderAlreadyExists),
        ApiErrorCode(FOLDER_DOES_NOT_EXIST, R.string.errorFolderDoesNotExist),
        ApiErrorCode(FOLDER_NAME_TOO_LONG, R.string.errorNewFolderNameTooLong),
        ApiErrorCode(FOLDER_UNABLE_TO_CREATE, R.string.errorUnableToCreateFolder),
        ApiErrorCode(FOLDER_UNABLE_TO_FLUSH, R.string.errorUnableToFlushFolder),

        )

    private val ignoredErrorCodesForDrafts = setOf(
        DRAFT_ALREADY_SCHEDULED_OR_SENT,
    )

    fun getTranslateResForDrafts(code: String?): Int? {
        return if (ignoredErrorCodesForDrafts.contains(code)) {
            null
        } else {
            apiErrorCodes.firstOrNull { it.code == code }?.translateRes ?: RCore.string.anErrorHasOccurred
        }
    }
}

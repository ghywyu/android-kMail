/*
 * Infomaniak ikMail - Android
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
package com.infomaniak.mail.ui.main.thread.actions

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.infomaniak.mail.data.cache.mailboxContent.AttachmentController
import com.infomaniak.mail.data.models.Attachment
import com.infomaniak.mail.di.IoDispatcher
import com.infomaniak.mail.utils.LocalStorageUtils
import com.infomaniak.mail.utils.context
import com.infomaniak.mail.utils.coroutineContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

@HiltViewModel
class DownloadAttachmentViewModel @Inject constructor(
    application: Application,
    private val attachmentController: AttachmentController,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {

    private val ioCoroutineContext = viewModelScope.coroutineContext(ioDispatcher)

    /**
     * We keep the Attachment, in case the ViewModel is destroyed before it finishes downloading
     */
    private var attachment: Attachment? = null

    fun downloadAttachment(resource: String) = liveData(ioCoroutineContext) {
        val attachment = attachmentController.getAttachment(resource).also { attachment = it }
        val attachmentFile = attachment.getCacheFile(context)

        if (attachment.hasUsableCache(context, attachmentFile)) {
            emit(attachment.openWithIntent(context))
            this@DownloadAttachmentViewModel.attachment = null
            return@liveData
        }

        if (LocalStorageUtils.saveAttachmentToCache(resource, attachmentFile)) {
            emit(attachment.openWithIntent(context))
            this@DownloadAttachmentViewModel.attachment = null
        } else {
            emit(null)
        }
    }

    override fun onCleared() {
        // If we end up with an incomplete cached Attachment, we delete it
        attachment?.getCacheFile(context)?.apply { if (exists()) delete() }
        super.onCleared()
    }
}

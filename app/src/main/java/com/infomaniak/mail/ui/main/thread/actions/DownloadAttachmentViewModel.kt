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
package com.infomaniak.mail.ui.main.thread.actions

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.infomaniak.mail.data.cache.mailboxContent.AttachmentController
import com.infomaniak.mail.data.models.Attachment
import com.infomaniak.mail.utils.LocalStorageUtils
import kotlinx.coroutines.Dispatchers

class DownloadAttachmentViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * We keep the Attachment, in case the ViewModel is destroyed before it finishes downloading
     */
    private var attachment: Attachment? = null

    fun downloadAttachment(resource: String): LiveData<Intent?> {
        return liveData(viewModelScope.coroutineContext + Dispatchers.IO) {
            val attachment = AttachmentController.getAttachment(resource).also { attachment = it }
            val attachmentFile = attachment.getCacheFile(getApplication())

            if (attachment.hasUsableCache(getApplication(), attachmentFile)) {
                emit(attachment.openWithIntent(getApplication()))
                this@DownloadAttachmentViewModel.attachment = null
                return@liveData
            }

            if (LocalStorageUtils.saveAttachmentToCache(resource, attachmentFile)) {
                emit(attachment.openWithIntent(getApplication()))
                this@DownloadAttachmentViewModel.attachment = null
            } else {
                emit(null)
            }
        }
    }

    override fun onCleared() {
        // If we end up with an incomplete cached Attachment, we delete it
        attachment?.getCacheFile(getApplication())?.apply { if (exists()) delete() }
        super.onCleared()
    }
}

/*
 * Infomaniak kMail - Android
 * Copyright (C) 2022-2023 Infomaniak Network SA
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
package com.infomaniak.mail.workers

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.*
import com.infomaniak.lib.core.api.ApiController
import com.infomaniak.lib.core.models.ApiResponse
import com.infomaniak.lib.core.networking.HttpUtils
import com.infomaniak.lib.core.utils.FORMAT_DATE_WITH_TIMEZONE
import com.infomaniak.lib.core.utils.isNetworkException
import com.infomaniak.mail.data.api.ApiRepository
import com.infomaniak.mail.data.api.ApiRoutes
import com.infomaniak.mail.data.cache.RealmDatabase
import com.infomaniak.mail.data.cache.mailboxContent.DraftController
import com.infomaniak.mail.data.cache.mailboxContent.FolderController
import com.infomaniak.mail.data.cache.mailboxContent.MessageController
import com.infomaniak.mail.data.cache.mailboxInfo.MailboxController
import com.infomaniak.mail.data.models.AppSettings
import com.infomaniak.mail.data.models.Attachment
import com.infomaniak.mail.data.models.Folder.FolderRole
import com.infomaniak.mail.data.models.Mailbox
import com.infomaniak.mail.data.models.draft.Draft
import com.infomaniak.mail.data.models.draft.Draft.DraftAction
import com.infomaniak.mail.utils.*
import com.infomaniak.mail.utils.NotificationUtils.showDraftActionsNotification
import io.realm.kotlin.MutableRealm
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.text.SimpleDateFormat
import java.util.*
import kotlin.properties.Delegates

class DraftsActionsWorker(appContext: Context, params: WorkerParameters) : BaseCoroutineWorker(appContext, params) {

    private val mailboxContentRealm by lazy { RealmDatabase.newMailboxContentInstance }
    private val mailboxInfoRealm by lazy { RealmDatabase.newMailboxInfoInstance }

    private lateinit var okHttpClient: OkHttpClient
    private var mailboxId: Int = AppSettings.DEFAULT_ID
    private lateinit var mailbox: Mailbox
    private var userId: Int by Delegates.notNull()

    override suspend fun launchWork(): Result = withContext(Dispatchers.IO) {
        if (DraftController.getDraftsWithActionsCount() == 0L) return@withContext Result.success()
        if (AccountUtils.currentMailboxId == AppSettings.DEFAULT_ID) return@withContext Result.failure()

        userId = inputData.getIntOrNull(USER_ID_KEY) ?: return@withContext Result.failure()
        mailboxId = inputData.getIntOrNull(MAILBOX_ID_KEY) ?: return@withContext Result.failure()

        mailbox = MailboxController.getMailbox(userId, mailboxId, mailboxInfoRealm) ?: return@withContext Result.failure()
        okHttpClient = AccountUtils.getHttpClient(userId)

        moveServiceToForeground()

        handleDraftsActions()
    }

    override fun onFinish() {
        mailboxContentRealm.close()
        mailboxInfoRealm.close()
    }

    private suspend fun moveServiceToForeground() {
        applicationContext.showDraftActionsNotification().apply {
            setForeground(ForegroundInfo(NotificationUtils.DRAFT_ACTIONS_ID, build()))
        }
    }

    private suspend fun handleDraftsActions(): Result {

        val scheduledDates = mutableListOf<String>()

        val isFailure = mailboxContentRealm.writeBlocking {

            val drafts = DraftController.getDraftsWithActions(realm = this).ifEmpty { return@writeBlocking false }

            var hasRemoteException = false

            drafts.reversed().forEach { draft ->
                try {
                    draft.uploadAttachments(realm = this)
                    executeDraftAction(draft, mailbox.uuid, realm = this, okHttpClient)?.also(scheduledDates::add)
                } catch (exception: Exception) {
                    exception.printStackTrace()
                    if (exception is ApiController.NetworkException) throw exception
                    Sentry.captureException(exception)
                    hasRemoteException = true
                }
            }

            return@writeBlocking hasRemoteException
        }

        return if (isFailure) {
            Result.failure()
        } else {
            updateFolderAfterScheduledDate(scheduledDates)
            Result.success()
        }
    }

    private suspend fun updateFolderAfterScheduledDate(scheduledDates: MutableList<String>) {

        val folder = FolderController.getFolder(FolderRole.DRAFT, realm = mailboxContentRealm)

        if (folder?.cursor != null) {

            val timeNow = Date().time
            val simpleDateFormat = SimpleDateFormat(FORMAT_DATE_WITH_TIMEZONE, Locale.ROOT)

            val times = scheduledDates.mapNotNull { simpleDateFormat.parse(it)?.time }
            var delay = REFRESH_DELAY
            if (times.isNotEmpty()) delay += times.maxOf { it } - timeNow
            delay(delay)

            MessageController.fetchCurrentFolderMessages(mailbox, folder.id, okHttpClient, mailboxContentRealm)
        }
    }

    private fun Draft.uploadAttachments(realm: MutableRealm): Result {
        getNotUploadedAttachments(this).forEach { attachment ->
            try {
                attachment.startUpload(this.localUuid, realm)
            } catch (exception: Exception) {
                if (exception.isNetworkException()) throw ApiController.NetworkException()
                throw exception
            }
        }
        return Result.success()
    }

    private fun getNotUploadedAttachments(draft: Draft): List<Attachment> {
        return draft.attachments.filter { attachment -> attachment.uploadLocalUri != null }
    }

    private fun Attachment.startUpload(localDraftUuid: String, realm: MutableRealm) {
        val attachmentFile = getUploadLocalFile(applicationContext, localDraftUuid).also { if (!it.exists()) return }
        val request = Request.Builder().url(ApiRoutes.createAttachment(mailbox.uuid))
            .headers(HttpUtils.getHeaders(contentType = null))
            .addHeader("x-ws-attachment-filename", name)
            .addHeader("x-ws-attachment-mime-type", mimeType)
            .addHeader("x-ws-attachment-disposition", "attachment")
            .post(attachmentFile.asRequestBody(mimeType.toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()

        val apiResponse = ApiController.json.decodeFromString<ApiResponse<Attachment>>(response.body?.string() ?: "")
        if (apiResponse.isSuccess() && apiResponse.data != null) {
            attachmentFile.delete()
            LocalStorageUtils.deleteAttachmentsDirIfEmpty(applicationContext, localDraftUuid, userId, mailbox.mailboxId)
            updateLocalAttachment(localDraftUuid, apiResponse.data!!, realm)
        }
    }

    private fun Attachment.updateLocalAttachment(localDraftUuid: String, attachment: Attachment, realm: MutableRealm) {
        DraftController.updateDraft(localDraftUuid, realm) {
            realm.delete(it.attachments.first { attachment -> attachment.uuid == uuid })
            it.attachments.add(attachment)
        }
    }

    private fun executeDraftAction(draft: Draft, mailboxUuid: String, realm: MutableRealm, okHttpClient: OkHttpClient): String? {

        var scheduledDate: String? = null

        when (draft.action) {
            DraftAction.SAVE -> with(ApiRepository.saveDraft(mailboxUuid, draft, okHttpClient)) {
                if (data != null) {
                    DraftController.updateDraft(draft.localUuid, realm) {
                        it.remoteUuid = data?.draftRemoteUuid
                        it.messageUid = data?.messageUid
                        it.action = null
                    }
                } else throwErrorAsException()
            }
            DraftAction.SEND -> with(ApiRepository.sendDraft(mailboxUuid, draft, okHttpClient)) {
                if (isSuccess()) {
                    scheduledDate = data?.scheduledDate
                    realm.delete(draft)
                } else {
                    throwErrorAsException()
                }
            }
            else -> Unit
        }

        return scheduledDate
    }

    private fun Data.getIntOrNull(key: String) = getInt(key, 0).run { if (this == 0) null else this }

    companion object {
        private const val TAG = "DraftsActionsWorker"
        private const val USER_ID_KEY = "userId"
        private const val MAILBOX_ID_KEY = "mailboxIdKey"
        private const val REFRESH_DELAY = 1_500L

        fun scheduleWork(context: Context) {

            if (AccountUtils.currentMailboxId == AppSettings.DEFAULT_ID) return
            if (DraftController.getDraftsWithActionsCount() == 0L) return

            val workData = workDataOf(USER_ID_KEY to AccountUtils.currentUserId, MAILBOX_ID_KEY to AccountUtils.currentMailboxId)
            val workRequest = OneTimeWorkRequestBuilder<DraftsActionsWorker>()
                .addTag(TAG)
                .setInputData(workData)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpeditedWorkRequest()
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)
        }

        fun getCompletedWorkInfosLiveData(context: Context): LiveData<MutableList<WorkInfo>> {
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG)).addStates(listOf(WorkInfo.State.SUCCEEDED)).build()
            return WorkManager.getInstance(context).getWorkInfosLiveData(workQuery)
        }
    }
}

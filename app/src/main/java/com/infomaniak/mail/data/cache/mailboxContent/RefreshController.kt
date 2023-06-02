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
package com.infomaniak.mail.data.cache.mailboxContent

import android.util.Log
import com.infomaniak.mail.data.api.ApiRepository
import com.infomaniak.mail.data.cache.RealmDatabase
import com.infomaniak.mail.data.cache.mailboxContent.MessageController.deleteMessage
import com.infomaniak.mail.data.cache.mailboxContent.RefreshController.RefreshMode.*
import com.infomaniak.mail.data.cache.mailboxContent.ThreadController.upsertThread
import com.infomaniak.mail.data.models.Folder
import com.infomaniak.mail.data.models.Folder.FolderRole
import com.infomaniak.mail.data.models.getMessages.ActivitiesResult.MessageFlags
import com.infomaniak.mail.data.models.mailbox.Mailbox
import com.infomaniak.mail.data.models.message.Message
import com.infomaniak.mail.data.models.thread.Thread
import com.infomaniak.mail.utils.*
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.TypedRealm
import io.realm.kotlin.ext.copyFromRealm
import io.realm.kotlin.ext.isManaged
import io.sentry.Sentry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.Date
import kotlin.math.max
import kotlin.math.min

object RefreshController {

    private inline val defaultRealm get() = RealmDatabase.mailboxContent()

    private var refreshThreadsJob: Job? = null

    enum class RefreshMode {
        REFRESH_FOLDER,
        REFRESH_FOLDER_WITH_ROLE,
        // OLD_MESSAGES, /* Unused for now */
        ONE_PAGE_OF_OLD_MESSAGES,
    }

    //region Fetch Messages
    suspend fun refreshThreads(
        refreshMode: RefreshMode,
        mailbox: Mailbox,
        folder: Folder,
        okHttpClient: OkHttpClient? = null,
        realm: Realm = defaultRealm,
        started: (() -> Unit)? = null,
        stopped: (() -> Unit)? = null,
    ): List<Thread>? = withContext(Dispatchers.IO) {

        refreshThreadsJob?.cancel()

        val job = async {

            return@async runCatching {
                started?.invoke()
                return@runCatching realm.fetchMessages(refreshMode, scope = this, mailbox, folder, okHttpClient)
            }.getOrElse {
                // It failed, but not because we cancelled it. Something bad happened, so we call the `stopped` callback.
                if (it !is CancellationException) stopped?.invoke()
                if (it is ApiErrorException) it.handleApiErrors()
                return@getOrElse null
            }
        }

        refreshThreadsJob = job

        return@withContext job.await().also {
            if (it != null) stopped?.invoke()
        }
    }

    private fun ApiErrorException.handleApiErrors() {
        when (errorCode) {
            ErrorCode.FOLDER_DOES_NOT_EXIST -> Unit
            else -> Sentry.captureException(this)
        }
    }

    private suspend fun Realm.fetchMessages(
        refreshMode: RefreshMode,
        scope: CoroutineScope,
        mailbox: Mailbox,
        folder: Folder,
        okHttpClient: OkHttpClient?
    ): List<Thread> {
        return when (refreshMode) {
            REFRESH_FOLDER_WITH_ROLE -> fetchNewMessagesForRoleFolder(scope, mailbox, folder, okHttpClient)
            REFRESH_FOLDER -> fetchNewMessages(scope, mailbox, folder, okHttpClient)
            // OLD_MESSAGES -> fetchOldMessages(scope, mailbox, folder, okHttpClient) /* Unused for now */
            ONE_PAGE_OF_OLD_MESSAGES -> {
                fetchOneBatchOfOldMessages(scope, mailbox, folder, okHttpClient)
                emptyList()
            }
        }
    }

    private suspend fun Realm.fetchNewMessagesForRoleFolder(
        scope: CoroutineScope,
        mailbox: Mailbox,
        folder: Folder,
        okHttpClient: OkHttpClient?,
    ): List<Thread> {

        val impactedCurrentFolderThreads = fetchNewMessages(scope, mailbox, folder, okHttpClient)
        scope.ensureActive()

        val roles = when (folder.role) {
            FolderRole.INBOX -> listOf(FolderRole.SENT, FolderRole.DRAFT)
            FolderRole.SENT -> listOf(FolderRole.INBOX, FolderRole.DRAFT)
            FolderRole.DRAFT -> listOf(FolderRole.INBOX, FolderRole.SENT)
            else -> emptyList()
        }

        roles.forEach { role ->
            FolderController.getFolder(role)?.let { folder ->
                fetchNewMessages(scope, mailbox, folder, okHttpClient)
                scope.ensureActive()
            }
        }

        return impactedCurrentFolderThreads
    }

    private suspend fun Realm.fetchNewMessages(
        scope: CoroutineScope,
        mailbox: Mailbox,
        folder: Folder,
        okHttpClient: OkHttpClient?,
    ): List<Thread> {

        val previousCursor = folder.cursor
        val impactedCurrentFolderThreads = mutableSetOf<Thread>()

        val uids = if (previousCursor == null) {
            getMessagesUids(mailbox.uuid, folder.id, okHttpClient)
        } else {
            getMessagesUidsDelta(mailbox.uuid, folder.id, previousCursor, okHttpClient)
        } ?: return emptyList()
        scope.ensureActive()

        impactedCurrentFolderThreads += handleMessagesUids(scope, mailbox, folder, okHttpClient, uids).also {
            writeBlocking {
                findLatest(folder)?.let {
                    SentryDebug.sendOrphanMessages(previousCursor, folder = it)
                    SentryDebug.sendOrphanThreads(previousCursor, folder = it, realm = this)
                }
            }
        }

        if (folder.remainingOldMessagesToFetch > 0) {
            impactedCurrentFolderThreads += fetchOldMessages(scope, mailbox, folder, okHttpClient)
        }

        return impactedCurrentFolderThreads.toList()
    }

    private suspend fun Realm.fetchOldMessages(
        scope: CoroutineScope,
        mailbox: Mailbox,
        folder: Folder,
        okHttpClient: OkHttpClient?,
    ): List<Thread> {

        var remainingOldMessagesToFetch = folder.remainingOldMessagesToFetch
        val impactedCurrentFolderThreads = mutableListOf<Thread>()

        while (remainingOldMessagesToFetch > 0) {
            val (newCount, threads) = fetchOneBatchOfOldMessages(scope, mailbox, folder, okHttpClient)
            remainingOldMessagesToFetch = newCount
            impactedCurrentFolderThreads += threads
        }

        return impactedCurrentFolderThreads
    }

    private suspend fun Realm.fetchOneBatchOfOldMessages(
        scope: CoroutineScope,
        mailbox: Mailbox,
        folder: Folder,
        okHttpClient: OkHttpClient?,
    ): Pair<Int, List<Thread>> {

        fun saveCompletedHistory() {
            FolderController.updateFolder(folder.id, realm = this) {
                it.remainingOldMessagesToFetch = 0
                it.isHistoryComplete = true
            }
        }

        val shouldStop = 0 to emptyList<Thread>()

        val offsetUid = MessageController.getOldestMessage(folder.id, realm = this)?.shortUid.also { oldestUid ->
            if (oldestUid == null || oldestUid.toInt() <= 1) {
                saveCompletedHistory()
                return shouldStop
            }
        }

        val olderUids = getMessagesUids(mailbox.uuid, folder.id, okHttpClient, offsetUid).also { uids ->
            if (uids?.addedShortUids?.isEmpty() == true) saveCompletedHistory()
            if (uids == null || uids.addedShortUids.isEmpty()) return shouldStop
        }
        scope.ensureActive()

        val impactedCurrentFolderThreads = handleMessagesUids(
            scope,
            mailbox,
            folder,
            okHttpClient,
            olderUids!!,
            shouldUpdateCursor = false,
        ).also {
            with(olderUids.addedShortUids) {
                if (count() < Utils.PAGE_SIZE) {
                    saveCompletedHistory()
                    return 0 to it
                }
            }
        }

        val newCount = writeBlocking {
            val latestFolder = FolderController.getFolder(folder.id, realm = this)!!
            val newCount = max(latestFolder.remainingOldMessagesToFetch - olderUids.addedShortUids.count(), 0)
            latestFolder.remainingOldMessagesToFetch = newCount
            return@writeBlocking newCount
        }

        return newCount to impactedCurrentFolderThreads
    }
    //endregion

    //region Handle updates
    private suspend fun Realm.handleMessagesUids(
        scope: CoroutineScope,
        mailbox: Mailbox,
        folder: Folder,
        okHttpClient: OkHttpClient?,
        uids: MessagesUids,
        shouldUpdateCursor: Boolean = true,
    ): List<Thread> {

        val logMessage =
            "Added: ${uids.addedShortUids.count()} | Deleted: ${uids.deletedUids.count()} | Updated: ${uids.updatedMessages.count()}"
        Log.i("API", "$logMessage | ${folder.name}")

        val impactedFoldersIds = writeBlocking {
            mutableSetOf<String>().apply {
                addAll(handleDeletedUids(scope, uids.deletedUids))
                addAll(handleUpdatedUids(scope, uids.updatedMessages, folder.id))
            }
        }

        val impactedThreads = if (uids.addedShortUids.isEmpty()) {
            emptyList()
        } else {
            handleAddedUids(
                scope = scope,
                mailboxUuid = mailbox.uuid,
                folder = folder,
                okHttpClient = okHttpClient,
                messagesUids = uids,
                logMessage = logMessage,
            )
        }

        return writeBlocking {

            val impactedCurrentFolderThreads = impactedThreads.filter { it.folderId == folder.id }
            impactedFoldersIds += impactedThreads.map { it.folderId } + folder.id

            impactedFoldersIds.forEach { folderId ->
                FolderController.refreshUnreadCount(folderId, mailbox.objectId, realm = this)
            }
            scope.ensureActive()

            FolderController.getFolder(folder.id, realm = this)?.let {
                it.lastUpdatedAt = Date().toRealmInstant()
                if (shouldUpdateCursor) it.cursor = uids.cursor
            }

            return@writeBlocking impactedCurrentFolderThreads
        }
    }
    //endregion

    //region Added Messages
    private suspend fun Realm.handleAddedUids(
        scope: CoroutineScope,
        mailboxUuid: String,
        folder: Folder,
        okHttpClient: OkHttpClient?,
        messagesUids: MessagesUids,
        logMessage: String,
    ): List<Thread> {

        val impactedThreads = mutableSetOf<Thread>()
        val shortUids = messagesUids.addedShortUids
        val uids = getOnlyNewUids(folder, shortUids)
        var pageStart = 0
        val pageSize = Utils.PAGE_SIZE

        while (pageStart < uids.count()) {
            scope.ensureActive()

            val pageEnd = min(pageStart + pageSize, uids.count())
            val page = uids.subList(pageStart, pageEnd)

            val before = System.currentTimeMillis()
            val apiResponse = ApiRepository.getMessagesByUids(mailboxUuid, folder.id, page, okHttpClient)
            val after = System.currentTimeMillis()
            if (!apiResponse.isSuccess()) apiResponse.throwErrorAsException()
            scope.ensureActive()

            apiResponse.data?.messages?.let { messages ->

                writeBlocking {
                    findLatest(folder)?.let { latestFolder ->
                        val threads = createMultiMessagesThreads(scope, latestFolder, messages)
                        Log.d("Realm", "Saved Messages: ${latestFolder.name} | ${latestFolder.messages.count()}")
                        impactedThreads.addAll(threads)
                    }
                }

                /**
                 * Realm really doesn't like to be written on too frequently.
                 * So we want to be sure that we don't write twice in less than 500 ms.
                 * Appreciable side effect: it will also reduce the stress on the API.
                 */
                val delay = Utils.MAX_DELAY_BETWEEN_API_CALLS - (after - before)
                if (delay > 0L) {
                    delay(delay)
                    scope.ensureActive()
                }

                SentryDebug.addThreadsAlgoBreadcrumb(
                    message = logMessage,
                    data = mapOf(
                        "1_folderName" to folder.name,
                        "2_folderId" to folder.id,
                        "3_added" to shortUids,
                        "4_deleted" to messagesUids.deletedUids.map { "${it.toShortUid()}" },
                        "5_updated" to messagesUids.updatedMessages.map { it.shortUid },
                    ),
                )

                SentryDebug.sendMissingMessages(page, messages, folder, messagesUids.cursor)
            }

            pageStart += pageSize
        }

        return impactedThreads.toList()
    }
    //endregion

    //region Deleted Messages
    private fun MutableRealm.handleDeletedUids(scope: CoroutineScope, uids: List<String>): Set<String> {

        val impactedFolders = mutableSetOf<String>()
        val threads = mutableSetOf<Thread>()

        uids.forEach { messageUid ->
            scope.ensureActive()

            val message = MessageController.getMessage(messageUid, this) ?: return@forEach

            for (thread in message.threads.reversed()) {
                scope.ensureActive()

                val isSuccess = thread.messages.remove(message)
                val numberOfMessagesInFolder = thread.messages.count { it.folderId == thread.folderId }

                // We need to save this value because the Thread could be deleted before we use this `folderId`.
                val threadFolderId = thread.folderId

                if (numberOfMessagesInFolder == 0) {
                    threads.removeIf { it.uid == thread.uid }
                    delete(thread)
                } else if (isSuccess) {
                    threads += thread
                } else {
                    continue
                }

                impactedFolders.add(threadFolderId)
            }

            deleteMessage(message)
        }

        threads.forEach {
            scope.ensureActive()
            it.recomputeThread(realm = this)
        }

        return impactedFolders
    }
    //endregion

    //region Updated Messages
    private fun MutableRealm.handleUpdatedUids(
        scope: CoroutineScope,
        messageFlags: List<MessageFlags>,
        folderId: String,
    ): Set<String> {

        val impactedFolders = mutableSetOf<String>()
        val threads = mutableSetOf<Thread>()

        messageFlags.forEach { flags ->
            scope.ensureActive()

            val uid = flags.shortUid.toLongUid(folderId)
            MessageController.getMessage(uid, realm = this)?.let { message ->
                message.updateFlags(flags)
                threads += message.threads
            }
        }

        threads.forEach { thread ->
            scope.ensureActive()
            impactedFolders.add(thread.folderId)
            thread.recomputeThread(realm = this)
        }

        return impactedFolders
    }
    //endregion

    //region Create Threads
    private fun MutableRealm.createMultiMessagesThreads(
        scope: CoroutineScope,
        folder: Folder,
        messages: List<Message>,
    ): List<Thread> {

        val idsOfFoldersWithIncompleteThreads = FolderController.getIdsOfFoldersWithIncompleteThreads(realm = this)
        val threadsToUpsert = mutableMapOf<String, Thread>()

        messages.forEach { message ->
            scope.ensureActive()

            message.apply {
                initMessageIds()
                isSpam = folder.role == FolderRole.SPAM
                shortUid = uid.toShortUid()
            }

            val existingMessage = folder.messages.firstOrNull { it == message }
            if (existingMessage == null) {
                folder.messages.add(message)
            } else if (!existingMessage.isOrphan()) {
                SentryDebug.sendAlreadyExistingMessage(folder, existingMessage, message)
                return@forEach
            }

            val existingThreads = ThreadController.getThreads(message.messageIds, realm = this).toList()

            createNewThreadIfRequired(scope, existingThreads, message, idsOfFoldersWithIncompleteThreads)?.let { newThread ->
                upsertThread(newThread).also {
                    folder.threads.add(it)
                    threadsToUpsert[it.uid] = it
                }
            }

            val allExistingMessages = mutableSetOf<Message>().apply {
                existingThreads.forEach { addAll(it.messages) }
                add(message)
            }
            existingThreads.forEach { thread ->
                scope.ensureActive()
                allExistingMessages.forEach { existingMessage ->
                    scope.ensureActive()
                    if (!thread.messages.contains(existingMessage)) {
                        thread.messagesIds += existingMessage.messageIds
                        thread.addMessageWithConditions(existingMessage, realm = this)
                    }
                }

                threadsToUpsert[thread.uid] = upsertThread(thread)
            }
        }

        val impactedThreads = mutableListOf<Thread>()
        threadsToUpsert.forEach { (_, thread) ->
            scope.ensureActive()
            thread.recomputeThread(realm = this)
            upsertThread(thread)
            impactedThreads.add(if (thread.isManaged()) thread.copyFromRealm(1u) else thread)
        }

        return impactedThreads
    }

    private fun TypedRealm.createNewThreadIfRequired(
        scope: CoroutineScope,
        existingThreads: List<Thread>,
        newMessage: Message,
        idsOfFoldersWithIncompleteThreads: List<String>,
    ): Thread? {
        var newThread: Thread? = null

        if (existingThreads.none { it.folderId == newMessage.folderId }) {

            newThread = newMessage.toThread()
            newThread.addFirstMessage(newMessage)

            val referenceThread = getReferenceThread(existingThreads, idsOfFoldersWithIncompleteThreads)
            if (referenceThread != null) addPreviousMessagesToThread(scope, newThread, referenceThread)
        }

        return newThread
    }

    /**
     * We need to add 2 things to a new Thread:
     * - the previous Messages `messagesIds`
     * - the previous Messages, depending on conditions (for example, we don't want deleted Messages outside of the Trash)
     * If there is no `existingThread` with all the Messages, we fallback on an `incompleteThread` to get its `messagesIds`.
     */
    private fun getReferenceThread(existingThreads: List<Thread>, idsOfFoldersWithIncompleteThreads: List<String>): Thread? {
        return existingThreads.firstOrNull { !idsOfFoldersWithIncompleteThreads.contains(it.folderId) }
            ?: existingThreads.firstOrNull()
    }

    private fun TypedRealm.addPreviousMessagesToThread(scope: CoroutineScope, newThread: Thread, existingThread: Thread) {

        newThread.messagesIds += existingThread.messagesIds

        existingThread.messages.forEach { message ->
            scope.ensureActive()
            newThread.addMessageWithConditions(message, realm = this)
        }
    }
    //endregion

    private fun getMessagesUids(
        mailboxUuid: String,
        folderId: String,
        okHttpClient: OkHttpClient?,
        offsetUid: Int? = null,
    ): MessagesUids? {
        val apiResponse = ApiRepository.getMessagesUids(mailboxUuid, folderId, offsetUid, okHttpClient)
        if (!apiResponse.isSuccess()) apiResponse.throwErrorAsException()
        return apiResponse.data?.let {
            MessagesUids(
                addedShortUids = it.addedShortUids,
                cursor = it.cursor,
            )
        }
    }

    private fun getMessagesUidsDelta(
        mailboxUuid: String,
        folderId: String,
        previousCursor: String,
        okHttpClient: OkHttpClient?,
    ): MessagesUids? {
        val apiResponse = ApiRepository.getMessagesUidsDelta(mailboxUuid, folderId, previousCursor, okHttpClient)
        if (!apiResponse.isSuccess()) apiResponse.throwErrorAsException()
        return apiResponse.data?.let {
            MessagesUids(
                addedShortUids = it.addedShortUids,
                deletedUids = it.deletedShortUids.map { shortUid -> shortUid.toLongUid(folderId) },
                updatedMessages = it.updatedMessages,
                cursor = it.cursor,
            )
        }
    }

    private fun getOnlyNewUids(folder: Folder, remoteUids: List<Int>): List<Int> {
        val localUids = folder.messages.map { it.shortUid }.toSet()
        return remoteUids.subtract(localUids).toList()
    }

    data class MessagesUids(
        var addedShortUids: List<Int> = emptyList(),
        var deletedUids: List<String> = emptyList(),
        var updatedMessages: List<MessageFlags> = emptyList(),
        var cursor: String,
    )
    //endregion
}
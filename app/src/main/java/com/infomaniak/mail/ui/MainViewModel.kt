/*
 * Infomaniak kMail - Android
 * Copyright (C) 2022 Infomaniak Network SA
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
package com.infomaniak.mail.ui

import android.Manifest.permission.READ_CONTACTS
import android.content.Context
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.CommonDataKinds.Contactables
import android.util.Log
import androidx.lifecycle.*
import com.infomaniak.lib.core.utils.SingleLiveEvent
import com.infomaniak.mail.data.UiSettings.ThreadMode
import com.infomaniak.mail.data.api.ApiRepository
import com.infomaniak.mail.data.api.ApiRepository.OFFSET_FIRST_PAGE
import com.infomaniak.mail.data.api.ApiRoutes.resource
import com.infomaniak.mail.data.cache.RealmDatabase
import com.infomaniak.mail.data.cache.mailboxContent.FolderController
import com.infomaniak.mail.data.cache.mailboxContent.MessageController
import com.infomaniak.mail.data.cache.mailboxContent.ThreadController
import com.infomaniak.mail.data.cache.mailboxContent.ThreadController.markThreadAsSeen
import com.infomaniak.mail.data.cache.mailboxContent.ThreadController.markThreadAsUnseen
import com.infomaniak.mail.data.cache.mailboxInfos.MailboxController
import com.infomaniak.mail.data.cache.userInfos.AddressBookController
import com.infomaniak.mail.data.cache.userInfos.MergedContactController
import com.infomaniak.mail.data.models.Folder
import com.infomaniak.mail.data.models.Folder.FolderRole
import com.infomaniak.mail.data.models.Mailbox
import com.infomaniak.mail.data.models.MergedContact
import com.infomaniak.mail.data.models.Recipient
import com.infomaniak.mail.data.models.message.Message
import com.infomaniak.mail.data.models.thread.Thread
import com.infomaniak.mail.data.models.thread.Thread.ThreadFilter
import com.infomaniak.mail.utils.AccountUtils
import com.infomaniak.mail.utils.ModelsUtils.formatFoldersListWithAllChildren
import io.sentry.android.core.internal.util.Permissions.hasPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    companion object {
        private val TAG: String = MainViewModel::class.java.simpleName
        private val DEFAULT_SELECTED_FOLDER = FolderRole.INBOX

        val currentMailboxObjectId = MutableLiveData<String?>()
        val currentFolderId = MutableLiveData<String?>()
        val currentThreadUid = MutableLiveData<String?>()
        val currentMessageUid = MutableLiveData<String?>()
    }

    val isInternetAvailable = SingleLiveEvent<Boolean>()
    var canPaginate = true
    var currentOffset = OFFSET_FIRST_PAGE
    var isDownloadingChanges = MutableLiveData(false)

    var mergedContacts = MutableLiveData<Map<Recipient, MergedContact>?>()

    fun close() {
        Log.i(TAG, "close")
        RealmDatabase.close()

        currentMessageUid.value = null
        currentThreadUid.value = null
        currentFolderId.value = null
        currentMailboxObjectId.value = null
    }

    fun listenToMailboxes(userId: Int = AccountUtils.currentUserId): LiveData<List<Mailbox>> = liveData(Dispatchers.IO) {
        emitSource(
            MailboxController.getMailboxesAsync(userId)
                .map { it.list }
                .asLiveData()
        )
    }

    fun getMailbox(objectId: String): LiveData<Mailbox?> = liveData(Dispatchers.IO) {
        emit(MailboxController.getMailbox(objectId))
    }

    fun getFolder(folderId: String): LiveData<Folder?> = liveData(Dispatchers.IO) {
        emit(FolderController.getFolder(folderId))
    }

    private fun selectMailbox(mailbox: Mailbox) {
        if (mailbox.objectId != currentMailboxObjectId.value) {
            Log.i(TAG, "selectMailbox: ${mailbox.email}")
            AccountUtils.currentMailboxId = mailbox.mailboxId

            currentMailboxObjectId.postValue(mailbox.objectId)

            currentMessageUid.postValue(null)
            currentThreadUid.postValue(null)
            currentFolderId.postValue(null)
        }
    }

    private fun selectFolder(folderId: String) {
        if (folderId != currentFolderId.value) {
            Log.i(TAG, "selectFolder: $folderId")
            currentOffset = OFFSET_FIRST_PAGE

            currentFolderId.postValue(folderId)

            currentMessageUid.postValue(null)
            currentThreadUid.postValue(null)
        }
    }

    private fun selectThread(thread: Thread) {
        if (thread.uid != currentThreadUid.value) {
            Log.i(TAG, "selectThread: ${thread.subject}")

            currentThreadUid.postValue(thread.uid)

            currentMessageUid.postValue(null)
        }
    }

    fun updateUserInfo(context: Context) = viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "updateUserInfo")
        updateAddressBooks()
        updateContacts(context)
    }

    fun loadCurrentMailbox(threadMode: ThreadMode) {
        Log.i(TAG, "loadCurrentMailbox")
        updateMailboxes()
        MailboxController.getMailbox(AccountUtils.currentUserId, AccountUtils.currentMailboxId)
            ?.let { openMailbox(it, threadMode) }
    }

    fun openMailbox(mailbox: Mailbox, threadMode: ThreadMode) = viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "switchToMailbox: ${mailbox.email}")
        selectMailbox(mailbox)
        updateFolders(mailbox)
        FolderController.getFolder(DEFAULT_SELECTED_FOLDER)?.let { folder ->
            selectFolder(folder.id)
            refreshThreads(mailbox.uuid, folder.id, threadMode)
        }
    }

    fun forceRefreshMailboxes() = viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "forceRefreshMailboxes")
        updateMailboxes()
        updateCurrentMailboxQuotas()
    }

    private fun updateCurrentMailboxQuotas() {
        val mailbox = currentMailboxObjectId.value?.let(MailboxController::getMailbox) ?: return
        if (mailbox.isLimited) {
            ApiRepository.getQuotas(mailbox.hostingId, mailbox.mailbox).data?.let { quotas ->
                MailboxController.updateMailbox(mailbox.objectId) {
                    it.quotas = quotas
                }
            }
        }
    }

    fun openFolder(folderId: String, threadMode: ThreadMode) = viewModelScope.launch(Dispatchers.IO) {
        val mailboxObjectId = currentMailboxObjectId.value ?: return@launch
        val mailboxUuid = MailboxController.getMailbox(mailboxObjectId)?.uuid ?: return@launch
        if (folderId == currentFolderId.value) return@launch

        Log.i(TAG, "openFolder: $folderId")

        selectFolder(folderId)
        refreshThreads(mailboxUuid, folderId, threadMode)
    }

    fun openThread(thread: Thread) = viewModelScope.launch(Dispatchers.IO) {
        selectThread(thread)
        markAsSeen(thread, currentFolderId.value!!)
        updateMessages(thread)
    }

    fun forceRefreshThreads(filter: ThreadFilter, threadMode: ThreadMode) = viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "forceRefreshThreads")
        val mailboxObjectId = currentMailboxObjectId.value ?: return@launch
        val mailboxUuid = MailboxController.getMailbox(mailboxObjectId)?.uuid ?: return@launch
        val folderId = currentFolderId.value ?: return@launch
        currentOffset = OFFSET_FIRST_PAGE
        isDownloadingChanges.postValue(true)
        refreshThreads(mailboxUuid, folderId, threadMode, filter)
    }

    fun deleteDraft(message: Message) = viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "deleteDraft: ${message.body}")
        if (ApiRepository.deleteDraft(message.draftResource).isSuccess()) MessageController.deleteMessage(message.uid)
    }

    private fun updateAddressBooks() {
        val apiAddressBooks = ApiRepository.getAddressBooks().data?.addressBooks ?: emptyList()

        AddressBookController.update(apiAddressBooks)
    }

    private fun updateContacts(context: Context) {
        val apiContacts = ApiRepository.getContacts().data ?: emptyList()
        val phoneMergedContacts = getPhoneContacts(context)

        apiContacts.forEach { apiContact ->
            apiContact.emails.forEach { email ->
                val key = Recipient().initLocalValues(email, apiContact.name)
                val contactAvatar = apiContact.avatar?.let { avatar -> resource(avatar) }
                if (phoneMergedContacts.contains(key)) { // If we have already encountered this user
                    if (phoneMergedContacts[key]?.avatar == null) { // Only replace the avatar if we didn't have any before
                        phoneMergedContacts[key]?.avatar = contactAvatar
                    }
                } else { // If we haven't yet encountered this user, add him
                    phoneMergedContacts[key] = MergedContact().initLocalValues(email, apiContact.name, contactAvatar)
                }
            }
        }

        MergedContactController.update(phoneMergedContacts.values.toList())
    }

    private fun getPhoneContacts(context: Context): MutableMap<Recipient, MergedContact> {
        if (!hasPermission(context, READ_CONTACTS)) return mutableMapOf()

        val emails = getListOfEmails(context)
        if (emails.isEmpty()) return mutableMapOf()

        val contacts: Map<Recipient, String?> = getNameAndAvatarForMails(context, emails)

        return contacts.toMergedContactsMap()
    }

    private fun getListOfEmails(context: Context): Map<Long, Set<String>> {
        val mailDictionary: MutableMap<Long, MutableSet<String>> = mutableMapOf()
        val projection = arrayOf(CommonDataKinds.Email.CONTACT_ID, CommonDataKinds.Email.ADDRESS)
        val contentUri = CommonDataKinds.Email.CONTENT_URI
        val cursor = context.contentResolver.query(contentUri, projection, null, null, null) ?: return emptyMap()

        while (cursor.moveToNext()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(CommonDataKinds.Email.CONTACT_ID))
            val address = cursor.getString(cursor.getColumnIndexOrThrow(CommonDataKinds.Email.ADDRESS))

            mailDictionary[id]?.add(address) ?: run { mailDictionary[id] = mutableSetOf(address) }
        }
        cursor.close()

        return mailDictionary
    }

    private fun getNameAndAvatarForMails(context: Context, emails: Map<Long, Set<String>>): Map<Recipient, String?> {
        val projection = arrayOf(Contactables.CONTACT_ID, Contactables.DISPLAY_NAME, Contactables.PHOTO_THUMBNAIL_URI)
        val cursor = context.contentResolver.query(Contactables.CONTENT_URI, projection, null, null, null) ?: return emptyMap()

        val contacts: MutableMap<Recipient, String?> = mutableMapOf()
        while (cursor.moveToNext()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(Contactables.CONTACT_ID))

            if (emails.contains(id)) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow(Contactables.DISPLAY_NAME))
                val photoUri = cursor.getString(cursor.getColumnIndexOrThrow(Contactables.PHOTO_THUMBNAIL_URI))

                emails[id]!!.forEach { email ->
                    val key = Recipient().initLocalValues(email, name)
                    contacts[key] = photoUri
                }
            }
        }
        cursor.close()
        return contacts
    }

    private fun Map<Recipient, String?>.toMergedContactsMap(): MutableMap<Recipient, MergedContact> {
        val mergedContacts = mutableMapOf<Recipient, MergedContact>()
        forEach { (key, avatarUri) ->
            mergedContacts[key] = MergedContact().initLocalValues(key.email, key.name, avatarUri)
        }
        return mergedContacts
    }

    fun listenToRealmMergedContacts() = viewModelScope.launch(Dispatchers.IO) {
        MergedContactController.getMergedContactsAsync().collect {
            mergedContacts.postValue(
                it.list.associateBy { mergedContact ->
                    Recipient().initLocalValues(mergedContact.email, mergedContact.name)
                }
            )
        }
    }

    private fun updateMailboxes() {
        val apiMailboxes = ApiRepository.getMailboxes().data
            ?.map { it.initLocalValues(AccountUtils.currentUserId) }
            ?: emptyList()

        MailboxController.update(apiMailboxes, AccountUtils.currentUserId)
    }

    private fun updateFolders(mailbox: Mailbox) {
        val apiFolders = ApiRepository.getFolders(mailbox.uuid).data?.formatFoldersListWithAllChildren() ?: emptyList()

        FolderController.update(apiFolders)
    }

    private fun refreshThreads(
        mailboxUuid: String,
        folderId: String,
        threadMode: ThreadMode,
        filter: ThreadFilter = ThreadFilter.ALL,
    ) {
        val threadsResult = ApiRepository.getThreads(mailboxUuid, folderId, threadMode, OFFSET_FIRST_PAGE, filter).data ?: return
        canPaginate = ThreadController.refreshThreads(threadsResult, mailboxUuid, folderId, filter)
        FolderController.updateFolderLastUpdatedAt(folderId)
        isDownloadingChanges.postValue(false)
    }

    fun loadMoreThreads(
        mailboxUuid: String,
        folderId: String,
        threadMode: ThreadMode,
        offset: Int,
        filter: ThreadFilter,
    ) = viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "loadMoreThreads: $offset")
        isDownloadingChanges.postValue(true)
        val threadsResult = ApiRepository.getThreads(mailboxUuid, folderId, threadMode, offset, filter).data ?: return@launch
        canPaginate = ThreadController.loadMoreThreads(threadsResult, mailboxUuid, folderId, offset, filter)
        isDownloadingChanges.postValue(false)
    }

    private fun updateMessages(thread: Thread) {
        val apiMessages = fetchMessages(thread)
        MessageController.update(thread.messages, apiMessages)
    }

    private fun fetchMessages(thread: Thread): List<Message> {
        return thread.messages.mapNotNull { localMessage ->
            if (localMessage.fullyDownloaded) {
                localMessage
            } else {
                ApiRepository.getMessage(localMessage.resource).data?.also { completedMessage ->
                    completedMessage.fullyDownloaded = true
                    // TODO: Uncomment this when managing Drafts folder
                    // if (completedMessage.isDraft && currentFolder.role = Folder.FolderRole.DRAFT) {
                    //     Log.e("TAG", "fetchMessagesFromApi: ${completedMessage.subject} | ${completedMessage.body?.value}")
                    //     val draft = fetchDraft(completedMessage.draftResource, completedMessage.uid)
                    //     completedMessage.draftUuid = draft?.uuid
                    // }
                }
            }
        }
    }

    fun toggleSeenStatus(thread: Thread, folderId: String) = viewModelScope.launch(Dispatchers.IO) {
        if (thread.unseenMessagesCount == 0) {
            markAsUnseen(thread, folderId)
        } else {
            markAsSeen(thread, folderId)
        }
    }

    private fun markAsUnseen(thread: Thread, folderId: String) {
        RealmDatabase.mailboxContent.writeBlocking {
            val latestThread = findLatest(thread) ?: return@writeBlocking
            val uid = ThreadController.getThreadLastMessageUid(latestThread)
            val apiResponse = ApiRepository.markMessagesAsUnseen(latestThread.mailboxUuid, uid)
            if (apiResponse.isSuccess()) markThreadAsUnseen(latestThread, folderId)
        }
    }

    private fun markAsSeen(thread: Thread, folderId: String) {
        if (thread.unseenMessagesCount == 0) return

        RealmDatabase.mailboxContent.writeBlocking {
            val latestThread = findLatest(thread) ?: return@writeBlocking
            val uids = ThreadController.getThreadUnseenMessagesUids(latestThread)
            val apiResponse = ApiRepository.markMessagesAsSeen(latestThread.mailboxUuid, uids)
            if (apiResponse.isSuccess()) markThreadAsSeen(latestThread, folderId)
        }
    }
}

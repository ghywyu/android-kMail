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

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.infomaniak.lib.core.utils.SingleLiveEvent
import com.infomaniak.mail.data.LocalSettings
import com.infomaniak.mail.data.api.ApiRepository
import com.infomaniak.mail.data.api.ApiRepository.OFFSET_FIRST_PAGE
import com.infomaniak.mail.data.cache.RealmDatabase
import com.infomaniak.mail.data.cache.mailboxContent.DraftController
import com.infomaniak.mail.data.cache.mailboxContent.FolderController
import com.infomaniak.mail.data.cache.mailboxContent.FolderController.incrementFolderUnreadCount
import com.infomaniak.mail.data.cache.mailboxContent.MessageController.deleteMessages
import com.infomaniak.mail.data.cache.mailboxContent.SignatureController
import com.infomaniak.mail.data.cache.mailboxContent.ThreadController
import com.infomaniak.mail.data.cache.mailboxInfo.MailboxController
import com.infomaniak.mail.data.cache.userInfo.AddressBookController
import com.infomaniak.mail.data.cache.userInfo.MergedContactController
import com.infomaniak.mail.data.models.Folder
import com.infomaniak.mail.data.models.Folder.FolderRole
import com.infomaniak.mail.data.models.Mailbox
import com.infomaniak.mail.data.models.MergedContact
import com.infomaniak.mail.data.models.correspondent.Recipient
import com.infomaniak.mail.data.models.thread.Thread
import com.infomaniak.mail.data.models.thread.Thread.ThreadFilter
import com.infomaniak.mail.utils.AccountUtils
import com.infomaniak.mail.utils.ContactUtils.getPhoneContacts
import com.infomaniak.mail.utils.ContactUtils.mergeApiContactsIntoPhoneContacts
import com.infomaniak.mail.utils.ModelsUtils.formatFoldersListWithAllChildren
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val localSettings by lazy { LocalSettings.getInstance(application) }

    val isInternetAvailable = SingleLiveEvent<Boolean>()
    var canPaginate = true
    var currentOffset = OFFSET_FIRST_PAGE
    var isDownloadingChanges = MutableLiveData(false)

    var mergedContacts = MutableLiveData<Map<Recipient, MergedContact>?>()

    fun close() {
        Log.i(TAG, "close")
        RealmDatabase.close()
        resetAllCurrentLiveData()
    }

    fun resetAllCurrentLiveData() {
        currentThreadUid.value = null
        currentFolderId.value = null
        currentMailboxObjectId.value = null
    }

    fun observeMailboxes(userId: Int = AccountUtils.currentUserId): LiveData<List<Mailbox>> = liveData(Dispatchers.IO) {
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

            currentThreadUid.postValue(null)
            currentFolderId.postValue(null)
        }
    }

    private fun selectFolder(folderId: String) {
        if (folderId != currentFolderId.value) {
            Log.i(TAG, "selectFolder: $folderId")
            currentOffset = OFFSET_FIRST_PAGE

            currentFolderId.postValue(folderId)

            currentThreadUid.postValue(null)
        }
    }

    fun updateUserInfo() = viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "updateUserInfo")
        updateAddressBooks()
        updateContacts()
    }

    fun loadCurrentMailbox() = viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "loadCurrentMailbox")
        updateMailboxes()
        MailboxController.getMailbox(AccountUtils.currentUserId, AccountUtils.currentMailboxId)?.let(::openMailbox)
    }

    fun openMailbox(mailbox: Mailbox) = viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "switchToMailbox: ${mailbox.email}")
        selectMailbox(mailbox)
        updateSignatures(mailbox)
        updateFolders(mailbox)
        FolderController.getFolder(DEFAULT_SELECTED_FOLDER)?.let { folder ->
            selectFolder(folder.id)
            refreshThreads(mailbox.uuid, folder.id)
        }
    }

    fun forceRefreshMailboxes() = viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "forceRefreshMailboxes")
        updateMailboxes()
        updateCurrentMailboxQuotas()
    }

    private fun updateCurrentMailboxQuotas() {
        val mailbox = currentMailboxObjectId.value?.let(MailboxController::getMailbox) ?: return
        if (mailbox.isLimited) with(ApiRepository.getQuotas(mailbox.hostingId, mailbox.mailboxName)) {
            if (isSuccess()) MailboxController.updateMailbox(mailbox.objectId) {
                it.quotas = data
            }
        }
    }

    fun openFolder(folderId: String) = viewModelScope.launch(Dispatchers.IO) {
        val mailboxObjectId = currentMailboxObjectId.value ?: return@launch
        val mailboxUuid = MailboxController.getMailbox(mailboxObjectId)?.uuid ?: return@launch
        if (folderId == currentFolderId.value) return@launch

        Log.i(TAG, "openFolder: $folderId")

        selectFolder(folderId)
        refreshThreads(mailboxUuid, folderId)
    }

    fun forceRefreshThreads(filter: ThreadFilter) = viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "forceRefreshThreads")
        val mailboxObjectId = currentMailboxObjectId.value ?: return@launch
        val mailboxUuid = MailboxController.getMailbox(mailboxObjectId)?.uuid ?: return@launch
        val folderId = currentFolderId.value ?: return@launch
        currentOffset = OFFSET_FIRST_PAGE
        refreshThreads(mailboxUuid, folderId, filter)
    }

    private fun updateAddressBooks() {
        with(ApiRepository.getAddressBooks()) {
            if (isSuccess()) AddressBookController.update(data?.addressBooks ?: emptyList())
        }
    }

    private fun updateContacts() {
        with(ApiRepository.getContacts()) {
            if (isSuccess()) {
                val apiContacts = data ?: emptyList()
                val phoneMergedContacts = getPhoneContacts(getApplication())
                mergeApiContactsIntoPhoneContacts(apiContacts, phoneMergedContacts)
                MergedContactController.update(phoneMergedContacts.values.toList())
            }
        }
    }

    fun observeRealmMergedContacts() = viewModelScope.launch(Dispatchers.IO) {
        MergedContactController.getMergedContactsAsync().collect {
            mergedContacts.postValue(
                it.list.associateBy { mergedContact ->
                    Recipient().initLocalValues(mergedContact.email, mergedContact.name)
                }
            )
        }
    }

    private fun updateMailboxes() {
        with(ApiRepository.getMailboxes()) {
            if (isSuccess()) MailboxController.update(
                apiMailboxes = data?.map { it.initLocalValues(AccountUtils.currentUserId) } ?: emptyList(),
                userId = AccountUtils.currentUserId,
            )
        }
    }

    private fun updateSignatures(mailbox: Mailbox) = viewModelScope.launch(Dispatchers.IO) {
        with(ApiRepository.getSignatures(mailbox.hostingId, mailbox.mailboxName)) {
            if (isSuccess()) SignatureController.update(data?.signatures ?: emptyList())
        }
    }

    private fun updateFolders(mailbox: Mailbox) {
        with(ApiRepository.getFolders(mailbox.uuid)) {
            if (isSuccess()) FolderController.update(data?.formatFoldersListWithAllChildren() ?: emptyList())
        }
    }

    // TODO: Find a way to use the `filter`
    private fun refreshThreads(
        mailboxUuid: String,
        folderId: String,
        filter: ThreadFilter = ThreadFilter.ALL,
    ) = viewModelScope.launch(Dispatchers.IO) {

        isDownloadingChanges.postValue(true)

        MessageController.fetchMessages(mailboxUuid, folderId)

        // TODO: Handle this.
        canPaginate = true

        isDownloadingChanges.postValue(false)
    }

    fun loadMoreThreads(
        mailboxUuid: String,
        folderId: String,
        offset: Int,
        filter: ThreadFilter,
    ) = viewModelScope.launch(Dispatchers.IO) {
        Log.i(TAG, "Load more threads: $offset")
        isDownloadingChanges.postValue(true)

        ApiRepository.getThreads(mailboxUuid, folderId, localSettings.threadMode, offset, filter).data?.let { threadsResult ->
            canPaginate = ThreadController.loadMoreThreads(threadsResult, mailboxUuid, folderId, offset, filter)
        }

        isDownloadingChanges.postValue(false)
    }

    // Delete Thread
    fun deleteThread(thread: Thread, filter: ThreadFilter) = viewModelScope.launch(Dispatchers.IO) {

        val mailboxObjectId = currentMailboxObjectId.value ?: return@launch
        val mailboxUuid = MailboxController.getMailbox(mailboxObjectId)?.uuid ?: return@launch
        val currentFolderId = currentFolderId.value ?: return@launch

        RealmDatabase.mailboxContent().writeBlocking {
            val currentFolderRole = FolderController.getFolder(currentFolderId, this)?.role
            val messagesUids = thread.messages.map { it.uid }

            val isSuccess = if (currentFolderRole == FolderRole.TRASH) {
                ApiRepository.deleteMessages(mailboxUuid, messagesUids).isSuccess()
            } else {
                val trashId = FolderController.getFolder(FolderRole.TRASH, this)!!.id
                ApiRepository.moveMessages(mailboxUuid, messagesUids, trashId).isSuccess()
            }

            if (isSuccess) {
                incrementFolderUnreadCount(currentFolderId, -thread.unseenMessagesCount)
                deleteMessages(thread.messages)
                ThreadController.getThread(thread.uid, this)?.let(::delete)
            } else {
                // When the swiped animation finished, the Thread has been removed from the UI.
                // So if the API call failed, we need to put back this Thread in the UI.
                // Force-refreshing Realm will do that.
                forceRefreshThreads(filter)
            }
        }
    }
    //endregion

    companion object {
        private val TAG: String = MainViewModel::class.java.simpleName
        private val DEFAULT_SELECTED_FOLDER = FolderRole.INBOX

        val currentMailboxObjectId = MutableLiveData<String?>()
        val currentFolderId = MutableLiveData<String?>()
        val currentThreadUid = MutableLiveData<String?>()
    }
}

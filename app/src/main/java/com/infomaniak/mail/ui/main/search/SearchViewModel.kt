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
package com.infomaniak.mail.ui.main.search

import android.util.Log
import androidx.lifecycle.*
import com.infomaniak.lib.core.models.ApiResponse
import com.infomaniak.mail.data.api.ApiRepository
import com.infomaniak.mail.data.cache.mailboxContent.FolderController
import com.infomaniak.mail.data.cache.mailboxContent.MessageController
import com.infomaniak.mail.data.cache.mailboxContent.ThreadController
import com.infomaniak.mail.data.cache.mailboxInfo.MailboxController
import com.infomaniak.mail.data.models.Folder
import com.infomaniak.mail.data.models.Thread
import com.infomaniak.mail.data.models.Thread.ThreadFilter
import com.infomaniak.mail.utils.AccountUtils
import com.infomaniak.mail.utils.SearchUtils
import com.infomaniak.mail.utils.SearchUtils.convertToSearchThreads
import io.sentry.Sentry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map

class SearchViewModel : ViewModel() {

    private val searchQuery = MutableLiveData<String>()
    val selectedFilters = MutableLiveData(mutableSetOf<ThreadFilter>())

    private lateinit var currentFolderId: String
    private var selectedFolder: Folder? = null
    private var resourceNext: String? = null
    private var resourcePrevious: String? = null

    private var fetchThreadsJob: Job? = null

    val searchResults = observeSearchAndFilters().switchMap { (query, filters) -> fetchThreads(query, filters) }
    val foldersLive = FolderController.getFoldersAsync().map { it.list }.asLiveData(Dispatchers.IO)

    val hasNextPage get() = !resourceNext.isNullOrBlank()

    private fun observeSearchAndFilters() = MediatorLiveData<Pair<String?, Set<ThreadFilter>>>().apply {
        value = searchQuery.value to selectedFilters.value!!
        addSource(searchQuery) { value = it to value!!.second }
        addSource(selectedFilters) { value = value?.first to it }
    }

    fun init(currentFolderId: String) {
        this.currentFolderId = currentFolderId
    }

    fun refreshSearch() {
        resetPagination()
        searchQuery(searchQuery.value ?: "")
    }

    fun searchQuery(query: String) {
        resetPagination()
        searchQuery.value = query
    }

    fun selectFolder(folder: Folder?) {
        resetPagination()
        if (selectedFilters.value?.contains(ThreadFilter.FOLDER) == false) {
            selectedFilters.value = selectedFilters.value?.apply { add(ThreadFilter.FOLDER) }
        }
        selectedFolder = folder
    }

    fun toggleFilter(filter: ThreadFilter) {
        resetPagination()
        if (selectedFilters.value?.contains(filter) == true) {
            selectedFilters.value = selectedFilters.value?.apply { remove(filter) }
        } else {
            selectedFilters.value = SearchUtils.selectFilter(filter, selectedFilters.value)
        }
    }

    fun nextPage() {
        if (resourceNext.isNullOrBlank()) return
        searchQuery(searchQuery.value ?: "")
    }

    override fun onCleared() {
        CoroutineScope(Dispatchers.IO).launch {
            fetchThreadsJob?.cancelAndJoin()
            SearchUtils.deleteRealmSearchData()
            Log.i(TAG, "SearchViewModel>onCleared: called")
        }
        super.onCleared()
    }

    private fun resetPagination() {
        resourceNext = null
        resourcePrevious = null
    }

    private fun fetchThreads(query: String?, filters: Set<ThreadFilter>): LiveData<List<Thread>> {
        suspend fun ApiResponse<Thread.ThreadResult>.keepOldMessagesData() {
            runCatching {
                this.data?.threads?.let { ThreadController.getThreadsWithLocalMessages(it) }
            }.getOrElse { exception ->
                exception.printStackTrace()
                if (fetchThreadsJob?.isActive == true) Sentry.captureException(exception)
            }
        }

        fetchThreadsJob?.cancel()
        fetchThreadsJob = Job()
        return liveData(Dispatchers.IO + fetchThreadsJob!!) {
            if (!hasNextPage && resourcePrevious.isNullOrBlank()) SearchUtils.deleteRealmSearchData()

            if (fetchThreadsJob?.isCancelled == true) return@liveData
            val currentMailbox = MailboxController.getMailbox(AccountUtils.currentUserId, AccountUtils.currentMailboxId)!!
            val folderId = selectedFolder?.id ?: currentFolderId
            val searchFilters = SearchUtils.searchFilters(query, filters)
            val apiResponse = ApiRepository.searchThreads(currentMailbox.uuid, folderId, searchFilters, resourceNext)

            if (apiResponse.isSuccess()) {
                with(apiResponse) {
                    keepOldMessagesData()
                    resourceNext = data?.resourceNext
                    resourcePrevious = data?.resourcePrevious
                }
            } else if (resourceNext.isNullOrBlank()) {
                val threads = MessageController.searchMessages(query, filters, selectedFolder?.id).convertToSearchThreads()
                ThreadController.saveThreads(threads)
            }

            emitSource(ThreadController.getSearchThreadsAsync().map { it.list }.asLiveData(Dispatchers.IO))
        }
    }

    private companion object {
        val TAG = SearchViewModel::class.simpleName
    }

}
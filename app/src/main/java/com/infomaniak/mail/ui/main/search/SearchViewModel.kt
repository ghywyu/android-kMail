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
import com.infomaniak.lib.core.utils.SingleLiveEvent
import com.infomaniak.mail.data.api.ApiRepository
import com.infomaniak.mail.data.cache.mailboxContent.FolderController
import com.infomaniak.mail.data.cache.mailboxContent.MessageController
import com.infomaniak.mail.data.cache.mailboxContent.ThreadController
import com.infomaniak.mail.data.cache.mailboxInfo.MailboxController
import com.infomaniak.mail.data.models.Folder
import com.infomaniak.mail.data.models.thread.Thread
import com.infomaniak.mail.data.models.thread.Thread.ThreadFilter
import com.infomaniak.mail.data.models.thread.ThreadResult
import com.infomaniak.mail.ui.main.search.SearchFragment.VisibilityMode
import com.infomaniak.mail.utils.AccountUtils
import com.infomaniak.mail.utils.SearchUtils
import io.sentry.Sentry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val _selectedFilters = MutableStateFlow(emptySet<ThreadFilter>())
    private inline val selectedFilters get() = _selectedFilters.value.toMutableSet()
    val visibilityMode = MutableLiveData(VisibilityMode.RECENT_SEARCHES)
    val history = SingleLiveEvent<String>()

    private val coroutineContext = viewModelScope.coroutineContext + Dispatchers.IO

    /** It is simply used as a default value for the API */
    private lateinit var dummyFolderId: String
    private var resourceNext: String? = null
    private var resourcePrevious: String? = null

    private val isLastPage get() = resourceNext.isNullOrBlank()

    val searchResults = observeSearchAndFilters().flatMapLatest { (query, filters) ->
        val searchQuery = if (isLengthTooShort(query)) null else query
        fetchThreads(searchQuery, filters)
    }.asLiveData(coroutineContext)

    val folders = liveData(coroutineContext) { emit(FolderController.getFolders()) }

    var selectedFolder: Folder? = null
    var previousSearch: String? = null
    var previousMutuallyExclusiveChips: Int? = null
    var previousAttachments: Boolean? = null

    private fun observeSearchAndFilters() = searchQuery.combine(_selectedFilters) { query, filters ->
        query to filters
    }.debounce(SEARCH_DEBOUNCE_DURATION)

    fun init(dummyFolderId: String) {
        this.dummyFolderId = dummyFolderId
    }

    fun refreshSearch() {
        searchQuery(searchQuery.value ?: "")
    }

    fun searchQuery(query: String, resetPagination: Boolean = true) {
        if (resetPagination) resetPagination()
        searchQuery.value = query
    }

    fun selectFolder(folder: Folder?) {
        resetPagination()
        if (folder == null && selectedFilters.contains(ThreadFilter.FOLDER)) {
            ThreadFilter.FOLDER.unselect()
        } else if (folder != null) {
            ThreadFilter.FOLDER.select()
        }
        selectedFolder = folder
    }

    fun toggleFilter(filter: ThreadFilter) {
        resetPagination()
        if (selectedFilters.contains(filter)) filter.unselect() else filter.select()
    }

    fun unselectMutuallyExclusiveFilters() {
        resetPagination()
        _selectedFilters.value = selectedFilters.apply {
            removeAll(listOf(ThreadFilter.SEEN, ThreadFilter.UNSEEN, ThreadFilter.STARRED))
        }
    }

    fun nextPage() {
        if (isLastPage) return
        searchQuery(query = searchQuery.value ?: "", resetPagination = false)
    }

    override fun onCleared() {
        CoroutineScope(coroutineContext).launch {
            SearchUtils.deleteRealmSearchData()
            Log.i(TAG, "SearchViewModel>onCleared: called")
        }
        super.onCleared()
    }

    private fun ThreadFilter.select() {
        _selectedFilters.value = SearchUtils.selectFilter(this, selectedFilters)
    }

    private fun ThreadFilter.unselect() {
        _selectedFilters.value = selectedFilters.apply { remove(this@unselect) }
    }

    private fun resetPagination() {
        resourceNext = null
        resourcePrevious = null
    }

    private fun isLengthTooShort(query: String?) = query == null || query.length < MIN_SEARCH_QUERY

    private fun fetchThreads(query: String?, filters: Set<ThreadFilter>): Flow<List<Thread>> = flow {

        suspend fun ApiResponse<ThreadResult>.initSearchFolderThreads() {
            runCatching {
                this.data?.threads?.let { ThreadController.initAndGetSearchFolderThreads(it) }
            }.getOrElse { exception ->
                exception.printStackTrace()
                Sentry.captureException(exception)
            }
        }

        if (isLastPage && resourcePrevious.isNullOrBlank()) SearchUtils.deleteRealmSearchData()
        if (filters.isEmpty() && query.isNullOrBlank()) {
            visibilityMode.postValue(VisibilityMode.RECENT_SEARCHES)
            return@flow
        }

        visibilityMode.postValue(VisibilityMode.LOADING)

        val currentMailbox = MailboxController.getMailbox(AccountUtils.currentUserId, AccountUtils.currentMailboxId)!!
        val folderId = selectedFolder?.id ?: dummyFolderId
        val searchFilters = SearchUtils.searchFilters(query, filters)
        val apiResponse = ApiRepository.searchThreads(currentMailbox.uuid, folderId, searchFilters, resourceNext)

        if (apiResponse.isSuccess()) with(apiResponse) {
            initSearchFolderThreads()
            resourceNext = data?.resourceNext
            resourcePrevious = data?.resourcePrevious
        } else if (isLastPage) {
            ThreadController.saveThreads(searchMessages = MessageController.searchMessages(query, filters, folderId))
        }

        emitAll(ThreadController.getSearchThreadsAsync().mapLatest {
            query?.let(history::postValue)
            it.list.also { threads ->
                val resultsVisibilityMode = when {
                    selectedFilters.isEmpty() && isLengthTooShort(searchQuery.value) -> VisibilityMode.RECENT_SEARCHES
                    threads.isEmpty() -> VisibilityMode.NO_RESULTS
                    else -> VisibilityMode.RESULTS
                }
                visibilityMode.postValue(resultsVisibilityMode)
            }
        })
    }

    private companion object {

        val TAG = SearchViewModel::class.simpleName

        /**
         * The minimum value allowed for a search query
         */
        const val MIN_SEARCH_QUERY = 3

        const val SEARCH_DEBOUNCE_DURATION = 500L
    }
}

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
package com.infomaniak.mail.ui.main.menu

import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.liveData
import com.infomaniak.mail.data.cache.mailboxContent.FolderController
import com.infomaniak.mail.data.cache.mailboxInfo.QuotasController
import com.infomaniak.mail.data.models.Folder
import com.infomaniak.mail.ui.MainViewModel
import com.infomaniak.mail.utils.Utils.formatFoldersListWithAllChildren
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map

class MenuDrawerViewModel : ViewModel() {

    val folders = Transformations.switchMap(MainViewModel.currentMailboxObjectId) {
        liveData(Dispatchers.IO) {
            if (it != null) emitSource(FolderController.getFoldersAsync().map { getMenuFolders(it.list) }.asLiveData())
        }
    }

    val quotas = Transformations.switchMap(MainViewModel.currentMailboxObjectId) {
        liveData(Dispatchers.IO) { if (it != null) emitSource(QuotasController.getQuotasAsync(it).asLiveData()) }
    }

    private fun getMenuFolders(folders: List<Folder>): Triple<Folder?, List<Folder>, List<Folder>> {
        return folders.toMutableList().let { list ->

            val inbox = list
                .find { it.role == Folder.FolderRole.INBOX }
                ?.also(list::remove)

            val defaultFolders = list
                .filter { it.role != null }
                .sortedBy { it.role?.order }
                .also(list::removeAll)

            val customFolders = list
                .filter { it.parentFolder.isEmpty() }
                .sortedByDescending { it.isFavorite }
                .formatFoldersListWithAllChildren()

            Triple(inbox, defaultFolders, customFolders)
        }
    }
}

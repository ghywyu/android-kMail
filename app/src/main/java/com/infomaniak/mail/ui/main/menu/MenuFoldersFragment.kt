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
package com.infomaniak.mail.ui.main.menu

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import com.infomaniak.mail.ui.MainViewModel
import com.infomaniak.mail.views.MenuDrawerItemView

abstract class MenuFoldersFragment : Fragment() {

    protected val mainViewModel: MainViewModel by activityViewModels()

    protected abstract val inboxFolder: MenuDrawerItemView
    protected abstract val defaultFoldersList: RecyclerView
    protected abstract val customFoldersList: RecyclerView

    protected open val isInMenuDrawer: Boolean = true

    protected var inboxFolderId: String? = null

    protected val defaultFoldersAdapter: FolderAdapter by lazy {
        FolderAdapter(onClick = ::onFolderSelected, isInMenuDrawer = isInMenuDrawer)
    }

    protected val customFoldersAdapter: FolderAdapter by lazy {
        FolderAdapter(onClick = ::onFolderSelected, isInMenuDrawer = isInMenuDrawer)
    }

    protected abstract fun onFolderSelected(folderId: String)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        setupAdapters()
    }

    open fun setupListeners() {
        inboxFolder.setOnClickListener { inboxFolderId?.let(::onFolderSelected) }
    }

    open fun setupAdapters() {
        defaultFoldersList.adapter = defaultFoldersAdapter
        customFoldersList.adapter = customFoldersAdapter
    }
}
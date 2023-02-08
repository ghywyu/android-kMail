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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.infomaniak.lib.core.utils.safeNavigate
import com.infomaniak.mail.data.models.Folder.FolderRole
import com.infomaniak.mail.databinding.FragmentMoveBinding
import com.infomaniak.mail.ui.MainViewModel

class MoveFragment : Fragment() {

    private lateinit var binding: FragmentMoveBinding
    private val navigationArgs: MoveFragmentArgs by navArgs()
    private val mainViewModel: MainViewModel by activityViewModels()
    private val moveViewModel: MoveViewModel by viewModels()

    private var inboxFolderId: String? = null

    private val defaultFoldersAdapter = FolderAdapter(
        onClick = { folderId -> moveToFolder(folderId) },
        isInMenuDrawer = false,
    )
    private val customFoldersAdapter = FolderAdapter(
        onClick = { folderId -> moveToFolder(folderId) },
        isInMenuDrawer = false,
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentMoveBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAdapters()
        setupListeners()
        observeFolders()
    }

    private fun setupAdapters() = with(binding) {
        defaultFoldersList.adapter = defaultFoldersAdapter
        customFoldersList.adapter = customFoldersAdapter
    }

    private fun setupListeners() = with(binding) {
        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        iconAddFolder.setOnClickListener { safeNavigate(MoveFragmentDirections.actionMoveFragmentToNewFolderDialog()) }
        inboxFolder.setOnClickListener { inboxFolderId?.let(::moveToFolder) }
    }

    private fun observeFolders() = with(navigationArgs) {
        moveViewModel.currentFolders.observe(viewLifecycleOwner) { (inbox, defaultFolders, customFolders) ->

            inboxFolderId = inbox?.id

            binding.inboxFolder.setSelectedState(folderId == inboxFolderId)
            defaultFoldersAdapter.setFolders(defaultFolders.filterNot { it.role == FolderRole.DRAFT }, folderId)
            customFoldersAdapter.setFolders(customFolders, folderId)
        }
    }

    private fun moveToFolder(folderId: String) = with(navigationArgs) {
        mainViewModel.moveTo(folderId, threadUid, messageUid)
        findNavController().popBackStack()
    }
}

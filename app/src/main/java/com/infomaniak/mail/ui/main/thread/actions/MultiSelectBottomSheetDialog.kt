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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.infomaniak.mail.MatomoMail.ACTION_MOVE_NAME
import com.infomaniak.mail.MatomoMail.ACTION_SPAM_NAME
import com.infomaniak.mail.MatomoMail.trackMultiSelectActionEvent
import com.infomaniak.mail.R
import com.infomaniak.mail.databinding.BottomSheetMultiSelectBinding
import com.infomaniak.mail.ui.MainViewModel
import com.infomaniak.mail.ui.main.folder.ThreadListFragmentDirections
import com.infomaniak.mail.utils.animatedNavigation

class MultiSelectBottomSheetDialog : ActionsBottomSheetDialog() {

    private lateinit var binding: BottomSheetMultiSelectBinding
    private val mainViewModel: MainViewModel by activityViewModels()

    private val currentClassName: String by lazy { MultiSelectBottomSheetDialog::class.java.name }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return BottomSheetMultiSelectBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = with(mainViewModel) {
        super.onViewCreated(view, savedInstanceState)

        binding.mainActions.setClosingOnClickListener { id: Int ->
            val selectedThreadsUids = selectedThreads.map { it.uid }
            val selectedThreadsCount = selectedThreadsUids.count()

            when (id) {
                R.id.actionMove -> {
                    trackMultiSelectActionEvent(ACTION_MOVE_NAME, selectedThreadsCount, isFromBottomSheet = true)
                    animatedNavigation(
                        ThreadListFragmentDirections.actionThreadListFragmentToMoveFragment(
                            threadsUids = selectedThreadsUids.toTypedArray(),
                        ),
                        currentClassName = currentClassName,
                    )
                }
                R.id.actionSpam -> {
                    trackMultiSelectActionEvent(ACTION_SPAM_NAME, selectedThreadsCount, isFromBottomSheet = true)
                    toggleThreadsSpamStatus(selectedThreadsUids)
                }
                // R.id.actionPostpone -> {
                //     trackMultiSelectActionEvent(ACTION_POSTPONE_NAME, selectedThreadsCount, isFromBottomSheet = true)
                //     notYetImplemented()
                // }
            }
            isMultiSelectOn = false
        }
    }
}
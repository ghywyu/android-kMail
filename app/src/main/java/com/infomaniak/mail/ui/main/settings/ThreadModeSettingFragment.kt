/*
 * Infomaniak ikMail - Android
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
package com.infomaniak.mail.ui.main.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.infomaniak.mail.R
import com.infomaniak.mail.data.LocalSettings
import com.infomaniak.mail.data.LocalSettings.ThreadMode
import com.infomaniak.mail.data.LocalSettings.ThreadMode.CONVERSATION
import com.infomaniak.mail.data.LocalSettings.ThreadMode.MESSAGE
import com.infomaniak.mail.databinding.FragmentThreadModeSettingBinding
import com.infomaniak.mail.utils.createDescriptionDialog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ThreadModeSettingFragment : Fragment() {

    @Inject
    lateinit var localSettings: LocalSettings

    private lateinit var binding: FragmentThreadModeSettingBinding
    private val threadModeSettingViewModel: ThreadModeSettingViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentThreadModeSettingBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = with(binding.radioGroup) {
        super.onViewCreated(view, savedInstanceState)

        initBijectionTable(
            R.id.conversationMode to CONVERSATION,
            R.id.messageMode to MESSAGE,
        )

        check(localSettings.threadMode)

        onItemCheckedListener { _, _, enum ->
            val threadMode = enum as ThreadMode
            createDescriptionDialog(
                title = getString(R.string.settingsThreadModeWarningTitle, getString(threadMode.localisedNameRes)),
                description = getString(R.string.settingsThreadModeWarningDescription),
                onPositiveButtonClicked = {
                    localSettings.threadMode = threadMode
                    threadModeSettingViewModel.dropAllMailboxesContentThenReloadApp()
                },
                onDismissed = { check(localSettings.threadMode) },
            ).show()
        }
    }
}

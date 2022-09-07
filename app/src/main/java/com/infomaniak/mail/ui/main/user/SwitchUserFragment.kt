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
package com.infomaniak.mail.ui.main.user

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.infomaniak.lib.core.models.user.User
import com.infomaniak.mail.data.models.Mailbox
import com.infomaniak.mail.databinding.FragmentSwitchUserBinding
import com.infomaniak.mail.ui.MainViewModel
import com.infomaniak.mail.ui.login.LoginActivity
import com.infomaniak.mail.ui.main.user.SwitchUserAccountsAdapter.UiAccount
import com.infomaniak.mail.utils.AccountUtils
import com.infomaniak.mail.utils.sortMailboxes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SwitchUserFragment : Fragment() {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val switchUserViewModel: SwitchUserViewModel by viewModels()

    private lateinit var binding: FragmentSwitchUserBinding

    private val accountsAdapter = SwitchUserAccountsAdapter { selectedMailbox ->
        if (selectedMailbox.userId == AccountUtils.currentUserId) {
            mainViewModel.openMailbox(selectedMailbox)
            findNavController().popBackStack()
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                AccountUtils.currentUser = AccountUtils.getUserById(selectedMailbox.userId)
                AccountUtils.currentMailboxId = selectedMailbox.mailboxId

                withContext(Dispatchers.Main) {
                    mainViewModel.close()

                    AccountUtils.reloadApp?.invoke()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentSwitchUserBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = with(binding) {
        super.onViewCreated(view, savedInstanceState)
        recyclerViewAccount.adapter = accountsAdapter
        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        addAccount.setOnClickListener { startActivity(Intent(context, LoginActivity::class.java)) }
        observeAccounts()
    }

    private fun observeAccounts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val users = AccountUtils.getAllUsersSync()
            with(switchUserViewModel) {
                fetchUsersMailboxes(users)
                users.forEach { user ->
                    withContext(Dispatchers.Main) {
                        mainViewModel.listenToMailboxes(user.id).observe(viewLifecycleOwner) { mailboxes ->
                            onMailboxesChange(user, mailboxes)
                        }
                    }
                }
            }
        }
    }

    private fun onMailboxesChange(user: User, mailboxes: List<Mailbox>) {
        val uiAccount = UiAccount(user, mailboxes.sortMailboxes())
        accountsAdapter.upsertAccount(uiAccount, MainViewModel.currentMailboxObjectId.value)
    }
}
/*
 * Infomaniak kMail - Android
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
package com.infomaniak.mail.ui.main.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.infomaniak.lib.core.utils.context
import com.infomaniak.lib.core.utils.safeNavigate
import com.infomaniak.mail.MatomoMail.trackAccountEvent
import com.infomaniak.mail.R
import com.infomaniak.mail.databinding.FragmentAccountBinding
import com.infomaniak.mail.ui.main.menu.SwitchMailboxesAdapter
import com.infomaniak.mail.utils.AccountUtils
import com.infomaniak.mail.utils.animatedNavigation
import com.infomaniak.mail.utils.createDescriptionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AccountFragment : Fragment() {

    private lateinit var binding: FragmentAccountBinding
    private val accountViewModel: AccountViewModel by viewModels()

    private val logoutAlert by lazy { initLogoutAlert() }

    private var mailboxAdapter = SwitchMailboxesAdapter(isInMenuDrawer = false, lifecycleScope)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentAccountBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?): Unit = with(binding) {
        AccountUtils.currentUser?.let { user ->
            avatar.loadAvatar(user)
            name.apply {
                isGone = user.displayName.isNullOrBlank()
                text = user.displayName
            }
            mail.text = user.email
        }

        changeAccountButton.setOnClickListener {
            animatedNavigation(AccountFragmentDirections.actionAccountFragmentToSwitchUserFragment())
        }

        attachNewMailboxButton.setOnClickListener {
            context.trackAccountEvent("addMailbox")
            safeNavigate(AccountFragmentDirections.actionAccountFragmentToAttachMailboxFragment())
        }

        disconnectAccountButton.setOnClickListener {
            context.trackAccountEvent("logOut")
            logoutAlert.show()
        }

        mailboxesRecyclerView.apply {
            adapter = mailboxAdapter
            isFocusable = false
        }

        observeAccountsLive()
    }

    private fun removeCurrentUser() = lifecycleScope.launch(Dispatchers.IO) {
        requireContext().trackAccountEvent("logOutConfirm")
        AccountUtils.removeUser(requireContext(), AccountUtils.currentUser!!)
    }

    private fun observeAccountsLive() = with(accountViewModel) {
        observeAccountsLive.observe(viewLifecycleOwner, mailboxAdapter::setMailboxes)
        lifecycleScope.launch(Dispatchers.IO) { updateMailboxes() }
    }

    private fun initLogoutAlert() = createDescriptionDialog(
        title = getString(R.string.confirmLogoutTitle),
        description = AccountUtils.currentUser?.let { getString(R.string.confirmLogoutDescription, it.email) } ?: "",
        onPositiveButtonClicked = ::removeCurrentUser,
    )
}
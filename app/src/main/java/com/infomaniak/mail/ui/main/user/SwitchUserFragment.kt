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
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.infomaniak.lib.core.utils.context
import com.infomaniak.mail.MatomoMail.trackAccountEvent
import com.infomaniak.mail.data.cache.RealmDatabase
import com.infomaniak.mail.data.models.AppSettings
import com.infomaniak.mail.databinding.FragmentSwitchUserBinding
import com.infomaniak.mail.ui.login.LoginActivity
import com.infomaniak.mail.utils.AccountUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SwitchUserFragment : Fragment() {

    private lateinit var binding: FragmentSwitchUserBinding
    private val switchUserViewModel: SwitchUserViewModel by viewModels()

    private val accountsAdapter = SwitchUserAdapter(AccountUtils.currentUserId) { user ->
        lifecycleScope.launch(Dispatchers.IO) {
            if (user.id != AccountUtils.currentUserId) {
                requireContext().trackAccountEvent("switch")
                AccountUtils.currentUser = user
                AccountUtils.currentMailboxId = AppSettings.DEFAULT_ID
                RealmDatabase.close()
                AccountUtils.reloadApp?.invoke()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentSwitchUserBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = with(binding) {
        super.onViewCreated(view, savedInstanceState)
        setAdapter()
        setupOnClickListener()
        observeAccounts()
    }

    private fun setAdapter() {
        binding.recyclerViewAccount.adapter = accountsAdapter
    }

    private fun setupOnClickListener() = with(binding) {
        addAccount.setOnClickListener {
            context.trackAccountEvent("add")
            startActivity(Intent(context, LoginActivity::class.java))
        }
    }

    private fun observeAccounts() {
        switchUserViewModel.allUsers.observe(viewLifecycleOwner, accountsAdapter::initializeAccounts)
    }
}

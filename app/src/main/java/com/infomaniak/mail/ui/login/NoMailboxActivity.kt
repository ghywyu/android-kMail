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
package com.infomaniak.mail.ui.login

import android.os.Bundle
import com.infomaniak.lib.core.utils.Utils.lockOrientationForSmallScreens
import com.infomaniak.lib.core.utils.UtilsUi.openUrl
import com.infomaniak.lib.core.utils.isNightModeEnabled
import com.infomaniak.mail.BuildConfig.SHOP_URL
import com.infomaniak.mail.R
import com.infomaniak.mail.data.LocalSettings
import com.infomaniak.mail.databinding.ActivityNoMailboxBinding
import com.infomaniak.mail.ui.ThemedActivity
import com.infomaniak.mail.utils.changePathColor
import com.infomaniak.mail.utils.repeatFrame

class NoMailboxActivity : ThemedActivity() {

    private val binding by lazy { ActivityNoMailboxBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        lockOrientationForSmallScreens()

        super.onCreate(savedInstanceState)

        with(binding) {

            setContentView(root)

            val isNightModeEnabled = isNightModeEnabled()

            noMailboxIconLayout.apply {
                IlluColors.illuNoMailboxColors.forEach { changePathColor(it, isNightModeEnabled) }

                val isPink = LocalSettings.getInstance(this@NoMailboxActivity).accentColor == LocalSettings.AccentColor.PINK
                val sleeveColors = if (isPink) IlluColors.illuNoMailboxPinkColor else IlluColors.illuNoMailboxBlueColor
                sleeveColors.forEach { changePathColor(it, isNightModeEnabled) }

                setAnimation(R.raw.illu_no_mailbox)
                repeatFrame(42, 112)
            }

            noMailboxActionButton.setOnClickListener {
                openUrl(SHOP_URL)
                onBackPressedDispatcher.onBackPressed()
            }

            connectAnotherAccountButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
    }
}
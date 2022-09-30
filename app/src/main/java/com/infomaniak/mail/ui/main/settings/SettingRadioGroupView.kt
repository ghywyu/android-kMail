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
package com.infomaniak.mail.ui.main.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.core.view.children
import com.infomaniak.mail.R
import com.infomaniak.lib.core.R as RCore


class SettingRadioGroupView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), OnCheckListener {

    @IdRes
    private var checkedId: Int = View.NO_ID
    private var checkedValue: String? = null
    private var onItemCheckedListener: ((Int, String?) -> Unit)? = null
    private var shouldAddDividers = true

    init {
        orientation = VERTICAL

        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.SettingRadioGroupView, 0, 0)

            checkedId = typedArray.getResourceId(R.styleable.SettingRadioGroupView_defaultCheckedId, View.NO_ID)
            check(checkedId)

            shouldAddDividers = !typedArray.getBoolean(R.styleable.SettingRadioGroupView_ignoreDividers, false)

            typedArray.recycle()
        }

        if (shouldAddDividers) {
            showDividers = SHOW_DIVIDER_MIDDLE
            dividerPadding = resources.getDimension(RCore.dimen.marginStandardSmall).toInt()
            dividerDrawable = ContextCompat.getDrawable(context, R.drawable.setting_divider)
        }
    }

    override fun onFinishInflate() {
        children.forEach {
            if (it is RadioCheckable && it.id == View.NO_ID) it.id = generateViewId()
        }
    }

    override fun onChecked(@IdRes viewId: Int) {
        check(viewId)
        if (viewId != checkedId) onItemCheckedListener?.invoke(checkedId, checkedValue)
    }

    @Suppress("TypeParameterFindViewById")
    private fun check(@IdRes viewId: Int) {
        if (viewId == checkedId) return

        (findViewById(checkedId) as? RadioCheckable)?.uncheck()
        (findViewById(viewId) as RadioCheckable).apply {
            check()
            checkedValue = associatedValue
        }

        checkedId = viewId
    }

    fun onItemCheckedListener(listener: ((Int, String?) -> Unit)?) {
        onItemCheckedListener = listener
    }
}

interface RadioCheckable {
    val associatedValue: String?
    fun check()
    fun uncheck()
}

interface OnCheckListener {
    fun onChecked(@IdRes viewId: Int)
}
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
package com.infomaniak.mail.ui.main.newMessage

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.content.res.getColorOrThrow
import com.infomaniak.mail.R
import com.infomaniak.mail.databinding.ItemToggleableTextFormatterBinding

class ToggleableTextFormatterItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var isSwitched: Boolean = false
        set(isActivated) = with(binding) {
            field = isActivated
            if (isActivated) {
                binding.icon.setColorFilter(selectedIconColor)
                background.setBackgroundColor(selectedBackgroundColor)
            } else {
                binding.icon.setColorFilter(unselectedIconColor)
                background.setBackgroundColor(unselectedBackgroundColor)
            }
        }

    var binding: ItemToggleableTextFormatterBinding

    @ColorInt
    private var unselectedIconColor: Int = -1
    @ColorInt
    private var selectedIconColor: Int = -1
    @ColorInt
    private var unselectedBackgroundColor: Int = -1
    @ColorInt
    private var selectedBackgroundColor: Int = -1

    init {
        binding = ItemToggleableTextFormatterBinding.inflate(LayoutInflater.from(context), this, true)

        with(binding) {
            if (attrs != null) {
                val typedArray = context.obtainStyledAttributes(attrs, R.styleable.ToggleableTextFormatterItemView, 0, 0)

                val iconDrawable = typedArray.getDrawable(R.styleable.ToggleableTextFormatterItemView_icon)
                val contentDescription = typedArray.getString(
                    R.styleable.ToggleableTextFormatterItemView_android_contentDescription
                )

                unselectedIconColor = typedArray.getColorOrThrow(R.styleable.ToggleableTextFormatterItemView_iconColor)
                selectedIconColor = typedArray.getColorOrThrow(R.styleable.ToggleableTextFormatterItemView_selectedIconColor)
                unselectedBackgroundColor = typedArray.getColorOrThrow(
                    R.styleable.ToggleableTextFormatterItemView_backgroundColor,
                )
                selectedBackgroundColor = typedArray.getColorOrThrow(
                    R.styleable.ToggleableTextFormatterItemView_selectedBackgroundColor,
                )

                icon.apply {
                    contentDescription?.let { this.contentDescription = it }
                    setImageDrawable(iconDrawable)
                    setColorFilter(unselectedIconColor)
                }
                background.setBackgroundColor(unselectedBackgroundColor)

                typedArray.recycle()
            }
        }
    }

    override fun setOnClickListener(listener: OnClickListener?) {
        binding.root.setOnClickListener {
            isSwitched = !isSwitched
            listener?.onClick(it)
        }
    }
}

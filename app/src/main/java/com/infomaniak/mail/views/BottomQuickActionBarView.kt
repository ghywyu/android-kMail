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
package com.infomaniak.mail.views

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MenuInflater
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.view.forEach
import androidx.core.view.isGone
import com.google.android.material.button.MaterialButton
import com.infomaniak.mail.R
import com.infomaniak.mail.databinding.ViewBottomQuickActionBarBinding

class BottomQuickActionBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var binding: ViewBottomQuickActionBarBinding
    private var buttons: List<MaterialButton>

    init {
        binding = ViewBottomQuickActionBarBinding.inflate(LayoutInflater.from(context), this, true)

        with(binding) {
            buttons = listOf(button1, button2, button3, button4, button5)

            if (attrs != null) {
                val typedArray = context.obtainStyledAttributes(attrs, R.styleable.BottomQuickActionBarView, 0, 0)

                val menuRes = typedArray.getResourceId(R.styleable.BottomQuickActionBarView_menu, -1)
                if (menuRes == -1) return@with

                val menu = MenuBuilder(context)
                MenuInflater(context).inflate(menuRes, menu)

                val items = mutableListOf<Pair<Drawable, CharSequence>>().apply {
                    menu.forEach { item -> add(item.icon to item.title) }
                }.take(buttons.count())

                buttons.forEachIndexed { index, button ->
                    if (index >= items.count()) {
                        button.isGone = true
                    } else {
                        val (icon, title) = items[index]
                        button.icon = icon
                        button.text = title
                    }
                }

                typedArray.recycle()
            }
        }
    }

    fun setOnItemClickListener(callback: (Int) -> Unit) {
        buttons.forEachIndexed { index, button ->
            button.setOnClickListener { callback(index) }
        }
    }

    fun changeIcon(index: Int, @DrawableRes icon: Int) {
        buttons[index].setIconResource(icon)
    }

    fun changeText(index: Int, @StringRes text: Int) {
        buttons[index].setText(text)
    }
}

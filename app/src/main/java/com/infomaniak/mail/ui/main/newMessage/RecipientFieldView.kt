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
package com.infomaniak.mail.ui.main.newMessage

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.infomaniak.lib.core.utils.getAttributes
import com.infomaniak.lib.core.utils.hideKeyboard
import com.infomaniak.lib.core.utils.showKeyboard
import com.infomaniak.lib.core.utils.toPx
import com.infomaniak.mail.MatomoMail.trackMessageEvent
import com.infomaniak.mail.R
import com.infomaniak.mail.data.models.correspondent.MergedContact
import com.infomaniak.mail.data.models.correspondent.Recipient
import com.infomaniak.mail.databinding.ChipContactBinding
import com.infomaniak.mail.databinding.ViewContactChipContextMenuBinding
import com.infomaniak.mail.databinding.ViewRecipientFieldBinding
import com.infomaniak.mail.utils.isEmail
import com.infomaniak.mail.utils.toggleChevron
import kotlin.math.min

class RecipientFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding by lazy { ViewRecipientFieldBinding.inflate(LayoutInflater.from(context), this, true) }

    private var contactAdapter: ContactAdapter? = null
    private var contactMap: Map<String, Map<String, MergedContact>> = emptyMap()
    private val recipients = mutableSetOf<Recipient>()

    private val popupMaxWidth by lazy { 300.toPx() }

    private val contextMenuBinding by lazy {
        ViewContactChipContextMenuBinding.inflate(
            LayoutInflater.from(context),
            null,
            false
        )
    }

    private val contactPopupWindow by lazy {
        PopupWindow(context).apply {
            contentView = contextMenuBinding.root
            height = ViewGroup.LayoutParams.WRAP_CONTENT

            val displayMetrics = context.resources.displayMetrics
            val percentageOfScreen = (displayMetrics.widthPixels * MAX_WIDTH_PERCENTAGE).toInt()
            width = min(percentageOfScreen, popupMaxWidth)

            isFocusable = true
        }
    }

    private var onAutoCompletionToggled: ((hasOpened: Boolean) -> Unit)? = null
    private var onToggle: ((isCollapsed: Boolean) -> Unit)? = null
    private var onContactRemoved: ((Recipient) -> Unit)? = null
    private var onContactAdded: ((Recipient) -> Unit)? = null
    private var onCopyContactAddress: ((Recipient) -> Unit)? = null
    private var setSnackBar: ((Int) -> Unit) = {}

    private var isToggleable = false
    private var isCollapsed = true
        set(value) {
            if (value == field) return
            field = value
            updateCollapsedUiState(value)
        }

    private lateinit var autoCompletedContacts: RecyclerView

    private var isAutoCompletionOpened
        get() = autoCompletedContacts.isVisible
        set(value) {
            autoCompletedContacts.isVisible = value
            binding.chevron.isGone = value || !isToggleable
        }

    init {
        with(binding) {
            attrs?.getAttributes(context, R.styleable.RecipientFieldView) {
                prefix.text = getText(R.styleable.RecipientFieldView_title)
                isToggleable = getBoolean(R.styleable.RecipientFieldView_toggleable, isToggleable)
            }

            chevron.isVisible = isToggleable
            isCollapsed = isToggleable

            if (isToggleable) {
                chevron.setOnClickListener {
                    context.trackMessageEvent("openRecipientsFields", isCollapsed)
                    isCollapsed = !isCollapsed
                    if (isCollapsed) textInput.hideKeyboard()
                }

                plusChip.setOnClickListener { isCollapsed = !isCollapsed }

                transparentButton.setOnClickListener {
                    isCollapsed = !isCollapsed
                    textInput.showKeyboard()
                }

                singleChip.root.setOnClickListener {
                    removeRecipient(recipients.first())
                    updateCollapsedChipValues(isCollapsed)
                }
            }

            contactAdapter = ContactAdapter(
                usedContacts = mutableSetOf(),
                onContactClicked = { addRecipient(it.email, it.name) },
                onAddUnrecognizedContact = {
                    val input = textInput.text.toString()
                    if (input.isEmail()) {
                        addRecipient(email = input, name = input)
                    } else {
                        setSnackBar(R.string.addUnknownRecipientInvalidEmail)
                    }
                },
                setSnackBar = { setSnackBar(it) },
            )

            setTextInputListeners()

            if (isInEditMode) {
                singleChip.root.isVisible = isToggleable
                plusChip.isVisible = isToggleable
            }
        }
    }

    private fun ViewRecipientFieldBinding.setTextInputListeners() {
        textInput.apply {
            doOnTextChanged { text, _, _, _ ->
                if (text?.isNotEmpty() == true) {
                    if ((text.trim().count()) > 0) contactAdapter!!.filterField(text) else contactAdapter!!.clear()
                    if (!isAutoCompletionOpened) openAutoCompletion()
                } else if (isAutoCompletionOpened) {
                    closeAutoCompletion()
                }
            }

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE && textInput.text?.isNotBlank() == true) {
                    contactAdapter!!.addFirstAvailableItem()
                }
                true // Keep keyboard open
            }

            setBackspaceOnEmptyFieldListener(::focusLastChip)
        }
    }

    private fun focusLastChip() = with(binding) {
        if (itemsChipGroup.childCount > 0) itemsChipGroup.children.last().requestFocusFromTouch()
    }

    private fun focusTextField() {
        binding.textInput.requestFocus()
    }

    private fun updateCollapsedUiState(isCollapsed: Boolean) = with(binding) {
        chevron.toggleChevron(isCollapsed)

        updateCollapsedChipValues(isCollapsed)
        itemsChipGroup.isGone = isCollapsed

        onToggle?.invoke(isCollapsed)
    }

    private fun updateCollapsedChipValues(isCollapsed: Boolean) = with(binding) {
        val isTextInputAccessible = !isCollapsed || recipients.isEmpty()

        singleChip.root.apply {
            isGone = isTextInputAccessible
            text = recipients.firstOrNull()?.getNameOrEmail() ?: ""
        }
        plusChip.apply {
            isGone = !isCollapsed || recipients.count() <= 1
            text = "+${recipients.count() - 1}"
        }

        transparentButton.isGone = isTextInputAccessible
        textInput.isVisible = isTextInputAccessible
    }

    fun updateContacts(allContacts: List<MergedContact>, newContactMap: Map<String, Map<String, MergedContact>>) {
        contactAdapter?.updateContacts(allContacts)
        contactMap = newContactMap
    }

    private fun openAutoCompletion() {
        isAutoCompletionOpened = true
        onAutoCompletionToggled?.invoke(isAutoCompletionOpened)
    }

    private fun closeAutoCompletion() {
        isAutoCompletionOpened = false
        onAutoCompletionToggled?.invoke(isAutoCompletionOpened)
    }

    private fun addRecipient(email: String, name: String) {
        if (recipients.isEmpty()) isCollapsed = false
        val recipient = Recipient().initLocalValues(email, name)
        val recipientIsNew = contactAdapter!!.addUsedContact(email)
        if (recipientIsNew) {
            recipients.add(recipient)
            createChip(recipient)
            onContactAdded?.invoke(recipient)
            clearField()
        }
    }

    private fun createChip(recipient: Recipient) {
        ChipContactBinding.inflate(LayoutInflater.from(context)).root.apply {
            text = recipient.getNameOrEmail()
            setOnClickListener { showContactContextMenu(recipient) }
            setOnBackspaceListener {
                removeRecipient(recipient)
                focusTextField()
            }
            binding.itemsChipGroup.addView(this)
        }
    }

    private fun BackspaceAwareChip.showContactContextMenu(recipient: Recipient) {
        contextMenuBinding.apply {
            // TODO : Opti only assign listener once but change id every time
            copyContactAddressButton.setOnClickListener {
                onCopyContactAddress?.invoke(recipient)
                contactPopupWindow.dismiss()
            }
            deleteContactButton.setOnClickListener {
                removeRecipient(recipient)
                contactPopupWindow.dismiss()
            }
            contactDetails.setRecipient(recipient, contactMap)
        }

        hideKeyboard()
        contactPopupWindow.showAsDropDown(this)
    }

    private fun removeRecipient(recipient: Recipient) = with(binding) {
        val index = recipients.indexOf(recipient)
        val successfullyRemoved = contactAdapter!!.removeUsedEmail(recipient.email)
        if (successfullyRemoved) {
            recipients.remove(recipient)
            itemsChipGroup.removeViewAt(index)
            onContactRemoved?.invoke(recipient)
        }
    }

    fun initRecipientField(
        autoComplete: RecyclerView,
        onAutoCompletionToggledCallback: (hasOpened: Boolean) -> Unit,
        onContactAddedCallback: ((Recipient) -> Unit),
        onContactRemovedCallback: ((Recipient) -> Unit),
        onCopyContactAddressCallback: ((Recipient) -> Unit),
        onToggleCallback: ((isCollapsed: Boolean) -> Unit)? = null,
        setSnackBarCallback: (titleRes: Int) -> Unit,
    ) {
        autoCompletedContacts = autoComplete
        autoCompletedContacts.adapter = contactAdapter

        onToggle = onToggleCallback
        onAutoCompletionToggled = onAutoCompletionToggledCallback
        onContactAdded = onContactAddedCallback
        onContactRemoved = onContactRemovedCallback
        onCopyContactAddress = onCopyContactAddressCallback

        setSnackBar = setSnackBarCallback
    }

    fun clearField() {
        binding.textInput.setText("")
    }

    fun initRecipients(initialRecipients: List<Recipient>) {
        initialRecipients.forEach {
            if (recipients.add(it)) {
                createChip(it)
                contactAdapter!!.addUsedContact(it.email)
            }
        }
        updateCollapsedChipValues(isCollapsed)
    }

    private companion object {
        const val MAX_WIDTH_PERCENTAGE = 0.8
    }
}

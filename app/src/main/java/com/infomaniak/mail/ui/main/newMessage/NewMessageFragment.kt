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

import android.content.ClipDescription
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.transition.TransitionManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.navArgs
import com.infomaniak.lib.core.utils.FilePicker
import com.infomaniak.lib.core.utils.SnackbarUtils.showSnackbar
import com.infomaniak.mail.R
import com.infomaniak.mail.data.models.Mailbox
import com.infomaniak.mail.data.models.MergedContact
import com.infomaniak.mail.databinding.FragmentNewMessageBinding
import com.infomaniak.mail.ui.main.newMessage.NewMessageActivity.EditorAction
import com.infomaniak.mail.ui.main.newMessage.NewMessageFragment.FieldType.*
import com.infomaniak.mail.ui.main.newMessage.NewMessageViewModel.ImportationResult
import com.infomaniak.mail.ui.main.thread.AttachmentAdapter
import com.infomaniak.mail.utils.context
import com.infomaniak.mail.utils.notYetImplemented
import com.infomaniak.mail.workers.DraftsActionsWorker
import com.google.android.material.R as RMaterial

class NewMessageFragment : Fragment() {

    private lateinit var binding: FragmentNewMessageBinding
    private val newMessageActivityArgs by lazy { requireActivity().navArgs<NewMessageActivityArgs>().value }
    private val newMessageViewModel: NewMessageViewModel by activityViewModels()

    private val addressListPopupWindow by lazy { ListPopupWindow(binding.root.context) }
    private lateinit var filePicker: FilePicker

    private val attachmentAdapter = AttachmentAdapter(shouldDisplayCloseButton = true, onDelete = ::onDeleteAttachment)

    private var mailboxes = emptyList<Mailbox>()
    private var selectedMailboxIndex = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentNewMessageBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?): Unit = with(binding) {
        super.onViewCreated(view, savedInstanceState)

        filePicker = FilePicker(this@NewMessageFragment)
        initDraftAndUi()

        doAfterSubjectChange()
        doAfterBodyChange()

        observeMailboxes()

        observeEditorActions()
        observeNewAttachments()
    }

    private fun initDraftAndUi() = with(binding) {
        attachmentsRecyclerView.adapter = attachmentAdapter

        setupAutoCompletionFields()

        subjectTextField.apply {
            // Enables having imeOptions="actionNext" and inputType="textMultiLine" at the same time
            setRawInputType(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)

            filters = arrayOf<InputFilter>(object : InputFilter {
                override fun filter(source: CharSequence?, s: Int, e: Int, d: Spanned?, dS: Int, dE: Int): CharSequence? {
                    source?.toString()?.let { if (it.contains("\n")) return it.replace("\n", "") }
                    return null
                }
            })
        }

        bodyText.setOnFocusChangeListener { _, hasFocus -> toggleEditor(hasFocus) }
        setOnKeyboardListener { isOpened -> toggleEditor(bodyText.hasFocus() && isOpened) }

        newMessageViewModel.initDraftAndUi(newMessageActivityArgs).observe(viewLifecycleOwner) { isSuccess ->
            if (isSuccess) {
                observeContacts()
                populateUiWithExistingDraftData()
            } else {
                requireActivity().finish()
            }
        }
    }

    private fun setupAutoCompletionFields() = with(binding) {
        toField.apply {
            setOnToggleListener(::openAdvancedFields)
            onAutoCompletionToggled { hasOpened -> toggleAutoCompletion(TO, hasOpened) }
            onContactAdded(newMessageViewModel.mailTo::add)
            onContactRemoved(newMessageViewModel.mailTo::remove)
            // onFocusNext {
            //     openAdvancedFields(false)
            //     ccField.requestFocus()
            // }
        }

        ccField.apply {
            onAutoCompletionToggled { hasOpened -> toggleAutoCompletion(CC, hasOpened) }
            onContactAdded(newMessageViewModel.mailCc::add)
            onContactRemoved(newMessageViewModel.mailCc::remove)
            //     onFocusNext { bccField.requestFocus() }
            //     onFocusPrevious { toField.requestFocus() }
        }

        bccField.apply {
            onAutoCompletionToggled { hasOpened -> toggleAutoCompletion(BCC, hasOpened) }
            onContactAdded(newMessageViewModel.mailBcc::add)
            onContactRemoved(newMessageViewModel.mailBcc::remove)
            //     onFocusNext { subjectTextField.requestFocus() }
            //     onFocusPrevious { ccField.requestFocus() }
        }
    }

    private fun openAdvancedFields(isCollapsed: Boolean) = with(binding) {
        cc.isGone = isCollapsed
        bcc.isGone = isCollapsed
    }

    private fun setOnKeyboardListener(callback: (isOpened: Boolean) -> Unit) {
        ViewCompat.setOnApplyWindowInsetsListener(requireActivity().window.decorView) { _, insets ->
            val isKeyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            callback(isKeyboardVisible)
            insets
        }
    }

    private fun observeContacts() {
        newMessageViewModel.getMergedContacts().observe(viewLifecycleOwner, ::setupContactsAdapter)
    }

    private fun setupContactsAdapter(allContacts: List<MergedContact>) = with(newMessageViewModel) {
        binding.toField.updateContacts(binding.autoCompleteTo, allContacts, mutableSetOf())
        binding.ccField.updateContacts(binding.autoCompleteCc, allContacts, mutableSetOf())
        binding.bccField.updateContacts(binding.autoCompleteBcc, allContacts, mutableSetOf())
    }

    private fun toggleAutoCompletion(field: FieldType? = null, isAutoCompletionOpened: Boolean) = with(newMessageViewModel) {
        binding.preFields.isGone = isAutoCompletionOpened
        binding.to.isVisible = !isAutoCompletionOpened || field == TO
        binding.cc.isVisible = !isAutoCompletionOpened || field == CC
        binding.bcc.isVisible = !isAutoCompletionOpened || field == BCC
        binding.postFields.isGone = isAutoCompletionOpened

        newMessageViewModel.isAutoCompletionOpened = isAutoCompletionOpened
    }

    private fun populateUiWithExistingDraftData() = with(newMessageViewModel) {
        attachmentAdapter.addAll(mailAttachments)
        binding.attachmentsRecyclerView.isGone = attachmentAdapter.itemCount == 0
        binding.subjectTextField.setText(mailSubject)
        binding.bodyText.setText(mailBody)
        mailSignature?.let {
            binding.signatureWebView.loadDataWithBaseURL("", it, ClipDescription.MIMETYPE_TEXT_HTML, "utf-8", "")
            binding.removeSignature.setOnClickListener {
                mailSignature = null
                binding.separatedSignature.isGone = true
            }
            binding.separatedSignature.isVisible = true
        }
        binding.toField.initRecipients(mailTo)
        binding.ccField.initRecipients(mailCc)
        binding.bccField.initRecipients(mailBcc)
    }

    private fun doAfterSubjectChange() {
        binding.subjectTextField.doAfterTextChanged { editable ->
            editable?.toString()?.let(newMessageViewModel::updateMailSubject)
        }
    }

    private fun doAfterBodyChange() {
        binding.bodyText.doAfterTextChanged { editable ->
            editable?.toString()?.let(newMessageViewModel::updateMailBody)
        }
    }

    private fun observeMailboxes() {
        newMessageViewModel.observeMailboxes().observe(viewLifecycleOwner) {
            setupFromField(it.first, it.second)
        }
    }

    // TODO: Since we don't want to allow changing email & signature, maybe this code could be simplified?
    private fun setupFromField(mailboxes: List<Mailbox>, currentMailboxIndex: Int) = with(binding) {

        this@NewMessageFragment.mailboxes = mailboxes
        selectedMailboxIndex = currentMailboxIndex
        val mails = mailboxes.map { it.email }

        fromMailAddress.text = mailboxes[selectedMailboxIndex].email

        val adapter = ArrayAdapter(context, RMaterial.layout.support_simple_spinner_dropdown_item, mails)
        addressListPopupWindow.apply {
            setAdapter(adapter)
            isModal = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            anchorView = fromMailAddress
            width = fromMailAddress.width
            setOnItemClickListener { _, _, position, _ ->
                fromMailAddress.text = mails[position]
                selectedMailboxIndex = position
                dismiss()
            }
        }

        // TODO: This is disabled for now, because we probably don't want to allow changing email & signature.
        // if (mails.count() > 1) {
        //     fromMailAddress.apply {
        //         setOnClickListener { _ -> addressListPopupWindow.show() }
        //         isClickable = true
        //         isFocusable = true
        //     }
        // }
    }

    private fun observeEditorActions() {
        newMessageViewModel.editorAction.observe(requireActivity()) { (editorAction, isToggled) ->
            when (editorAction) {
                EditorAction.ATTACHMENT -> {
                    filePicker.open { uris ->
                        requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                        newMessageViewModel.importAttachments(uris)
                    }
                }
                EditorAction.CAMERA -> notYetImplemented()
                EditorAction.LINK -> notYetImplemented()
                EditorAction.CLOCK -> notYetImplemented()
                else -> Log.wtf("SelectedText", "Impossible action got triggered: $editorAction")
            }
        }
    }

    private fun observeNewAttachments() = with(binding) {
        newMessageViewModel.importedAttachments.observe(requireActivity()) { (attachments, importationResult) ->
            attachmentAdapter.addAll(attachments)
            attachmentsRecyclerView.isGone = attachmentAdapter.itemCount == 0

            if (importationResult == ImportationResult.FILE_SIZE_TOO_BIG) showSnackbar(R.string.attachmentFileLimitReached)
        }
    }

    override fun onStop() {
        DraftsActionsWorker.scheduleWork(requireContext())
        super.onStop()
    }

    fun closeAutoCompletion() = with(binding) {
        toField.clearField()
        ccField.clearField()
        bccField.clearField()
    }

    private fun onDeleteAttachment(position: Int, itemCountLeft: Int) = with(newMessageViewModel) {
        if (itemCountLeft == 0) {
            TransitionManager.beginDelayedTransition(binding.root)
            binding.attachmentsRecyclerView.isGone = true
        }
        mailAttachments[position].getUploadLocalFile(requireContext(), currentDraftLocalUuid).delete()
        mailAttachments.removeAt(position)
    }

    private fun toggleEditor(hasFocus: Boolean) = (activity as NewMessageActivity).toggleEditor(hasFocus)

    enum class FieldType {
        TO,
        CC,
        BCC,
    }
}

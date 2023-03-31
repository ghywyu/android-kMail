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

import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.transition.TransitionManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import androidx.core.net.MailTo
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.infomaniak.lib.core.utils.*
import com.infomaniak.lib.core.utils.SnackbarUtils.showSnackbar
import com.infomaniak.mail.MatomoMail.trackNewMessageEvent
import com.infomaniak.mail.R
import com.infomaniak.mail.data.models.Attachment.AttachmentDisposition.INLINE
import com.infomaniak.mail.data.models.Mailbox
import com.infomaniak.mail.data.models.correspondent.MergedContact
import com.infomaniak.mail.data.models.correspondent.Recipient
import com.infomaniak.mail.data.models.draft.Draft.DraftMode
import com.infomaniak.mail.databinding.FragmentNewMessageBinding
import com.infomaniak.mail.ui.main.newMessage.NewMessageActivity.EditorAction
import com.infomaniak.mail.ui.main.newMessage.NewMessageFragment.FieldType.*
import com.infomaniak.mail.ui.main.newMessage.NewMessageViewModel.ImportationResult
import com.infomaniak.mail.ui.main.thread.AttachmentAdapter
import com.infomaniak.mail.utils.*
import com.infomaniak.mail.utils.Utils
import com.infomaniak.mail.workers.DraftsActionsWorker
import com.google.android.material.R as RMaterial

class NewMessageFragment : Fragment() {

    private lateinit var binding: FragmentNewMessageBinding
    private val newMessageActivityArgs by lazy {
        // When opening this fragment via deeplink, it can happen that the navigation
        // extras aren't yet initialized, so we don't use the `navArgs` here.
        requireActivity().intent?.extras?.let(NewMessageActivityArgs::fromBundle) ?: NewMessageActivityArgs()
    }
    private val newMessageViewModel: NewMessageViewModel by activityViewModels()

    private val addressListPopupWindow by lazy { ListPopupWindow(binding.root.context) }
    private lateinit var filePicker: FilePicker

    private val attachmentAdapter = AttachmentAdapter(shouldDisplayCloseButton = true, onDelete = ::onDeleteAttachment)

    private var mailboxes = emptyList<Mailbox>()
    private var selectedMailboxIndex = 0
    private var lastFieldToTakeFocus: FieldType? = TO

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentNewMessageBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = with(binding) {
        super.onViewCreated(view, savedInstanceState)

        filePicker = FilePicker(this@NewMessageFragment)

        initUi()
        initDraftAndViewModel()

        focusCorrectView()
        setOnFocusChangedListeners()

        doAfterSubjectChange()
        doAfterBodyChange()

        observeContacts()
        observeMailboxes()
        observeEditorActions()
        observeNewAttachments()
        observeCcAndBccVisibility()
    }

    override fun onStart() {
        super.onStart()
        newMessageViewModel.updateDraftInLocalIfRemoteHasChanged()
    }

    private fun initUi() = with(binding) {

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(signatureWebView.settings, true)
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(quoteWebView.settings, true)
        }

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
    }

    private fun initDraftAndViewModel() {
        newMessageViewModel.initDraftAndViewModel(newMessageActivityArgs).observe(viewLifecycleOwner) { isSuccess ->
            if (isSuccess) {
                populateViewModelWithExternalMailData()
                populateUiWithViewModel()
            } else {
                requireActivity().finish()
            }
        }
    }

    private fun focusCorrectView() = with(binding) {
        when (newMessageActivityArgs.draftMode) {
            DraftMode.REPLY,
            DraftMode.REPLY_ALL -> bodyText.requestFocus()
            DraftMode.NEW_MAIL,
            DraftMode.FORWARD -> toField.requestFocus()
        }
    }

    private fun setOnFocusChangedListeners() = with(binding) {
        val listener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) fieldGotFocus(null) }
        subjectTextField.onFocusChangeListener = listener
        bodyText.onFocusChangeListener = listener
    }

    private fun setupAutoCompletionFields() = with(binding) {
        toField.initRecipientField(
            autoComplete = autoCompleteTo,
            onAutoCompletionToggledCallback = { hasOpened -> toggleAutoCompletion(TO, hasOpened) },
            onContactAddedCallback = { newMessageViewModel.addRecipientToField(it, TO) },
            onContactRemovedCallback = { newMessageViewModel.removeRecipientFromField(it, TO) },
            onCopyContactAddressCallback = ::copyRecipientEmailToClipboard,
            gotFocusCallback = { fieldGotFocus(TO) },
            onToggleEverythingCallback = ::openAdvancedFields,
            setSnackBarCallback = ::setSnackBar,
        )

        ccField.initRecipientField(
            autoComplete = autoCompleteCc,
            onAutoCompletionToggledCallback = { hasOpened -> toggleAutoCompletion(CC, hasOpened) },
            onContactAddedCallback = { newMessageViewModel.addRecipientToField(it, CC) },
            onContactRemovedCallback = { newMessageViewModel.removeRecipientFromField(it, CC) },
            onCopyContactAddressCallback = ::copyRecipientEmailToClipboard,
            gotFocusCallback = { fieldGotFocus(CC) },
            setSnackBarCallback = ::setSnackBar,
        )

        bccField.initRecipientField(
            autoComplete = autoCompleteBcc,
            onAutoCompletionToggledCallback = { hasOpened -> toggleAutoCompletion(BCC, hasOpened) },
            onContactAddedCallback = { newMessageViewModel.addRecipientToField(it, BCC) },
            onContactRemovedCallback = { newMessageViewModel.removeRecipientFromField(it, BCC) },
            onCopyContactAddressCallback = ::copyRecipientEmailToClipboard,
            gotFocusCallback = { fieldGotFocus(BCC) },
            setSnackBarCallback = ::setSnackBar,
        )
    }

    private fun fieldGotFocus(field: FieldType?) = with(binding) {
        if (lastFieldToTakeFocus == field && lastFieldToTakeFocus != null) return
        when (field) {
            TO -> {
                ccField.collapse()
                bccField.collapse()
            }
            CC -> {
                toField.collapse()
                bccField.collapse()
            }
            BCC -> {
                toField.collapse()
                ccField.collapse()
            }
            else -> {
                toField.collapse()
                ccField.collapse()
                bccField.collapse()
            }
        }
        lastFieldToTakeFocus = field
    }

    private fun openAdvancedFields(isCollapsed: Boolean) = with(binding) {
        cc.isGone = isCollapsed
        bcc.isGone = isCollapsed
    }

    private fun setSnackBar(titleRes: Int) {
        newMessageViewModel.snackBarManager.setValue(getString(titleRes))
    }

    private fun observeContacts() {
        newMessageViewModel.mergedContacts.observe(viewLifecycleOwner) { (sortedContactList, contactMap) ->
            updateRecipientFieldsContacts(sortedContactList, contactMap)
        }
    }

    private fun updateRecipientFieldsContacts(
        sortedContactList: List<MergedContact>,
        contactMap: Map<String, Map<String, MergedContact>>,
    ) = with(binding) {
        toField.updateContacts(sortedContactList, contactMap)
        ccField.updateContacts(sortedContactList, contactMap)
        bccField.updateContacts(sortedContactList, contactMap)
    }

    private fun toggleAutoCompletion(field: FieldType? = null, isAutoCompletionOpened: Boolean) = with(binding) {
        preFields.isGone = isAutoCompletionOpened
        to.isVisible = !isAutoCompletionOpened || field == TO
        cc.isVisible = !isAutoCompletionOpened || field == CC
        bcc.isVisible = !isAutoCompletionOpened || field == BCC
        postFields.isGone = isAutoCompletionOpened

        newMessageViewModel.isAutoCompletionOpened = isAutoCompletionOpened
    }

    private fun populateUiWithViewModel() = with(binding) {
        val draft = newMessageViewModel.draft

        toField.initRecipients(draft.to)
        ccField.initRecipients(draft.cc)
        bccField.initRecipients(draft.bcc)

        subjectTextField.setText(draft.subject)

        attachmentAdapter.addAll(draft.attachments.filterNot { it.disposition == INLINE })
        attachmentsRecyclerView.isGone = attachmentAdapter.itemCount == 0

        bodyText.setText(draft.uiBody)

        draft.uiSignature?.let { html ->
            signatureWebView.loadContent(html)
            removeSignature.setOnClickListener {
                trackNewMessageEvent("deleteSignature")
                draft.uiSignature = null
                signatureGroup.isGone = true
            }
            signatureGroup.isVisible = true
        }

        draft.uiQuote?.let { html ->
            quoteWebView.apply {
                loadContent(html)
                initWebViewClient(draft.attachments)
            }
            removeQuote.setOnClickListener {
                trackNewMessageEvent("deleteQuote")
                draft.uiQuote = null
                quoteGroup.isGone = true
            }
            quoteGroup.isVisible = true
        }
    }

    private fun WebView.loadContent(html: String) {
        var content = if (context.isNightModeEnabled()) context.injectCssInHtml(R.raw.custom_dark_mode, html) else html
        content = context.injectCssInHtml(R.raw.remove_margin, content)
        loadDataWithBaseURL("", content, ClipDescription.MIMETYPE_TEXT_HTML, Utils.UTF_8, "")
    }

    private fun populateViewModelWithExternalMailData() {
        when (requireActivity().intent?.action) {
            Intent.ACTION_SEND -> handleSingleSendIntent()
            Intent.ACTION_SEND_MULTIPLE -> handleMultipleSendIntent()
            Intent.ACTION_VIEW, Intent.ACTION_SENDTO -> handleMailTo()
        }
    }

    /**
     * Handle `MailTo` from [Intent.ACTION_VIEW] or [Intent.ACTION_SENDTO]
     * Get [Intent.ACTION_VIEW] data with [MailTo] and [Intent.ACTION_SENDTO] with [Intent]
     */
    private fun handleMailTo() = with(newMessageViewModel) {
        fun String.splitToRecipientList() = split(",").map {
            val email = it.trim()
            Recipient().initLocalValues(email, email)
        }

        val intent = requireActivity().intent
        intent?.data?.let { uri ->
            if (!MailTo.isMailTo(uri)) return@with

            val mailToIntent = MailTo.parse(uri)
            val to = mailToIntent.to?.splitToRecipientList()
                ?: emptyList()
            val cc = mailToIntent.cc?.splitToRecipientList()
                ?: intent.getStringArrayExtra(Intent.EXTRA_CC)?.map { Recipient().initLocalValues(it, it) }
                ?: emptyList()
            val bcc = mailToIntent.bcc?.splitToRecipientList()
                ?: intent.getStringArrayExtra(Intent.EXTRA_BCC)?.map { Recipient().initLocalValues(it, it) }
                ?: emptyList()

            draft.to.addAll(to)
            draft.cc.addAll(cc)
            draft.bcc.addAll(bcc)

            draft.subject = mailToIntent.subject ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            draft.uiBody = mailToIntent.body ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""

            saveDraftDebouncing()
        }
    }

    private fun handleSingleSendIntent() = with(requireActivity().intent) {
        if (hasExtra(Intent.EXTRA_TEXT)) {
            getStringExtra(Intent.EXTRA_SUBJECT)?.let { newMessageViewModel.draft.subject = it }
            getStringExtra(Intent.EXTRA_TEXT)?.let { newMessageViewModel.draft.uiBody = it }
        }

        if (hasExtra(Intent.EXTRA_STREAM)) {
            (parcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uri ->
                newMessageViewModel.importAttachments(listOf(uri))
            }
        }
    }

    private fun handleMultipleSendIntent() {
        requireActivity().intent
            .parcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
            ?.filterIsInstance<Uri>()
            ?.let(newMessageViewModel::importAttachments)
    }

    private fun doAfterSubjectChange() {
        binding.subjectTextField.doAfterTextChanged { editable ->
            editable?.toString()?.let { newMessageViewModel.updateMailSubject(it.ifBlank { null }) }
        }
    }

    private fun doAfterBodyChange() {
        binding.bodyText.doAfterTextChanged { editable ->
            editable?.toString()?.let(newMessageViewModel::updateMailBody)
        }
    }

    private fun observeMailboxes() {
        newMessageViewModel.mailboxes.observe(viewLifecycleOwner) {
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
        newMessageViewModel.editorAction.observe(requireActivity()) { (editorAction, /*isToggled*/ _) ->
            when (editorAction) {
                EditorAction.ATTACHMENT -> {
                    filePicker.open { uris ->
                        requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                        newMessageViewModel.importAttachments(uris)
                    }
                }
                EditorAction.CAMERA -> notYetImplemented()
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

    private fun observeCcAndBccVisibility() = with(newMessageViewModel) {
        isCollapseChevronVisible.observe(viewLifecycleOwner, binding.toField::setChevronVisibility)
        initializeFieldsAsOpen.observe(viewLifecycleOwner) { openAdvancedFields(!it) }
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

    private fun onDeleteAttachment(position: Int, itemCountLeft: Int) = with(binding) {
        val draft = newMessageViewModel.draft

        if (itemCountLeft == 0) {
            TransitionManager.beginDelayedTransition(binding.root)
            attachmentsRecyclerView.isGone = true
        }

        draft.attachments[position].getUploadLocalFile(requireContext(), draft.localUuid).delete()
        draft.attachments.removeAt(position)
    }

    enum class FieldType {
        TO,
        CC,
        BCC,
    }
}

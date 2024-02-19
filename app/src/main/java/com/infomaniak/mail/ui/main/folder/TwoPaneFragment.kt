/*
 * Infomaniak Mail - Android
 * Copyright (C) 2023-2024 Infomaniak Network SA
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
package com.infomaniak.mail.ui.main.folder

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.infomaniak.lib.core.utils.getBackNavigationResult
import com.infomaniak.lib.core.utils.safeNavigate
import com.infomaniak.lib.core.utils.toPx
import com.infomaniak.mail.MatomoMail.OPEN_FROM_DRAFT_NAME
import com.infomaniak.mail.MatomoMail.trackNewMessageEvent
import com.infomaniak.mail.R
import com.infomaniak.mail.data.cache.mailboxContent.FolderController
import com.infomaniak.mail.data.models.thread.Thread
import com.infomaniak.mail.ui.MainActivity
import com.infomaniak.mail.ui.MainViewModel
import com.infomaniak.mail.ui.main.search.SearchFragment
import com.infomaniak.mail.ui.main.thread.ThreadFragment
import com.infomaniak.mail.utils.extensions.AttachmentExtensions
import com.infomaniak.mail.utils.extensions.safeNavigateToNewMessageActivity
import com.infomaniak.mail.utils.extensions.setSystemBarsColors
import javax.inject.Inject
import kotlin.math.min

abstract class TwoPaneFragment : Fragment() {

    val mainViewModel: MainViewModel by activityViewModels()
    protected val twoPaneViewModel: TwoPaneViewModel by activityViewModels()

    // TODO: When we'll update DragDropSwipeRecyclerViewLib, we'll need to make the adapter nullable.
    //  For now it causes a memory leak, because we can't remove the strong reference
    //  between the ThreadList's RecyclerView and its Adapter as it throws an NPE.
    @Inject
    lateinit var threadListAdapter: ThreadListAdapter

    private val leftStatusBarColor: Int by lazy {
        requireContext().getColor(if (this is ThreadListFragment) R.color.backgroundHeaderColor else R.color.backgroundColor)
    }
    private val leftNavigationBarColor: Int by lazy { requireContext().getColor(R.color.backgroundColor) }
    private val rightStatusBarColor: Int by lazy { requireContext().getColor(R.color.backgroundColor) }
    private val rightNavigationBarColor: Int by lazy { requireContext().getColor(R.color.elevatedBackground) }

    abstract fun getLeftPane(): View?
    abstract fun getRightPane(): View?
    abstract fun getAnchor(): View?
    open fun doAfterFolderChanged() = Unit

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateTwoPaneVisibilities()
        observeCurrentFolder()
        observeThreadUid()
        observeThreadNavigation()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateDrawerLockMode()
        updateTwoPaneVisibilities()
    }

    private fun observeCurrentFolder() = with(twoPaneViewModel) {
        mainViewModel.currentFolder.observe(viewLifecycleOwner) { folder ->

            val (folderId, name) = if (this@TwoPaneFragment is SearchFragment) {
                FolderController.SEARCH_FOLDER_ID to getString(R.string.searchFolderName)
            } else {
                if (folder == null) return@observe
                folder.id to folder.getLocalizedName(requireContext())
            }

            rightPaneFolderName.value = name

            if (folderId != previousFolderId) {
                if (isThreadOpen && previousFolderId != null) closeThread()
                previousFolderId = folderId
            }

            doAfterFolderChanged()
        }
    }

    private fun observeThreadUid() {
        twoPaneViewModel.currentThreadUid.observe(viewLifecycleOwner) { threadUid ->
            updateTwoPaneVisibilities()
            updateDrawerLockMode()
            val isOpeningThread = threadUid != null
            if (isOpeningThread) {
                setSystemBarsColors(statusBarColor = R.color.backgroundColor, navigationBarColor = null)
            } else {
                resetPanes()
            }
        }
    }

    private fun observeThreadNavigation() = with(twoPaneViewModel) {
        getBackNavigationResult(AttachmentExtensions.DOWNLOAD_ATTACHMENT_RESULT, ::startActivity)

        newMessageArgs.observe(viewLifecycleOwner) {
            safeNavigateToNewMessageActivity(args = it.toBundle())
        }

        navArgs.observe(viewLifecycleOwner) { (resId, args) ->
            safeNavigate(resId, args)
        }
    }

    fun handleOnBackPressed() {
        when {
            twoPaneViewModel.isOnlyRightShown -> twoPaneViewModel.closeThread()
            this is ThreadListFragment -> requireActivity().finish()
            else -> findNavController().popBackStack()
        }
    }

    fun navigateToThread(thread: Thread) = with(twoPaneViewModel) {
        if (thread.isOnlyOneDraft) {
            trackNewMessageEvent(OPEN_FROM_DRAFT_NAME)
            openDraft(thread)
        } else {
            openThread(thread.uid)
        }
    }

    private fun resetPanes() {

        setSystemBarsColors(
            statusBarColor = if (this@TwoPaneFragment is ThreadListFragment) R.color.backgroundHeaderColor else null,
            navigationBarColor = R.color.backgroundColor,
        )

        threadListAdapter.selectNewThread(newPosition = null, threadUid = null)

        childFragmentManager.beginTransaction().replace(R.id.threadHostFragment, ThreadFragment()).commit()
    }

    // TODO: When we'll add the feature of swiping between Threads, we'll need to check if this function is still needed.
    private fun updateDrawerLockMode() {
        if (this is ThreadListFragment) {
            (requireActivity() as MainActivity).setDrawerLockMode(
                isLocked = twoPaneViewModel.isInThreadInPhoneMode(requireContext()),
            )
        }
    }

    private fun updateTwoPaneVisibilities() = with(requireActivity().application.resources.displayMetrics) {

        val (leftWidth, rightWidth) = computeTwoPaneWidths(widthPixels, heightPixels, twoPaneViewModel.isThreadOpen)

        getLeftPane()?.layoutParams?.width = leftWidth
        getRightPane()?.layoutParams?.width = rightWidth

        Log.e("TOTO", "onConfigurationChanged | leftWidth:$leftWidth | rightWidth: $rightWidth")
    }

    private fun computeTwoPaneWidths(
        widthPixels: Int,
        heightPixels: Int,
        isThreadOpen: Boolean,
    ): Pair<Int, Int> = with(twoPaneViewModel) {

        val smallestSupportedSize = resources.getInteger(R.integer.smallestSupportedSize).toPx()
        val minimumRequiredWidth = resources.getInteger(R.integer.minimumRequiredWidth).toPx()
        val leftPaneWidthRatio = ResourcesCompat.getFloat(resources, R.dimen.leftPaneWidthRatio)
        val rightPaneWidthRatio = ResourcesCompat.getFloat(resources, R.dimen.rightPaneWidthRatio)

        val smallestWidth = min(widthPixels, heightPixels)

        return if (
            smallestWidth < smallestSupportedSize || // If height in Landscape is too small to correctly display Tablet Mode.
            widthPixels < minimumRequiredWidth // If screen is big enough to display Tablet Mode, but currently in Portrait.
        ) {
            isOnlyOneShown = true
            if (isThreadOpen) {
                isOnlyLeftShown = false
                isOnlyRightShown = true
                0 to widthPixels
            } else {
                isOnlyLeftShown = true
                isOnlyRightShown = false
                widthPixels to 0
            }
        } else { // Screen is big enough and in Landscape, so we can finally display Tablet Mode.
            isOnlyOneShown = false
            isOnlyLeftShown = false
            isOnlyRightShown = false
            (leftPaneWidthRatio * widthPixels).toInt() to (rightPaneWidthRatio * widthPixels).toInt()
        }
    }
}

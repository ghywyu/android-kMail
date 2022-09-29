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
package com.infomaniak.mail.data.cache

import com.infomaniak.mail.data.models.*
import com.infomaniak.mail.data.models.addressBook.AddressBook
import com.infomaniak.mail.data.models.correspondent.Contact
import com.infomaniak.mail.data.models.correspondent.Recipient
import com.infomaniak.mail.data.models.message.Body
import com.infomaniak.mail.data.models.message.Message
import com.infomaniak.mail.data.models.signature.Signature
import com.infomaniak.mail.data.models.signature.SignatureEmail
import com.infomaniak.mail.data.models.thread.Thread
import com.infomaniak.mail.utils.AccountUtils
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.UpdatePolicy
import io.realm.kotlin.ext.isManaged
import io.realm.kotlin.ext.query
import io.realm.kotlin.types.RealmObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("ObjectPropertyName")
object RealmDatabase {

    val TAG: String = RealmDatabase::class.java.simpleName

    private var _appSettings: Realm? = null
    private var _userInfos: Realm? = null
    private var _mailboxInfos: Realm? = null
    private var _mailboxContent: Realm? = null

    private val appSettingsMutex = Mutex()
    private val userInfosMutex = Mutex()
    private val mailboxInfosMutex = Mutex()
    private val mailboxContentMutex = Mutex()

    fun appSettings(): Realm = runBlocking(Dispatchers.IO) {
        appSettingsMutex.withLock {
            _appSettings ?: Realm.open(RealmConfig.appSettings).also { _appSettings = it }
        }
    }

    fun userInfos(): Realm = runBlocking(Dispatchers.IO) {
        userInfosMutex.withLock {
            _userInfos ?: Realm.open(RealmConfig.userInfos).also { _userInfos = it }
        }
    }

    fun mailboxInfos(): Realm = runBlocking(Dispatchers.IO) {
        mailboxInfosMutex.withLock {
            _mailboxInfos ?: Realm.open(RealmConfig.mailboxInfos).also { _mailboxInfos = it }
        }
    }

    fun mailboxContent(): Realm = runBlocking(Dispatchers.IO) {
        mailboxContentMutex.withLock {
            _mailboxContent ?: Realm.open(RealmConfig.mailboxContent(AccountUtils.currentMailboxId)).also { _mailboxContent = it }
        }
    }

    private const val appSettingsDbName = "AppSettings.realm"
    fun userInfosDbName(userId: Int) = "User-${userId}.realm"
    private const val mailboxInfosDbName = "MailboxInfos.realm"
    fun mailboxContentDbNamePrefix(userId: Int) = "Mailbox-${userId}-"
    private fun mailboxContentDbName(userId: Int, mailboxId: Int) = "${mailboxContentDbNamePrefix(userId)}${mailboxId}.realm"

    inline fun <reified T : RealmObject> Realm.update(items: List<RealmObject>) {
        writeBlocking {
            delete(query<T>())
            copyListToRealm(items)
        }
    }

    // TODO: There is currently no way to insert multiple objects in one call (https://github.com/realm/realm-kotlin/issues/938)
    fun MutableRealm.copyListToRealm(items: List<RealmObject>, alsoCopyManagedItems: Boolean = true) {
        items.forEach { if (alsoCopyManagedItems || !it.isManaged()) copyToRealm(it, UpdatePolicy.ALL) }
    }

    fun deleteMailboxContent(mailboxId: Int) {
        Realm.deleteRealm(RealmConfig.mailboxContent(mailboxId))
    }

    fun close() {
        closeMailboxContent()
        closeMailboxInfos()
        closeUserInfos()
        closeAppSettings()
    }

    private fun closeAppSettings() {
        _appSettings?.close()
        _appSettings = null
    }

    fun closeUserInfos() {
        _userInfos?.close()
        _userInfos = null
    }

    private fun closeMailboxInfos() {
        _mailboxInfos?.close()
        _mailboxInfos = null
    }

    fun closeMailboxContent() {
        _mailboxContent?.close()
        _mailboxContent = null
    }

    private object RealmConfig {

        val appSettings =
            RealmConfiguration
                .Builder(RealmSets.appSettings)
                .name(appSettingsDbName)
                // .deleteRealmIfMigrationNeeded() // TODO: Do we want to keep this in production?
                .build()

        val userInfos
            get() = RealmConfiguration
                .Builder(RealmSets.userInfos)
                .name(userInfosDbName(AccountUtils.currentUserId))
                // .deleteRealmIfMigrationNeeded() // TODO: Do we want to keep this in production?
                .build()

        val mailboxInfos =
            RealmConfiguration
                .Builder(RealmSets.mailboxInfos)
                .name(mailboxInfosDbName)
                // .deleteRealmIfMigrationNeeded() // TODO: Do we want to keep this in production?
                .build()

        fun mailboxContent(mailboxId: Int) =
            RealmConfiguration
                .Builder(RealmSets.mailboxContent)
                .name(mailboxContentDbName(AccountUtils.currentUserId, mailboxId))
                // .deleteRealmIfMigrationNeeded() // TODO: Do we want to keep this in production?
                .build()

        private object RealmSets {

            val appSettings = setOf(
                AppSettings::class,
            )

            val userInfos = setOf(
                AddressBook::class,
                Contact::class,
            )

            val mailboxInfos = setOf(
                Mailbox::class,
                Quotas::class,
            )

            val mailboxContent = setOf(
                Folder::class,
                Thread::class,
                Message::class,
                Draft::class,
                Recipient::class,
                Body::class,
                Attachment::class,
            )

            val miscellaneous = setOf(
                Signature::class,
                SignatureEmail::class,
            )
        }
    }
}

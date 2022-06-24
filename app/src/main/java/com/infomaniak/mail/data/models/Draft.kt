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
package com.infomaniak.mail.data.models

import io.realm.RealmList
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import io.realm.realmListOf
import io.realm.toRealmList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Draft : RealmObject {
    @PrimaryKey
    var uuid: String = ""
    @SerialName("identity_id")
    var identityId: String = ""
    @SerialName("in_reply_to_uid")
    var inReplyToUid: String? = null
    @SerialName("forwarded_uid")
    var forwardedUid: String? = null
    var references: String? = null
    @SerialName("in_reply_to")
    var inReplyTo: String? = null
    @SerialName("mime_type")
    var mimeType: String = "any/any"
    var body: String = ""
    var cc: RealmList<Recipient> = realmListOf()
    var bcc: RealmList<Recipient> = realmListOf()
    var to: RealmList<Recipient> = realmListOf()
    var subject: String = ""
    @SerialName("ack_request")
    var ackRequest: Boolean = false
    var priority: String? = null
    @SerialName("st_uuid")
    var stUuid: String? = null
    var attachments: RealmList<Attachment> = realmListOf()

    /**
     * Local
     */
    var parentMessageUid: String = ""

    fun initLocalValues(messageUid: String): Draft {
        uuid = "${OFFLINE_DRAFT_UUID_PREFIX}_${messageUid}"
        parentMessageUid = messageUid

        cc = cc.map { it.initLocalValues() }.toRealmList() // TODO: Remove this when we have EmbeddedObjects
        bcc = bcc.map { it.initLocalValues() }.toRealmList() // TODO: Remove this when we have EmbeddedObjects
        to = to.map { it.initLocalValues() }.toRealmList() // TODO: Remove this when we have EmbeddedObjects

        return this
    }

    companion object {
        const val OFFLINE_DRAFT_UUID_PREFIX = "offline"
    }
}

/*
 * Infomaniak Mail - Android
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
package com.infomaniak.mail.data.models.calendar

import android.os.Parcel
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.infomaniak.mail.R
import com.infomaniak.mail.data.models.correspondent.Correspondent
import io.realm.kotlin.types.EmbeddedRealmObject
import io.realm.kotlin.types.annotations.Ignore
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
class Attendee : EmbeddedRealmObject, Correspondent {
    @SerialName("address")
    override var email: String = ""
    override var name: String = ""
    @SerialName("organizer")
    var isOrganizer = false

    @Ignore // TODO : Put this enum in realm
    var state = AttendanceState.NEEDS_ACTION

    @delegate:Ignore
    override val initials by lazy { computeInitials() }

    fun initLocalValues(email: String, name: String?) {
        this.email = email
        name?.let { this.name = it }
    }

    enum class AttendanceState(@DrawableRes val icon: Int?, @ColorRes val iconColor: Int) {
        ACCEPTED(R.drawable.ic_check_rounded, R.color.greenSuccess),
        NEEDS_ACTION(null, 0),
        TENTATIVE(R.drawable.ic_calendar_maybe, R.color.iconColorSecondaryText),
        DECLINED(R.drawable.ic_calendar_no, R.color.redDestructiveAction),
    }

    companion object : Parceler<Attendee> {
        override fun create(parcel: Parcel): Attendee {
            val email = parcel.readString()!!
            val name = parcel.readString()!!

            return Attendee().apply { initLocalValues(email, name) }
        }

        override fun Attendee.write(parcel: Parcel, flags: Int) {
            parcel.writeString(email)
            parcel.writeString(name)
        }
    }
}

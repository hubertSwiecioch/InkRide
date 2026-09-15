package com.speedevand.inkride.settings.presentation

import com.speedevand.inkride.core.domain.settings.BikeType

/**
 * Stable identifiers for the bike-profile list and its add/edit form. Rows are
 * tagged by profile id, because every row renders the same "Edit"/"Delete"
 * labels and a text selector could not tell them apart.
 */
object BikeProfilesTestTags {
    const val ADD_BUTTON = "bike_profiles_add"
    const val NAME_FIELD = "bike_profiles_name_field"
    const val WEIGHT_FIELD = "bike_profiles_weight_field"
    const val SAVE_BUTTON = "bike_profiles_save"
    const val CANCEL_BUTTON = "bike_profiles_cancel"
    const val EMPTY_STATE = "bike_profiles_empty"
    const val BACK_BUTTON = "bike_profiles_back"

    fun row(id: Long): String = "bike_profile_row_$id"

    fun activeRadio(id: Long): String = "bike_profile_active_$id"

    fun editButton(id: Long): String = "bike_profile_edit_$id"

    fun deleteButton(id: Long): String = "bike_profile_delete_$id"

    fun typeRadio(type: BikeType): String = "bike_profile_type_${type.name}"
}

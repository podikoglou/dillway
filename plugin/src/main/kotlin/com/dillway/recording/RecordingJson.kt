package com.dillway.recording

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * One JSON encoder for the recording wire format. The naming policy turns
 * camelCase properties into the legacy snake_case keys; the few keys that stay
 * camelCase (`f`, `hasPos`, `hasLook`, `itemInHandIndex`) have explicit
 * `@SerializedName` values.
 */
object RecordingJson {

    val gson: Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()
}

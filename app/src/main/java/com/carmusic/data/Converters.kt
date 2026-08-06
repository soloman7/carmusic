package com.carmusic.data

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun mapToJson(map: Map<String, String>): String = gson.toJson(map)

    @TypeConverter
    fun jsonToMap(json: String?): Map<String, String> =
        if (json.isNullOrBlank()) emptyMap()
        else gson.fromJson(json, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
}

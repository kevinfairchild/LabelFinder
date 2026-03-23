package com.labelfinder.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val enabledFormats: String = "CODE_39,CODE_128,EAN_13,EAN_8,UPC_A,UPC_E,QR_CODE,DATA_MATRIX,PDF_417,AZTEC",
    val alertVolume: Int = 100,
    val vibrationStrength: Int = 2,
    val alertToneType: Int = 5,  // ToneGenerator.TONE_PROP_BEEP
    val stripChars: String = "",  // Deprecated — kept for migration
    val prefixes: String = "",    // Pipe-delimited prefix strings to ignore
    val suffixes: String = "",    // Pipe-delimited suffix strings to ignore
    val partialMatch: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabledFormats", JSONArray(enabledFormats.split(",")))
        put("alertVolume", alertVolume)
        put("vibrationStrength", vibrationStrength)
        put("alertToneType", alertToneType)
        put("prefixes", JSONArray(if (prefixes.isBlank()) emptyList() else prefixes.split("|")))
        put("suffixes", JSONArray(if (suffixes.isBlank()) emptyList() else suffixes.split("|")))
        put("partialMatch", partialMatch)
    }

    companion object {
        fun fromJson(json: JSONObject): AppSettings {
            val formats = (0 until json.getJSONArray("enabledFormats").length())
                .map { json.getJSONArray("enabledFormats").getString(it) }
                .joinToString(",")
            val prefixArray = json.optJSONArray("prefixes")
            val suffixArray = json.optJSONArray("suffixes")
            val prefixes = if (prefixArray != null)
                (0 until prefixArray.length()).map { prefixArray.getString(it) }.joinToString("|")
            else ""
            val suffixes = if (suffixArray != null)
                (0 until suffixArray.length()).map { suffixArray.getString(it) }.joinToString("|")
            else ""

            return AppSettings(
                enabledFormats = formats,
                alertVolume = json.optInt("alertVolume", 100),
                vibrationStrength = json.optInt("vibrationStrength", 2),
                alertToneType = json.optInt("alertToneType", 5),
                prefixes = prefixes,
                suffixes = suffixes,
                partialMatch = json.optBoolean("partialMatch", false)
            )
        }
    }
}

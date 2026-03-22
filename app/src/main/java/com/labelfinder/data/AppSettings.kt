package com.labelfinder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val enabledFormats: String = "CODE_39,CODE_128,EAN_13,EAN_8,UPC_A,UPC_E,QR_CODE,DATA_MATRIX,PDF_417,AZTEC",
    val alertVolume: Int = 100,
    val vibrationStrength: Int = 2,
    val alertToneType: Int = 5,  // ToneGenerator.TONE_PROP_BEEP
    val stripChars: String = "",  // Deprecated — kept for migration
    val prefixes: String = "",    // Pipe-delimited prefix strings to ignore
    val suffixes: String = ""     // Pipe-delimited suffix strings to ignore
)

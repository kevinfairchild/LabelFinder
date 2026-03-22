package com.labelfinder.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSettingsDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AppSettingsDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.appSettingsDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun returnsNullWhenNoSettings() = runTest {
        assertNull(dao.get())
    }

    @Test
    fun saveAndRetrieve() = runTest {
        val settings = AppSettings(alertVolume = 66, stripChars = "#+")
        dao.save(settings)
        val loaded = dao.get()
        assertNotNull(loaded)
        assertEquals(66, loaded!!.alertVolume)
        assertEquals("#+", loaded.stripChars)
    }

    @Test
    fun saveOverwritesPrevious() = runTest {
        dao.save(AppSettings(alertVolume = 33))
        dao.save(AppSettings(alertVolume = 100))
        val loaded = dao.get()
        assertEquals(100, loaded!!.alertVolume)
    }

    @Test
    fun enabledFormatsStoredAsCommaSeparated() = runTest {
        val formats = "CODE_39,QR_CODE"
        dao.save(AppSettings(enabledFormats = formats))
        val loaded = dao.get()
        assertEquals(formats, loaded!!.enabledFormats)
        val formatList = loaded.enabledFormats.split(",")
        assertEquals(2, formatList.size)
        assertTrue("CODE_39" in formatList)
        assertTrue("QR_CODE" in formatList)
    }
}

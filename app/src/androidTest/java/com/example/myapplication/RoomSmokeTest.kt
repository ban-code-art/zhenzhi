package com.example.myapplication

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.UserEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSmokeTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndQueryUser() = runBlocking {
        val now = System.currentTimeMillis()
        val id = db.userDao().insert(
            UserEntity(
                username = "student1",
                passwordHash = "hash",
                salt = "salt",
                nickname = "学生一",
                createdAt = now,
                updatedAt = now
            )
        )

        val loaded = db.userDao().findByUsername("student1")
        assertNotNull(loaded)
        assertEquals(id, loaded!!.id)
        assertEquals("学生一", loaded.nickname)
        assertEquals(1, db.userDao().countByUsername("student1"))
    }
}

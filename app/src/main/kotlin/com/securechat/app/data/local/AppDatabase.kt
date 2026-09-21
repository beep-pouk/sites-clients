package com.securechat.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

class Converters {
    @TypeConverter
    fun fromDirection(direction: MessageDirection): String = direction.name

    @TypeConverter
    fun toDirection(value: String): MessageDirection = MessageDirection.valueOf(value)
}

@Database(
    entities = [ContactEntity::class, SessionEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao

    companion object {
        /** [passphrase] should come from [com.securechat.app.data.keystore.SecureKeyStorage]. */
        fun create(context: Context, passphrase: ByteArray): AppDatabase {
            SQLiteDatabase.loadLibs(context)
            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "securechat.db")
                .openHelperFactory(SupportFactory(passphrase))
                .build()
        }
    }
}

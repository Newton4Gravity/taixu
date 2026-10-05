package top.wkbin.taixu.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ToolEntity::class,
        InstallLogEntity::class,
        InstallTaskEntity::class,
        RuntimeEntity::class,
        RuntimeReferenceEntity::class,
        StorageMountBindingEntity::class,
        WorkspaceEntity::class,
        BuildScriptEntity::class,
        TerminalSessionEntity::class,
        ToolSettingsEntity::class,
        PluginEntity::class,
        SkillEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun toolDao(): ToolDao
    abstract fun installLogDao(): InstallLogDao
    abstract fun installTaskDao(): InstallTaskDao
    abstract fun runtimeDao(): RuntimeDao
    abstract fun storageMountBindingDao(): StorageMountBindingDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun buildScriptDao(): BuildScriptDao
    abstract fun terminalSessionDao(): TerminalSessionDao
    abstract fun toolSettingsDao(): ToolSettingsDao
    abstract fun pluginDao(): PluginDao
    abstract fun skillDao(): SkillDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "taixu.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class Converters {
    @androidx.room.TypeConverter
    fun fromList(list: List<String>?): String = list?.joinToString(",") ?: ""

    @androidx.room.TypeConverter
    fun toList(value: String): List<String> = value.split(",").filter { it.isNotBlank() }

    @androidx.room.TypeConverter
    fun fromPermissions(list: List<String>?): String = fromList(list)

    @androidx.room.TypeConverter
    fun toPermissions(value: String): List<String> = toList(value)
}
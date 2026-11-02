package app.radd.data

import android.content.Context
import androidx.room.*
import app.radd.engine.Found
import app.radd.model.Quality
import app.radd.model.RecoverableItem
import app.radd.model.Source
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "found", indices = [Index(value = ["sha256"], unique = true)])
data class FoundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val quality: String,
    val path: String,
    val offset: Long,
    val length: Long,
    val modifiedMs: Long,
    val width: Int,
    val height: Int,
    val format: String,
    val sha256: String,
    val name: String,
)

fun Found.toEntity() = FoundEntity(
    source = source.name, quality = quality.name, path = path, offset = offset, length = length, modifiedMs = modifiedMs,
    width = width, height = height, format = format, sha256 = sha256, name = name,
)

/** A row whose enum names no longer exist (an old database) is dropped rather than crashing the list. */
fun FoundEntity.toItem(): RecoverableItem? {
    val s = Source.entries.firstOrNull { it.name == source } ?: return null
    val q = Quality.entries.firstOrNull { it.name == quality } ?: return null
    return RecoverableItem(
        id = id, name = name, source = s, quality = q, sizeBytes = length, modifiedMs = modifiedMs, width = width, height = height,
        originPath = path, format = format, offset = offset, length = length, sha256 = sha256,
    )
}

@Dao
interface FoundDao {
    @Query("SELECT * FROM found ORDER BY modifiedMs DESC, id DESC")
    fun observeAll(): Flow<List<FoundEntity>>

    @Query("SELECT COUNT(*) FROM found")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM found")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<FoundEntity>)

    @Query("DELETE FROM found")
    suspend fun clear()

    @Query("DELETE FROM found WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Database(entities = [FoundEntity::class], version = 1, exportSchema = false)
abstract class RaddDatabase : RoomDatabase() {
    abstract fun found(): FoundDao

    companion object {
        const val NAME = "radd.db"
        fun open(context: Context, name: String = NAME): RaddDatabase =
            Room.databaseBuilder(context.applicationContext, RaddDatabase::class.java, name).fallbackToDestructiveMigration().build()
    }
}

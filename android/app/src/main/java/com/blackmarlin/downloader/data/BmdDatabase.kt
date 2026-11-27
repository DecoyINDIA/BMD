package com.blackmarlin.downloader.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks", indices = [Index(value = ["submissionId"], unique = true), Index(value = ["jobId"], unique = true), Index(value = ["stage"])])
data class TaskEntity(
    @PrimaryKey val id: String,
    val submissionId: String,
    val name: String,
    val sourceUrl: String,
    val origin: String,
    val jobId: Int,
    val mimeType: String = "application/octet-stream",
    val totalBytes: Long? = null,
    val networkPolicy: String = "UNMETERED",
    val allowMeteredOnce: Boolean = false,
    val httpConsent: Boolean = false,
    val expectedSha256: String? = null,
    val destinationKind: String = "PUBLIC_DOWNLOADS",
    val destinationUri: String? = null,
    val destinationLabel: String = "Downloads",
    val stage: String = "QUEUED",
    val userIntent: String = "RUN",
    val scheduleEpoch: Long = 1,
    val runGeneration: Long = 0,
    val ownerSession: String? = null,
    val userResumeRequired: Boolean = false,
    val receivedBytes: Long = 0,
    val checkpointBytes: Long = 0,
    val speed: Long = 0,
    val saveBytes: Long = 0,
    val verified: Boolean = false,
    val sha256Verified: Boolean = false,
    val errorCode: String? = null,
    val detail: String? = null,
    val savedBeforeCancellation: Boolean = false,
    val cancelRequestedDuringSave: Boolean = false,
    val recoveryCandidateUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    override fun toString() = "TaskEntity(id=$id, stage=$stage, generation=$runGeneration)"
}

@Entity(tableName = "publications", indices = [Index(value = ["attemptId"], unique = true)])
data class PublicationEntity(
    @PrimaryKey val taskId: String,
    val attemptId: String,
    val adapter: String,
    val phase: String,
    val uri: String? = null,
    val marker: String,
    val copiedBytes: Long = 0,
    val expectedBytes: Long = 0,
    val digest: String? = null,
    val runGeneration: Long = 0,
    val ownerSession: String? = null,
    val commitPlanned: Boolean = false,
    val finalName: String = "download",
    val destinationUri: String? = null,
    val destinationLabel: String = "Downloads",
    val createdAt: Long = System.currentTimeMillis(),
) {
    override fun toString() = "PublicationEntity(taskId=$taskId, phase=$phase)"
}

/** Retains uncertain prior output identities when the user explicitly saves a separate copy. */
@Entity(tableName = "publication_history", indices = [Index(value = ["taskId"])])
data class PublicationHistoryEntity(
    @PrimaryKey val attemptId: String,
    val taskId: String,
    val snapshot: String,
    val archivedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "files", indices = [Index(value = ["taskId"], unique = true), Index(value = ["uri"], unique = true)])
data class SavedFileEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val name: String,
    val uri: String,
    val mimeType: String,
    val bytes: Long,
    val destinationLabel: String,
    val sha256Verified: Boolean,
    val accessible: Boolean = true,
    val savedAt: Long = System.currentTimeMillis(),
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC") fun observeTasks(): Flow<List<TaskEntity>>
    @Query("SELECT * FROM files ORDER BY savedAt DESC") fun observeFiles(): Flow<List<SavedFileEntity>>
    @Query("SELECT * FROM publications") fun observePublications(): Flow<List<PublicationEntity>>
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC") suspend fun tasks(): List<TaskEntity>
    @Query("SELECT * FROM files ORDER BY savedAt DESC") suspend fun files(): List<SavedFileEntity>
    @Query("SELECT * FROM tasks WHERE id=:id") suspend fun task(id: String): TaskEntity?
    @Query("SELECT * FROM tasks WHERE submissionId=:key") suspend fun submitted(key: String): TaskEntity?
    @Query("SELECT * FROM tasks WHERE sourceUrl=:url AND stage NOT IN ('CANCELED') LIMIT 1") suspend fun duplicate(url: String): TaskEntity?
    @Query("SELECT * FROM publications WHERE taskId=:id") suspend fun publication(id: String): PublicationEntity?
    @Query("SELECT * FROM files WHERE id=:id") suspend fun file(id: String): SavedFileEntity?
    @Query("SELECT * FROM files WHERE taskId=:id") suspend fun fileForTask(id: String): SavedFileEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(task: TaskEntity)
    @Update suspend fun update(task: TaskEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPublication(publication: PublicationEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertFile(file: SavedFileEntity)
    @Update suspend fun updateFile(file: SavedFileEntity)
    @Query("DELETE FROM files WHERE id=:id") suspend fun removeFile(id: String)
    @Query("DELETE FROM tasks WHERE id=:id") suspend fun removeTask(id: String)
    @Query("DELETE FROM publications WHERE taskId=:id") suspend fun removePublication(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun archivePublication(history: PublicationHistoryEntity)
    @Query("SELECT * FROM publication_history WHERE taskId=:id ORDER BY archivedAt") suspend fun publicationHistory(id: String): List<PublicationHistoryEntity>
    @Query("DELETE FROM publication_history WHERE taskId=:id") suspend fun removePublicationHistory(id: String)
    @Query("UPDATE tasks SET receivedBytes=:received, checkpointBytes=:checkpoint, speed=:speed, stage=:stage, updatedAt=:now WHERE id=:id AND runGeneration=:generation AND ownerSession=:owner AND userIntent='RUN'")
    suspend fun progress(id: String, generation: Long, owner: String, received: Long, checkpoint: Long, speed: Long, stage: String, now: Long): Int
    @Query("UPDATE tasks SET saveBytes=:bytes, updatedAt=:now WHERE id=:id AND runGeneration=:generation AND ownerSession=:owner")
    suspend fun saveProgress(id: String, generation: Long, owner: String, bytes: Long, now: Long): Int
}

@Database(entities = [TaskEntity::class, PublicationEntity::class, SavedFileEntity::class, PublicationHistoryEntity::class], version = 1, exportSchema = true)
abstract class BmdDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, BmdDatabase::class.java, "bmd-tasks.db").build()
    }
}

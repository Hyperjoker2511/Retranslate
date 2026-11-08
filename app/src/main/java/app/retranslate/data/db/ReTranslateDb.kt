package app.retranslate.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * The app database (PLAN.md §8). Schema v2 (v1.1.3 — see
 * [MIGRATION_1_2]), exported from day one (§8.3); from the first
 * shipped build every change ships a manual migration — destructive
 * fallback is never configured here.
 */
@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        KnowledgeBaseEntity::class,
        KbEntryEntity::class,
        SampleSetEntity::class,
        SampleChapterEntity::class,
        CachedTranslationEntity::class,
        ProviderLanguageEntity::class,
        JobRunEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class ReTranslateDb : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao
    abstract fun sampleDao(): SampleDao
    abstract fun cachedTranslationDao(): CachedTranslationDao
    abstract fun providerLanguageDao(): ProviderLanguageDao
    abstract fun jobRunDao(): JobRunDao

    companion object {
        const val NAME = "retranslate.db"

        fun create(context: Context): ReTranslateDb =
            Room.databaseBuilder(context.applicationContext, ReTranslateDb::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

/**
 * Runs a block inside one database transaction. An interface (production:
 * Room's `withTransaction`; tests: run the block directly against fake
 * DAOs) so import-commit logic is JVM-testable without a device.
 * A plain interface, not a `fun interface` — the method is generic,
 * which SAM interfaces cannot be.
 */
interface TxRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomTxRunner(private val db: ReTranslateDb) : TxRunner {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}

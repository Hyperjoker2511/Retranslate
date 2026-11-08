package app.retranslate.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Manual migrations (§8.3: no destructive fallback, ever). Each one is
 * additive — new nullable columns or new columns with defaults — so a
 * migration can never lose a user's book.
 *
 * v1 → v2 (v1.1.3):
 *  - chapters.improvedAt — the durable "Improved with AI" marker (§A3)
 *    and the "Not improved yet" bulk-scope signal (§G).
 *  - job_runs.polishApplied / polishRejected — polish outcomes are
 *    counted durably (§A2), never discarded.
 *  - job_runs.polishScope / polishStrength — a paused POLISH run
 *    resumes as the SAME job after the app restarts (§G).
 *  - job_runs.providerPick — a TRANSLATE run's single-engine pick
 *    (§I); null = Automatic.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapters ADD COLUMN improvedAt INTEGER")
        db.execSQL(
            "ALTER TABLE job_runs ADD COLUMN polishApplied INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE job_runs ADD COLUMN polishRejected INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL("ALTER TABLE job_runs ADD COLUMN polishScope TEXT")
        db.execSQL("ALTER TABLE job_runs ADD COLUMN polishStrength TEXT")
        db.execSQL("ALTER TABLE job_runs ADD COLUMN providerPick TEXT")
    }
}

package ru.fitprogress.ai

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "exercises") data class ExerciseRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "workouts") data class WorkoutRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "runs") data class RunRow(@PrimaryKey val id: String, val payload: String)
@Entity(tableName = "personal_records") data class PersonalRow(@PrimaryKey val id: String, val kind: String, val payload: String)
@Dao interface FitDao {
    @Query("SELECT * FROM personal_records ORDER BY id") fun personal(): Flow<List<PersonalRow>>
    @Query("SELECT * FROM personal_records") suspend fun allPersonal(): List<PersonalRow>
    @Query("SELECT * FROM personal_records WHERE id=:id") suspend fun personalById(id: String): PersonalRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPersonal(row: PersonalRow)
    @Query("DELETE FROM personal_records WHERE id=:id") suspend fun deletePersonal(id: String)
    @Query("DELETE FROM personal_records") suspend fun clearPersonal()
    @Query("SELECT * FROM exercises") fun exercises(): Flow<List<ExerciseRow>>
    @Query("SELECT * FROM workouts") fun workouts(): Flow<List<WorkoutRow>>
    @Query("SELECT * FROM runs") fun runs(): Flow<List<RunRow>>
    @Query("SELECT * FROM exercises") suspend fun allExercises(): List<ExerciseRow>
    @Query("SELECT * FROM workouts") suspend fun allWorkouts(): List<WorkoutRow>
    @Query("SELECT * FROM runs") suspend fun allRuns(): List<RunRow>
    @Query("SELECT * FROM workouts WHERE id=:id") suspend fun workout(id: String): WorkoutRow?
    @Query("SELECT * FROM runs WHERE id=:id") suspend fun run(id: String): RunRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putExercise(row: ExerciseRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWorkout(row: WorkoutRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRun(row: RunRow)
    @Query("DELETE FROM exercises WHERE id=:id") suspend fun deleteExercise(id: String)
    @Query("DELETE FROM workouts WHERE id=:id") suspend fun deleteWorkout(id: String)
    @Query("DELETE FROM exercises") suspend fun clearExercises()
    @Query("DELETE FROM workouts") suspend fun clearWorkouts()
    @Query("DELETE FROM runs") suspend fun clearRuns()
}
@Database(entities = [ExerciseRow::class, WorkoutRow::class, RunRow::class, PersonalRow::class], version = 2, exportSchema = true)
abstract class FitDatabase : RoomDatabase() { abstract fun dao(): FitDao }

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL(MIGRATION_1_2_SQL) }
}

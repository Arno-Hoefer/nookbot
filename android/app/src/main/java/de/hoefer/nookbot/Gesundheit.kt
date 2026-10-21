@file:OptIn(androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi::class)

package de.hoefer.nookbot

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.*
import de.hoefer.nookbot.data.Settings
import de.hoefer.nookbot.net.Api
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * Health Connect → Tageswerte → Pi (nur NookBot, nur im Tailnet). Gelesen wird alles, wofür der Nutzer Lesen erlaubt hat;
 * zum Pi gehen zusammengefasste Werte je Tag (Summen, Min/Max/Ø, Schlaf- und Trainingseinheiten, Messungen), keine Minutenreihen.
 */
object Gesundheit {
    val TYPEN: List<KClass<out Record>> = listOf(
        StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class,
        FloorsClimbedRecord::class, ElevationGainedRecord::class, HeartRateRecord::class, RestingHeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class, SleepSessionRecord::class, ExerciseSessionRecord::class, WeightRecord::class,
        HeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class, BoneMassRecord::class, BodyWaterMassRecord::class,
        BasalMetabolicRateRecord::class, BloodPressureRecord::class, BloodGlucoseRecord::class, OxygenSaturationRecord::class,
        RespiratoryRateRecord::class, BodyTemperatureRecord::class, BasalBodyTemperatureRecord::class, Vo2MaxRecord::class,
        HydrationRecord::class, NutritionRecord::class, PowerRecord::class, SpeedRecord::class, StepsCadenceRecord::class,
        CyclingPedalingCadenceRecord::class, WheelchairPushesRecord::class, MenstruationFlowRecord::class,
        MenstruationPeriodRecord::class, OvulationTestRecord::class, CervicalMucusRecord::class,
        IntermenstrualBleedingRecord::class, SexualActivityRecord::class, SkinTemperatureRecord::class,
        MindfulnessSessionRecord::class, PlannedExerciseSessionRecord::class,
    )

    val BERECHTIGUNGEN: Set<String> = TYPEN.map { HealthPermission.getReadPermission(it) }.toSet() +
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun verfuegbar(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    fun client(ctx: Context) = HealthConnectClient.getOrCreate(ctx)

    suspend fun erlaubt(ctx: Context): Set<String> = client(ctx).permissionController.getGrantedPermissions()

    /** Summen/Durchschnitte je Tag – Name, Metrik, Umrechnung in eine lesbare Zahl. */
    private data class Kennzahl(val name: String, val typ: KClass<out Record>, val metrik: AggregateMetric<*>, val wert: (Any) -> Double)

    private val KENNZAHLEN = listOf(
        Kennzahl("schritte", StepsRecord::class, StepsRecord.COUNT_TOTAL) { (it as Long).toDouble() },
        Kennzahl("strecke_km", DistanceRecord::class, DistanceRecord.DISTANCE_TOTAL) { (it as androidx.health.connect.client.units.Length).inKilometers },
        Kennzahl("aktiv_kcal", ActiveCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) { (it as androidx.health.connect.client.units.Energy).inKilocalories },
        Kennzahl("gesamt_kcal", TotalCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord.ENERGY_TOTAL) { (it as androidx.health.connect.client.units.Energy).inKilocalories },
        Kennzahl("grundumsatz_kcal", BasalMetabolicRateRecord::class, BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL) { (it as androidx.health.connect.client.units.Energy).inKilocalories },
        Kennzahl("stockwerke", FloorsClimbedRecord::class, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL) { (it as Double) },
        Kennzahl("hoehenmeter", ElevationGainedRecord::class, ElevationGainedRecord.ELEVATION_GAINED_TOTAL) { (it as androidx.health.connect.client.units.Length).inMeters },
        Kennzahl("puls_min", HeartRateRecord::class, HeartRateRecord.BPM_MIN) { (it as Long).toDouble() },
        Kennzahl("puls_max", HeartRateRecord::class, HeartRateRecord.BPM_MAX) { (it as Long).toDouble() },
        Kennzahl("puls_avg", HeartRateRecord::class, HeartRateRecord.BPM_AVG) { (it as Long).toDouble() },
        Kennzahl("ruhepuls", RestingHeartRateRecord::class, RestingHeartRateRecord.BPM_AVG) { (it as Long).toDouble() },
        Kennzahl("gewicht_kg", WeightRecord::class, WeightRecord.WEIGHT_AVG) { (it as androidx.health.connect.client.units.Mass).inKilograms },
        Kennzahl("trinken_l", HydrationRecord::class, HydrationRecord.VOLUME_TOTAL) { (it as androidx.health.connect.client.units.Volume).inLiters },
        Kennzahl("essen_kcal", NutritionRecord::class, NutritionRecord.ENERGY_TOTAL) { (it as androidx.health.connect.client.units.Energy).inKilocalories },
        Kennzahl("eiweiss_g", NutritionRecord::class, NutritionRecord.PROTEIN_TOTAL) { (it as androidx.health.connect.client.units.Mass).inGrams },
        Kennzahl("kohlenhydrate_g", NutritionRecord::class, NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL) { (it as androidx.health.connect.client.units.Mass).inGrams },
        Kennzahl("fett_g", NutritionRecord::class, NutritionRecord.TOTAL_FAT_TOTAL) { (it as androidx.health.connect.client.units.Mass).inGrams },
        Kennzahl("schlaf_min", SleepSessionRecord::class, SleepSessionRecord.SLEEP_DURATION_TOTAL) { (it as Duration).toMinutes().toDouble() },
        Kennzahl("training_min", ExerciseSessionRecord::class, ExerciseSessionRecord.EXERCISE_DURATION_TOTAL) { (it as Duration).toMinutes().toDouble() },
        Kennzahl("rollstuhl_schuebe", WheelchairPushesRecord::class, WheelchairPushesRecord.COUNT_TOTAL) { (it as Long).toDouble() },
        Kennzahl("leistung_w_avg", PowerRecord::class, PowerRecord.POWER_AVG) { (it as androidx.health.connect.client.units.Power).inWatts },
        Kennzahl("tempo_kmh_avg", SpeedRecord::class, SpeedRecord.SPEED_AVG) { (it as androidx.health.connect.client.units.Velocity).inKilometersPerHour },
        Kennzahl("schrittfrequenz_avg", StepsCadenceRecord::class, StepsCadenceRecord.RATE_AVG) { (it as Double) },
        Kennzahl("trittfrequenz_avg", CyclingPedalingCadenceRecord::class, CyclingPedalingCadenceRecord.RPM_AVG) { (it as Double) },
    )

    /** Liest die letzten [tage] Tage. Ergebnis: {"tage": {"2026-10-21": {...}}, "quellen": {"paket": anzahl}, "arten": [...]} */
    suspend fun sammeln(ctx: Context, tage: Int): JSONObject {
        val c = client(ctx)
        val darf = c.permissionController.getGrantedPermissions()
        fun ok(t: KClass<out Record>) = HealthPermission.getReadPermission(t) in darf
        val heute = LocalDate.now(zone)
        val von = heute.minusDays(tage.toLong() - 1)
        val start = von.atStartOfDay()
        val ende = heute.plusDays(1).atStartOfDay()
        val tageJson = JSONObject()
        val quellen = HashMap<String, Int>()
        fun tag(d: LocalDate): JSONObject = tageJson.optJSONObject(d.toString()) ?: JSONObject().also { tageJson.put(d.toString(), it) }
        fun tagVon(i: Instant): LocalDate = i.atZone(zone).toLocalDate()
        fun r1(x: Double) = Math.round(x * 10) / 10.0

        // 1) Summen und Durchschnitte je Tag
        val metriken = KENNZAHLEN.filter { ok(it.typ) }
        if (metriken.isNotEmpty()) {
            runCatching {
                c.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(metriken.map { it.metrik }.toSet(),
                    TimeRangeFilter.between(start, ende), Period.ofDays(1)))
            }.getOrNull()?.forEach { p ->
                val t = tag(p.startTime.toLocalDate())
                for (k in metriken) {
                    val v = p.result[k.metrik] ?: continue
                    t.put(k.name, r1(k.wert(v)))
                }
                p.result.dataOrigins.forEach { quellen.merge(it.packageName, 1, Int::plus) }
            }
        }

        val bereich = TimeRangeFilter.between(start, ende)
        suspend fun <T : Record> lies(typ: KClass<T>, je: (T) -> Unit) {
            if (!ok(typ)) return
            var token: String? = null
            var seiten = 0
            do {
                val r = runCatching { c.readRecords(ReadRecordsRequest(typ, bereich, pageSize = 1000, pageToken = token)) }.getOrNull() ?: return
                r.records.forEach { rec -> quellen.merge(rec.metadata.dataOrigin.packageName, 1, Int::plus); je(rec) }
                token = r.pageToken
            } while (token != null && ++seiten < 20)
        }
        fun liste(t: JSONObject, key: String) = t.optJSONArray(key) ?: JSONArray().also { t.put(key, it) }
        fun uhr(i: Instant) = i.atZone(zone).toLocalTime().toString().take(5)

        // 2) Einheiten: Schlaf (mit Phasen), Training (Art, Titel, App), Achtsamkeit, geplante Trainings
        lies(SleepSessionRecord::class) { s ->
            val phasen = JSONObject()
            s.stages.groupBy { it.stage }.forEach { (st, l) ->
                phasen.put(SCHLAFPHASE[st] ?: "phase$st", l.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() })
            }
            liste(tag(tagVon(s.endTime)), "schlaf").put(JSONObject().put("von", uhr(s.startTime)).put("bis", uhr(s.endTime))
                .put("min", Duration.between(s.startTime, s.endTime).toMinutes()).put("phasen_min", phasen)
                .put("app", s.metadata.dataOrigin.packageName).apply { s.title?.let { put("titel", it) } })
        }
        lies(ExerciseSessionRecord::class) { e ->
            liste(tag(tagVon(e.startTime)), "training").put(JSONObject().put("art", trainingsart(e.exerciseType)).put("von", uhr(e.startTime))
                .put("min", Duration.between(e.startTime, e.endTime).toMinutes()).put("app", e.metadata.dataOrigin.packageName)
                .apply { e.title?.let { put("titel", it) }; e.notes?.let { put("notiz", it.take(300)) }
                    if (e.segments.isNotEmpty()) put("abschnitte", e.segments.size)
                    if (e.laps.isNotEmpty()) put("runden", e.laps.size) })
        }
        lies(MindfulnessSessionRecord::class) { m ->
            liste(tag(tagVon(m.startTime)), "achtsamkeit").put(JSONObject().put("von", uhr(m.startTime))
                .put("min", Duration.between(m.startTime, m.endTime).toMinutes()).apply { m.title?.let { put("titel", it) } })
        }
        lies(PlannedExerciseSessionRecord::class) { p ->
            liste(tag(tagVon(p.startTime)), "training_geplant").put(JSONObject().put("art", trainingsart(p.exerciseType))
                .apply { p.title?.let { put("titel", it) } })
        }

        // 3) Einzelne Messungen (wenige am Tag) – Uhrzeit + Wert
        fun messung(t: Instant, key: String, wert: Any) = liste(tag(tagVon(t)), key).put(JSONObject().put("um", uhr(t)).put("wert", wert))
        lies(BloodPressureRecord::class) { messung(it.time, "blutdruck", "${it.systolic.inMillimetersOfMercury.toInt()}/${it.diastolic.inMillimetersOfMercury.toInt()}") }
        lies(BloodGlucoseRecord::class) { messung(it.time, "blutzucker_mg_dl", r1(it.level.inMilligramsPerDeciliter)) }
        lies(OxygenSaturationRecord::class) { messung(it.time, "sauerstoff_prozent", r1(it.percentage.value)) }
        lies(RespiratoryRateRecord::class) { messung(it.time, "atemfrequenz", r1(it.rate)) }
        lies(BodyTemperatureRecord::class) { messung(it.time, "koerpertemperatur_c", r1(it.temperature.inCelsius)) }
        lies(BasalBodyTemperatureRecord::class) { messung(it.time, "basaltemperatur_c", r1(it.temperature.inCelsius)) }
        lies(HeartRateVariabilityRmssdRecord::class) { messung(it.time, "hrv_rmssd_ms", r1(it.heartRateVariabilityMillis)) }
        lies(Vo2MaxRecord::class) { messung(it.time, "vo2max", r1(it.vo2MillilitersPerMinuteKilogram)) }
        lies(HeightRecord::class) { messung(it.time, "groesse_cm", r1(it.height.inMeters * 100)) }
        lies(BodyFatRecord::class) { messung(it.time, "koerperfett_prozent", r1(it.percentage.value)) }
        lies(LeanBodyMassRecord::class) { messung(it.time, "magermasse_kg", r1(it.mass.inKilograms)) }
        lies(BoneMassRecord::class) { messung(it.time, "knochenmasse_kg", r1(it.mass.inKilograms)) }
        lies(BodyWaterMassRecord::class) { messung(it.time, "koerperwasser_kg", r1(it.mass.inKilograms)) }
        lies(SkinTemperatureRecord::class) { s -> s.deltas.firstOrNull()?.let { messung(s.startTime, "hauttemperatur_abweichung_c", r1(it.delta.inCelsius)) } }
        lies(MenstruationFlowRecord::class) { messung(it.time, "menstruation", it.flow) }
        lies(MenstruationPeriodRecord::class) { liste(tag(tagVon(it.startTime)), "periode").put(JSONObject().put("bis", tagVon(it.endTime).toString())) }
        lies(OvulationTestRecord::class) { messung(it.time, "ovulationstest", it.result) }
        lies(CervicalMucusRecord::class) { messung(it.time, "zervixschleim", "${it.appearance}/${it.sensation}") }
        lies(IntermenstrualBleedingRecord::class) { messung(it.time, "zwischenblutung", true) }
        lies(SexualActivityRecord::class) { messung(it.time, "sex", it.protectionUsed) }

        val arten = TYPEN.filter { ok(it) }.map { it.simpleName?.removeSuffix("Record") ?: "?" }
        return JSONObject().put("tage", tageJson).put("quellen", JSONObject(quellen as Map<*, *>)).put("arten", JSONArray(arten))
            .put("von", von.toString()).put("bis", heute.toString())
    }

    /** Lesen und zum Pi schicken. Erstes Mal 90 Tage (mit Verlaufs-Erlaubnis, sonst 30), danach die letzten 10 Tage. */
    suspend fun abgleichen(ctx: Context): String {
        val s = Settings(ctx)
        val darf = erlaubt(ctx)
        if (darf.none { it.startsWith("android.permission.health.READ_") && !it.endsWith("_IN_BACKGROUND") && !it.endsWith("_HISTORY") }) {
            return "Keine Health-Connect-Daten freigegeben."
        }
        val erstes = s.gesundheitStand == 0L
        val tage = if (erstes) (if (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in darf) 90 else 30) else 10
        val daten = sammeln(ctx, tage)
        val api = Api({ s.serverUrl }, { s.chatToken })
        val o = api.post("/api/gesundheit", daten)
        s.gesundheitStand = System.currentTimeMillis()
        return "${daten.getJSONObject("tage").length()} Tage abgeglichen · ${o.optInt("tage_gesamt")} auf dem Pi"
    }

    /** Täglicher Abgleich im Hintergrund (braucht die Erlaubnis „im Hintergrund lesen“). */
    fun planen(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<Abgleich>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("gesundheit", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun stoppen(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork("gesundheit")

    class Abgleich(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
        override suspend fun doWork(): Result = runCatching { abgleichen(applicationContext) }.fold({ Result.success() }, { Result.retry() })
    }

    private val SCHLAFPHASE = mapOf(
        SleepSessionRecord.STAGE_TYPE_AWAKE to "wach", SleepSessionRecord.STAGE_TYPE_SLEEPING to "schlaf",
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to "aufgestanden", SleepSessionRecord.STAGE_TYPE_LIGHT to "leicht",
        SleepSessionRecord.STAGE_TYPE_DEEP to "tief", SleepSessionRecord.STAGE_TYPE_REM to "rem",
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to "wach_im_bett",
    )

    private val ARTEN = mapOf(
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "Gehen", ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Laufen",
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "Laufband", ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Radfahren",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "Ergometer", ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "Krafttraining",
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "Gewichtheben", ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to "Eigengewicht",
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT", ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "Yoga",
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "Pilates", ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "Schwimmen (Becken)",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Schwimmen (offen)", ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "Wandern",
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "Crosstrainer", ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "Rudergerät",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "Stepper", ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to "Dehnen",
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "Fußball", ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "Tennis",
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "Badminton", ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to "Turnen",
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to "Bootcamp", ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "Tanzen",
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Training",
    )

    fun trainingsart(t: Int) = ARTEN[t] ?: "Art $t"
}

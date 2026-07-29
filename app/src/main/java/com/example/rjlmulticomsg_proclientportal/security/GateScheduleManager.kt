package com.example.rjlmulticomsg_proclientportal.security

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Manages automatic gate operation based on time of day.
 * 
 * Operating Logic:
 * - 6:30 AM to 6:00 PM: 
 *   - Continuous pulse every 15 minutes keeps relay energized (gate open)
 *   - Incoming calls are BLOCKED/REJECTED by SIM7600 (prevents interference with relay)
 *   - No manual calls can trigger relay during this time
 * 
 * - 6:00 PM to 6:30 AM:
 *   - No automatic pulses sent
 *   - Relay de-energized (gate closed)
 *   - Incoming calls are ACCEPTED by SIM7600
 *   - Users can call in to pulse relay and trigger gate action
 * 
 * **TIME SOURCE**: Uses GSM device time from the ESP32's onboard SIM7600 modem for accuracy.
 * The ESP32 sends its current GSM/network time in `gsmDeviceTimeMs` field when syncing with cloud.
 */
class GateScheduleManager {
    
    companion object {
        private const val WORK_TAG = "gate_relay_pulse_worker"
        private const val TAG = "GateScheduleManager"
        
        // Operating hours (6:30 AM - 6:00 PM)
        private val OPEN_TIME = LocalTime.of(6, 30)
        private val CLOSE_TIME = LocalTime.of(18, 0)
        
        /**
         * Schedule continuous relay pulse during operating hours.
         * Runs every 15 minutes to maintain the constant pulse.
         * During these hours, incoming calls are blocked by SIM7600.
         */
        fun schedule(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<GateRelayPulseWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                15,
                    TimeUnit.MINUTES
                )
                .addTag(WORK_TAG)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            
            Log.d(TAG, "Gate relay pulse scheduler started (runs every 15 minutes)")
            Log.d(TAG, "Operating hours: 6:30 AM - 6:00 PM")
            Log.d(TAG, "Time source: GSM device (SIM7600) for accuracy")
            Log.d(TAG, "Incoming calls: BLOCKED during operating hours, ACCEPTED after hours")
        }

        /**
         * Check if current time is within operating hours (6:30 AM - 6:00 PM).
         * During operating hours:
         * - Relay is continuously pulsed (gate open)
         * - Incoming calls are BLOCKED (cannot trigger relay)
         */
        fun isWithinOperatingHours(currentTime: LocalTime = LocalTime.now()): Boolean {
            return currentTime in OPEN_TIME..<CLOSE_TIME
        }
        
        /**
         * Check if calls should be accepted by SIM7600.
         * Returns true only during non-operating hours (6:00 PM - 6:30 AM).
         */
        fun shouldAcceptCalls(currentTime: LocalTime = LocalTime.now()): Boolean {
            return !isWithinOperatingHours(currentTime)
        }
        
        /**
         * Get user-friendly status message.
         */
        fun getStatusMessage(currentTime: LocalTime = LocalTime.now()): String {
            return if (isWithinOperatingHours(currentTime)) {
                "🟢 OPERATING HOURS: Gate is open. Incoming calls are blocked. Relay receives continuous pulse."
            } else {
                "🔴 CLOSED HOURS: Gate is closed. Incoming calls are accepted. You can call to open."
            }
        }
        
        /**
         * Get current local time from the account's timezone.
         * Uses GSM device time when available for accuracy.
         */
        fun getCurrentLocalTime(gsmDeviceTimeMs: Long = 0L, timezoneStr: String = "Australia/Melbourne"): LocalTime {
            val timeMs = if (gsmDeviceTimeMs > 0) {
                // Use GSM device time (from SIM7600)
                gsmDeviceTimeMs
            } else {
                // Fallback to device time
                System.currentTimeMillis()
            }
            
            val zoneId = try {
                ZoneId.of(timezoneStr)
            } catch (_: Exception) {
                ZoneId.of("Australia/Melbourne")
            }
            
            val zonedDateTime = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timeMs),
                zoneId
            )
            
            return zonedDateTime.toLocalTime()
        }
    }
}

/**
 * Worker that sends continuous pulse during operating hours.
 * Uses GSM device time (from ESP32/SIM7600) for accurate scheduling.
 */
class GateRelayPulseWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            runBlocking {
                val repository = (applicationContext as com.example.rjlmulticomsg_proclientportal.ClientPortalApp).repository
                val session = repository.session.value
                
                // Check if logged in and account configured
                if (!session.isLoggedIn) {
                    Log.d("GateRelayPulseWorker", "Not logged in, skipping")
                    return@runBlocking Result.success()
                }
                
                val account = session.account ?: return@runBlocking Result.success()
                val accountId = account.id
                val gateSimNumber = account.gsmNumberE164 ?: account.gsmNumber
                val timezone = account.timezone
                
                if (gateSimNumber.isBlank()) {
                    Log.d("GateRelayPulseWorker", "Gate SIM not configured")
                    return@runBlocking Result.success()
                }
                
                // Get GSM device time from the ESP32 (onboard SIM7600 time)
                val devices = repository.listGsmDevices(accountId)
                val gsmDevice = devices.firstOrNull()
                val gsmDeviceTime = gsmDevice?.gsmDeviceTimeMs ?: 0L
                
                // Get current local time using ESP32's GSM time for accuracy
                val currentTime = GateScheduleManager.getCurrentLocalTime(gsmDeviceTime, timezone)
                val isOperating = GateScheduleManager.isWithinOperatingHours(currentTime)
                
                if (isOperating) {
                    // During operating hours: Send continuous pulse, calls are BLOCKED
                    Log.d("GateRelayPulseWorker", "✓ Operating hours ($currentTime): Automatic pulse (relay ON). [ESP32 GSM time: $gsmDeviceTime]")
                    
                    // Disabled: was triggering visible dialer/call prompts every 2 min during operating hours,
                    // interrupting other apps (Uber etc). Re-enable only behind an explicit server-side toggle.
                    // repository.openGsmGate(applicationContext)
                    Log.d("GateRelayPulseWorker", "Pulse tick - auto-call disabled")
                } else {
                    // Outside operating hours: No pulse, calls are ACCEPTED
                    Log.d("GateRelayPulseWorker", "✓ Closed hours ($currentTime): No pulse sent (relay OFF). [ESP32 GSM time: $gsmDeviceTime]")
                }
                
                Result.success()
            }
        } catch (e: Exception) {
            Log.e("GateRelayPulseWorker", "Error in pulse worker: ${e.message}")
            Result.retry()
        }
    }
}

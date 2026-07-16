package com.example.messagesender

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.messagesender.databinding.ActivityMainBinding
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val background = Executors.newSingleThreadExecutor()

    /** Chosen start time (epoch millis); 0 = not chosen yet. */
    private var scheduledAtMillis: Long = 0L

    private val ui = Handler(Looper.getMainLooper())
    private val statusPoller = object : Runnable {
        override fun run() {
            refreshRunningUi()
            ui.postDelayed(this, 1000)
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val smsGranted = result[Manifest.permission.SEND_SMS] == true
        if (smsGranted) {
            commitStart()
        } else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.editToken.setText(LicenseManager.savedToken(this))
        binding.buttonActivate.setOnClickListener { onActivateClicked() }
        binding.buttonSchedule.setOnClickListener { onScheduleClicked() }
        binding.buttonStart.setOnClickListener { onStartClicked() }
        binding.buttonStop.setOnClickListener { stopSending() }

        LicenseRefreshScheduler.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        updateLicenseUi()
        ui.removeCallbacks(statusPoller)
        ui.post(statusPoller)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(statusPoller)
    }

    /** Reflects the live sender status (running / scheduled / stopped) in the UI. */
    private fun refreshRunningUi() {
        if (!LicenseManager.hasValidLease(this)) {
            // Token expired or was revoked: return to the token screen.
            updateLicenseUi()
            return
        }
        val jobActive = SenderState.isCycleEnabled(this)
        val running = SenderStatus.running

        binding.buttonStart.isEnabled = !jobActive
        binding.buttonStop.isEnabled = jobActive

        val base = when {
            running -> getString(R.string.status_running, SenderStatus.sentCount)
            jobActive -> getString(R.string.status_scheduled)
            else -> getString(R.string.status_idle)
        }
        val err = SenderStatus.lastError
        binding.textSendStatus.text =
            if (err != null && (running || jobActive)) {
                base + "\n" + getString(R.string.status_error, err)
            } else {
                base
            }
    }

    /** Tapping outside the focused text field clears focus and hides the keyboard. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val bounds = Rect()
                focused.getGlobalVisibleRect(bounds)
                if (!bounds.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    focused.clearFocus()
                    val imm = getSystemService(InputMethodManager::class.java)
                    imm?.hideSoftInputFromWindow(focused.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // --- Licensing ---

    private fun onActivateClicked() {
        val token = binding.editToken.text?.toString()?.trim().orEmpty()
        if (token.isBlank()) {
            binding.editToken.error = getString(R.string.error_token_required)
            return
        }
        binding.buttonActivate.isEnabled = false
        binding.textLicenseStatus.text = getString(R.string.license_status_checking)

        background.execute {
            val result = LicenseManager.validate(this, token)
            runOnUiThread {
                binding.buttonActivate.isEnabled = true
                when (result.outcome) {
                    LicenseManager.Outcome.VALID ->
                        Toast.makeText(this, R.string.license_activated, Toast.LENGTH_SHORT).show()
                    LicenseManager.Outcome.INVALID_OR_DISABLED ->
                        Toast.makeText(this, R.string.license_invalid, Toast.LENGTH_LONG).show()
                    LicenseManager.Outcome.NETWORK_ERROR ->
                        Toast.makeText(this, R.string.license_network_error, Toast.LENGTH_LONG).show()
                    LicenseManager.Outcome.BAD_SIGNATURE ->
                        Toast.makeText(this, R.string.license_bad_signature, Toast.LENGTH_LONG).show()
                    LicenseManager.Outcome.CONFIG_ERROR ->
                        Toast.makeText(this, R.string.license_config_error, Toast.LENGTH_LONG).show()
                }
                updateLicenseUi()
            }
        }
    }

    private fun updateLicenseUi() {
        val active = LicenseManager.hasValidLease(this)
        // Token screen and functionality are mutually exclusive: the sender UI is
        // only reachable with a valid token, and a lost/expired/revoked token
        // sends the user back to the token screen.
        binding.tokenSection.visibility = if (active) View.GONE else View.VISIBLE
        binding.senderSection.visibility = if (active) View.VISIBLE else View.GONE
        if (!active) {
            binding.textLicenseStatus.text = getString(R.string.license_status_inactive)
        }
    }

    // --- Schedule picker ---

    private fun onScheduleClicked() {
        val options = arrayOf(
            getString(R.string.schedule_now),
            getString(R.string.schedule_option_pick)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.hint_schedule)
            .setItems(options) { _, which ->
                if (which == 0) {
                    // Start now.
                    scheduledAtMillis = System.currentTimeMillis()
                    binding.buttonSchedule.text = getString(R.string.schedule_now)
                } else {
                    pickDateTime()
                }
            }
            .show()
    }

    private fun pickDateTime() {
        val now = Calendar.getInstance()
        val initial = if (scheduledAtMillis > 0) {
            Calendar.getInstance().apply { timeInMillis = scheduledAtMillis }
        } else now

        DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        val chosen = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        scheduledAtMillis = chosen.timeInMillis
                        val d = Date(scheduledAtMillis)
                        val text = DateFormat.getMediumDateFormat(this).format(d) + " " +
                            DateFormat.getTimeFormat(this).format(d)
                        binding.buttonSchedule.text = text
                    },
                    initial.get(Calendar.HOUR_OF_DAY),
                    initial.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(this)
                ).show()
            },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // --- Sending ---

    private fun onStartClicked() {
        if (!LicenseManager.hasValidLease(this)) {
            Toast.makeText(this, R.string.license_required, Toast.LENGTH_LONG).show()
            updateLicenseUi()
            return
        }

        val phone = binding.editPhone.text?.toString()?.trim().orEmpty()
        val message1 = binding.editMessage1.text?.toString()?.trim().orEmpty()
        val message2 = binding.editMessage2.text?.toString()?.trim().orEmpty()

        if (phone.isBlank()) {
            binding.editPhone.error = getString(R.string.error_phone_required)
            return
        }
        if (message1.isBlank()) {
            binding.editMessage1.error = getString(R.string.error_message1_required)
            return
        }
        if (message2.isBlank()) {
            binding.editMessage2.error = getString(R.string.error_message2_required)
            return
        }
        if (scheduledAtMillis <= 0L) {
            Toast.makeText(this, R.string.error_schedule_required, Toast.LENGTH_LONG).show()
            return
        }

        if (hasRequiredPermissions()) {
            commitStart()
        } else {
            requestPermissions.launch(requiredPermissions())
        }
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean {
        val sendGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val receiveGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        return sendGranted && receiveGranted
    }

    /** Saves parameters and schedules (or immediately starts) the sender. */
    private fun commitStart() {
        val phone = binding.editPhone.text?.toString()?.trim().orEmpty()
        val message1 = binding.editMessage1.text?.toString()?.trim().orEmpty()
        val message2 = binding.editMessage2.text?.toString()?.trim().orEmpty()
        // The two fields are sent as one message, joined by a space.
        val message = "$message1 $message2"

        SenderState.save(this, phone, message)
        ScheduleManager.scheduleStart(this, scheduledAtMillis)

        val startedNow = scheduledAtMillis <= System.currentTimeMillis()
        if (startedNow) {
            Toast.makeText(this, R.string.started_now, Toast.LENGTH_SHORT).show()
        } else {
            val d = Date(scheduledAtMillis)
            val when_ = DateFormat.getMediumDateFormat(this).format(d) + " " +
                DateFormat.getTimeFormat(this).format(d)
            Toast.makeText(this, getString(R.string.scheduled_for, when_), Toast.LENGTH_LONG).show()
        }

        binding.buttonStart.isEnabled = false
        binding.buttonStop.isEnabled = true

        maybeRequestBatteryExemption()
    }

    /**
     * Aggressive battery optimization is the usual reason a background service
     * gets killed after the first send. Ask the user to exempt the app.
     */
    private fun maybeRequestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.battery_allow) { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        // No settings screen available; nothing more we can do.
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun stopSending() {
        // Cancel a pending scheduled start, disable the auto-cycle, and stop any
        // running sender.
        ScheduleManager.cancel(this)
        SenderState.setCycleEnabled(this, false)
        stopService(Intent(this, SmsSenderService::class.java))

        Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()

        binding.buttonStart.isEnabled = true
        binding.buttonStop.isEnabled = false
    }
}

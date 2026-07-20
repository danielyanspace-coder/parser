package com.example.messagesender

import android.Manifest
import android.app.DatePickerDialog
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
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

    /** Chosen start time (epoch millis); 0 = now. */
    private var scheduledAtMillis: Long = 0L

    /** Editable list of send windows. */
    private val windows = mutableListOf<Window>()

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

        // Quick-pick interval chips fill the open interval field.
        binding.chip5.setOnClickListener { binding.editInterval.setText("5") }
        binding.chip10.setOnClickListener { binding.editInterval.setText("10") }
        binding.chip15.setOnClickListener { binding.editInterval.setText("15") }

        binding.buttonAddWindow.setOnClickListener { addOrEditWindow(null) }

        prefillFromState()
        renderWindows()

        LicenseRefreshScheduler.schedule(this)
        UpdateManager.checkForUpdate(this)
        checkWhatsNew()
    }

    /** Restores the last-used configuration into the form. */
    private fun prefillFromState() {
        val savedInterval = (SenderState.intervalMs(this) / 1000L)
        binding.editInterval.setText(savedInterval.toString())
        windows.clear()
        windows.addAll(SenderState.windows(this))
        binding.switchRepeatDaily.isChecked = SenderState.repeatDaily(this)
        val limit = SenderState.triggerLimit(this)
        if (limit > 0) binding.editTriggerLimit.setText(limit.toString())
    }

    /** Shows a one-time "what's new" dialog after an update. */
    private fun checkWhatsNew() {
        val prefs = getSharedPreferences("app_meta", MODE_PRIVATE)
        val last = prefs.getInt("last_version", 0)
        val current = BuildConfig.VERSION_CODE
        // Show after an update. last==0 with a saved token means an existing user
        // whose previous version didn't record anything (e.g. updating from 1.0).
        val updated = (last in 1 until current) ||
            (last == 0 && current > 1 && LicenseManager.savedToken(this).isNotBlank())
        if (updated) {
            AlertDialog.Builder(this)
                .setTitle(R.string.whats_new_title)
                .setMessage(R.string.whats_new_text)
                .setPositiveButton(R.string.close, null)
                .show()
        }
        if (last != current) prefs.edit().putInt("last_version", current).apply()
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

        var base = when {
            running -> getString(R.string.status_running, SenderStatus.sentCount)
            jobActive -> getString(R.string.status_scheduled)
            else -> getString(R.string.status_idle)
        }
        if (jobActive) {
            val count = SenderState.triggerCount(this)
            val limit = SenderState.triggerLimit(this)
            val triggers = if (limit > 0) {
                getString(R.string.status_triggers_limit, count, limit)
            } else {
                getString(R.string.status_triggers, count)
            }
            base += "\n" + triggers
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
        val initial = if (scheduledAtMillis > 0) {
            Calendar.getInstance().apply { timeInMillis = scheduledAtMillis }
        } else {
            Calendar.getInstance()
        }

        DatePickerDialog(
            this,
            { _, year, month, day -> pickTimeWithSeconds(year, month, day, initial) },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** Time picker with a seconds wheel (HH:mm:ss), which the stock dialog lacks. */
    private fun pickTimeWithSeconds(year: Int, month: Int, day: Int, initial: Calendar) {
        val view = layoutInflater.inflate(R.layout.dialog_time_seconds, null)
        val hourPicker = view.findViewById<android.widget.NumberPicker>(R.id.pickerHour)
        val minutePicker = view.findViewById<android.widget.NumberPicker>(R.id.pickerMinute)
        val secondPicker = view.findViewById<android.widget.NumberPicker>(R.id.pickerSecond)

        hourPicker.minValue = 0; hourPicker.maxValue = 23
        minutePicker.minValue = 0; minutePicker.maxValue = 59
        secondPicker.minValue = 0; secondPicker.maxValue = 59
        val twoDigits = android.widget.NumberPicker.Formatter { String.format("%02d", it) }
        hourPicker.setFormatter(twoDigits)
        minutePicker.setFormatter(twoDigits)
        secondPicker.setFormatter(twoDigits)
        hourPicker.value = initial.get(Calendar.HOUR_OF_DAY)
        minutePicker.value = initial.get(Calendar.MINUTE)
        secondPicker.value = 0

        AlertDialog.Builder(this)
            .setTitle(R.string.hint_schedule)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = Calendar.getInstance().apply {
                    set(year, month, day, hourPicker.value, minutePicker.value, secondPicker.value)
                    set(Calendar.MILLISECOND, 0)
                }
                scheduledAtMillis = chosen.timeInMillis
                binding.buttonSchedule.text = formatSchedule(scheduledAtMillis)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatSchedule(millis: Long): String =
        java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault())
            .format(Date(millis))

    // --- Send windows ---

    private fun addOrEditWindow(index: Int?) {
        val existing = index?.let { windows.getOrNull(it) }
        pickTimeOfDay(R.string.window_start_title, existing?.startSec ?: 9 * 3600) { start ->
            pickTimeOfDay(R.string.window_end_title, existing?.endSec ?: (start + 3600) % 86400) { end ->
                if (end == start) {
                    Toast.makeText(this, R.string.error_window_order, Toast.LENGTH_LONG).show()
                    return@pickTimeOfDay
                }
                val w = Window(start, end)
                if (index == null) windows.add(w) else windows[index] = w
                renderWindows()
            }
        }
    }

    private fun pickTimeOfDay(titleRes: Int, initialSec: Int, onPicked: (Int) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_time_seconds, null)
        val h = view.findViewById<NumberPicker>(R.id.pickerHour)
        val m = view.findViewById<NumberPicker>(R.id.pickerMinute)
        val s = view.findViewById<NumberPicker>(R.id.pickerSecond)
        h.minValue = 0; h.maxValue = 23
        m.minValue = 0; m.maxValue = 59
        s.minValue = 0; s.maxValue = 59
        val twoDigits = NumberPicker.Formatter { String.format("%02d", it) }
        h.setFormatter(twoDigits); m.setFormatter(twoDigits); s.setFormatter(twoDigits)
        h.value = initialSec / 3600
        m.value = (initialSec % 3600) / 60
        s.value = initialSec % 60

        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onPicked(h.value * 3600 + m.value * 60 + s.value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderWindows() {
        val container = binding.windowsContainer
        container.removeAllViews()
        windows.forEachIndexed { i, w ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }
            val label = TextView(this).apply {
                text = w.label()
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val edit = actionView("✎") { addOrEditWindow(i) }
            val del = actionView("✕") { windows.removeAt(i); renderWindows() }
            row.addView(label)
            row.addView(edit)
            row.addView(del)
            container.addView(row)
        }
    }

    private fun actionView(symbol: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            textSize = 20f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.neon_cyan))
            setPadding(24, 8, 24, 8)
            isClickable = true
            setOnClickListener { onClick() }
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
        val intervalSeconds = binding.editInterval.text?.toString()?.trim()?.toLongOrNull() ?: 0L
        if (intervalSeconds < 1) {
            binding.editInterval.error = getString(R.string.error_interval_invalid)
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

    /** Saves the full configuration and starts the sender service. */
    private fun commitStart() {
        val phone = binding.editPhone.text?.toString()?.trim().orEmpty()
        val message1 = binding.editMessage1.text?.toString()?.trim().orEmpty()
        val message2 = binding.editMessage2.text?.toString()?.trim().orEmpty()
        // The two fields are sent as one message, joined by a space.
        val message = "$message1 $message2"
        val intervalSeconds = binding.editInterval.text?.toString()?.trim()?.toLongOrNull() ?: 15L
        val intervalMs = intervalSeconds * 1000L
        val triggerLimit = binding.editTriggerLimit.text?.toString()?.trim()?.toIntOrNull() ?: 0
        val repeatDaily = binding.switchRepeatDaily.isChecked
        // scheduledAtMillis is only used when there are no windows. A time in the
        // past (or 0) means "start now".
        val startAt = if (scheduledAtMillis > System.currentTimeMillis()) scheduledAtMillis else 0L

        SenderState.start(
            this, phone, message, intervalMs,
            windows.toList(), repeatDaily, startAt, triggerLimit
        )
        ContextCompat.startForegroundService(this, Intent(this, SmsSenderService::class.java))

        val scheduledLater = windows.isEmpty() && startAt > 0L
        if (scheduledLater) {
            Toast.makeText(
                this,
                getString(R.string.scheduled_for, formatSchedule(startAt)),
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, R.string.started_now, Toast.LENGTH_SHORT).show()
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
        // Disable the cycle and stop any running sender.
        SenderState.setCycleEnabled(this, false)
        stopService(Intent(this, SmsSenderService::class.java))

        Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()

        binding.buttonStart.isEnabled = true
        binding.buttonStop.isEnabled = false
    }
}

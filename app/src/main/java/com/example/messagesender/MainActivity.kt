package com.example.messagesender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.messagesender.databinding.ActivityMainBinding
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val background = Executors.newSingleThreadExecutor()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val smsGranted = result[Manifest.permission.SEND_SMS] == true
        if (smsGranted) {
            startSending()
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
        binding.buttonStart.setOnClickListener { onStartClicked() }
        binding.buttonStop.setOnClickListener { stopSending() }

        LicenseRefreshScheduler.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        updateLicenseUi()
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
        if (active) {
            val exp = Date(LicenseManager.leaseExp(this))
            val formatted = DateFormat.getMediumDateFormat(this).format(exp) + " " +
                DateFormat.getTimeFormat(this).format(exp)
            binding.textLicenseStatus.text = getString(R.string.license_status_active, formatted)
        } else {
            binding.textLicenseStatus.text = getString(R.string.license_status_inactive)
        }
        binding.buttonStart.isEnabled = active
    }

    // --- Sending ---

    private fun onStartClicked() {
        if (!LicenseManager.hasValidLease(this)) {
            Toast.makeText(this, R.string.license_required, Toast.LENGTH_LONG).show()
            return
        }

        val phone = binding.editPhone.text?.toString()?.trim().orEmpty()
        val message = binding.editMessage.text?.toString()?.trim().orEmpty()
        val intervalText = binding.editInterval.text?.toString()?.trim().orEmpty()

        if (phone.isBlank()) {
            binding.editPhone.error = getString(R.string.error_phone_required)
            return
        }
        if (message.isBlank()) {
            binding.editMessage.error = getString(R.string.error_message_required)
            return
        }

        val intervalSeconds = intervalText.toLongOrNull() ?: 15L
        if (intervalSeconds < 1) {
            binding.editInterval.error = getString(R.string.error_interval_invalid)
            return
        }

        if (hasRequiredPermissions()) {
            startSending()
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

    private fun startSending() {
        if (!LicenseManager.hasValidLease(this)) {
            Toast.makeText(this, R.string.license_required, Toast.LENGTH_LONG).show()
            updateLicenseUi()
            return
        }

        val phone = binding.editPhone.text?.toString()?.trim().orEmpty()
        val message = binding.editMessage.text?.toString()?.trim().orEmpty()
        val intervalSeconds = binding.editInterval.text?.toString()?.trim()?.toLongOrNull() ?: 15L
        val intervalMs = intervalSeconds * 1000L

        // Persist parameters and enable the automatic cycle so SmsReceiver can
        // pause on "символ" and resume on "успешно" later.
        SenderState.save(this, phone, message, intervalMs)

        val intent = Intent(this, SmsSenderService::class.java).apply {
            putExtra(SmsSenderService.EXTRA_PHONE, phone)
            putExtra(SmsSenderService.EXTRA_MESSAGE, message)
            putExtra(SmsSenderService.EXTRA_INTERVAL_MS, intervalMs)
        }
        ContextCompat.startForegroundService(this, intent)

        Toast.makeText(
            this,
            getString(R.string.started, intervalSeconds),
            Toast.LENGTH_SHORT
        ).show()

        binding.buttonStart.isEnabled = false
        binding.buttonStop.isEnabled = true
    }

    private fun stopSending() {
        val intent = Intent(this, SmsSenderService::class.java).apply {
            action = SmsSenderService.ACTION_STOP
        }
        startService(intent)

        Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()

        binding.buttonStart.isEnabled = LicenseManager.hasValidLease(this)
        binding.buttonStop.isEnabled = false
    }
}

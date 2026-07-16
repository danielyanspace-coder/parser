package com.example.messagesender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.messagesender.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        binding.buttonStart.setOnClickListener { onStartClicked() }
        binding.buttonStop.setOnClickListener { stopSending() }
    }

    private fun onStartClicked() {
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

        binding.buttonStart.isEnabled = true
        binding.buttonStop.isEnabled = false
    }
}

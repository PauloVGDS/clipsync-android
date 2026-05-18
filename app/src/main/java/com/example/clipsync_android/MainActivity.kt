package com.example.clipsync_android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * UI minima: pede permissoes, oferece botoes para ligar/desligar o
 * BleService, mostra estado e ultimo evento. Toda a logica BLE vive no
 * service (sobrevive a app em background).
 */
class MainActivity : ComponentActivity() {

    // Pedido em batch das permissoes que precisamos.
    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* o usuario pode negar; vamos so reabrir a UI - botao Iniciar nao funciona sem perms */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissions()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ClipSyncScreen(
                        onStart = { startBleService(start = true) },
                        onStop  = { startBleService(start = false) }
                    )
                }
            }
        }
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: novo modelo
            if (!hasPerm(Manifest.permission.BLUETOOTH_SCAN))
                needed += Manifest.permission.BLUETOOTH_SCAN
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT))
                needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            // Android < 12: scan exige ACCESS_FINE_LOCATION
            if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION))
                needed += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPerm(Manifest.permission.POST_NOTIFICATIONS))
                needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (needed.isNotEmpty()) permsLauncher.launch(needed.toTypedArray())
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startBleService(start: Boolean) {
        val i = Intent(this, BleService::class.java).apply {
            action = if (start) BleService.ACTION_START else BleService.ACTION_STOP
        }
        if (start && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }
}

@Composable
private fun ClipSyncScreen(onStart: () -> Unit, onStop: () -> Unit) {
    val state by BleService.state.collectAsStateWithLifecycle()
    val lastEvent by BleService.lastEvent.collectAsStateWithLifecycle()

    Scaffold { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("ClipSync", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Cliente Android - mantem clipboard em sync com o ESP32 via BLE",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Estado", fontWeight = FontWeight.SemiBold)
                    Text(state.label())
                    Spacer(Modifier.height(8.dp))
                    Text("Ultimo evento", fontWeight = FontWeight.SemiBold)
                    Text(if (lastEvent.isBlank()) "-" else lastEvent)
                }
            }

            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Iniciar (foreground service)")
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("Parar")
            }

            Text(
                "O servico continua rodando com o app em background. " +
                    "A notificacao persistente e a forma que o Android exige " +
                    "para manter o BLE vivo.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun BleService.ConnectionState.label() = when (this) {
    BleService.ConnectionState.IDLE         -> "Ocioso"
    BleService.ConnectionState.SCANNING     -> "Procurando ClipSync..."
    BleService.ConnectionState.CONNECTING   -> "Conectando..."
    BleService.ConnectionState.CONNECTED    -> "Conectado"
    BleService.ConnectionState.DISCONNECTED -> "Desconectado (tentando reconectar)"
}

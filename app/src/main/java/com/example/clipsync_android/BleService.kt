package com.example.clipsync_android

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Foreground Service que mantem o BLE conectado ao ESP32 ClipSync, mesmo
 * com a UI em background.
 *
 *  - Scan filtrado pelo SERVICE_UUID do firmware (sem depender de nome)
 *  - Connect + GATT discovery + subscribe nas duas characteristics
 *    relevantes pro lado celular: `cmd` (notify) e `to_mobile` (notify).
 *  - cmd == 0x02 (REQ_MOBILE) -> le ClipboardManager.primaryClip e
 *    escreve em `from_mobile`.
 *  - to_mobile notify -> grava o payload na ClipboardManager local.
 *  - Reconexao automatica quando o link cai.
 *
 * Estado exposto via StateFlow estatico (UI binda direto, sem AIDL).
 */
class BleService : Service() {

    companion object {
        private const val TAG = "BleService"

        // Cmd codes - precisam bater com src/main.cpp do firmware.
        private const val CMD_REQ_PC: Byte = 0x01
        private const val CMD_REQ_MOBILE: Byte = 0x02

        // UUIDs - mesmas do firmware.
        val SERVICE_UUID: UUID = UUID.fromString("b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e")
        val CHAR_CMD_UUID: UUID = UUID.fromString("b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5f")
        val CHAR_FROM_MOBILE_UUID: UUID = UUID.fromString("b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d61")
        val CHAR_TO_MOBILE_UUID: UUID = UUID.fromString("b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d63")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Notificacao do foreground service
        private const val NOTIF_CHANNEL_ID = "clipsync_ble"
        private const val NOTIF_ID = 1

        // ACTIONs
        const val ACTION_START = "com.example.clipsync_android.action.START"
        const val ACTION_STOP  = "com.example.clipsync_android.action.STOP"

        // Estado global, observado pela UI.
        private val _state = MutableStateFlow(ConnectionState.IDLE)
        val state: StateFlow<ConnectionState> = _state.asStateFlow()
        private val _lastEvent = MutableStateFlow("")
        val lastEvent: StateFlow<String> = _lastEvent.asStateFlow()

        // Limite seguro: MTU negociado tipico 185-247 em Android, payload
        // util ~180-240 bytes. Sem chunking no firmware (item #4 do
        // CLAUDE.md), o que exceder e truncado pelo proprio chip BLE.
        private const val MAX_PAYLOAD_BYTES = 180
    }

    enum class ConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED }

    private val handler get() = android.os.Handler(mainLooper)
    private var bleAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var fromMobile: BluetoothGattCharacteristic? = null
    private var scanning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bleAdapter = bm.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBleAndService()
                return START_NOT_STICKY
            }
            else -> {
                startInForeground()
                startScan()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        teardownGatt()
        _state.value = ConnectionState.IDLE
    }

    // -------- Foreground notification --------

    private fun createNotificationChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(NOTIF_CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            NOTIF_CHANNEL_ID, "ClipSync BLE", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Mantem a conexao BLE com o ESP32 viva" }
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ClipSync")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun startInForeground() {
        val notif = buildNotification("Procurando ESP32...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    // -------- BLE scan --------

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return
        val adapter = bleAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateState(ConnectionState.IDLE, "Bluetooth desligado")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            updateState(ConnectionState.IDLE, "Sem scanner BLE")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        updateState(ConnectionState.SCANNING, "Procurando ClipSync...")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        bleAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.d(TAG, "scan hit ${device.address}")
            stopScan()
            connect(device)
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            updateState(ConnectionState.IDLE, "Scan falhou: $errorCode")
            // Tenta de novo em 5s
            handler.postDelayed({ startScan() }, 5_000)
        }
    }

    // -------- GATT --------

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        updateState(ConnectionState.CONNECTING, "Conectando em ${device.address}")
        gatt = device.connectGatt(this, /*autoConnect=*/false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun teardownGatt() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        fromMobile = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    updateState(ConnectionState.CONNECTED, "Conectado, descobrindo servicos...")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    teardownGatt()
                    updateState(ConnectionState.DISCONNECTED, "Caiu - tentando reconectar")
                    // Reconect: simples - re-scan apos pequeno backoff.
                    handler.postDelayed({ startScan() }, 2_000)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID) ?: run {
                updateState(ConnectionState.IDLE, "Service nao encontrado")
                teardownGatt(); return
            }
            fromMobile = svc.getCharacteristic(CHAR_FROM_MOBILE_UUID)
            val cmd = svc.getCharacteristic(CHAR_CMD_UUID)
            val toMobile = svc.getCharacteristic(CHAR_TO_MOBILE_UUID)
            if (cmd == null || toMobile == null || fromMobile == null) {
                updateState(ConnectionState.IDLE, "Characteristics ausentes")
                teardownGatt(); return
            }
            // Habilita notify nas duas que recebemos
            enableNotify(g, cmd)
            // Enfileira a segunda apos a primeira (Android exige um descritor por vez)
            handler.postDelayed({ enableNotify(g, toMobile) }, 200)
            updateState(ConnectionState.CONNECTED, "Pronto")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleNotify(ch.uuid, value)
        }

        // Compat fallback p/ APIs antigas do callback (algumas implementacoes
        // ainda chamam o overload deprecated).
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleNotify(ch.uuid, ch.value ?: byteArrayOf())
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    // -------- Handlers de evento BLE --------

    @SuppressLint("MissingPermission")
    private fun handleNotify(uuid: UUID, value: ByteArray) {
        when (uuid) {
            CHAR_CMD_UUID -> {
                if (value.isEmpty()) return
                Log.d(TAG, "cmd recebido: 0x${value[0].toString(16)}")
                if (value[0] == CMD_REQ_MOBILE) sendClipboardToEsp()
            }
            CHAR_TO_MOBILE_UUID -> {
                val text = value.toString(Charsets.UTF_8)
                Log.d(TAG, "to_mobile: ${value.size} bytes")
                writeClipboardLocal(text)
                _lastEvent.value = "Recebido: ${value.size} bytes -> clipboard"
                updateNotification("Recebido ${value.size} bytes")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendClipboardToEsp() {
        val text = readClipboardLocal() ?: ""
        if (text.isEmpty()) {
            _lastEvent.value = "Clipboard vazia, nada a enviar"
            return
        }
        val bytes = text.toByteArray(Charsets.UTF_8).let {
            if (it.size > MAX_PAYLOAD_BYTES) it.copyOf(MAX_PAYLOAD_BYTES) else it
        }
        val ch = fromMobile ?: return
        val g = gatt ?: return
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
        _lastEvent.value = if (ok)
            "Enviado ${bytes.size} bytes ao ESP32"
        else
            "Falhou ao enviar clipboard"
        updateNotification("Enviado ${bytes.size} bytes")
    }

    private fun readClipboardLocal(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this).toString()
    }

    private fun writeClipboardLocal(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ClipSync", text))
    }

    // -------- helpers --------

    private fun updateState(s: ConnectionState, msg: String) {
        _state.value = s
        _lastEvent.value = msg
        updateNotification(msg)
        Log.d(TAG, "[$s] $msg")
    }

    private fun stopBleAndService() {
        stopScan()
        teardownGatt()
        _state.value = ConnectionState.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}

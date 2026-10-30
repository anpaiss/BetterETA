package com.anpaiss.boschrange

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * The Karoo as a Bosch Live Data Interface accessory (LDI V1.0), proven with
 * the LdiProbe on 30/10/2026: the bike is the GAP central and GATT server, we
 * advertise with the Live Data Service in the Service Solicitation UUIDs and
 * host a Battery + Device Information service (what the Flow app shows for the
 * accessory); when the bike connects we become its GATT client, ask MTU 247,
 * read eb21 once (the encryption it requires triggers the bonding, done from
 * Flow → Add component → Accessories the first time) and subscribe to its
 * notifications: the first carries every field, the next ones only what
 * changed, about once a second.
 *
 * Reconnection costs nothing: the bonded bike connects by itself whenever it is
 * on and we are advertising, so the advertising never stops (restarted after
 * every disconnection) while the extension lives. Other devices that connect
 * to the Karoo (the phone, a watch) reach the server callback too: a device
 * without the Live Data Service is left alone after one look, the bike's
 * address is remembered in the prefs.
 */
@SuppressLint("MissingPermission")
class LdiLink(
    private val ctx: Context,
    private val logger: BoschRangeLogger,
    private val prefs: Prefs,
    private val listener: Listener,
) {
    interface Listener {
        fun onLiveData(data: LiveData)
        fun onLinkChanged(connected: Boolean)
    }

    companion object {
        val LDI_SERVICE: UUID = UUID.fromString("0000eb20-eaa2-11e9-81b4-2a2ae2dbcce4")
        val LDI_CHAR: UUID    = UUID.fromString("0000eb21-eaa2-11e9-81b4-2a2ae2dbcce4")
        val CCCD: UUID        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE: UUID  = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID    = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DIS_SERVICE: UUID      = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val DIS_MANUFACTURER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        val DIS_MODEL: UUID        = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        /** LDI-003: below this the bike truncates the notifications to 20 bytes. */
        const val MTU = 247
        private const val CLIENT_DELAY_MS = 500L
        private const val TAG = "LDI  "
    }

    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val main = Handler(Looper.getMainLooper())
    private var advertiser: BluetoothLeAdvertiser? = null
    private var server: BluetoothGattServer? = null
    private var gatt: BluetoothGatt? = null
    private var pendingServices = mutableListOf<BluetoothGattService>()
    /** Devices seen this session without the Live Data Service: not probed again. */
    private val notTheBike = mutableSetOf<String>()
    private var started = false
    private var notifications = 0L

    @Volatile var connected = false
        private set

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> { logger.log(TAG, "Bluetooth acceso: riparto"); main.post { startRadio() } }
                BluetoothAdapter.STATE_TURNING_OFF -> { logger.log(TAG, "Bluetooth spento"); main.post { stopRadio() } }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        ctx.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        startRadio()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { ctx.unregisterReceiver(stateReceiver) }
        stopRadio()
        logger.log(TAG, "Accessorio LDI fermato")
    }

    private fun startRadio() {
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) { logger.log(TAG, "Bluetooth spento o assente: accessorio LDI in attesa"); return }
        if (server == null) openServer()
        startAdvertising()
    }

    private fun stopRadio() {
        runCatching { advertiser?.stopAdvertising(advCallback) }
        advertiser = null
        closeClient()
        runCatching { server?.close() }
        server = null
    }

    // ── GATT server: Battery + DIS, and the callback that announces the bike's connection ──

    private fun openServer() {
        val s = manager.openGattServer(ctx, serverCallback) ?: run { logger.log(TAG, "openGattServer fallita"); return }
        server = s
        val bas = BluetoothGattService(BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(BluetoothGattCharacteristic(BATTERY_LEVEL,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ).apply {
                addDescriptor(BluetoothGattDescriptor(CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            })
        }
        val dis = BluetoothGattService(DIS_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(BluetoothGattCharacteristic(DIS_MANUFACTURER, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            addCharacteristic(BluetoothGattCharacteristic(DIS_MODEL, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        }
        pendingServices = mutableListOf(dis)      // addService is asynchronous: one at a time
        s.addService(bas)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            pendingServices.removeFirstOrNull()?.let { server?.addService(it) }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val known = prefs.bikeAddress
                when {
                    known == addr -> logger.log(TAG, "Bici connessa ($addr)")
                    known == null && addr !in notTheBike -> logger.log(TAG, "Connessione da $addr '${device.name ?: ""}': cerco il Live Data Service")
                    else -> return                                     // the phone, the watch: not ours
                }
                main.postDelayed({ connectAsClient(device) }, CLIENT_DELAY_MS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (addr == prefs.bikeAddress) {
                    logger.log(TAG, "Bici disconnessa (status $status) dopo $notifications notifiche")
                    closeClient()
                }
                main.post { startAdvertising() }   // a connection ends the connectable advertising: let the bike find us again
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            val value = when (ch.uuid) {
                BATTERY_LEVEL -> byteArrayOf(karooBatteryPct().toByte())
                DIS_MANUFACTURER -> "anpaiss".toByteArray()
                DIS_MODEL -> "boschRange on Karoo".toByteArray()
                else -> byteArrayOf()
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(minOf(offset, value.size), value.size))
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }
    }

    private fun karooBatteryPct(): Int =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 100

    // ── Advertising: solicitation of the Live Data Service, name in the scan response ──

    private fun startAdvertising() {
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled || !started) return
        val adv = adapter.bluetoothLeAdvertiser ?: run { logger.log(TAG, "Nessun advertiser LE: ruolo accessorio non supportato"); return }
        if (advertiser != null) runCatching { adv.stopAdvertising(advCallback) }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // 128-bit solicitation UUID = 18 bytes + 3 of flags: the name only fits in the scan response
        val data = AdvertiseData.Builder()
            .addServiceSolicitationUuid(ParcelUuid(LDI_SERVICE))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(BATTERY_SERVICE))
            .build()
        adv.startAdvertising(settings, data, scanResponse, advCallback)
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            logger.log(TAG, "Advertising come accessorio LDI (bici nota: ${prefs.bikeAddress ?: "nessuna, associare da Flow → Accessori"})")
        }
        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return
            logger.log(TAG, "Advertising FALLITO, codice $errorCode (1 dati troppo lunghi, 2 troppi advertiser, 4 interno, 5 non supportato)")
        }
    }

    // ── GATT client on the bike's own connection ──

    private fun connectAsClient(device: BluetoothDevice) {
        if (gatt != null || !started) return
        gatt = device.connectGatt(ctx, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeClient() {
        runCatching { gatt?.close() }
        gatt = null
        if (connected) { connected = false; listener.onLinkChanged(false) }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(MTU)) logger.log(TAG, "requestMtu rifiutata")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (gatt === g) closeClient() else g.close()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (mtu < MTU) logger.log(TAG, "MTU $mtu < $MTU: le notifiche arriveranno troncate")
            if (!g.discoverServices()) logger.log(TAG, "discoverServices rifiutata")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(LDI_SERVICE)?.getCharacteristic(LDI_CHAR)
            val addr = g.device.address
            if (ch == null) {
                logger.log(TAG, "$addr non ha il Live Data Service: non è la bici")
                notTheBike += addr
                g.close(); if (gatt === g) gatt = null
                return
            }
            if (prefs.bikeAddress != addr) { prefs.bikeAddress = addr; logger.log(TAG, "Bici trovata: $addr '${g.device.name ?: ""}', indirizzo salvato") }
            notifications = 0L
            // The read forces the encryption eb21 requires: pairing the first time, then the bond does it
            if (!g.readCharacteristic(ch)) logger.log(TAG, "Lettura di eb21 rifiutata")
        }

        @Deprecated("Android 12 (API 32) still calls this signature")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val d = deliver(ch.value ?: byteArrayOf()) ?: return
                logger.log(TAG, "Prima lettura: $d")
                if (!connected) { connected = true; listener.onLinkChanged(true) }
            } else {
                logger.log(TAG, "Lettura di eb21 fallita, status $status" +
                    if (status == 5 || status == 8 || status == 15) " (cifratura: la bici deve essere associata da Flow → Accessori)" else "")
            }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD) ?: run { logger.log(TAG, "eb21 senza CCCD"); return }
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) logger.log(TAG, "Abilitazione notifiche fallita, status $status")
        }

        @Deprecated("Android 12 (API 32) still calls this signature")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            notifications++
            deliver(ch.value ?: byteArrayOf()) ?: return
            if (!connected) { connected = true; listener.onLinkChanged(true) }
        }
    }

    private fun deliver(bytes: ByteArray): LiveData? {
        val d = runCatching { LiveData.decode(bytes) }
            .onFailure { logger.log(TAG, "Messaggio LiveData non decodificabile (${bytes.size} byte): ${it.message}") }
            .getOrNull() ?: return null
        listener.onLiveData(d)
        return d
    }
}

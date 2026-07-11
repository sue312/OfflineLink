package com.example.offlinelink.transport

import android.Manifest
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
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal const val LONG_RANGE_GATT_VALUE_BYTES = 45
internal const val ATT_PROTOCOL_OVERHEAD_BYTES = 3

internal fun boundedGattValueBytes(
  currentMtu: Int,
  providerMtu: Int,
): Int {
  val negotiatedValueBytes = maxOf(currentMtu, providerMtu) - ATT_PROTOCOL_OVERHEAD_BYTES
  return maxOf(GattFrameCodec.HEADER_BYTES + 1, minOf(LONG_RANGE_GATT_VALUE_BYTES, negotiatedValueBytes))
}

class GattChatTransport(context: Context) : ChatTransport {
  private val appContext = context.applicationContext
  private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 128)
  private val lock = Any()
  private val discoveredEndpoints = mutableMapOf<String, GattDiscoveredEndpoint>()
  private val pendingServerSessions = mutableMapOf<String, GattServerSession>()
  private val connections = mutableMapOf<String, GattConnection>()
  private val emittedEndpointIds = mutableSetOf<String>()
  private val lastSignalUpdateAtMs = mutableMapOf<String, Long>()

  private var gattServer: BluetoothGattServer? = null
  private var serverTxCharacteristic: BluetoothGattCharacteristic? = null
  private var bleAdvertiseCallback: AdvertiseCallback? = null
  private var bleAdvertisingSet: AdvertisingSet? = null
  private var bleAdvertisingSetCallback: AdvertisingSetCallback? = null
  private var bleScanCallback: ScanCallback? = null

  override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

  @SuppressLint("MissingPermission")
  override fun startAdvertising(
    displayName: String,
    deviceId: String,
  ) {
    val adapter =
      readyAdapter(
        requireConnectPermission = true,
        requireScanPermission = false,
        requireAdvertisePermission = true,
      ) ?: return
    if (!ensureGattServer()) return
    startBleAdvertising(adapter, deviceId = deviceId)
    logDebug("Bluetooth GATT listener started for $displayName")
  }

  @SuppressLint("MissingPermission")
  override fun startDiscovery() {
    val adapter = readyAdapter(requireConnectPermission = true, requireScanPermission = true) ?: return
    clearServiceChecks()
    val bleScanStarted = startBleScan(adapter)
    if (bleScanStarted) {
      logDebug("Bluetooth GATT beacon scan requested")
      return
    }
    emitFailure("Bluetooth LE scan is required for OfflineLink GATT mode")
  }

  override fun stopAdvertising() {
    stopBleAdvertising()
  }

  @SuppressLint("MissingPermission")
  override fun stopDiscovery() {
    stopBleScan()
    adapterOrNull()?.let { adapter ->
      runCatching {
        if (hasScanPermission() && adapter.isDiscovering) adapter.cancelDiscovery()
      }
    }
  }

  @SuppressLint("MissingPermission")
  override fun startSignalMonitoring(
    displayName: String,
    deviceId: String,
  ) {
    val adapter =
      readyAdapter(
        requireConnectPermission = true,
        requireScanPermission = true,
        requireAdvertisePermission = true,
      ) ?: return
    if (!ensureGattServer()) return
    startBleAdvertising(adapter, deviceId = deviceId, mode = BleBeaconMode.SignalMonitoring)
    val scanStarted = startBleScan(adapter, signalMonitoring = true)
    logDebug("Bluetooth GATT signal monitoring requested for $displayName/$deviceId started=$scanStarted")
  }

  override fun stopSignalMonitoring() {
    stopBleScan()
    stopBleAdvertising()
    synchronized(lock) {
      lastSignalUpdateAtMs.clear()
    }
    logDebug("Bluetooth GATT signal monitoring stopped")
  }

  @SuppressLint("MissingPermission")
  override fun requestConnection(
    endpoint: NearbyEndpoint,
    displayName: String,
    deviceId: String,
  ) {
    val adapter = readyAdapter(requireConnectPermission = true, requireScanPermission = false) ?: return
    val discoveredEndpoint =
      synchronized(lock) { discoveredEndpoints[endpoint.id] }
        ?: OfflineLinkBluetoothService.parseBleGattEndpointId(endpoint.id)?.let {
          GattDiscoveredEndpoint(adapter.getRemoteDevice(it.address), endpoint)
        }
    if (discoveredEndpoint == null) {
      emitFailure("OfflineLink GATT mode requires a BLE GATT endpoint")
      return
    }
    stopBleScan()
    if (hasScanPermission() && adapter.isDiscovering) {
      runCatching { adapter.cancelDiscovery() }
    }
    startClientConnection(discoveredEndpoint.device, discoveredEndpoint.endpoint)
  }

  override fun acceptConnection(endpointId: String) {
    val session =
      synchronized(lock) {
        pendingServerSessions[endpointId]
      }
    if (session == null) {
      emitFailure("No pending Bluetooth GATT connection")
      return
    }
    if (!session.notificationsEnabled) {
      session.accepted = true
      logDebug("Bluetooth GATT accept delayed endpoint=${endpointId.takeLast(5)} waiting for notifications")
      return
    }
    activateAcceptedServerSession(endpointId)
  }

  override fun rejectConnection(endpointId: String) {
    val session =
      synchronized(lock) {
        pendingServerSessions.remove(endpointId)
      }
    session?.let {
      runCatching { gattServer?.cancelConnection(it.device) }
    }
  }

  override fun send(
    endpointId: String,
    bytes: ByteArray,
    onResult: (Result<Unit>) -> Unit,
  ) {
    val connection =
      synchronized(lock) {
        connections[endpointId]
      }
    if (connection == null) {
      onResult(Result.failure(IllegalStateException("Bluetooth GATT endpoint is not connected")))
      return
    }
    connection.send(bytes, onResult)
  }

  override fun linkStats(endpointId: String): TransportLinkStats =
    synchronized(lock) {
      connections[endpointId]?.linkStats() ?: TransportLinkStats()
    }

  override fun disconnectEndpoint(endpointId: String) {
    val removed =
      synchronized(lock) {
        lastSignalUpdateAtMs.remove(endpointId)
        Pair(connections.remove(endpointId), pendingServerSessions.remove(endpointId))
      }
    removed.first?.close()
    removed.second?.let { runCatching { gattServer?.cancelConnection(it.device) } }
    emit(TransportEvent.Disconnected(endpointId))
  }

  override fun stopAll() {
    stopAdvertising()
    stopDiscovery()
    val removed =
      synchronized(lock) {
        val activeConnections = connections.values.toList()
        val pendingSessions = pendingServerSessions.values.toList()
        connections.clear()
        pendingServerSessions.clear()
        discoveredEndpoints.clear()
        emittedEndpointIds.clear()
        lastSignalUpdateAtMs.clear()
        Pair(activeConnections, pendingSessions)
      }
    removed.first.forEach { it.close() }
    removed.second.forEach { runCatching { gattServer?.cancelConnection(it.device) } }
    closeGattServer()
  }

  @SuppressLint("MissingPermission")
  private fun ensureGattServer(): Boolean {
    if (!hasConnectPermission()) {
      emitFailure("Bluetooth connect permission is required")
      return false
    }
    synchronized(lock) {
      if (gattServer != null) return true
    }

    val server =
      runCatching {
        bluetoothManager?.openGattServer(appContext, serverCallback)
      }.getOrElse {
        emitFailure("Could not open Bluetooth GATT server", it)
        return false
      }
    if (server == null) {
      emitFailure("Could not open Bluetooth GATT server")
      return false
    }

    val service = BluetoothGattService(OfflineLinkBluetoothService.UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    val rxCharacteristic =
      BluetoothGattCharacteristic(
        GATT_RX_CHARACTERISTIC_UUID,
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE,
      )
    val txCharacteristic =
      BluetoothGattCharacteristic(
        GATT_TX_CHARACTERISTIC_UUID,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ,
      )
    txCharacteristic.addDescriptor(
      BluetoothGattDescriptor(
        CLIENT_CHARACTERISTIC_CONFIG_UUID,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
      ),
    )
    service.addCharacteristic(rxCharacteristic)
    service.addCharacteristic(txCharacteristic)

    if (!server.addService(service)) {
      runCatching { server.close() }
      emitFailure("Could not add OfflineLink GATT service")
      return false
    }
    synchronized(lock) {
      gattServer = server
      serverTxCharacteristic = txCharacteristic
    }
    return true
  }

  @SuppressLint("MissingPermission")
  private fun closeGattServer() {
    val server =
      synchronized(lock) {
        val current = gattServer
        gattServer = null
        serverTxCharacteristic = null
        current
      } ?: return
    runCatching { server.close() }
    logDebug("Bluetooth GATT server closed")
  }

  @SuppressLint("MissingPermission")
  private fun startClientConnection(
    device: BluetoothDevice,
    endpoint: NearbyEndpoint,
  ) {
    val callback = ClientGattCallback(endpoint)
    val gatt =
      runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_CODED_MASK)
        } else {
          device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
      }.getOrElse {
        emitFailure("Could not connect over Bluetooth GATT", it)
        return
      }
    callback.gatt = gatt
    logDebug("Bluetooth GATT connect requested endpoint=${endpoint.id.takeLast(5)}")
  }

  private inner class ClientGattCallback(
    private val endpoint: NearbyEndpoint,
  ) : BluetoothGattCallback() {
    var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var mtu = DEFAULT_ATT_MTU
    private var connection: GattConnection? = null

    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(
      gatt: BluetoothGatt,
      status: Int,
      newState: Int,
    ) {
      if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
        logDebug("Bluetooth GATT client connected endpoint=${endpoint.id.takeLast(5)}; requesting LE_CODED/S8")
        requestCodedPhy(gatt, endpoint.id)
        requestClientLongRangeConnectionParams(gatt, endpoint.id)
        if (!gatt.requestMtu(PREFERRED_ATT_MTU)) {
          logDebug("Bluetooth GATT MTU request not accepted endpoint=${endpoint.id.takeLast(5)}; discovering services")
          gatt.discoverServices()
        }
        return
      }
      if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        logDebug("Bluetooth GATT client disconnected endpoint=${endpoint.id.takeLast(5)} status=$status")
        if (connection == null) {
          emitFailure(
            "Bluetooth GATT connection failed",
            IllegalStateException("status=$status state=$newState"),
          )
        }
        handleGattDisconnected(endpoint.id, gatt)
      } else if (status != BluetoothGatt.GATT_SUCCESS) {
        emitFailure("Bluetooth GATT client connection failed", IllegalStateException("status=$status state=$newState"))
      }
    }

    @SuppressLint("MissingPermission")
    override fun onMtuChanged(
      gatt: BluetoothGatt,
      mtu: Int,
      status: Int,
    ) {
      if (status == BluetoothGatt.GATT_SUCCESS) {
        this.mtu = mtu
      }
      logDebug("Bluetooth GATT MTU changed endpoint=${endpoint.id.takeLast(5)} mtu=$mtu status=$status")
      gatt.discoverServices()
    }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(
      gatt: BluetoothGatt,
      status: Int,
    ) {
      if (status != BluetoothGatt.GATT_SUCCESS) {
        emitFailure("Bluetooth GATT service discovery failed", IllegalStateException("status=$status"))
        gatt.close()
        return
      }
      val service = gatt.getService(OfflineLinkBluetoothService.UUID)
      val rx = service?.getCharacteristic(GATT_RX_CHARACTERISTIC_UUID)
      val tx = service?.getCharacteristic(GATT_TX_CHARACTERISTIC_UUID)
      val cccd = tx?.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
      if (service == null || rx == null || tx == null || cccd == null) {
        emitFailure("OfflineLink GATT service is missing required characteristics")
        gatt.close()
        return
      }
      rxCharacteristic = rx
      txCharacteristic = tx
      if (!gatt.setCharacteristicNotification(tx, true)) {
        emitFailure("Could not enable Bluetooth GATT notifications")
        gatt.close()
        return
      }
      cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
      if (!gatt.writeDescriptor(cccd)) {
        emitFailure("Could not write Bluetooth GATT notification descriptor")
        gatt.close()
      }
    }

    override fun onDescriptorWrite(
      gatt: BluetoothGatt,
      descriptor: BluetoothGattDescriptor,
      status: Int,
    ) {
      if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) return
      if (status != BluetoothGatt.GATT_SUCCESS) {
        emitFailure("Bluetooth GATT notification descriptor write failed", IllegalStateException("status=$status"))
        gatt.close()
        return
      }
      val rx = rxCharacteristic
      if (rx == null) {
        emitFailure("Bluetooth GATT write characteristic is not ready")
        gatt.close()
        return
      }
      val clientConnection =
        GattConnection(
          endpointId = endpoint.id,
          device = gatt.device,
          mtuProvider = { mtu },
          writeFragment = { fragment ->
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            rx.value = fragment
            gatt.writeCharacteristic(rx)
          },
          writeResponseExpected = false,
          closeAction = {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
          },
          onBytes = { bytes -> emit(TransportEvent.BytesReceived(endpoint.id, bytes)) },
          onFailure = { message, throwable -> emitFailure(message, throwable) },
        )
      clientConnection.start()
      connection = clientConnection
      val previous =
        synchronized(lock) {
          connections.put(endpoint.id, clientConnection)
        }
      previous?.close()
      logDebug("Bluetooth GATT client ready endpoint=${endpoint.id.takeLast(5)} mtu=$mtu")
      emit(TransportEvent.Connected(endpoint))
    }

    override fun onCharacteristicWrite(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      status: Int,
    ) {
      connection?.completeOutgoing(status)
    }

    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
    ) {
      if (characteristic.uuid == GATT_TX_CHARACTERISTIC_UUID) {
        connection?.acceptInboundFragment(characteristic.value)
      }
    }

    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray,
    ) {
      if (characteristic.uuid == GATT_TX_CHARACTERISTIC_UUID) {
        connection?.acceptInboundFragment(value)
      }
    }

    override fun onPhyUpdate(
      gatt: BluetoothGatt,
      txPhy: Int,
      rxPhy: Int,
      status: Int,
    ) {
      logDebug(
        "Bluetooth GATT PHY update endpoint=${endpoint.id.takeLast(5)} " +
          "txPhy=${blePhyName(txPhy)} rxPhy=${blePhyName(rxPhy)} status=$status",
      )
    }

    override fun onPhyRead(
      gatt: BluetoothGatt,
      txPhy: Int,
      rxPhy: Int,
      status: Int,
    ) {
      logDebug(
        "Bluetooth GATT PHY read endpoint=${endpoint.id.takeLast(5)} " +
          "txPhy=${blePhyName(txPhy)} rxPhy=${blePhyName(rxPhy)} status=$status",
      )
    }
  }

  private val serverCallback =
    object : BluetoothGattServerCallback() {
      override fun onConnectionStateChange(
        device: BluetoothDevice,
        status: Int,
        newState: Int,
      ) {
        val endpoint = endpointForDevice(device) ?: return
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
          requestServerCodedPhy(device, endpoint.id)
          synchronized(lock) {
            pendingServerSessions.getOrPut(endpoint.id) { GattServerSession(device = device, endpoint = endpoint) }
          }
          logDebug("Bluetooth GATT server connected endpoint=${endpoint.id.takeLast(5)}")
          return
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
          logDebug("Bluetooth GATT server disconnected endpoint=${endpoint.id.takeLast(5)} status=$status")
          handleGattDisconnected(endpoint.id)
        } else if (status != BluetoothGatt.GATT_SUCCESS) {
          emitFailure("Bluetooth GATT server connection failed", IllegalStateException("status=$status state=$newState"))
        }
      }

      @SuppressLint("MissingPermission")
      override fun onDescriptorReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor,
      ) {
        if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) {
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, ByteArray(0))
          return
        }
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
      }

      @SuppressLint("MissingPermission")
      override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
      ) {
        val endpoint = endpointForDevice(device)
        val enableNotifications =
          descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID &&
            value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        val status =
          if (endpoint != null && !preparedWrite && offset == 0 && enableNotifications) {
            BluetoothGatt.GATT_SUCCESS
          } else {
            BluetoothGatt.GATT_FAILURE
          }
        if (responseNeeded) {
          gattServer?.sendResponse(device, requestId, status, offset, ByteArray(0))
        }
        if (status != BluetoothGatt.GATT_SUCCESS || endpoint == null) return

        var initiatedEvent: TransportEvent? = null
        val shouldActivate =
          synchronized(lock) {
            val session = pendingServerSessions.getOrPut(endpoint.id) { GattServerSession(device = device, endpoint = endpoint) }
            session.notificationsEnabled = true
            if (!session.initiatedEmitted && !connections.containsKey(endpoint.id)) {
              session.initiatedEmitted = true
              initiatedEvent = connectionInitiatedEvent(session)
            }
            session.accepted
          }
        initiatedEvent?.let(::emit)
        logDebug("Bluetooth GATT notifications enabled endpoint=${endpoint.id.takeLast(5)}")
        if (shouldActivate) {
          activateAcceptedServerSession(endpoint.id)
        }
      }

      @SuppressLint("MissingPermission")
      override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
      ) {
        val endpoint = endpointForDevice(device)
        if (endpoint == null || characteristic.uuid != GATT_RX_CHARACTERISTIC_UUID || preparedWrite || offset != 0) {
          if (responseNeeded) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, ByteArray(0))
          }
          return
        }

        val inboundTarget =
          synchronized(lock) {
            val connection = connections[endpoint.id]
            if (connection != null) {
              ServerInboundTarget.Active(connection)
            } else {
              val session = pendingServerSessions.getOrPut(endpoint.id) { GattServerSession(device = device, endpoint = endpoint) }
              ServerInboundTarget.Pending(session)
            }
          }
        val writeSucceeded = acceptServerInboundFragment(endpoint.id, inboundTarget, value)
        if (responseNeeded) {
          val responseStatus = if (writeSucceeded) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
          gattServer?.sendResponse(device, requestId, responseStatus, offset, ByteArray(0))
        }
        if (!writeSucceeded) {
          synchronized(lock) {
            pendingServerSessions.remove(endpoint.id)
          }
          runCatching { gattServer?.cancelConnection(device) }
        }
      }

      override fun onNotificationSent(
        device: BluetoothDevice,
        status: Int,
      ) {
        val endpointId = endpointForDevice(device)?.id ?: return
        synchronized(lock) {
          connections[endpointId]
        }?.completeOutgoing(status)
      }

      override fun onMtuChanged(
        device: BluetoothDevice,
        mtu: Int,
      ) {
        val endpointId = endpointForDevice(device)?.id ?: return
        synchronized(lock) {
          pendingServerSessions[endpointId]?.mtu = mtu
          connections[endpointId]?.setMtu(mtu)
        }
        logDebug("Bluetooth GATT server MTU changed endpoint=${endpointId.takeLast(5)} mtu=$mtu")
      }

      override fun onPhyUpdate(
        device: BluetoothDevice,
        txPhy: Int,
        rxPhy: Int,
        status: Int,
      ) {
        val endpointId = endpointForDevice(device)?.id ?: deviceAddress(device).orEmpty()
        logDebug(
          "Bluetooth GATT server PHY update endpoint=${endpointId.takeLast(5)} " +
            "txPhy=${blePhyName(txPhy)} rxPhy=${blePhyName(rxPhy)} status=$status",
        )
      }

      override fun onPhyRead(
        device: BluetoothDevice,
        txPhy: Int,
        rxPhy: Int,
        status: Int,
      ) {
        val endpointId = endpointForDevice(device)?.id ?: deviceAddress(device).orEmpty()
        logDebug(
          "Bluetooth GATT server PHY read endpoint=${endpointId.takeLast(5)} " +
            "txPhy=${blePhyName(txPhy)} rxPhy=${blePhyName(rxPhy)} status=$status",
        )
      }
    }

  private fun activateAcceptedServerSession(endpointId: String) {
    val activation =
      synchronized(lock) {
        val session = pendingServerSessions[endpointId] ?: return
        if (!session.notificationsEnabled) return
        val server = gattServer ?: return
        val txCharacteristic = serverTxCharacteristic ?: return
        val connection =
          GattConnection(
            endpointId = session.endpoint.id,
            device = session.device,
            initialMtu = session.mtu,
            mtuProvider = { session.mtu },
            writeFragment = { fragment ->
              txCharacteristic.value = fragment
              server.notifyCharacteristicChanged(session.device, txCharacteristic, false)
            },
            closeAction = { runCatching { server.cancelConnection(session.device) } },
            onBytes = { bytes -> emit(TransportEvent.BytesReceived(session.endpoint.id, bytes)) },
            onFailure = { message, throwable -> emitFailure(message, throwable) },
          )
        connection.start()
        pendingServerSessions.remove(endpointId)
        val previous = connections.put(endpointId, connection)
        val pendingInbound =
          synchronized(session) {
            session.pendingInbound.toList().also {
              session.pendingInbound.clear()
            }
          }
        Activation(session, connection, previous, pendingInbound)
      }
    activation.previousConnection?.close()
    logDebug("Bluetooth GATT server accepted endpoint=${endpointId.takeLast(5)} mtu=${activation.connection.currentMtu()}")
    emit(TransportEvent.Connected(activation.session.endpoint))
    activation.pendingInbound.forEach { bytes ->
      emit(TransportEvent.BytesReceived(endpointId, bytes))
    }
  }

  private fun acceptServerInboundFragment(
    endpointId: String,
    target: ServerInboundTarget,
    value: ByteArray,
  ): Boolean =
    when (target) {
      is ServerInboundTarget.Active -> target.connection.acceptInboundFragment(value)
      is ServerInboundTarget.Pending -> {
        if (!GattFrameCodec.isFrame(value.firstOrNull() ?: 0)) {
          synchronized(target.session) {
            target.session.pendingInbound.addLast(value)
          }
          return true
        }
        runCatching {
          synchronized(target.session) {
            target.session.reassembler.accept(value)?.let { frame ->
              target.session.pendingInbound.addLast(frame)
            }
          }
        }.onFailure {
          emitFailure("Bluetooth GATT frame decode failed endpoint=${endpointId.takeLast(5)}", it)
        }.isSuccess
      }
    }

  @SuppressLint("MissingPermission")
  private fun requestClientLongRangeConnectionParams(
    gatt: BluetoothGatt,
    endpointId: String,
  ) {
    if (!hasConnectPermission()) {
      logDebug("Bluetooth GATT client long-range connection params skipped endpoint=${endpointId.takeLast(5)} no permission")
      return
    }
    if (requestClientLeConnectionUpdate(gatt, endpointId)) return
    runCatching {
      val accepted = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
      logDebug(
        "Bluetooth GATT client connection priority fallback requested endpoint=${endpointId.takeLast(5)} " +
          "priority=LOW_POWER accepted=$accepted exactSupervisionTimeout=false",
      )
    }.onFailure {
      logDebug("Bluetooth GATT client connection priority fallback failed endpoint=${endpointId.takeLast(5)} ${it.message}")
    }
  }

  @Suppress("PrivateApi")
  private fun requestClientLeConnectionUpdate(
    gatt: BluetoothGatt,
    endpointId: String,
  ): Boolean {
    val params = BleLongRangeConnectionParams
    return runCatching {
      val method =
        runCatching {
          gatt.javaClass.getMethod(
            "requestLeConnectionUpdate",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
          )
        }.getOrElse {
          gatt.javaClass.getDeclaredMethod(
            "requestLeConnectionUpdate",
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
          ).also { hiddenMethod ->
            hiddenMethod.isAccessible = true
          }
        }
      val accepted =
        method.invoke(
          gatt,
          params.MIN_INTERVAL_UNITS,
          params.MAX_INTERVAL_UNITS,
          params.PERIPHERAL_LATENCY,
          params.SUPERVISION_TIMEOUT_UNITS,
          params.MIN_CONNECTION_EVENT_LENGTH_UNITS,
          params.MAX_CONNECTION_EVENT_LENGTH_UNITS,
        ) as? Boolean ?: false
      logDebug(
        "Bluetooth GATT client LE connection update requested endpoint=${endpointId.takeLast(5)} " +
          "intervalMs=${params.intervalMs(params.MIN_INTERVAL_UNITS)}-${params.intervalMs(params.MAX_INTERVAL_UNITS)} " +
          "latency=${params.PERIPHERAL_LATENCY} supervisionTimeoutMs=${params.supervisionTimeoutMs} " +
          "accepted=$accepted",
      )
      accepted
    }.getOrElse {
      logDebug(
        "Bluetooth GATT client LE connection update unavailable endpoint=${endpointId.takeLast(5)} " +
          it.connectionParamErrorMessage(),
      )
      false
    }
  }

  @SuppressLint("MissingPermission")
  private fun requestCodedPhy(
    gatt: BluetoothGatt,
    endpointId: String,
  ) {
    val adapter = adapterOrNull()
    val codedPhySupported = adapter?.isLeCodedPhySupported == true
    val hasConnectPermission = hasConnectPermission()
    if (
      !BluetoothCodedPhyPreference.shouldRequest(
        sdkInt = Build.VERSION.SDK_INT,
        codedPhySupported = codedPhySupported,
        hasConnectPermission = hasConnectPermission,
      )
    ) {
      logDebug(
        "Bluetooth GATT coded PHY request skipped endpoint=${endpointId.takeLast(5)} " +
          "sdk=${Build.VERSION.SDK_INT} codedPhySupported=$codedPhySupported " +
          "hasConnectPermission=$hasConnectPermission",
      )
      return
    }
    gatt.setPreferredPhy(
      BluetoothDevice.PHY_LE_CODED_MASK,
      BluetoothDevice.PHY_LE_CODED_MASK,
      BluetoothDevice.PHY_OPTION_S8,
    )
    gatt.readPhy()
  }

  @SuppressLint("MissingPermission")
  private fun requestServerCodedPhy(
    device: BluetoothDevice,
    endpointId: String,
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val adapter = adapterOrNull()
    val codedPhySupported = adapter?.isLeCodedPhySupported == true
    val hasConnectPermission = hasConnectPermission()
    if (
      !BluetoothCodedPhyPreference.shouldRequest(
        sdkInt = Build.VERSION.SDK_INT,
        codedPhySupported = codedPhySupported,
        hasConnectPermission = hasConnectPermission,
      )
    ) {
      logDebug(
        "Bluetooth GATT server coded PHY request skipped endpoint=${endpointId.takeLast(5)} " +
          "sdk=${Build.VERSION.SDK_INT} codedPhySupported=$codedPhySupported " +
          "hasConnectPermission=$hasConnectPermission",
      )
      return
    }
    gattServer?.setPreferredPhy(
      device,
      BluetoothDevice.PHY_LE_CODED_MASK,
      BluetoothDevice.PHY_LE_CODED_MASK,
      BluetoothDevice.PHY_OPTION_S8,
    )
    gattServer?.readPhy(device)
  }

  @SuppressLint("MissingPermission")
  private fun startBleAdvertising(
    adapter: BluetoothAdapter,
    deviceId: String? = null,
    mode: BleBeaconMode = BleBeaconMode.Discovery,
  ) {
    if (!hasAdvertisePermission()) return
    val advertiser =
      adapter.bluetoothLeAdvertiser
        ?: run {
          logDebug("Bluetooth LE advertiser is not available")
          return
        }
    synchronized(lock) {
      if (isBleAdvertisingLocked()) return
    }

    val serviceUuid = ParcelUuid(OfflineLinkBluetoothService.UUID)
    val serviceData = OfflineLinkBluetoothService.encodeBleGattServiceData(deviceId)
    if (startCodedBleAdvertisingSet(adapter, advertiser, serviceUuid, serviceData, mode)) {
      return
    }
    startLegacyBleAdvertising(advertiser, serviceUuid, serviceData, mode)
  }

  private fun startCodedBleAdvertisingSet(
    adapter: BluetoothAdapter,
    advertiser: BluetoothLeAdvertiser,
    serviceUuid: ParcelUuid,
    serviceData: ByteArray,
    mode: BleBeaconMode,
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val codedPhySupported = adapter.isLeCodedPhySupported
    val extendedAdvertisingSupported = adapter.isLeExtendedAdvertisingSupported
    if (!OfflineLinkBluetoothService.shouldUseCodedAdvertising(codedPhySupported, extendedAdvertisingSupported)) {
      logDebug(
        "Bluetooth GATT coded beacon not used codedPhySupported=$codedPhySupported " +
          "extendedAdvertisingSupported=$extendedAdvertisingSupported",
      )
      return false
    }
    synchronized(lock) {
      if (isBleAdvertisingLocked()) return true
    }

    val parameters =
      AdvertisingSetParameters.Builder()
        .setLegacyMode(false)
        .setConnectable(true)
        .setScannable(false)
        .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
        .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
        .setInterval(
          when (mode) {
            BleBeaconMode.Discovery -> AdvertisingSetParameters.INTERVAL_LOW
            BleBeaconMode.SignalMonitoring -> AdvertisingSetParameters.INTERVAL_LOW
          },
        )
        .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
        .build()
    val data =
      AdvertiseData.Builder()
        .addServiceData(serviceUuid, serviceData)
        .setIncludeDeviceName(false)
        .build()
    val callback =
      object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(
          advertisingSet: AdvertisingSet?,
          txPower: Int,
          status: Int,
        ) {
          if (status == ADVERTISE_SUCCESS && advertisingSet != null) {
            synchronized(lock) {
              if (bleAdvertisingSetCallback === this) {
                bleAdvertisingSet = advertisingSet
              }
            }
            logDebug("Bluetooth GATT coded beacon started txPower=$txPower primaryPhy=LE_CODED secondaryPhy=LE_CODED")
            return
          }
          val shouldFallback =
            synchronized(lock) {
              if (bleAdvertisingSetCallback === this) {
                bleAdvertisingSet = null
                bleAdvertisingSetCallback = null
                true
              } else {
                false
              }
            }
          if (shouldFallback) {
            logDebug("Bluetooth GATT coded beacon failed status=$status; using legacy BLE advertising")
            startLegacyBleAdvertising(advertiser, serviceUuid, serviceData, mode)
          }
        }
      }

    synchronized(lock) {
      if (isBleAdvertisingLocked()) return true
      bleAdvertisingSetCallback = callback
    }
    runCatching {
      advertiser.startAdvertisingSet(parameters, data, null, null, null, callback)
    }.onFailure {
      synchronized(lock) {
        if (bleAdvertisingSetCallback === callback) {
          bleAdvertisingSet = null
          bleAdvertisingSetCallback = null
        }
      }
      logDebug("Could not start Bluetooth GATT coded beacon; using legacy BLE advertising")
      startLegacyBleAdvertising(advertiser, serviceUuid, serviceData, mode)
    }
    return true
  }

  private fun startLegacyBleAdvertising(
    advertiser: BluetoothLeAdvertiser,
    serviceUuid: ParcelUuid,
    serviceData: ByteArray,
    mode: BleBeaconMode,
  ) {
    synchronized(lock) {
      if (isBleAdvertisingLocked()) return
    }
    val settings =
      AdvertiseSettings.Builder()
        .setAdvertiseMode(
          when (mode) {
            BleBeaconMode.Discovery -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            BleBeaconMode.SignalMonitoring -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
          },
        )
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(true)
        .build()
    val data =
      AdvertiseData.Builder()
        .addServiceData(serviceUuid, serviceData)
        .setIncludeDeviceName(false)
        .build()
    val callback =
      object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
          logDebug("Bluetooth GATT beacon started")
        }

        override fun onStartFailure(errorCode: Int) {
          synchronized(lock) {
            if (bleAdvertiseCallback === this) bleAdvertiseCallback = null
          }
          emitFailure("Could not start Bluetooth GATT beacon", IllegalStateException("Advertise error $errorCode"))
        }
      }

    synchronized(lock) {
      bleAdvertiseCallback = callback
    }
    runCatching {
      advertiser.startAdvertising(settings, data, callback)
    }.onFailure {
      synchronized(lock) {
        if (bleAdvertiseCallback === callback) bleAdvertiseCallback = null
      }
      emitFailure("Could not start Bluetooth GATT beacon", it)
    }
  }

  @SuppressLint("MissingPermission")
  private fun stopBleAdvertising() {
    val callbacks =
      synchronized(lock) {
        val legacy = bleAdvertiseCallback
        val advertisingSetCallback = bleAdvertisingSetCallback
        bleAdvertiseCallback = null
        bleAdvertisingSet = null
        bleAdvertisingSetCallback = null
        Pair(legacy, advertisingSetCallback)
      }
    runCatching {
      val advertiser = adapterOrNull()?.bluetoothLeAdvertiser ?: return@runCatching
      if (hasAdvertisePermission()) {
        callbacks.first?.let { advertiser.stopAdvertising(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          callbacks.second?.let { advertiser.stopAdvertisingSet(it) }
        }
      }
    }
    if (callbacks.first != null || callbacks.second != null) {
      logDebug("Bluetooth GATT beacon stopped")
    }
  }

  @SuppressLint("MissingPermission")
  private fun startBleScan(
    adapter: BluetoothAdapter,
    signalMonitoring: Boolean = false,
  ): Boolean {
    if (!hasScanPermission()) return false
    val scanner =
      adapter.bluetoothLeScanner
        ?: run {
          logDebug("Bluetooth LE scanner is not available")
          return false
        }
    stopBleScan()
    val useLongRangeScan =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        OfflineLinkBluetoothService.shouldUseCodedAdvertising(
          codedPhySupported = adapter.isLeCodedPhySupported,
          extendedAdvertisingSupported = adapter.isLeExtendedAdvertisingSupported,
        )
    val requestExtendedAdvertisements =
      BluetoothScanCompatibilityPolicy.shouldRequestExtendedAdvertisements(
        useLongRangeScan = useLongRangeScan,
        advertiserMayFallbackToLegacy = true,
      )

    val callback =
      object : ScanCallback() {
        override fun onScanResult(
          callbackType: Int,
          result: ScanResult,
        ) {
          handleBleScanResult(result, signalMonitoring)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
          results.forEach { result -> handleBleScanResult(result, signalMonitoring) }
        }

        override fun onScanFailed(errorCode: Int) {
          synchronized(lock) {
            if (bleScanCallback === this) bleScanCallback = null
          }
          emitFailure("Could not scan Bluetooth GATT beacon", IllegalStateException("Scan error $errorCode"))
        }
      }
    val filters = emptyList<ScanFilter>()
    val settings =
      ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .apply {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && signalMonitoring) {
            setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
          }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter.isLeCodedPhySupported) {
            setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            if (requestExtendedAdvertisements) {
              setLegacy(false)
            }
          }
        }
        .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      logDebug(
        "Bluetooth GATT scan settings longRange=$useLongRangeScan " +
          "extended=$requestExtendedAdvertisements " +
          "codedPhySupported=${adapter.isLeCodedPhySupported} " +
          "extendedAdvertisingSupported=${adapter.isLeExtendedAdvertisingSupported}",
      )
    }

    synchronized(lock) {
      bleScanCallback = callback
    }
    return runCatching {
      scanner.startScan(filters, settings, callback)
      true
    }.onFailure {
      synchronized(lock) {
        if (bleScanCallback === callback) bleScanCallback = null
      }
      emitFailure("Could not scan Bluetooth GATT beacon", it)
    }.getOrDefault(false)
  }

  @SuppressLint("MissingPermission")
  private fun stopBleScan() {
    val callback =
      synchronized(lock) {
        val current = bleScanCallback
        bleScanCallback = null
        current
      } ?: return
    runCatching {
      if (hasScanPermission()) adapterOrNull()?.bluetoothLeScanner?.stopScan(callback)
    }
    logDebug("Bluetooth GATT beacon scan stopped")
  }

  @SuppressLint("MissingPermission")
  private fun handleBleScanResult(
    result: ScanResult,
    signalMonitoring: Boolean,
  ) {
    val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
    val serviceData = result.scanRecord?.getServiceData(ParcelUuid(OfflineLinkBluetoothService.UUID))
    if (!OfflineLinkBluetoothService.isConnectableBleGattAdvertisement(serviceData)) return
    val endpoint =
      OfflineLinkBluetoothService.endpointFromBleGattAdvertisement(
        address = deviceAddress(result.device),
        name = result.scanRecord?.deviceName ?: deviceName(result.device),
        serviceUuids = serviceUuids,
        serviceData = serviceData,
        rssi = result.rssi,
      ) ?: return
    if (signalMonitoring) {
      handleBleSignalResult(endpoint)
      return
    }
    val shouldEmit =
      synchronized(lock) {
        discoveredEndpoints[endpoint.id] = GattDiscoveredEndpoint(result.device, endpoint)
        emittedEndpointIds.add(endpoint.id)
      }
    if (!shouldEmit) return
    logDebug("OfflineLink GATT endpoint found ${endpoint.logLabel()} ${result.phyLogSuffix()}")
    emit(TransportEvent.EndpointFound(endpoint))
  }

  private fun handleBleSignalResult(endpoint: NearbyEndpoint) {
    val rssi = endpoint.rssi ?: return
    val signalKey = endpoint.signalId ?: endpoint.deviceId ?: endpoint.id
    if (!shouldEmitSignalUpdate(signalKey)) return
    logDebug("Bluetooth GATT signal update ${endpoint.logLabel()} rssi=$rssi signalId=${endpoint.signalId ?: "none"}")
    emit(TransportEvent.EndpointSignalChanged(endpoint.id, endpoint.deviceId, rssi, endpoint.signalId))
  }

  private fun shouldEmitSignalUpdate(endpointId: String): Boolean {
    val now = System.currentTimeMillis()
    return synchronized(lock) {
      val lastUpdateAt = lastSignalUpdateAtMs[endpointId]
      if (lastUpdateAt != null && now - lastUpdateAt < SIGNAL_UPDATE_MIN_INTERVAL_MS) {
        false
      } else {
        lastSignalUpdateAtMs[endpointId] = now
        true
      }
    }
  }

  private fun readyAdapter(
    requireConnectPermission: Boolean,
    requireScanPermission: Boolean,
    requireAdvertisePermission: Boolean = false,
  ): BluetoothAdapter? {
    val adapter = adapterOrNull()
    if (adapter == null) {
      emitFailure("Bluetooth is not available")
      return null
    }
    if (requireConnectPermission && !hasConnectPermission()) {
      emitFailure("Bluetooth connect permission is required")
      return null
    }
    if (requireScanPermission && !hasScanPermission()) {
      emitFailure("Bluetooth scan permission is required")
      return null
    }
    if (requireAdvertisePermission && !hasAdvertisePermission()) {
      emitFailure("Bluetooth advertise permission is required")
      return null
    }
    if (Build.VERSION.SDK_INT < 31 && requireScanPermission && !hasLocationPermission()) {
      emitFailure("Location permission is required for Bluetooth LE scan")
      return null
    }
    if (!adapter.isEnabled) {
      emitFailure("Bluetooth is turned off")
      return null
    }
    return adapter
  }

  private fun adapterOrNull(): BluetoothAdapter? = bluetoothManager?.adapter

  private fun hasConnectPermission(): Boolean =
    Build.VERSION.SDK_INT < 31 || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

  private fun hasScanPermission(): Boolean =
    Build.VERSION.SDK_INT < 31 || hasPermission(Manifest.permission.BLUETOOTH_SCAN)

  private fun hasAdvertisePermission(): Boolean =
    Build.VERSION.SDK_INT < 31 || hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)

  private fun hasLocationPermission(): Boolean =
    hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
      hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

  private fun hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

  @SuppressLint("MissingPermission")
  private fun deviceAddress(device: BluetoothDevice): String? =
    runCatching { device.address }.getOrNull()

  @SuppressLint("MissingPermission")
  private fun deviceName(device: BluetoothDevice): String? =
    runCatching { device.name }.getOrNull()

  @SuppressLint("MissingPermission")
  private fun endpointForDevice(device: BluetoothDevice): NearbyEndpoint? {
    val address = runCatching { device.address }.getOrNull() ?: return null
    val endpointId = OfflineLinkBluetoothService.bleGattEndpointId(address) ?: return null
    val name =
      runCatching { device.name }.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "Bluetooth ${address.takeLast(5)}"
    return NearbyEndpoint(id = endpointId, name = name, deviceId = address)
  }

  private fun connectionInitiatedEvent(session: GattServerSession): TransportEvent =
    TransportEvent.ConnectionInitiated(
      PendingConnection(
        endpointId = session.endpoint.id,
        endpointName = session.endpoint.name,
        authenticationToken = bluetoothToken(session.endpoint.id),
        deviceId = session.endpoint.deviceId,
      ),
    )

  private fun handleGattDisconnected(
    endpointId: String,
    gatt: BluetoothGatt? = null,
  ) {
    val removed =
      synchronized(lock) {
        val connection = connections.remove(endpointId)
        val pending = pendingServerSessions.remove(endpointId)
        lastSignalUpdateAtMs.remove(endpointId)
        Pair(connection, pending)
      }
    removed.first?.close()
    gatt?.let { runCatching { it.close() } }
    if (removed.first != null || removed.second != null) {
      emit(TransportEvent.Disconnected(endpointId))
    }
  }

  private fun clearServiceChecks() {
    synchronized(lock) {
      discoveredEndpoints.clear()
      emittedEndpointIds.clear()
    }
  }

  private fun bluetoothToken(address: String): String =
    address.filter { it.isLetterOrDigit() }.takeLast(6).ifBlank { "BT" }

  private fun emitFailure(
    message: String,
    throwable: Throwable? = null,
  ) {
    if (throwable == null) {
      Log.w(TAG, message)
    } else {
      Log.w(TAG, message, throwable)
    }
    emit(TransportEvent.OperationFailed(message, throwable))
  }

  private fun emit(event: TransportEvent) {
    mutableEvents.tryEmit(event)
  }

  private fun logDebug(message: String) {
    Log.d(TAG, message)
  }

  private fun isBleAdvertisingLocked(): Boolean =
    bleAdvertiseCallback != null || bleAdvertisingSetCallback != null

  private fun NearbyEndpoint.logLabel(): String =
    "$name/${id.takeLast(5)}"

  private fun ScanResult.phyLogSuffix(): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "primaryPhy=legacy"
    return "primaryPhy=${blePhyName(primaryPhy)} secondaryPhy=${blePhyName(secondaryPhy)} txPower=$txPower"
  }

  private fun blePhyName(phy: Int): String =
    when (phy) {
      BluetoothDevice.PHY_LE_1M -> "LE_1M"
      BluetoothDevice.PHY_LE_2M -> "LE_2M"
      BluetoothDevice.PHY_LE_CODED -> "LE_CODED"
      0 -> "UNUSED"
      else -> "UNKNOWN_$phy"
    }

  private fun Throwable.connectionParamErrorMessage(): String {
    val root =
      if (this is InvocationTargetException) {
        targetException ?: this
      } else {
        this
      }
    return "${root.javaClass.simpleName}: ${root.message ?: "no message"}"
  }

  private class GattConnection(
    private val endpointId: String,
    private val device: BluetoothDevice,
    initialMtu: Int = DEFAULT_ATT_MTU,
    private val mtuProvider: () -> Int,
    private val writeFragment: (ByteArray) -> Boolean,
    private val closeAction: () -> Unit,
    private val onBytes: (ByteArray) -> Unit,
    private val onFailure: (String, Throwable) -> Unit,
    private val writeResponseExpected: Boolean = true,
  ) {
    private val closed = AtomicBoolean(false)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val sendLock = Object()
    private val writeQueue = ArrayDeque<OutboundWrite>()
    private val reassembler = GattFrameCodec.Reassembler()
    private val linkMetrics = BluetoothLinkMetrics()
    private val writeTracker = GattWriteOperationTracker()
    private var senderThread: Thread? = null
    private var nextMessageId = 1
    @Volatile private var mtu = initialMtu

    fun start() {
      senderThread =
        Thread({ sendLoop() }, "OfflineLinkGattSend").apply {
          isDaemon = true
          start()
        }
    }

    fun send(
      bytes: ByteArray,
      onResult: (Result<Unit>) -> Unit,
    ) {
      var statsReport: TransportLinkStats? = null
      val failure =
        synchronized(sendLock) {
          if (closed.get()) {
            Result.failure<Unit>(IllegalStateException("Bluetooth GATT endpoint is closed"))
          } else {
            writeQueue.addLast(OutboundWrite(bytes = bytes, onResult = onResult))
            statsReport = linkMetrics.recordEnqueued(writeQueue.size)
            sendLock.notifyAll()
            null
          }
        }
      statsReport?.let(::logStats)
      failure?.let(onResult)
    }

    fun linkStats(): TransportLinkStats = linkMetrics.snapshot()

    fun setMtu(value: Int) {
      mtu = value
    }

    fun currentMtu(): Int = mtu

    fun acceptInboundFragment(fragment: ByteArray): Boolean {
      if (!GattFrameCodec.isFrame(fragment.firstOrNull() ?: 0)) {
        onBytes(fragment)
        return true
      }
      val frame =
        runCatching { reassembler.accept(fragment) }
          .getOrElse {
            onFailure("Bluetooth GATT frame decode failed", it)
            close()
            return false
          } ?: return true
      onBytes(frame)
      return true
    }

    fun completeOutgoing(status: Int) {
      synchronized(sendLock) {
        val token = writeTracker.activeToken() ?: return
        val result =
          if (status == BluetoothGatt.GATT_SUCCESS) {
            Result.success(Unit)
          } else {
            Result.failure(IllegalStateException("Bluetooth GATT write status=$status"))
          }
        if (writeTracker.complete(token, result)) {
          sendLock.notifyAll()
        }
      }
    }

    fun close() {
      if (closed.compareAndSet(false, true)) {
        val pending =
          synchronized(sendLock) {
            val queued = writeQueue.toList()
            writeQueue.clear()
            writeTracker.failActive(IllegalStateException("Bluetooth GATT endpoint is closed"))
            sendLock.notifyAll()
            queued
          }
        runCatching { closeAction() }
        pending.forEach {
          it.onResult(Result.failure(IllegalStateException("Bluetooth GATT endpoint is closed")))
        }
      }
    }

    private fun sendLoop() {
      while (!closed.get()) {
        val item = nextWrite() ?: return
        val result = sendItem(item)
        if (result.isFailure && result.exceptionOrNull() is BackpressureException) {
          synchronized(sendLock) {
            if (closed.get()) return
            writeQueue.addFirst(item)
            sendLock.notifyAll()
          }
          Thread.sleep(BACKPRESSURE_RETRY_DELAY_MS)
          continue
        }
        item.onResult(result)
        result.exceptionOrNull()?.let { exception ->
          if (!closed.get()) {
            onFailure("Bluetooth GATT send failed", exception)
            close()
          }
          return
        }
      }
    }

    private fun nextWrite(): OutboundWrite? =
      synchronized(sendLock) {
        while (!closed.get() && writeQueue.isEmpty()) {
          sendLock.wait()
        }
        if (writeQueue.isEmpty()) {
          null
        } else {
          writeQueue.removeFirst().also {
            linkMetrics.recordQueueLength(writeQueue.size)
          }
        }
      }

    private fun sendItem(item: OutboundWrite): Result<Unit> {
      val maxValue = maxGattValueBytes()
      if (item.bytes.size <= maxValue) {
        val startedAtNs = System.nanoTime()
        val result = writeFragmentAndWait(item.bytes)
        val writeBlockedMs = (System.nanoTime() - startedAtNs).coerceAtLeast(0L) / 1_000_000L
        linkMetrics
          .recordWrite(
            payloadBytes = item.bytes.size,
            writeBlockedMs = writeBlockedMs,
            queueLength = currentQueueLength(),
          )?.let(::logStats)
        return result
      }
      val messageId =
        synchronized(sendLock) {
          nextMessageId++
        }
      val fragments = GattFrameCodec.fragment(item.bytes, maxValue, messageId)
      Log.d(
        TAG,
        "Bluetooth GATT outbound fragmented payload endpoint=${endpointId.takeLast(5)} " +
          "bytes=${item.bytes.size} valueBytes=$maxValue " +
          "chunkBytes=${maxValue - GattFrameCodec.HEADER_BYTES} " +
          "fragments=${fragments.size} writeNoResponse=${!writeResponseExpected} " +
          "paceMs=${if (writeResponseExpected) 0 else FRAGMENTED_WRITE_WITHOUT_RESPONSE_PACING_MS}",
      )
      fragments.forEachIndexed { index, fragment ->
        val startedAtNs = System.nanoTime()
        val result = writeFragmentAndWait(fragment)
        val writeBlockedMs = (System.nanoTime() - startedAtNs).coerceAtLeast(0L) / 1_000_000L
        linkMetrics
          .recordWrite(
            payloadBytes = fragment.size,
            writeBlockedMs = writeBlockedMs,
            queueLength = currentQueueLength(),
          )?.let(::logStats)
        if (result.isFailure) return result
        paceFragmentedNoResponseWrite(index, fragments.lastIndex)
      }
      return Result.success(Unit)
    }

    private fun paceFragmentedNoResponseWrite(
      index: Int,
      lastIndex: Int,
    ) {
      if (writeResponseExpected || index >= lastIndex) return
      runCatching {
        Thread.sleep(FRAGMENTED_WRITE_WITHOUT_RESPONSE_PACING_MS)
      }.onFailure {
        Thread.currentThread().interrupt()
      }
    }

    private fun writeFragmentAndWait(fragment: ByteArray): Result<Unit> {
      val token =
        synchronized(sendLock) {
          if (closed.get()) return Result.failure(IllegalStateException("Bluetooth GATT endpoint is closed"))
          writeTracker.begin()
        }
      synchronized(sendLock) {
        if (closed.get()) {
          writeTracker.cancel(token)
          return Result.failure(IllegalStateException("Bluetooth GATT endpoint is closed"))
        }
      }
      val accepted =
        runCatching { writeFragment(fragment) }
          .getOrElse { exception ->
            synchronized(sendLock) {
              writeTracker.cancel(token)
            }
            return Result.failure(exception)
          }
      if (!accepted) {
        synchronized(sendLock) {
          writeTracker.cancel(token)
        }
        return Result.failure(
          if (writeResponseExpected) {
            IllegalStateException("Bluetooth GATT stack rejected write to ${device.address}")
          } else {
            BackpressureException()
          }
        )
      }

      if (!writeResponseExpected) {
        synchronized(sendLock) {
          writeTracker.complete(token, Result.success(Unit))
        }
        return Result.success(Unit)
      }

      val deadline = System.currentTimeMillis() + GATT_OPERATION_TIMEOUT_MS
      synchronized(sendLock) {
        while (!closed.get() && writeTracker.result() == null) {
          val remaining = deadline - System.currentTimeMillis()
          if (remaining <= 0L) {
            val timeout = IllegalStateException("Bluetooth GATT write timed out")
            writeTracker.timeout(token, timeout)
            return Result.failure(timeout)
          }
          sendLock.wait(remaining)
        }
        return writeTracker.result() ?: Result.failure(IllegalStateException("Bluetooth GATT endpoint is closed"))
      }
    }

    private fun maxGattValueBytes(): Int {
      return boundedGattValueBytes(currentMtu = mtu, providerMtu = mtuProvider())
    }

    private fun currentQueueLength(): Int =
      synchronized(sendLock) {
        writeQueue.size
      }

    private fun logStats(stats: TransportLinkStats) {
      Log.d(
        TAG,
        "Bluetooth GATT link stats endpoint=${endpointId.takeLast(5)} " +
          "txBytesPerSec=${stats.sentBytesPerSecond} " +
          "avgWriteMs=${stats.averageWriteBlockedMs} " +
          "maxWriteMs=${stats.maxWriteBlockedMs} " +
          "queue=${stats.writeQueueLength} " +
          "maxQueue=${stats.maxWriteQueueLength} " +
          "congested=${stats.socketCongested}",
      )
    }

    private data class OutboundWrite(
      val bytes: ByteArray,
      val onResult: (Result<Unit>) -> Unit,
    )

    class BackpressureException : IllegalStateException("BLE write buffer full — backing off")
  }

  private data class GattDiscoveredEndpoint(
    val device: BluetoothDevice,
    val endpoint: NearbyEndpoint,
  )

  private sealed interface ServerInboundTarget {
    data class Active(val connection: GattConnection) : ServerInboundTarget

    data class Pending(val session: GattServerSession) : ServerInboundTarget
  }

  private data class GattServerSession(
    val device: BluetoothDevice,
    val endpoint: NearbyEndpoint,
    val reassembler: GattFrameCodec.Reassembler = GattFrameCodec.Reassembler(),
    val pendingInbound: ArrayDeque<ByteArray> = ArrayDeque(),
    var notificationsEnabled: Boolean = false,
    var accepted: Boolean = false,
    var initiatedEmitted: Boolean = false,
    var mtu: Int = DEFAULT_ATT_MTU,
  )

  private data class Activation(
    val session: GattServerSession,
    val connection: GattConnection,
    val previousConnection: GattConnection?,
    val pendingInbound: List<ByteArray>,
  )

  private enum class BleBeaconMode {
    Discovery,
    SignalMonitoring,
  }

  private companion object {
    const val TAG = "GattChatTransport"
    const val PREFERRED_ATT_MTU = 517
    const val DEFAULT_ATT_MTU = 23
    const val SIGNAL_UPDATE_MIN_INTERVAL_MS = 1_500L
    const val GATT_OPERATION_TIMEOUT_MS = 10_000L
    const val BACKPRESSURE_RETRY_DELAY_MS = 15L
    const val FRAGMENTED_WRITE_WITHOUT_RESPONSE_PACING_MS = 20L
    val GATT_RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("8e3f4b1b-31c4-4b64-8f10-7c9f8c94c2d6")
    val GATT_TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("8e3f4b1c-31c4-4b64-8f10-7c9f8c94c2d6")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
  }
}

package com.example.offlinelink.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class BluetoothChatTransport(context: Context) : ChatTransport {
  private val appContext = context.applicationContext
  private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
  private val lock = Any()
  private val pendingSockets = mutableMapOf<String, BluetoothSocket>()
  private val connections = mutableMapOf<String, BluetoothConnection>()
  private val bleL2capEndpoints = mutableMapOf<String, BleL2capEndpoint>()
  private val emittedEndpointIds = mutableSetOf<String>()
  private val lastSignalUpdateAtMs = mutableMapOf<String, Long>()

  private var l2capServerSocket: BluetoothServerSocket? = null
  private var l2capServerThread: Thread? = null
  private var l2capPsm: Int? = null
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
    if (isServerRunning()) {
      logDebug("Bluetooth listener already running")
      synchronized(lock) { l2capPsm }?.let { startBleAdvertising(adapter, it, deviceId = deviceId) }
      return
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      emitFailure("Bluetooth LE L2CAP requires Android 10 or later")
      return
    }

    val psm = startL2capListener(adapter) ?: return
    startBleAdvertising(adapter, psm, deviceId = deviceId)
    logDebug("Bluetooth LE L2CAP listener started for $displayName psm=$psm")
  }

  @SuppressLint("MissingPermission")
  override fun startDiscovery() {
    val adapter = readyAdapter(requireConnectPermission = true, requireScanPermission = true) ?: return
    clearServiceChecks()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      emitFailure("Bluetooth LE L2CAP requires Android 10 or later")
      return
    }
    val bleScanStarted = startBleScan(adapter)
    if (bleScanStarted) {
      logDebug("Bluetooth app beacon scan requested")
      return
    }
    emitFailure("Bluetooth LE scan is required for OfflineLink BLE mode")
  }

  override fun stopAdvertising() {
    val sockets =
      synchronized(lock) {
        val currentSockets = listOfNotNull(l2capServerSocket)
        l2capServerSocket = null
        l2capServerThread = null
        l2capPsm = null
        currentSockets
    }
    sockets.forEach { it.closeQuietly() }
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
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      logDebug("Bluetooth signal monitoring requires BLE L2CAP support")
      return
    }
    val adapter =
      readyAdapter(
        requireConnectPermission = true,
        requireScanPermission = true,
        requireAdvertisePermission = true,
      ) ?: return
    val psm = startL2capListener(adapter) ?: return
    startBleAdvertising(adapter, psm, deviceId = deviceId, mode = BleBeaconMode.SignalMonitoring)
    val scanStarted = startBleScan(adapter, signalMonitoring = true)
    logDebug("Bluetooth signal monitoring requested for $displayName/$deviceId started=$scanStarted")
  }

  override fun stopSignalMonitoring() {
    stopBleScan()
    stopBleAdvertising()
    synchronized(lock) {
      lastSignalUpdateAtMs.clear()
    }
    logDebug("Bluetooth signal monitoring stopped")
  }

  @SuppressLint("MissingPermission")
  override fun requestConnection(
    endpoint: NearbyEndpoint,
    displayName: String,
    deviceId: String,
  ) {
    val adapter = readyAdapter(requireConnectPermission = true, requireScanPermission = false) ?: return
    Thread(
      {
        var socket: BluetoothSocket? = null
        try {
          stopBleScan()
          if (hasScanPermission() && adapter.isDiscovering) {
            runCatching { adapter.cancelDiscovery() }
          }
          socket = createSocketForEndpoint(adapter, endpoint)
          socket.connect()
          startConnectedSocket(socket, endpoint)
        } catch (exception: Throwable) {
          socket?.closeQuietly()
          emitFailure("Could not connect over Bluetooth", exception)
        }
      },
      "OfflineLinkBluetoothConnect",
    ).apply {
      isDaemon = true
      start()
    }
  }

  override fun acceptConnection(endpointId: String) {
    val socket =
      synchronized(lock) {
        pendingSockets.remove(endpointId)
      }
    if (socket == null) {
      emitFailure("No pending Bluetooth connection")
      return
    }
    startConnectedSocket(socket)
  }

  override fun rejectConnection(endpointId: String) {
    val socket =
      synchronized(lock) {
        pendingSockets.remove(endpointId)
      }
    socket?.closeQuietly()
  }

  override fun send(endpointId: String, bytes: ByteArray, onResult: (Result<Unit>) -> Unit) {
    val connection =
      synchronized(lock) {
        connections[endpointId]
      }
    if (connection == null) {
      onResult(Result.failure(IllegalStateException("Bluetooth endpoint is not connected")))
      return
    }
    connection.send(bytes, onResult)
  }

  override fun linkStats(endpointId: String): TransportLinkStats =
    synchronized(lock) {
      connections[endpointId]?.linkStats() ?: TransportLinkStats()
    }

  override fun disconnectEndpoint(endpointId: String) {
    val disconnected =
      synchronized(lock) {
        lastSignalUpdateAtMs.remove(endpointId)
        Pair(connections.remove(endpointId), pendingSockets.remove(endpointId))
      }
    val connection = disconnected.first
    val pendingSocket = disconnected.second
    connection?.close()
    pendingSocket?.closeQuietly()
    emit(TransportEvent.Disconnected(endpointId))
  }

  override fun stopAll() {
    stopAdvertising()
    stopDiscovery()
    val sockets =
      synchronized(lock) {
        val activeConnections = connections.values.toList()
        val pending = pendingSockets.values.toList()
        connections.clear()
        pendingSockets.clear()
        bleL2capEndpoints.clear()
        emittedEndpointIds.clear()
        lastSignalUpdateAtMs.clear()
        Pair(activeConnections, pending)
      }
    val activeConnections = sockets.first
    val pending = sockets.second
    activeConnections.forEach { it.close() }
    pending.forEach { it.closeQuietly() }
  }

  private fun isServerRunning(): Boolean =
    synchronized(lock) {
      l2capServerSocket != null
    }

  @SuppressLint("MissingPermission")
  private fun startL2capListener(adapter: BluetoothAdapter): Int? {
    val currentPsm = synchronized(lock) { l2capPsm }
    if (currentPsm != null) return currentPsm
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

    val socket =
      runCatching {
        adapter.listenUsingInsecureL2capChannel()
      }.getOrElse {
        emitFailure("Could not start Bluetooth L2CAP listener", it)
        return null
      }
    val psm = socket.psm
    if (OfflineLinkBluetoothService.encodeBleL2capServiceData(psm) == null) {
      socket.closeQuietly()
      emitFailure("Could not start Bluetooth L2CAP listener", IllegalStateException("Invalid L2CAP PSM $psm"))
      return null
    }

    val thread =
      Thread({ acceptLoop(socket) }, "OfflineLinkBluetoothL2capAccept").apply {
        isDaemon = true
      }
    synchronized(lock) {
      l2capServerSocket = socket
      l2capServerThread = thread
      l2capPsm = psm
    }
    thread.start()
    return psm
  }

  private fun acceptLoop(socket: BluetoothServerSocket) {
    while (isCurrentServer(socket)) {
      val accepted =
        try {
          socket.accept()
        } catch (exception: IOException) {
          if (isCurrentServer(socket)) emitFailure("Bluetooth listener stopped", exception)
          return
        }
      handleAcceptedSocket(accepted)
    }
  }

  private fun isCurrentServer(socket: BluetoothServerSocket): Boolean =
    synchronized(lock) {
      l2capServerSocket === socket
    }

  private fun handleAcceptedSocket(socket: BluetoothSocket) {
    val endpoint =
      endpointForDevice(socket.remoteDevice)
        ?: run {
          socket.closeQuietly()
          return
        }

    val replacedPendingSocket: BluetoothSocket?
    synchronized(lock) {
      if (connections.containsKey(endpoint.id)) {
        socket.closeQuietly()
        return
      }
      replacedPendingSocket = pendingSockets.put(endpoint.id, socket)
    }
    replacedPendingSocket?.closeQuietly()

    emit(
      TransportEvent.ConnectionInitiated(
        PendingConnection(
          endpointId = endpoint.id,
          endpointName = endpoint.name,
          authenticationToken = bluetoothToken(endpoint.id),
          deviceId = endpoint.deviceId,
        ),
      ),
    )
  }

  private fun startConnectedSocket(
    socket: BluetoothSocket,
    fallbackEndpoint: NearbyEndpoint? = null,
  ) {
    val endpoint =
      endpointForDevice(socket.remoteDevice, fallbackEndpoint)
        ?: run {
          socket.closeQuietly()
          emitFailure("Connected Bluetooth device has no address")
          return
        }

    val connection =
      BluetoothConnection(
        endpointId = endpoint.id,
        deviceId = endpoint.deviceId,
        signalId = endpoint.signalId,
        socket = socket,
        codedPhyGatt = requestCodedPhy(socket.remoteDevice, endpoint.id),
        onBytes = { bytes -> emit(TransportEvent.BytesReceived(endpoint.id, bytes)) },
        onDisconnected = { handleSocketDisconnected(endpoint.id) },
        onFailure = { message, throwable -> emitFailure(message, throwable) },
      )
    val previous: BluetoothConnection?
    synchronized(lock) {
      previous = connections.put(endpoint.id, connection)
      pendingSockets.remove(endpoint.id)
    }
    previous?.close()
    connection.start()
    emit(TransportEvent.Connected(endpoint))
  }

  private fun handleSocketDisconnected(endpointId: String) {
    val removed =
      synchronized(lock) {
        val removed = connections.remove(endpointId) != null
        lastSignalUpdateAtMs.remove(endpointId)
        removed
      }
    if (removed) {
      emit(TransportEvent.Disconnected(endpointId))
    }
  }

  @SuppressLint("MissingPermission")
  private fun clearServiceChecks() {
    synchronized(lock) {
      bleL2capEndpoints.clear()
      emittedEndpointIds.clear()
    }
  }

  @SuppressLint("MissingPermission")
  private fun startBleAdvertising(
    adapter: BluetoothAdapter,
    psm: Int,
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
    val serviceData =
      OfflineLinkBluetoothService.encodeBleL2capServiceData(psm, deviceId)
        ?: run {
          emitFailure("Could not encode Bluetooth app beacon")
          return
        }
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
        "Bluetooth coded app beacon not used codedPhySupported=$codedPhySupported " +
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
            logDebug(
              "Bluetooth coded app beacon started txPower=$txPower primaryPhy=LE_CODED secondaryPhy=LE_CODED",
            )
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
            logDebug("Bluetooth coded app beacon failed status=$status; using legacy BLE advertising")
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
      logDebug("Could not start Bluetooth coded app beacon; using legacy BLE advertising")
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
          logDebug("Bluetooth app beacon started")
        }

        override fun onStartFailure(errorCode: Int) {
          synchronized(lock) {
            if (bleAdvertiseCallback === this) bleAdvertiseCallback = null
          }
          emitFailure("Could not start Bluetooth app beacon", IllegalStateException("Advertise error $errorCode"))
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
      emitFailure("Could not start Bluetooth app beacon", it)
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
      logDebug("Bluetooth app beacon stopped")
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
          emitFailure("Could not scan Bluetooth app beacon", IllegalStateException("Scan error $errorCode"))
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
        "Bluetooth BLE scan settings longRange=$useLongRangeScan " +
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
      emitFailure("Could not scan Bluetooth app beacon", it)
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
    logDebug("Bluetooth app beacon scan stopped")
  }

  @SuppressLint("MissingPermission")
  private fun handleBleScanResult(
    result: ScanResult,
    signalMonitoring: Boolean,
  ) {
    val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
    val serviceData = result.scanRecord?.getServiceData(ParcelUuid(OfflineLinkBluetoothService.UUID))
    if (!OfflineLinkBluetoothService.isConnectableBleL2capAdvertisement(serviceData)) return
    val psm = OfflineLinkBluetoothService.l2capPsmFromBleServiceData(serviceData) ?: return
    val endpoint =
      OfflineLinkBluetoothService.endpointFromBleAdvertisement(
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
        bleL2capEndpoints[endpoint.id] = BleL2capEndpoint(result.device, psm)
        emittedEndpointIds.add(endpoint.id)
      }
    if (!shouldEmit) return
    logDebug("OfflineLink endpoint found ${endpoint.logLabel()} source=ble ${result.phyLogSuffix()}")
    emit(TransportEvent.EndpointFound(endpoint))
  }

  private fun handleBleSignalResult(endpoint: NearbyEndpoint) {
    val rssi = endpoint.rssi ?: return
    val signalKey = endpoint.signalId ?: endpoint.deviceId ?: endpoint.id
    if (!shouldEmitSignalUpdate(signalKey)) return
    logDebug("Bluetooth signal update ${endpoint.logLabel()} rssi=$rssi signalId=${endpoint.signalId ?: "none"}")
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
  private fun createSocketForEndpoint(
    adapter: BluetoothAdapter,
    endpoint: NearbyEndpoint,
  ): BluetoothSocket {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      throw IllegalStateException("Bluetooth LE L2CAP requires Android 10 or later")
    }
    val bleEndpoint =
      synchronized(lock) { bleL2capEndpoints[endpoint.id] }
        ?: OfflineLinkBluetoothService.parseBleL2capEndpointId(endpoint.id)?.let {
          BleL2capEndpoint(adapter.getRemoteDevice(it.address), it.psm)
        }
        ?: throw IllegalArgumentException("OfflineLink BLE mode requires a BLE L2CAP endpoint")
    return bleEndpoint.device.createInsecureL2capChannel(bleEndpoint.psm)
  }

  @SuppressLint("MissingPermission")
  private fun requestCodedPhy(
    device: BluetoothDevice,
    endpointId: String,
  ): BluetoothGatt? {
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
        "Bluetooth coded PHY request skipped endpoint=${endpointId.takeLast(5)} " +
          "sdk=${Build.VERSION.SDK_INT} codedPhySupported=$codedPhySupported " +
          "hasConnectPermission=$hasConnectPermission",
      )
      return null
    }

    val callback =
      object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
          gatt: BluetoothGatt,
          status: Int,
          newState: Int,
        ) {
          if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            logDebug("Bluetooth coded PHY GATT connected endpoint=${endpointId.takeLast(5)}; requesting LE_CODED/S8")
            gatt.setPreferredPhy(
              BluetoothDevice.PHY_LE_CODED_MASK,
              BluetoothDevice.PHY_LE_CODED_MASK,
              BluetoothDevice.PHY_OPTION_S8,
            )
            gatt.readPhy()
            return
          }
          if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            logDebug("Bluetooth coded PHY GATT disconnected endpoint=${endpointId.takeLast(5)} status=$status")
          }
        }

        override fun onPhyUpdate(
          gatt: BluetoothGatt,
          txPhy: Int,
          rxPhy: Int,
          status: Int,
        ) {
          logDebug(
            "Bluetooth coded PHY update endpoint=${endpointId.takeLast(5)} " +
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
            "Bluetooth coded PHY read endpoint=${endpointId.takeLast(5)} " +
              "txPhy=${blePhyName(txPhy)} rxPhy=${blePhyName(rxPhy)} status=$status",
          )
        }
      }

    return runCatching {
      device.connectGatt(
        appContext,
        false,
        callback,
        BluetoothDevice.TRANSPORT_LE,
        BluetoothDevice.PHY_LE_CODED_MASK,
      )
    }.onSuccess {
      logDebug("Bluetooth coded PHY GATT connect requested endpoint=${endpointId.takeLast(5)}")
    }.onFailure {
      emitFailure("Could not request Bluetooth coded PHY", it)
    }.getOrNull()
  }

  @SuppressLint("MissingPermission")
  private fun endpointForDevice(
    device: BluetoothDevice,
    fallbackEndpoint: NearbyEndpoint? = null,
  ): NearbyEndpoint? {
    val address =
      runCatching { device.address }.getOrNull()
        ?: fallbackEndpoint?.deviceId
        ?: fallbackEndpoint?.id
        ?: return null
    val endpointId = fallbackEndpoint?.id ?: address
    val name =
      runCatching { device.name }.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: fallbackEndpoint?.name
        ?: "Bluetooth ${address.takeLast(5)}"
    return NearbyEndpoint(
      id = endpointId,
      name = name,
      deviceId = fallbackEndpoint?.deviceId ?: address,
      rssi = fallbackEndpoint?.rssi,
      signalId = fallbackEndpoint?.signalId,
    )
  }

  private fun bluetoothToken(address: String): String =
    address.filter { it.isLetterOrDigit() }.takeLast(6).ifBlank { "BT" }

  private fun emitFailure(message: String, throwable: Throwable? = null) {
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

  private fun BluetoothSocket.closeQuietly() {
    runCatching { close() }
  }

  private fun BluetoothServerSocket.closeQuietly() {
    runCatching { close() }
  }

  private class BluetoothConnection(
    private val endpointId: String,
    val deviceId: String?,
    val signalId: String?,
    private val socket: BluetoothSocket,
    private val codedPhyGatt: BluetoothGatt?,
    private val onBytes: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onFailure: (String, Throwable) -> Unit,
  ) {
    private val closed = AtomicBoolean(false)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val writeQueueLock = Object()
    private val writeQueue = ArrayDeque<OutboundWrite>()
    private val linkMetrics = BluetoothLinkMetrics()
    private var readThread: Thread? = null
    private var writeThread: Thread? = null

    fun start() {
      readThread =
        Thread({ readLoop() }, "OfflineLinkBluetoothRead").apply {
          isDaemon = true
          start()
        }
      writeThread =
        Thread({ writeLoop() }, "OfflineLinkBluetoothWrite").apply {
          isDaemon = true
          start()
        }
    }

    fun send(bytes: ByteArray, onResult: (Result<Unit>) -> Unit) {
      var statsReport: TransportLinkStats? = null
      val failure =
        synchronized(writeQueueLock) {
          if (closed.get()) {
            Result.failure<Unit>(IllegalStateException("Bluetooth endpoint is closed"))
          } else {
            writeQueue.addLast(OutboundWrite(bytes = bytes, onResult = onResult))
            statsReport = linkMetrics.recordEnqueued(writeQueue.size)
            writeQueueLock.notifyAll()
            null
          }
        }
      statsReport?.let(::logStats)
      failure?.let(onResult)
    }

    fun linkStats(): TransportLinkStats = linkMetrics.snapshot()

    private fun writeLoop() {
      while (true) {
        val item = nextWrite() ?: return
        if (closed.get()) {
          item.onResult(Result.failure(IllegalStateException("Bluetooth endpoint is closed")))
          continue
        }
        val startedAtNs = System.nanoTime()
        val result = runCatching { BluetoothFrameCodec.writeFrame(socket.outputStream, item.bytes) }
        val writeBlockedMs = (System.nanoTime() - startedAtNs).coerceAtLeast(0L) / 1_000_000L
        linkMetrics
          .recordWrite(
            payloadBytes = item.bytes.size,
            writeBlockedMs = writeBlockedMs,
            queueLength = currentQueueLength(),
          )?.let(::logStats)
        item.onResult(result)
        result.exceptionOrNull()?.let { exception ->
          if (!closed.get()) {
            onFailure("Bluetooth send failed", exception)
            close()
          }
          return
        }
      }
    }

    private fun nextWrite(): OutboundWrite? =
      synchronized(writeQueueLock) {
        while (!closed.get() && writeQueue.isEmpty()) {
          writeQueueLock.wait()
        }
        if (writeQueue.isEmpty()) {
          null
        } else {
          writeQueue.removeFirst().also {
            linkMetrics.recordQueueLength(writeQueue.size)
          }
        }
      }

    private fun currentQueueLength(): Int =
      synchronized(writeQueueLock) {
        writeQueue.size
      }

    fun close() {
      if (closed.compareAndSet(false, true)) {
        val pending =
          synchronized(writeQueueLock) {
            val queued = writeQueue.toList()
            writeQueue.clear()
            writeQueueLock.notifyAll()
            queued
          }
        runCatching { socket.close() }
        runCatching { codedPhyGatt?.close() }
        pending.forEach {
          it.onResult(Result.failure(IllegalStateException("Bluetooth endpoint is closed")))
        }
      }
    }

    private fun logStats(stats: TransportLinkStats) {
      Log.d(
        TAG,
        "Bluetooth link stats endpoint=${endpointId.takeLast(5)} " +
          "txBytesPerSec=${stats.sentBytesPerSecond} " +
          "avgWriteMs=${stats.averageWriteBlockedMs} " +
          "maxWriteMs=${stats.maxWriteBlockedMs} " +
          "queue=${stats.writeQueueLength} " +
          "maxQueue=${stats.maxWriteQueueLength} " +
          "congested=${stats.socketCongested}",
      )
    }

    private fun readLoop() {
      try {
        while (!closed.get()) {
          val frame = BluetoothFrameCodec.readFrame(socket.inputStream) ?: break
          onBytes(frame)
        }
      } catch (exception: Throwable) {
        if (!closed.get()) {
          onFailure("Bluetooth connection failed", exception)
        }
      } finally {
        close()
        onDisconnected()
      }
    }

    private data class OutboundWrite(
      val bytes: ByteArray,
      val onResult: (Result<Unit>) -> Unit,
    )
  }

  private data class BleL2capEndpoint(
    val device: BluetoothDevice,
    val psm: Int,
  )

  private enum class BleBeaconMode {
    Discovery,
    SignalMonitoring,
  }

  private companion object {
    const val TAG = "BluetoothChatTransport"
    const val SIGNAL_UPDATE_MIN_INTERVAL_MS = 1_500L
  }
}

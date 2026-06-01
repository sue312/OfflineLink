package com.example.offlinelink.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
  private val pendingServiceChecks = mutableMapOf<String, BluetoothDevice>()
  private val bleL2capEndpoints = mutableMapOf<String, BleL2capEndpoint>()
  private val verifiedOfflineLinkEndpoints = mutableSetOf<String>()
  private val lastSignalUpdateAtMs = mutableMapOf<String, Long>()

  private var serverSocket: BluetoothServerSocket? = null
  private var serverThread: Thread? = null
  private var l2capServerSocket: BluetoothServerSocket? = null
  private var l2capServerThread: Thread? = null
  private var l2capPsm: Int? = null
  private var discoveryReceiverRegistered = false
  private var bleAdvertiseCallback: AdvertiseCallback? = null
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val psm = startL2capListener(adapter) ?: return
      startBleAdvertising(adapter, psm, deviceId = deviceId)
      logDebug("Bluetooth L2CAP listener started for $displayName psm=$psm")
      return
    }

    if (!startRfcommListener(adapter)) return
    logDebug("Bluetooth listener started for $displayName")
  }

  @SuppressLint("MissingPermission")
  override fun startDiscovery() {
    val adapter = readyAdapter(requireConnectPermission = true, requireScanPermission = true) ?: return
    clearServiceChecks()
    val bleScanStarted = startBleScan(adapter)
    if (bleScanStarted) {
      logDebug("Bluetooth app beacon scan requested")
      return
    }

    val bondedCount = checkBondedDevices(adapter)
    registerDiscoveryReceiver()
    runCatching {
      if (adapter.isDiscovering) adapter.cancelDiscovery()
      adapter.startDiscovery()
    }.onFailure {
      emitFailure("Could not start Bluetooth discovery", it)
    }.onSuccess { started ->
      logDebug("Bluetooth discovery requested started=$started bonded=$bondedCount")
      if (!started) emitFailure("Could not start Bluetooth discovery")
    }
  }

  override fun stopAdvertising() {
    val sockets =
      synchronized(lock) {
        val currentSockets = listOfNotNull(serverSocket, l2capServerSocket)
        serverSocket = null
        serverThread = null
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
    unregisterDiscoveryReceiver()
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
        pendingServiceChecks.clear()
        bleL2capEndpoints.clear()
        verifiedOfflineLinkEndpoints.clear()
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
      serverSocket != null || l2capServerSocket != null
    }

  @SuppressLint("MissingPermission")
  private fun startRfcommListener(adapter: BluetoothAdapter): Boolean {
    if (synchronized(lock) { serverSocket != null }) return true
    val socket =
      runCatching {
        adapter.listenUsingRfcommWithServiceRecord(OfflineLinkBluetoothService.NAME, OfflineLinkBluetoothService.UUID)
      }.getOrElse {
        emitFailure("Could not start Bluetooth listener", it)
        return false
      }

    val thread =
      Thread({ acceptLoop(socket) }, "OfflineLinkBluetoothRfcommAccept").apply {
        isDaemon = true
      }
    synchronized(lock) {
      serverSocket = socket
      serverThread = thread
    }
    thread.start()
    return true
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
      serverSocket === socket || l2capServerSocket === socket
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
      pendingServiceChecks.clear()
      bleL2capEndpoints.clear()
      verifiedOfflineLinkEndpoints.clear()
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
      if (bleAdvertiseCallback != null) return
    }

    val serviceUuid = ParcelUuid(OfflineLinkBluetoothService.UUID)
    val serviceData =
      OfflineLinkBluetoothService.encodeBleL2capServiceData(psm, deviceId)
        ?: run {
          emitFailure("Could not encode Bluetooth app beacon")
          return
        }
    val settings =
      AdvertiseSettings.Builder()
        .setAdvertiseMode(
          when (mode) {
            BleBeaconMode.Discovery -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            BleBeaconMode.SignalMonitoring -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
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
    val callback =
      synchronized(lock) {
        val current = bleAdvertiseCallback
        bleAdvertiseCallback = null
        current
      } ?: return
    runCatching {
      if (hasAdvertisePermission()) adapterOrNull()?.bluetoothLeAdvertiser?.stopAdvertising(callback)
    }
    logDebug("Bluetooth app beacon stopped")
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

    val serviceUuid = ParcelUuid(OfflineLinkBluetoothService.UUID)
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
    val filters =
      listOf(
        ScanFilter.Builder()
          .setServiceData(
            serviceUuid,
            OfflineLinkBluetoothService.BLE_SERVICE_DATA_FILTER,
            OfflineLinkBluetoothService.BLE_SERVICE_DATA_FILTER_MASK,
          )
          .build(),
      )
    val settings =
      ScanSettings.Builder()
        .setScanMode(if (signalMonitoring) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .apply {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && signalMonitoring) {
            setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
          }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter.isLeCodedPhySupported) {
            setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
          }
        }
        .build()

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
        verifiedOfflineLinkEndpoints.add(endpoint.id)
      }
    if (!shouldEmit) return
    logDebug("OfflineLink endpoint found ${endpoint.logLabel()} source=ble")
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

  @SuppressLint("MissingPermission")
  private fun checkBondedDevices(adapter: BluetoothAdapter): Int =
    runCatching {
      var checkedCount = 0
      adapter.bondedDevices.orEmpty().forEach { device ->
        requestOfflineLinkServiceCheck(device, source = "paired")
        checkedCount += 1
      }
      logDebug("Queued $checkedCount paired Bluetooth devices for OfflineLink service check")
      checkedCount
    }.onFailure {
      emitFailure("Could not read paired Bluetooth devices", it)
    }.getOrDefault(0)

  @SuppressLint("MissingPermission")
  private fun requestOfflineLinkServiceCheck(
    device: BluetoothDevice,
    source: String,
  ) {
    val address = deviceAddress(device) ?: return
    if (OfflineLinkBluetoothService.matches(device.serviceUuids())) {
      emitOfflineLinkDevice(device, source = "$source cached")
      return
    }

    val shouldRequest =
      synchronized(lock) {
        if (verifiedOfflineLinkEndpoints.contains(address) || pendingServiceChecks.containsKey(address)) {
          false
        } else {
          pendingServiceChecks[address] = device
          true
        }
      }
    if (!shouldRequest) return

    runCatching { device.fetchUuidsWithSdp() }
      .onSuccess { requested ->
        logDebug("OfflineLink service check requested=$requested source=$source device=${address.takeLast(5)}")
        if (!requested) {
          synchronized(lock) { pendingServiceChecks.remove(address) }
        }
      }
      .onFailure { throwable ->
        synchronized(lock) { pendingServiceChecks.remove(address) }
        emitFailure("Could not check Bluetooth service UUID", throwable)
      }
  }

  private fun handleServiceUuidResult(
    device: BluetoothDevice,
    serviceUuids: Collection<java.util.UUID>?,
  ) {
    val address = deviceAddress(device) ?: return
    synchronized(lock) { pendingServiceChecks.remove(address) }
    if (OfflineLinkBluetoothService.matches(serviceUuids)) {
      emitOfflineLinkDevice(device, source = "sdp")
    } else {
      logDebug("Ignoring Bluetooth device without OfflineLink service ${address.takeLast(5)}")
    }
  }

  private fun emitOfflineLinkDevice(
    device: BluetoothDevice,
    source: String,
  ) {
    val endpoint = endpointForDevice(device) ?: return
    val shouldEmit =
      synchronized(lock) {
        verifiedOfflineLinkEndpoints.add(endpoint.id)
      }
    if (!shouldEmit) return
    logDebug("OfflineLink endpoint found ${endpoint.logLabel()} source=$source")
    emit(TransportEvent.EndpointFound(endpoint))
  }

  private fun registerDiscoveryReceiver() {
    synchronized(lock) {
      if (discoveryReceiverRegistered) return
      val filter =
        IntentFilter().apply {
          addAction(BluetoothDevice.ACTION_FOUND)
          addAction(BluetoothDevice.ACTION_UUID)
          addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
      if (Build.VERSION.SDK_INT >= 33) {
        appContext.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
      } else {
        @Suppress("DEPRECATION")
        appContext.registerReceiver(discoveryReceiver, filter)
      }
      discoveryReceiverRegistered = true
      logDebug("Bluetooth discovery receiver registered")
    }
  }

  private fun unregisterDiscoveryReceiver() {
    synchronized(lock) {
      if (!discoveryReceiverRegistered) return
      runCatching { appContext.unregisterReceiver(discoveryReceiver) }
      discoveryReceiverRegistered = false
      logDebug("Bluetooth discovery receiver unregistered")
    }
  }

  private val discoveryReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
          BluetoothDevice.ACTION_FOUND -> {
            val device = intent.bluetoothDeviceExtra() ?: return
            requestOfflineLinkServiceCheck(device, source = "discovery")
          }
          BluetoothDevice.ACTION_UUID -> {
            val device = intent.bluetoothDeviceExtra() ?: return
            handleServiceUuidResult(device, intent.bluetoothUuidExtras())
          }
          BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> logDebug("Bluetooth discovery finished")
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
      emitFailure("Location permission is required for Bluetooth discovery")
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
  private fun BluetoothDevice.serviceUuids(): List<java.util.UUID> =
    runCatching { uuids?.map { it.uuid }.orEmpty() }.getOrDefault(emptyList())

  @SuppressLint("MissingPermission")
  private fun createSocketForEndpoint(
    adapter: BluetoothAdapter,
    endpoint: NearbyEndpoint,
  ): BluetoothSocket {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val bleEndpoint =
        synchronized(lock) { bleL2capEndpoints[endpoint.id] }
          ?: OfflineLinkBluetoothService.parseBleL2capEndpointId(endpoint.id)?.let {
            BleL2capEndpoint(adapter.getRemoteDevice(it.address), it.psm)
          }
      if (bleEndpoint != null) {
        return bleEndpoint.device.createInsecureL2capChannel(bleEndpoint.psm)
      }
    }

    val device = adapter.getRemoteDevice(endpoint.id)
    return device.createRfcommSocketToServiceRecord(OfflineLinkBluetoothService.UUID)
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

  private fun NearbyEndpoint.logLabel(): String =
    "$name/${id.takeLast(5)}"

  private fun BluetoothSocket.closeQuietly() {
    runCatching { close() }
  }

  private fun BluetoothServerSocket.closeQuietly() {
    runCatching { close() }
  }

  private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= 33) {
      getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
      @Suppress("DEPRECATION")
      getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

  private fun Intent.bluetoothUuidExtras(): List<java.util.UUID> =
    if (Build.VERSION.SDK_INT >= 33) {
      getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
        ?.map { it.uuid }
        .orEmpty()
    } else {
      @Suppress("DEPRECATION")
      getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
        ?.mapNotNull { (it as? ParcelUuid)?.uuid }
        .orEmpty()
    }

  private class BluetoothConnection(
    private val endpointId: String,
    val deviceId: String?,
    val signalId: String?,
    private val socket: BluetoothSocket,
    private val onBytes: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onFailure: (String, Throwable) -> Unit,
  ) {
    private val closed = AtomicBoolean(false)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val writeQueueLock = Object()
    private val writeQueue = ArrayDeque<OutboundWrite>()
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
      val failure =
        synchronized(writeQueueLock) {
          if (closed.get()) {
            Result.failure<Unit>(IllegalStateException("Bluetooth endpoint is closed"))
          } else {
            writeQueue.addLast(OutboundWrite(bytes = bytes, onResult = onResult))
            writeQueueLock.notifyAll()
            null
          }
        }
      failure?.let(onResult)
    }

    private fun writeLoop() {
      while (true) {
        val item = nextWrite() ?: return
        if (closed.get()) {
          item.onResult(Result.failure(IllegalStateException("Bluetooth endpoint is closed")))
          continue
        }
        val result = runCatching { BluetoothFrameCodec.writeFrame(socket.outputStream, item.bytes) }
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
        if (writeQueue.isEmpty()) null else writeQueue.removeFirst()
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
        pending.forEach {
          it.onResult(Result.failure(IllegalStateException("Bluetooth endpoint is closed")))
        }
      }
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

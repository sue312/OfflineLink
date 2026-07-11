package com.example.offlinelink.transport

import com.example.offlinelink.model.NearbyEndpoint
import java.security.MessageDigest

internal object OfflineLinkBluetoothService {
  const val NAME = "OfflineLink"
  val UUID: java.util.UUID = java.util.UUID.fromString("8e3f4b1a-31c4-4b64-8f10-7c9f8c94c2d6")

  fun matches(serviceUuids: Collection<java.util.UUID>?): Boolean =
    serviceUuids?.any { it == UUID } == true

  fun encodeBleL2capServiceData(
    psm: Int,
    deviceId: String? = null,
  ): ByteArray? {
    if (psm !in MIN_L2CAP_PSM..MAX_L2CAP_PSM) return null
    val base =
      byteArrayOf(
      BLE_SERVICE_DATA_VERSION,
      BLE_TRANSPORT_L2CAP,
      (psm shr 8).toByte(),
      psm.toByte(),
    )
    val signalBytes = deviceId?.let(::signalIdBytesForDeviceId) ?: return base
    return base + signalBytes
  }

  fun encodeBleGattServiceData(deviceId: String? = null): ByteArray {
    val base =
      byteArrayOf(
        BLE_SERVICE_DATA_VERSION,
        BLE_TRANSPORT_GATT,
      )
    val signalBytes = deviceId?.let(::signalIdBytesForDeviceId) ?: return base
    return base + signalBytes
  }

  fun l2capPsmFromBleServiceData(serviceData: ByteArray?): Int? {
    if (serviceData == null || serviceData.size < BLE_L2CAP_SERVICE_DATA_LENGTH) return null
    if (serviceData[0] != BLE_SERVICE_DATA_VERSION || serviceData[1] != BLE_TRANSPORT_L2CAP) return null
    val psm = ((serviceData[2].toInt() and 0xff) shl 8) or (serviceData[3].toInt() and 0xff)
    return psm.takeIf { it in MIN_L2CAP_PSM..MAX_L2CAP_PSM }
  }

  fun isConnectableBleL2capAdvertisement(serviceData: ByteArray?): Boolean =
    l2capPsmFromBleServiceData(serviceData) != null

  fun isConnectableBleGattAdvertisement(serviceData: ByteArray?): Boolean =
    serviceData != null &&
      serviceData.size >= BLE_GATT_SERVICE_DATA_LENGTH &&
      serviceData[0] == BLE_SERVICE_DATA_VERSION &&
      serviceData[1] == BLE_TRANSPORT_GATT

  fun shouldUseCodedAdvertising(
    codedPhySupported: Boolean,
    extendedAdvertisingSupported: Boolean,
  ): Boolean =
    USE_CODED_ADVERTISING_BY_DEFAULT && codedPhySupported && extendedAdvertisingSupported

  fun endpointFromBleAdvertisement(
    address: String?,
    name: String?,
    serviceUuids: Collection<java.util.UUID>?,
    serviceData: ByteArray? = null,
    rssi: Int? = null,
  ): NearbyEndpoint? {
    val endpointAddress = normalizedBluetoothAddress(address) ?: return null
    val signalId = signalIdFromBleServiceData(serviceData)
    val psm = l2capPsmFromBleServiceData(serviceData) ?: return null
    val endpointId = bleL2capEndpointId(endpointAddress, psm) ?: return null
    val endpointName =
      name
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "Bluetooth ${endpointAddress.takeLast(5)}"
    return NearbyEndpoint(id = endpointId, name = endpointName, deviceId = endpointAddress, rssi = rssi, signalId = signalId)
  }

  fun endpointFromBleGattAdvertisement(
    address: String?,
    name: String?,
    serviceUuids: Collection<java.util.UUID>?,
    serviceData: ByteArray? = null,
    rssi: Int? = null,
  ): NearbyEndpoint? {
    val endpointAddress = normalizedBluetoothAddress(address) ?: return null
    if (!isConnectableBleGattAdvertisement(serviceData)) return null
    val endpointId = bleGattEndpointId(endpointAddress) ?: return null
    val signalId = signalIdFromBleServiceData(serviceData)
    val endpointName =
      name
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "Bluetooth ${endpointAddress.takeLast(5)}"
    return NearbyEndpoint(id = endpointId, name = endpointName, deviceId = endpointAddress, rssi = rssi, signalId = signalId)
  }

  fun signalIdForDeviceId(deviceId: String): String =
    signalIdBytesForDeviceId(deviceId).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

  fun bleL2capEndpointId(
    address: String,
    psm: Int,
  ): String? {
    val endpointAddress = normalizedBluetoothAddress(address) ?: return null
    if (psm !in MIN_L2CAP_PSM..MAX_L2CAP_PSM) return null
    return "$BLE_L2CAP_ENDPOINT_PREFIX$endpointAddress:$psm"
  }

  fun parseBleL2capEndpointId(endpointId: String): BleL2capEndpointId? {
    if (!endpointId.startsWith(BLE_L2CAP_ENDPOINT_PREFIX)) return null
    val body = endpointId.removePrefix(BLE_L2CAP_ENDPOINT_PREFIX)
    val separator = body.lastIndexOf(':')
    if (separator <= 0 || separator == body.lastIndex) return null
    val address = normalizedBluetoothAddress(body.substring(0, separator)) ?: return null
    val psm = body.substring(separator + 1).toIntOrNull() ?: return null
    if (psm !in MIN_L2CAP_PSM..MAX_L2CAP_PSM) return null
    return BleL2capEndpointId(address = address, psm = psm)
  }

  fun bleGattEndpointId(address: String): String? {
    val endpointAddress = normalizedBluetoothAddress(address) ?: return null
    return "$BLE_GATT_ENDPOINT_PREFIX$endpointAddress"
  }

  fun parseBleGattEndpointId(endpointId: String): BleGattEndpointId? {
    if (!endpointId.startsWith(BLE_GATT_ENDPOINT_PREFIX)) return null
    val address = normalizedBluetoothAddress(endpointId.removePrefix(BLE_GATT_ENDPOINT_PREFIX)) ?: return null
    return BleGattEndpointId(address = address)
  }

  data class BleL2capEndpointId(
    val address: String,
    val psm: Int,
  )

  data class BleGattEndpointId(
    val address: String,
  )

  private fun normalizedBluetoothAddress(address: String?): String? =
    bluetoothAddressBytes(address)?.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xff) }

  private fun bluetoothAddressBytes(address: String?): ByteArray? {
    val parts = address?.trim()?.split(':') ?: return null
    if (parts.size != BLUETOOTH_ADDRESS_BYTES) return null
    val bytes = ByteArray(BLUETOOTH_ADDRESS_BYTES)
    parts.forEachIndexed { index, part ->
      if (part.length != 2) return null
      val value = part.toIntOrNull(16) ?: return null
      if (value !in 0..0xff) return null
      bytes[index] = value.toByte()
    }
    val normalized = parts.joinToString(":") { it.uppercase() }
    if (normalized == "00:00:00:00:00:00" || normalized == "02:00:00:00:00:00") return null
    return bytes
  }

  private fun signalIdFromBleServiceData(serviceData: ByteArray?): String? {
    val baseLength =
      when {
        isConnectableBleL2capAdvertisement(serviceData) -> BLE_L2CAP_SERVICE_DATA_LENGTH
        isConnectableBleGattAdvertisement(serviceData) -> BLE_GATT_SERVICE_DATA_LENGTH
        else -> return null
      }
    if (serviceData == null || serviceData.size < baseLength + BLE_SIGNAL_ID_BYTES) return null
    val bytes = serviceData.copyOfRange(baseLength, baseLength + BLE_SIGNAL_ID_BYTES)
    return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
  }

  private fun signalIdBytesForDeviceId(deviceId: String): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256").digest(deviceId.trim().toByteArray(Charsets.UTF_8))
    return digest.copyOf(BLE_SIGNAL_ID_BYTES)
  }

  private const val BLE_SERVICE_DATA_VERSION: Byte = 2
  private const val BLE_TRANSPORT_L2CAP: Byte = 1
  private const val BLE_TRANSPORT_GATT: Byte = 2
  private const val BLE_L2CAP_ENDPOINT_PREFIX = "ble-l2cap:"
  private const val BLE_GATT_ENDPOINT_PREFIX = "ble-gatt:"
  private const val MIN_L2CAP_PSM = 1
  private const val MAX_L2CAP_PSM = 0xffff
  private const val BLUETOOTH_ADDRESS_BYTES = 6
  private const val BLE_L2CAP_SERVICE_DATA_LENGTH = 4
  private const val BLE_GATT_SERVICE_DATA_LENGTH = 2
  private const val BLE_SIGNAL_ID_BYTES = 4
  private const val USE_CODED_ADVERTISING_BY_DEFAULT = true
}

package com.example.offlinelink.transport

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothServiceFilterTest {
  @Test
  fun acceptsOnlyOfflineLinkServiceUuid() {
    assertTrue(OfflineLinkBluetoothService.matches(listOf(OfflineLinkBluetoothService.UUID)))
    assertFalse(OfflineLinkBluetoothService.matches(listOf(UUID.fromString("0000110a-0000-1000-8000-00805f9b34fb"))))
    assertFalse(OfflineLinkBluetoothService.matches(emptyList()))
    assertFalse(OfflineLinkBluetoothService.matches(null))
  }

  @Test
  fun ignoresBleAdvertisementWithoutL2capServiceData() {
    val endpoint =
      OfflineLinkBluetoothService.endpointFromBleAdvertisement(
        address = "00:11:22:33:44:55",
        name = "Test phone",
        serviceUuids = listOf(OfflineLinkBluetoothService.UUID),
      )

    assertNull(endpoint)
    assertNull(
      OfflineLinkBluetoothService.endpointFromBleAdvertisement(
        address = "00:11:22:33:44:55",
        name = "Other device",
        serviceUuids = listOf(UUID.fromString("0000110a-0000-1000-8000-00805f9b34fb")),
      ),
    )
    assertNull(
      OfflineLinkBluetoothService.endpointFromBleAdvertisement(
        address = null,
        name = "No address",
        serviceUuids = listOf(OfflineLinkBluetoothService.UUID),
      ),
    )
  }

  @Test
  fun bleAdvertisementEndpointCarriesL2capPsmFromServiceData() {
    val serviceData = OfflineLinkBluetoothService.encodeBleL2capServiceData(0x1234)
    val endpoint =
      OfflineLinkBluetoothService.endpointFromBleAdvertisement(
        address = "11:22:33:44:DC:D2",
        name = null,
        serviceUuids = emptyList(),
        serviceData = serviceData,
        rssi = -61,
      )

    assertEquals(0x1234, OfflineLinkBluetoothService.l2capPsmFromBleServiceData(serviceData))
    assertEquals("ble-l2cap:11:22:33:44:DC:D2:4660", endpoint?.id)
    assertEquals("Bluetooth DC:D2", endpoint?.name)
    assertEquals("11:22:33:44:DC:D2", endpoint?.deviceId)
    assertEquals(-61, endpoint?.rssi)
  }

  @Test
  fun bleAdvertisementEndpointCarriesStableSignalIdFromServiceData() {
    val serviceData = OfflineLinkBluetoothService.encodeBleL2capServiceData(0x1234, deviceId = "device-b")
    val endpoint =
      OfflineLinkBluetoothService.endpointFromBleAdvertisement(
        address = "11:22:33:44:DC:D2",
        name = "Phone B",
        serviceUuids = emptyList(),
        serviceData = serviceData,
        rssi = -63,
      )

    assertEquals(OfflineLinkBluetoothService.signalIdForDeviceId("device-b"), endpoint?.signalId)
    assertEquals(-63, endpoint?.rssi)
  }

  @Test
  fun recognizesOnlyConnectableBleL2capAdvertisements() {
    val connectableServiceData = OfflineLinkBluetoothService.encodeBleL2capServiceData(0x1234)

    assertTrue(OfflineLinkBluetoothService.isConnectableBleL2capAdvertisement(connectableServiceData))
    assertFalse(OfflineLinkBluetoothService.isConnectableBleL2capAdvertisement(null))
    assertFalse(OfflineLinkBluetoothService.isConnectableBleL2capAdvertisement(byteArrayOf(2, 1)))
    assertFalse(OfflineLinkBluetoothService.isConnectableBleL2capAdvertisement(byteArrayOf(2, 2, 0x12, 0x34)))
  }

  @Test
  fun bleGattAdvertisementEndpointCarriesStableSignalIdFromServiceData() {
    val serviceData = OfflineLinkBluetoothService.encodeBleGattServiceData(deviceId = "device-b")
    val endpoint =
      OfflineLinkBluetoothService.endpointFromBleGattAdvertisement(
        address = "11:22:33:44:DC:D2",
        name = null,
        serviceUuids = emptyList(),
        serviceData = serviceData,
        rssi = -70,
      )

    assertTrue(OfflineLinkBluetoothService.isConnectableBleGattAdvertisement(serviceData))
    assertEquals("ble-gatt:11:22:33:44:DC:D2", endpoint?.id)
    assertEquals("Bluetooth DC:D2", endpoint?.name)
    assertEquals("11:22:33:44:DC:D2", endpoint?.deviceId)
    assertEquals(-70, endpoint?.rssi)
    assertEquals(OfflineLinkBluetoothService.signalIdForDeviceId("device-b"), endpoint?.signalId)
    assertEquals(
      OfflineLinkBluetoothService.BleGattEndpointId(address = "11:22:33:44:DC:D2"),
      OfflineLinkBluetoothService.parseBleGattEndpointId(endpoint?.id.orEmpty()),
    )
  }

  @Test
  fun recognizesOnlyConnectableBleGattAdvertisements() {
    val connectableServiceData = OfflineLinkBluetoothService.encodeBleGattServiceData()

    assertTrue(OfflineLinkBluetoothService.isConnectableBleGattAdvertisement(connectableServiceData))
    assertFalse(OfflineLinkBluetoothService.isConnectableBleGattAdvertisement(null))
    assertFalse(OfflineLinkBluetoothService.isConnectableBleGattAdvertisement(byteArrayOf(2)))
    assertFalse(OfflineLinkBluetoothService.isConnectableBleGattAdvertisement(byteArrayOf(2, 1, 0x12, 0x34)))
  }

  @Test
  fun codedAdvertisingIsUsedByDefaultWhenLongRangeIsSupported() {
    assertTrue(
      OfflineLinkBluetoothService.shouldUseCodedAdvertising(
        codedPhySupported = true,
        extendedAdvertisingSupported = true,
      ),
    )
    assertFalse(
      OfflineLinkBluetoothService.shouldUseCodedAdvertising(
        codedPhySupported = true,
        extendedAdvertisingSupported = false,
      ),
    )
  }
}

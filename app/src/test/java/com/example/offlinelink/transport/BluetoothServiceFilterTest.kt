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
  fun createsEndpointOnlyForOfflineLinkBleAdvertisement() {
    val endpoint =
      OfflineLinkBluetoothService.endpointFromBleAdvertisement(
        address = "00:11:22:33:44:55",
        name = "Test phone",
        serviceUuids = listOf(OfflineLinkBluetoothService.UUID),
      )

    assertEquals("00:11:22:33:44:55", endpoint?.id)
    assertEquals("Test phone", endpoint?.name)
    assertEquals("00:11:22:33:44:55", endpoint?.deviceId)
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
}

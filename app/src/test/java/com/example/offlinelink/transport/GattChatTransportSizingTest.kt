package com.example.offlinelink.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class GattChatTransportSizingTest {
  @Test
  fun capsLargeMtuGattValuesForLongRangeTransmission() {
    assertEquals(LONG_RANGE_GATT_VALUE_BYTES, boundedGattValueBytes(currentMtu = 517, providerMtu = 517))
  }

  @Test
  fun keepsDefaultMtuWithinLongRangeTransmissionBudget() {
    assertEquals(20, boundedGattValueBytes(currentMtu = 23, providerMtu = 23))
  }
}

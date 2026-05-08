package com.example.offlinelink.ui.main

import com.example.offlinelink.model.ConnectionStatus
import com.example.offlinelink.model.CallStatus
import com.example.offlinelink.model.ChatMessage
import com.example.offlinelink.model.ChatUiState
import com.example.offlinelink.model.GroupMember
import com.example.offlinelink.model.GroupMemberStatus
import com.example.offlinelink.model.MessageKind
import com.example.offlinelink.model.MessageStatus
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import com.example.offlinelink.data.ChatHistoryRepository
import com.example.offlinelink.protocol.ChatProtocol
import com.example.offlinelink.protocol.DecodedWireMessage
import com.example.offlinelink.protocol.WireMember
import com.example.offlinelink.transport.ChatTransport
import com.example.offlinelink.transport.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    kotlinx.coroutines.Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    kotlinx.coroutines.Dispatchers.resetMain()
  }

  @Test
  fun startDiscoveryUpdatesStateAndDelegatesToTransport() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    viewModel.startDiscovery()

    assertEquals(ConnectionStatus.Discovering, viewModel.uiState.value.status)
    assertTrue(transport.discoveryStarted)
  }

  @Test
  fun startDiscoveryAlsoAdvertisesForFasterPairing() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    viewModel.startDiscovery()

    assertTrue(transport.discoveryStarted)
    assertTrue(transport.advertisingStarted)
  }

  @Test
  fun startDiscoveryWhileConnectedKeepsExistingChatUsable() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.clearDiscoveryState()

    viewModel.startDiscovery()
    advanceUntilIdle()

    assertEquals(ConnectionStatus.Connected, viewModel.uiState.value.status)
    assertEquals("Connected to Phone B", viewModel.uiState.value.statusMessage)
    assertTrue(transport.advertisingStarted)
    assertTrue(transport.discoveryStarted)

    transport.clearSentPayloads()
    viewModel.sendMessage("still connected")

    assertEquals("endpoint-b", transport.sentPayloads.last().endpointId)
    assertEquals(MessageStatus.Sent, viewModel.uiState.value.messages.last().status)
  }

  @Test
  fun connectedEventStopsDiscoveryToProtectEstablishedLink() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    viewModel.startDiscovery()
    transport.clearDiscoveryState()
    advanceUntilIdle()

    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()

    assertTrue(transport.discoveryStopped)
  }

  @Test
  fun endpointFoundWithLocalDeviceIdIsIgnored() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local", defaultDisplayName = "T517D")

    viewModel.startDiscovery()
    advanceUntilIdle()
    transport.emit(TransportEvent.EndpointFound(NearbyEndpoint("loopback", "T517D", deviceId = "local")))
    transport.emit(TransportEvent.EndpointFound(NearbyEndpoint("endpoint-b", "T517D", deviceId = "remote")))
    advanceUntilIdle()

    assertEquals(listOf(NearbyEndpoint("endpoint-b", "T517D", deviceId = "remote")), viewModel.uiState.value.discoveredEndpoints)
  }

  @Test
  fun initLoadsPersistedMessagesIntoUiState() = runTest {
    val persisted =
      listOf(
        ChatMessage(
          id = "msg-1",
          conversationId = "one-to-one",
          senderId = "local",
          text = "saved",
          createdAt = 1000L,
          status = MessageStatus.Received,
          isLocal = true,
        ),
      )
    val historyRepository = FakeChatHistoryRepository(persisted)

    val viewModel = MainScreenViewModel(FakeChatTransport(), requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local", historyRepository = historyRepository)

    assertEquals(persisted, viewModel.uiState.value.messages)
  }

  @Test
  fun initUsesProvidedDefaultDisplayName() = runTest {
    val viewModel =
      MainScreenViewModel(
        FakeChatTransport(),
        requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) },
        compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) },
        payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")),
        localDeviceId = "local",
        defaultDisplayName = "Pixel 8",
      )

    assertEquals("Pixel 8", viewModel.uiState.value.displayName)
  }

  @Test
  fun initUsesProvidedDefaultAvatarName() = runTest {
    val viewModel =
      MainScreenViewModel(
        FakeChatTransport(),
        requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) },
        compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) },
        payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")),
        localDeviceId = "local",
        defaultAvatarName = "Team Lead",
      )

    assertEquals("Team Lead", viewModel.uiState.value.avatarName)
  }

  @Test
  fun connectedEventSendsHelloWithCurrentGroupName() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    viewModel.setGroupName("Field Team")
    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()

    val hello = ChatProtocol.decode(transport.sentPayloads.single().bytes) as DecodedWireMessage.Hello
    assertEquals("Field Team", hello.groupName)
  }

  @Test
  fun connectedEventDoesNotAdvertiseTemporaryEndpointIdAsGroupMember() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-a")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()

    val hello = ChatProtocol.decode(transport.sentPayloads.single().bytes) as DecodedWireMessage.Hello
    assertEquals(listOf("device-a"), hello.members.map { it.id })
  }

  @Test
  fun setGroupNameWhileConnectedBroadcastsUpdatedHello() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-c", "Phone C")))
    advanceUntilIdle()
    transport.clearSentPayloads()

    viewModel.setGroupName("Field Team")

    assertEquals(listOf("endpoint-b", "endpoint-c"), transport.sentPayloads.map { it.endpointId })
    assertEquals(
      listOf("Field Team", "Field Team"),
      transport.sentPayloads.map { (ChatProtocol.decode(it.bytes) as DecodedWireMessage.Hello).groupName },
    )
  }

  @Test
  fun incomingHelloUpdatesGroupNameAndConnectedMemberName() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Nearby device")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-b",
            displayName = "Phone B",
            groupName = "Rescue Team",
            sentAt = 1000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals("Rescue Team", viewModel.uiState.value.groupName)
    assertEquals(listOf(NearbyEndpoint("endpoint-b", "Phone B")), viewModel.uiState.value.connectedEndpoints)
  }

  @Test
  fun incomingHelloFromNewEndpointRebroadcastsRosterToExistingEndpoints() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-a")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-b",
            displayName = "Phone B",
            groupName = "Offline group",
            sentAt = 1000L,
          ),
      ),
    )
    advanceUntilIdle()

    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-c", "Phone C")))
    advanceUntilIdle()
    transport.clearSentPayloads()

    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-c",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-c",
            displayName = "Phone C",
            groupName = "Offline group",
            sentAt = 2000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals(listOf("Phone B", "Phone C"), viewModel.uiState.value.groupMembers.map { it.displayName })
    assertEquals(listOf("endpoint-b", "endpoint-c"), transport.sentPayloads.map { it.endpointId })
    assertTrue(transport.sentPayloads.all { ChatProtocol.decode(it.bytes) is DecodedWireMessage.Hello })
    val helloToPhoneB =
      transport.sentPayloads
        .firstOrNull { it.endpointId == "endpoint-b" }
        ?.let { ChatProtocol.decode(it.bytes) as DecodedWireMessage.Hello }
    assertNotNull(helloToPhoneB)
    assertEquals(listOf("device-a", "device-b", "device-c"), helloToPhoneB!!.members.map { it.id })
  }

  @Test
  fun disconnectedRelayStartsAdvertisingAndDiscoveryWhenLocalDeviceIsLowestRemainingMember() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-b")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()

    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()

    assertTrue(transport.advertisingStarted)
    assertTrue(transport.discoveryStarted)
    assertEquals(ConnectionStatus.Discovering, viewModel.uiState.value.status)
    assertEquals(listOf("Phone C"), viewModel.uiState.value.groupMembers.map { it.displayName })
  }

  @Test
  fun disconnectedRelayStartsAdvertisingDiscoveryAndAutoConnectsWhenLocalDeviceIsNotLowestRemainingMember() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-c")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()

    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()
    transport.emit(TransportEvent.EndpointFound(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()

    assertTrue(transport.advertisingStarted)
    assertTrue(transport.discoveryStarted)
    assertEquals(ConnectionStatus.Connecting, viewModel.uiState.value.status)
    assertEquals(listOf(NearbyEndpoint("endpoint-b", "Phone B")), transport.requestedConnections)
  }

  @Test
  fun disconnectingOneMemberKeepsGroupMemberAndStartsDiscoveryForReconnect() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-a")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-b",
            displayName = "Phone B",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-c", "Phone C")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-c",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-c",
            displayName = "Phone C",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()
    transport.clearDiscoveryState()

    transport.emit(TransportEvent.Disconnected("endpoint-b"))
    advanceUntilIdle()

    assertEquals(listOf("Phone B", "Phone C"), viewModel.uiState.value.groupMembers.map { it.displayName })
    assertEquals(listOf(GroupMemberStatus.Reconnecting, GroupMemberStatus.Online), viewModel.uiState.value.groupMembers.map { it.status })
    assertTrue(transport.advertisingStarted)
    assertTrue(transport.discoveryStarted)
    assertEquals(ConnectionStatus.Connected, viewModel.uiState.value.status)
  }

  @Test
  fun manualSearchAfterStaleRecoveryClearsRosterAndStartsFreshPairing() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-c")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()
    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()
    transport.clearDiscoveryState()

    viewModel.startDiscovery()
    advanceUntilIdle()

    assertEquals(ConnectionStatus.Discovering, viewModel.uiState.value.status)
    assertEquals(emptyList<GroupMember>(), viewModel.uiState.value.groupMembers)
    assertTrue(transport.advertisingStarted)
    assertTrue(transport.discoveryStarted)
  }

  @Test
  fun disconnectedRelayMarksRemainingMembersReconnectingDuringRecovery() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-c")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()

    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()

    assertEquals(listOf(GroupMemberStatus.Reconnecting), viewModel.uiState.value.groupMembers.map { it.status })
  }

  @Test
  fun recoveryDiscoveryIgnoresEndpointsOutsideRemainingRoster() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-c")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()

    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()
    transport.emit(TransportEvent.EndpointFound(NearbyEndpoint("endpoint-x", "Random Phone")))
    advanceUntilIdle()

    assertEquals(emptyList<NearbyEndpoint>(), transport.requestedConnections)

    transport.emit(TransportEvent.EndpointFound(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()

    assertEquals(listOf(NearbyEndpoint("endpoint-b", "Phone B")), transport.requestedConnections)
  }

  @Test
  fun recoveryDiscoveryMatchesExpectedStableDeviceIdWhenDisplayNameChanged() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-c")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A", deviceId = "device-a")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Old Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()

    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()
    transport.emit(TransportEvent.EndpointFound(NearbyEndpoint("endpoint-b", "Renamed Phone B", deviceId = "device-b")))
    advanceUntilIdle()

    assertEquals(listOf(NearbyEndpoint("endpoint-b", "Renamed Phone B", deviceId = "device-b")), transport.requestedConnections)
  }

  @Test
  fun recoveryConnectionFailureKeepsSearchingAndAllowsRetry() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-c")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()
    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()
    transport.emit(TransportEvent.EndpointFound(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()

    transport.emit(TransportEvent.OperationFailed("Could not request connection", IllegalStateException("8012: STATUS_ENDPOINT_IO_ERROR")))
    advanceUntilIdle()

    assertEquals(ConnectionStatus.Discovering, viewModel.uiState.value.status)
    assertEquals(null, viewModel.uiState.value.lastError)

    transport.emit(TransportEvent.EndpointFound(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()

    assertEquals(ConnectionStatus.Connecting, viewModel.uiState.value.status)
    assertEquals(null, viewModel.uiState.value.lastError)
    assertEquals(
      listOf(
        NearbyEndpoint("endpoint-b", "Phone B"),
        NearbyEndpoint("endpoint-b", "Phone B"),
      ),
      transport.requestedConnections,
    )
  }

  @Test
  fun recoveryModeRejectsIncomingConnectionOutsideRemainingRoster() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-b")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()

    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.ConnectionInitiated(
        PendingConnection(
          endpointId = "endpoint-x",
          endpointName = "Random Phone",
          authenticationToken = "9999",
        ),
      ),
    )
    advanceUntilIdle()

    assertEquals(emptyList<String>(), transport.acceptedConnections)
    assertEquals(listOf("endpoint-x"), transport.rejectedConnections)
    assertEquals(null, viewModel.uiState.value.pendingConnection)

    transport.emit(
      TransportEvent.ConnectionInitiated(
        PendingConnection(
          endpointId = "endpoint-c",
          endpointName = "Phone C",
          authenticationToken = "1234",
        ),
      ),
    )
    advanceUntilIdle()

    assertEquals(listOf("endpoint-c"), transport.acceptedConnections)
  }

  @Test
  fun recoveryModeAutomaticallyAcceptsIncomingConnectionRequest() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-b")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()

    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.ConnectionInitiated(
        PendingConnection(
          endpointId = "endpoint-c",
          endpointName = "Phone C",
          authenticationToken = "1234",
        ),
      ),
    )
    advanceUntilIdle()

    assertEquals(listOf("endpoint-c"), transport.acceptedConnections)
    assertEquals(null, viewModel.uiState.value.pendingConnection)
  }

  @Test
  fun recoveryModeAcceptsExpectedStableDeviceIdWhenDisplayNameChanged() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-b")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A", deviceId = "device-a")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Old Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()

    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.ConnectionInitiated(
        PendingConnection(
          endpointId = "endpoint-c",
          endpointName = "Renamed Phone C",
          authenticationToken = "1234",
          deviceId = "device-c",
        ),
      ),
    )
    advanceUntilIdle()

    assertEquals(listOf("endpoint-c"), transport.acceptedConnections)
    assertEquals(null, viewModel.uiState.value.pendingConnection)
  }

  @Test
  fun remainingPeersAutoRequestEachOtherAfterRelayDisconnects() = runTest {
    val transportA = FakeChatTransport()
    val viewModelA = MainScreenViewModel(transportA, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads-a")), localDeviceId = "device-a", defaultDisplayName = "Phone A")
    val transportC = FakeChatTransport()
    val viewModelC = MainScreenViewModel(transportC, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads-c")), localDeviceId = "device-c", defaultDisplayName = "Phone C")

    advanceUntilIdle()
    transportA.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b-a", "Phone B", deviceId = "device-b")))
    transportC.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b-c", "Phone B", deviceId = "device-b")))
    advanceUntilIdle()
    transportA.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-b",
            displayName = "Phone B",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    transportC.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b-c",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-b",
            displayName = "Phone B",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()

    transportA.emit(TransportEvent.Disconnected("endpoint-b-a"))
    transportC.emit(TransportEvent.Disconnected("endpoint-b-c"))
    advanceUntilIdle()
    transportA.emit(TransportEvent.EndpointFound(NearbyEndpoint("endpoint-c", "Phone C", deviceId = "device-c")))
    transportC.emit(TransportEvent.EndpointFound(NearbyEndpoint("endpoint-a", "Phone A", deviceId = "device-a")))
    advanceUntilIdle()

    assertEquals(listOf(NearbyEndpoint("endpoint-c", "Phone C", deviceId = "device-c")), transportA.requestedConnections)
    assertEquals(listOf(NearbyEndpoint("endpoint-a", "Phone A", deviceId = "device-a")), transportC.requestedConnections)
  }

  @Test
  fun duplicateDisconnectEventDoesNotRestartGroupRecovery() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-b")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()

    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes = ChatProtocol.encodeDisconnect("User disconnected"),
      ),
    )
    advanceUntilIdle()
    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()

    assertEquals(1, transport.advertisingStartCount)
    assertEquals(1, transport.discoveryStartCount)
    assertEquals(ConnectionStatus.Discovering, viewModel.uiState.value.status)
  }

  @Test
  fun alreadyAdvertisingFailureDoesNotReplaceRecoveryStatus() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-b")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()
    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()

    transport.emit(TransportEvent.OperationFailed("Could not start advertising", IllegalStateException("8001: STATUS_ALREADY_ADVERTISING")))
    advanceUntilIdle()

    assertEquals(ConnectionStatus.Discovering, viewModel.uiState.value.status)
    assertEquals(null, viewModel.uiState.value.lastError)
  }

  @Test
  fun alreadyDiscoveringFailureDoesNotReplaceRecoveryStatus() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-c")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-a",
            displayName = "Phone A",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()
    transport.emit(TransportEvent.Disconnected("endpoint-a"))
    advanceUntilIdle()

    transport.emit(TransportEvent.OperationFailed("Could not start discovery", IllegalStateException("8002: STATUS_ALREADY_DISCOVERING")))
    advanceUntilIdle()

    assertEquals(ConnectionStatus.Discovering, viewModel.uiState.value.status)
    assertEquals(null, viewModel.uiState.value.lastError)
  }

  @Test
  fun incomingDefaultGroupNameDoesNotReplaceLocalCustomGroupName() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    viewModel.setGroupName("Field Team")
    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-b",
            displayName = "Phone B",
            groupName = "Offline group",
            sentAt = 1000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals("Field Team", viewModel.uiState.value.groupName)
  }

  @Test
  fun incomingDifferentCustomGroupNameKeepsLocalGroupAndShowsWarning() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    viewModel.setGroupName("Field Team")
    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Nearby device")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-b",
            displayName = "Phone B",
            groupName = "Rescue Team",
            sentAt = 1000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals("Field Team", viewModel.uiState.value.groupName)
    assertEquals("Group mismatch: Phone B uses Rescue Team", viewModel.uiState.value.groupWarning)
    assertEquals(listOf(NearbyEndpoint("endpoint-b", "Phone B")), viewModel.uiState.value.connectedEndpoints)
  }

  @Test
  fun incomingMatchingGroupNameClearsMismatchWarning() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    viewModel.setGroupName("Field Team")
    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-b",
            displayName = "Phone B",
            groupName = "Rescue Team",
            sentAt = 1000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals("Group mismatch: Phone B uses Rescue Team", viewModel.uiState.value.groupWarning)

    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-b",
            displayName = "Phone B",
            groupName = "Field Team",
            sentAt = 2000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals(null, viewModel.uiState.value.groupWarning)
  }

  @Test
  fun setGroupNameClearsExistingGroupMismatchWarning() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    viewModel.setGroupName("Field Team")
    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-b",
            displayName = "Phone B",
            groupName = "Rescue Team",
            sentAt = 1000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals("Group mismatch: Phone B uses Rescue Team", viewModel.uiState.value.groupWarning)

    viewModel.setGroupName("Rescue Team")

    assertEquals(null, viewModel.uiState.value.groupWarning)
  }

  @Test
  fun sendMessageQueuesAndSendsPayloadWhenConnected() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    viewModel.sendMessage("hello")

    val message = viewModel.uiState.value.messages.single()
    assertEquals("hello", message.text)
    assertEquals(MessageStatus.Sent, message.status)
    assertEquals("endpoint-b", transport.sentPayloads.last().endpointId)
    assertTrue(transport.sentPayloads.last().bytes.isNotEmpty())
  }

  @Test
  fun requestConnectionFailureWhileConnectedKeepsExistingChatUsableWithoutErrorBanner() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    viewModel.connectTo(NearbyEndpoint("endpoint-c", "Phone C"))
    transport.emit(TransportEvent.OperationFailed("Could not request connection", IllegalStateException("8012: STATUS_ENDPOINT_IO_ERROR")))
    advanceUntilIdle()

    assertEquals(ConnectionStatus.Connected, viewModel.uiState.value.status)
    assertEquals("Connected to Phone B", viewModel.uiState.value.statusMessage)
    assertEquals(null, viewModel.uiState.value.lastError)

    transport.clearSentPayloads()
    viewModel.sendMessage("still connected")

    assertEquals("endpoint-b", transport.sentPayloads.last().endpointId)
    assertEquals(MessageStatus.Sent, viewModel.uiState.value.messages.last().status)
  }

  @Test
  fun connectingAdditionalDeviceKeepsExistingChatConnected() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()

    viewModel.connectTo(NearbyEndpoint("endpoint-c", "Phone C"))
    advanceUntilIdle()

    assertEquals(ConnectionStatus.Connected, viewModel.uiState.value.status)
    assertEquals("Connected to Phone B", viewModel.uiState.value.statusMessage)
    assertEquals(listOf(NearbyEndpoint("endpoint-c", "Phone C")), transport.requestedConnections)

    transport.clearSentPayloads()
    viewModel.sendMessage("while adding c")

    assertEquals("endpoint-b", transport.sentPayloads.last().endpointId)
    assertEquals(MessageStatus.Sent, viewModel.uiState.value.messages.last().status)
  }

  @Test
  fun incomingPayloadFromUntrackedEndpointMarksEndpointConnected() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    viewModel.startDiscovery()
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeMessage(
            messageId = "remote-1",
            conversationId = "one-to-one",
            senderId = "device-b",
            text = "hello",
            createdAt = 1000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals(ConnectionStatus.Connected, viewModel.uiState.value.status)
    assertEquals(listOf(NearbyEndpoint("endpoint-b", "Nearby device")), viewModel.uiState.value.connectedEndpoints)

    transport.clearSentPayloads()
    viewModel.sendMessage("reply")

    assertEquals("endpoint-b", transport.sentPayloads.last().endpointId)
    assertEquals(MessageStatus.Sent, viewModel.uiState.value.messages.last().status)
  }

  @Test
  fun sendMessagePersistsUpdatedMessageStatus() = runTest {
    val transport = FakeChatTransport()
    val historyRepository = FakeChatHistoryRepository()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local", historyRepository = historyRepository)

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    viewModel.sendMessage("hello")
    advanceUntilIdle()

    assertEquals(listOf(viewModel.uiState.value.messages.single()), historyRepository.savedMessages.last())
    assertEquals(MessageStatus.Sent, historyRepository.savedMessages.last().single().status)
  }

  @Test
  fun connectedEventRetriesPersistedLocalMessagesThatAreNotDelivered() = runTest {
    val pendingMessage =
      ChatMessage(
        id = "msg-1",
        conversationId = "one-to-one",
        senderId = "local",
        text = "retry me",
        createdAt = 1000L,
        status = MessageStatus.Failed,
        isLocal = true,
      )
    val deliveredMessage =
      pendingMessage.copy(
        id = "msg-2",
        text = "already delivered",
        status = MessageStatus.Received,
      )
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local", historyRepository = FakeChatHistoryRepository(listOf(pendingMessage, deliveredMessage)))

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()

    val retriedMessages =
      transport.sentPayloads
        .mapNotNull { ChatProtocol.decode(it.bytes) as? DecodedWireMessage.Message }
    assertEquals(listOf("msg-1"), retriedMessages.map { it.messageId })
    assertEquals(MessageStatus.Sent, viewModel.uiState.value.messages.first { it.id == "msg-1" }.status)
    assertEquals(MessageStatus.Received, viewModel.uiState.value.messages.first { it.id == "msg-2" }.status)
  }

  @Test
  fun retryMessageResendsFailedLocalMessageToConnectedEndpoints() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.clearSentPayloads()
    transport.queueSendResult(Result.failure(IllegalStateException("radio busy")))

    viewModel.sendMessage("retry please")
    advanceUntilIdle()

    val messageId = viewModel.uiState.value.messages.single().id
    assertEquals(MessageStatus.Failed, viewModel.uiState.value.messages.single().status)
    transport.clearSentPayloads()

    viewModel.retryMessage(messageId)
    advanceUntilIdle()

    val retried = ChatProtocol.decode(transport.sentPayloads.single().bytes) as DecodedWireMessage.Message
    assertEquals(messageId, retried.messageId)
    assertEquals(MessageStatus.Sent, viewModel.uiState.value.messages.single().status)
  }

  @Test
  fun clearMessagesRemovesMessagesAndPersistsEmptyHistory() = runTest {
    val persisted =
      listOf(
        ChatMessage(
          id = "msg-1",
          conversationId = "one-to-one",
          senderId = "local",
          text = "saved",
          createdAt = 1000L,
          status = MessageStatus.Received,
          isLocal = true,
        ),
      )
    val historyRepository = FakeChatHistoryRepository(persisted)
    val viewModel = MainScreenViewModel(FakeChatTransport(), requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local", historyRepository = historyRepository)

    viewModel.clearMessages()
    advanceUntilIdle()

    assertEquals(emptyList<ChatMessage>(), viewModel.uiState.value.messages)
    assertEquals(emptyList<ChatMessage>(), historyRepository.savedMessages.last())
  }

  @Test
  fun retryOnlySendsPendingMessageToNewlyConnectedEndpointsOnce() = runTest {
    val pendingMessage =
      ChatMessage(
        id = "msg-1",
        conversationId = "one-to-one",
        senderId = "local",
        text = "retry me",
        createdAt = 1000L,
        status = MessageStatus.Failed,
        isLocal = true,
      )
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local", historyRepository = FakeChatHistoryRepository(listOf(pendingMessage)))

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-c", "Phone C")))
    advanceUntilIdle()

    val retriedPayloads =
      transport.sentPayloads
        .mapNotNull { sent ->
          (ChatProtocol.decode(sent.bytes) as? DecodedWireMessage.Message)?.let { sent.endpointId to it.messageId }
        }
    assertEquals(listOf("endpoint-b" to "msg-1", "endpoint-c" to "msg-1"), retriedPayloads)
  }

  @Test
  fun sendMessageBroadcastsPayloadToEveryConnectedEndpoint() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-c", "Phone C")))
    advanceUntilIdle()
    transport.clearSentPayloads()

    viewModel.sendMessage("hello group")

    assertEquals(listOf("endpoint-b", "endpoint-c"), transport.sentPayloads.map { it.endpointId })
    assertEquals(
      listOf("hello group", "hello group"),
      transport.sentPayloads.map { (ChatProtocol.decode(it.bytes) as DecodedWireMessage.Message).text },
    )
  }

  @Test
  fun sendVoiceMessageBroadcastsPayloadToEveryConnectedEndpoint() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-c", "Phone C")))
    advanceUntilIdle()
    transport.clearSentPayloads()

    viewModel.sendVoiceMessage(byteArrayOf(1, 2, 3, 4), durationMs = 2300L, mimeType = "audio/3gpp")

    assertEquals(MessageKind.Voice, viewModel.uiState.value.messages.single().kind)
    assertEquals(listOf("endpoint-b", "endpoint-c"), transport.sentPayloads.map { it.endpointId })
    assertEquals(
      listOf("AQIDBA==", "AQIDBA=="),
      transport.sentPayloads.map { (ChatProtocol.decode(it.bytes) as DecodedWireMessage.VoiceMessage).audioBase64 },
    )
  }

  @Test
  fun formatCallDurationUsesMinuteSecondClock() {
    assertEquals("0:00", formatCallDuration(0L))
    assertEquals("0:09", formatCallDuration(9_400L))
    assertEquals("1:05", formatCallDuration(65_000L))
    assertEquals("10:00", formatCallDuration(600_000L))
  }

  @Test
  fun callOutputRouteLabelShowsCurrentRoute() {
    assertEquals("Speaker", callOutputRouteLabel(true))
    assertEquals("Earpiece", callOutputRouteLabel(false))
  }

  @Test
  fun callTargetOptionsUseKnownGroupMembersWhenOnlyOneEndpointIsDirect() {
    val options =
      callTargetOptions(
        ChatUiState(
          localDeviceId = "device-a",
          connectedEndpoints = listOf(NearbyEndpoint("endpoint-b", "Phone B")),
          groupMembers =
            listOf(
              GroupMember("device-b", "Phone B"),
              GroupMember("device-c", "Phone C"),
            ),
        ),
      )

    assertEquals(listOf("device-b", "device-c"), options.map { it.id })
    assertEquals(listOf("Phone B", "Phone C"), options.map { it.name })
  }

  @Test
  fun startCallSendsCallRequestToConnectedEndpoint() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.clearSentPayloads()

    viewModel.startCall()

    assertEquals(CallStatus.Outgoing, viewModel.uiState.value.callState.status)
    assertEquals("endpoint-b", viewModel.uiState.value.callState.peerEndpointId)
    assertEquals("endpoint-b", transport.sentPayloads.single().endpointId)
    val request = ChatProtocol.decode(transport.sentPayloads.single().bytes) as DecodedWireMessage.CallRequest
    assertEquals("local", request.senderId)
    assertEquals(viewModel.uiState.value.callState.callId, request.callId)
  }

  @Test
  fun startCallCanTargetSelectedConnectedEndpoint() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-c", "Phone C")))
    advanceUntilIdle()
    transport.clearSentPayloads()

    viewModel.startCall("endpoint-c")

    assertEquals(CallStatus.Outgoing, viewModel.uiState.value.callState.status)
    assertEquals("endpoint-c", viewModel.uiState.value.callState.peerEndpointId)
    assertEquals("Phone C", viewModel.uiState.value.callState.peerName)
    assertEquals(listOf("endpoint-c"), transport.sentPayloads.map { it.endpointId })
  }

  @Test
  fun startCallCanTargetKnownGroupMemberThroughRelayEndpoint() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-a")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeHello(
            senderId = "device-b",
            displayName = "Phone B",
            members =
              listOf(
                WireMember("device-a", "Phone A"),
                WireMember("device-b", "Phone B"),
                WireMember("device-c", "Phone C"),
              ),
          ),
      ),
    )
    advanceUntilIdle()
    transport.clearSentPayloads()

    viewModel.startCall("device-c")

    assertEquals(CallStatus.Outgoing, viewModel.uiState.value.callState.status)
    assertEquals("endpoint-b", viewModel.uiState.value.callState.peerEndpointId)
    assertEquals("device-c", viewModel.uiState.value.callState.peerMemberId)
    assertEquals("Phone C", viewModel.uiState.value.callState.peerName)
    assertEquals(listOf("endpoint-b"), transport.sentPayloads.map { it.endpointId })
    val request = ChatProtocol.decode(transport.sentPayloads.single().bytes) as DecodedWireMessage.CallRequest
    assertEquals("device-c", request.targetId)
  }

  @Test
  fun startCallWithMissingTargetDoesNotSendCallRequest() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.clearSentPayloads()

    viewModel.startCall("endpoint-c")

    assertEquals(CallStatus.Idle, viewModel.uiState.value.callState.status)
    assertEquals(ConnectionStatus.Error, viewModel.uiState.value.status)
    assertEquals("Call target unavailable", viewModel.uiState.value.statusMessage)
    assertEquals(emptyList<SentPayload>(), transport.sentPayloads)
  }

  @Test
  fun incomingCallRequestForAnotherMemberIsForwardedToTargetEndpoint() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "device-b")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-a", "Phone A")))
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-c", "Phone C")))
    advanceUntilIdle()
    transport.emit(TransportEvent.BytesReceived("endpoint-a", ChatProtocol.encodeHello(senderId = "device-a", displayName = "Phone A")))
    transport.emit(TransportEvent.BytesReceived("endpoint-c", ChatProtocol.encodeHello(senderId = "device-c", displayName = "Phone C")))
    advanceUntilIdle()
    transport.clearSentPayloads()

    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-a",
        bytes =
          ChatProtocol.encodeCallRequest(
            callId = "call-1",
            senderId = "device-a",
            targetId = "device-c",
            createdAt = 1000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals(CallStatus.Idle, viewModel.uiState.value.callState.status)
    assertEquals(listOf("endpoint-c"), transport.sentPayloads.map { it.endpointId })
    val request = ChatProtocol.decode(transport.sentPayloads.single().bytes) as DecodedWireMessage.CallRequest
    assertEquals("call-1", request.callId)
    assertEquals("device-a", request.senderId)
    assertEquals("device-c", request.targetId)
  }

  @Test
  fun incomingCallRequestShowsIncomingCallState() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeCallRequest(
            callId = "call-1",
            senderId = "device-b",
            createdAt = 1000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals(CallStatus.Incoming, viewModel.uiState.value.callState.status)
    assertEquals("call-1", viewModel.uiState.value.callState.callId)
    assertEquals("endpoint-b", viewModel.uiState.value.callState.peerEndpointId)
    assertEquals("Phone B", viewModel.uiState.value.callState.peerName)
  }

  @Test
  fun acceptIncomingCallSendsCallAcceptAndActivatesCall() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes = ChatProtocol.encodeCallRequest(callId = "call-1", senderId = "device-b", createdAt = 1000L),
      ),
    )
    advanceUntilIdle()
    transport.clearSentPayloads()

    viewModel.acceptCall()

    assertEquals(CallStatus.Active, viewModel.uiState.value.callState.status)
    assertEquals("endpoint-b", transport.sentPayloads.single().endpointId)
    val accept = ChatProtocol.decode(transport.sentPayloads.single().bytes) as DecodedWireMessage.CallAccept
    assertEquals("call-1", accept.callId)
    assertEquals("local", accept.senderId)
  }

  @Test
  fun endActiveCallSendsCallEndAndClearsState() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes = ChatProtocol.encodeCallRequest(callId = "call-1", senderId = "device-b", createdAt = 1000L),
      ),
    )
    advanceUntilIdle()
    viewModel.acceptCall()
    transport.clearSentPayloads()

    viewModel.endCall()

    assertEquals(CallStatus.Idle, viewModel.uiState.value.callState.status)
    assertEquals("endpoint-b", transport.sentPayloads.single().endpointId)
    val end = ChatProtocol.decode(transport.sentPayloads.single().bytes) as DecodedWireMessage.CallEnd
    assertEquals("call-1", end.callId)
    assertEquals("local", end.senderId)
  }

  @Test
  fun sendCallVoiceMessageTargetsActiveCallPeerOnly() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-c", "Phone C")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes = ChatProtocol.encodeCallRequest(callId = "call-1", senderId = "device-b", createdAt = 1000L),
      ),
    )
    advanceUntilIdle()
    viewModel.acceptCall()
    transport.clearSentPayloads()

    viewModel.sendCallVoiceMessage(byteArrayOf(1, 2, 3, 4), durationMs = 2300L, mimeType = "audio/3gpp")

    assertEquals(emptyList<com.example.offlinelink.model.ChatMessage>(), viewModel.uiState.value.messages)
    assertEquals("Voice sent", viewModel.uiState.value.callState.activityLabel)
    assertEquals(listOf("endpoint-b"), transport.sentPayloads.map { it.endpointId })
    val callVoice = ChatProtocol.decode(transport.sentPayloads.single().bytes) as DecodedWireMessage.CallVoice
    assertEquals("call-1", callVoice.callId)
    assertEquals("AQIDBA==", callVoice.audioBase64)
  }

  @Test
  fun sendStreamingCallAudioFrameShowsLiveVoiceActivity() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-c", "Phone C")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes = ChatProtocol.encodeCallRequest(callId = "call-1", senderId = "device-b", createdAt = 1000L),
      ),
    )
    advanceUntilIdle()
    viewModel.acceptCall()
    transport.clearSentPayloads()

    viewModel.sendCallVoiceMessage(
      audioBytes = byteArrayOf(1, 2, 3, 4),
      durationMs = 40L,
      mimeType = "audio/pcm;rate=8000;encoding=pcm16",
    )

    assertEquals(emptyList<com.example.offlinelink.model.ChatMessage>(), viewModel.uiState.value.messages)
    assertEquals("Live voice", viewModel.uiState.value.callState.activityLabel)
    assertEquals(listOf("endpoint-b"), transport.sentPayloads.map { it.endpointId })
    val callAudio = ChatProtocol.decode(transport.sentPayloads.single().bytes) as DecodedWireMessage.CallAudioFrame
    assertEquals("call-1", callAudio.callId)
    assertEquals("audio/pcm;rate=8000;encoding=pcm16", callAudio.mimeType)
    assertEquals(40L, callAudio.durationMs)
    assertEquals(listOf(1, 2, 3, 4), callAudio.audioBytes.map { it.toInt() })
  }

  @Test
  fun streamingCallAudioBypassesPendingQueuedSends() = runTest {
    val transport = FakeChatTransport(autoCompleteSends = false)
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.completeNextSend()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes = ChatProtocol.encodeCallRequest(callId = "call-1", senderId = "device-b", createdAt = 1000L),
      ),
    )
    advanceUntilIdle()
    viewModel.acceptCall()
    advanceUntilIdle()

    viewModel.sendCallVoiceMessage(
      audioBytes = byteArrayOf(1, 2, 3, 4),
      durationMs = 40L,
      mimeType = "audio/pcm;rate=8000;encoding=pcm16",
    )

    assertTrue(
      transport.sentPayloads.any { ChatProtocol.decode(it.bytes) is DecodedWireMessage.CallAudioFrame },
    )
  }

  @Test
  fun incomingCallVoiceMessageCreatesPlaybackEventWithoutAppendingChatMessage() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes = ChatProtocol.encodeCallRequest(callId = "call-1", senderId = "device-b", createdAt = 1000L),
      ),
    )
    advanceUntilIdle()
    viewModel.acceptCall()
    transport.clearSentPayloads()

    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeCallVoice(
            callId = "call-1",
            clipId = "clip-1",
            senderId = "device-b",
            audioBase64 = "AQIDBA==",
            durationMs = 2300L,
            mimeType = "audio/3gpp",
            createdAt = 2000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals(emptyList<com.example.offlinelink.model.ChatMessage>(), viewModel.uiState.value.messages)
    assertEquals("Playing Phone B", viewModel.uiState.value.callState.activityLabel)
    val playback = viewModel.uiState.value.callPlayback
    assertNotNull(playback)
    assertEquals("clip-1", playback!!.clipId)
    assertEquals("AQIDBA==", playback.audioBase64)
    assertEquals(2300L, playback.durationMs)
  }

  @Test
  fun incomingStreamingCallAudioFrameShowsLiveVoicePlayback() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes = ChatProtocol.encodeCallRequest(callId = "call-1", senderId = "device-b", createdAt = 1000L),
      ),
    )
    advanceUntilIdle()
    viewModel.acceptCall()
    transport.clearSentPayloads()
    val playbackFrames = mutableListOf<com.example.offlinelink.model.CallAudioPlaybackFrame>()
    backgroundScope.launch { viewModel.callAudioFrames.toList(playbackFrames) }

    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeCallVoice(
            callId = "call-1",
            clipId = "frame-1",
            senderId = "device-b",
            audioBase64 = "AQIDBA==",
            durationMs = 40L,
            mimeType = "audio/pcm;rate=8000;encoding=pcm16",
            createdAt = 2000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals(emptyList<com.example.offlinelink.model.ChatMessage>(), viewModel.uiState.value.messages)
    assertEquals("Live voice from Phone B", viewModel.uiState.value.callState.activityLabel)
    assertEquals(null, viewModel.uiState.value.callPlayback)
    assertEquals(1, playbackFrames.size)
    val playback = playbackFrames.single()
    assertEquals("frame-1", playback.frameId)
    assertEquals(listOf(1, 2, 3, 4), playback.audioBytes.map { it.toInt() })
    assertEquals("audio/pcm;rate=8000;encoding=pcm16", playback.mimeType)
    assertEquals(40L, playback.durationMs)
  }

  @Test
  fun incomingBinaryCallAudioFrameShowsLiveVoicePlayback() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes = ChatProtocol.encodeCallRequest(callId = "call-1", senderId = "device-b", createdAt = 1000L),
      ),
    )
    advanceUntilIdle()
    viewModel.acceptCall()
    transport.clearSentPayloads()
    val playbackFrames = mutableListOf<com.example.offlinelink.model.CallAudioPlaybackFrame>()
    backgroundScope.launch { viewModel.callAudioFrames.toList(playbackFrames) }

    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeCallAudioFrame(
            callId = "call-1",
            frameId = "frame-1",
            senderId = "device-b",
            audioBytes = byteArrayOf(1, 2, 3, 4),
            durationMs = 20L,
            mimeType = "audio/opus;rate=16000",
            sequenceNumber = 7,
            createdAt = 2000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals(emptyList<com.example.offlinelink.model.ChatMessage>(), viewModel.uiState.value.messages)
    assertEquals("Live voice from Phone B", viewModel.uiState.value.callState.activityLabel)
    assertEquals(null, viewModel.uiState.value.callPlayback)
    assertEquals(1, playbackFrames.size)
    val playback = playbackFrames.single()
    assertEquals("frame-1", playback.frameId)
    assertEquals(listOf(1, 2, 3, 4), playback.audioBytes.map { it.toInt() })
    assertEquals("audio/opus;rate=16000", playback.mimeType)
    assertEquals(20L, playback.durationMs)
  }

  @Test
  fun finishingCallVoicePlaybackClearsPlaybackEvent() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()
    transport.emit(TransportEvent.BytesReceived("endpoint-b", ChatProtocol.encodeCallRequest(callId = "call-1", senderId = "device-b", createdAt = 1000L)))
    advanceUntilIdle()
    viewModel.acceptCall()
    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeCallVoice(
            callId = "call-1",
            clipId = "clip-1",
            senderId = "device-b",
            audioBase64 = "AQIDBA==",
            durationMs = 2300L,
            mimeType = "audio/3gpp",
            createdAt = 2000L,
          ),
      ),
    )
    advanceUntilIdle()

    viewModel.finishCallVoicePlayback("clip-1")

    assertEquals(null, viewModel.uiState.value.callPlayback)
    assertEquals(null, viewModel.uiState.value.callState.activityLabel)
  }

  @Test
  fun incomingMessageIsForwardedToOtherConnectedEndpoints() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-c", "Phone C")))
    advanceUntilIdle()
    transport.clearSentPayloads()

    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeMessage(
            messageId = "remote-1",
            conversationId = "one-to-one",
            senderId = "device-b",
            text = "hi everyone",
            createdAt = 2000L,
            sentAt = 3000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals("hi everyone", viewModel.uiState.value.messages.single().text)
    assertTrue(
      transport.sentPayloads.any {
        it.endpointId == "endpoint-b" && ChatProtocol.decode(it.bytes) is DecodedWireMessage.Ack
      },
    )
    assertTrue(
      transport.sentPayloads.any {
        it.endpointId == "endpoint-c" &&
          (ChatProtocol.decode(it.bytes) as? DecodedWireMessage.Message)?.messageId == "remote-1"
      },
    )
  }

  @Test
  fun incomingVoiceMessageIsForwardedToOtherConnectedEndpoints() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-c", "Phone C")))
    advanceUntilIdle()
    transport.clearSentPayloads()

    transport.emit(
      TransportEvent.BytesReceived(
        endpointId = "endpoint-b",
        bytes =
          ChatProtocol.encodeVoiceMessage(
            messageId = "voice-1",
            conversationId = "one-to-one",
            senderId = "device-b",
            audioBase64 = "AQIDBA==",
            durationMs = 2300L,
            mimeType = "audio/3gpp",
            createdAt = 2000L,
            sentAt = 3000L,
          ),
      ),
    )
    advanceUntilIdle()

    assertEquals(MessageKind.Voice, viewModel.uiState.value.messages.single().kind)
    assertTrue(
      transport.sentPayloads.any {
        it.endpointId == "endpoint-b" && ChatProtocol.decode(it.bytes) is DecodedWireMessage.Ack
      },
    )
    assertTrue(
      transport.sentPayloads.any {
        it.endpointId == "endpoint-c" &&
          (ChatProtocol.decode(it.bytes) as? DecodedWireMessage.VoiceMessage)?.messageId == "voice-1"
      },
    )
  }

  @Test
  fun connectedEventClearsDiscoveredEndpoints() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage(byteArrayOf(), "image/jpeg", 1, 1)) }, payloadCache = com.example.offlinelink.data.PayloadCache(java.io.File(System.getProperty("java.io.tmpdir"), "test-payloads")), localDeviceId = "local")
    val endpoint = NearbyEndpoint("endpoint-b", "Phone B")

    advanceUntilIdle()
    transport.emit(TransportEvent.EndpointFound(endpoint))
    advanceUntilIdle()

    assertEquals(listOf(endpoint), viewModel.uiState.value.discoveredEndpoints)

    transport.emit(TransportEvent.Connected(endpoint))
    advanceUntilIdle()

    assertEquals(ConnectionStatus.Connected, viewModel.uiState.value.status)
    assertTrue(viewModel.uiState.value.discoveredEndpoints.isEmpty())
  }
}

private data class SentPayload(val endpointId: String, val bytes: ByteArray)

private class FakeChatTransport(
  private val autoCompleteSends: Boolean = true,
) : ChatTransport {
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)
  override val events: Flow<TransportEvent> = mutableEvents
  var advertisingStarted = false
    private set
  var advertisingStartCount = 0
    private set
  var discoveryStarted = false
    private set
  var discoveryStartCount = 0
    private set
  var discoveryStopped = false
    private set
  var sentPayloads: List<SentPayload> = emptyList()
    private set
  var requestedConnections: List<NearbyEndpoint> = emptyList()
    private set
  var acceptedConnections: List<String> = emptyList()
    private set
  var rejectedConnections: List<String> = emptyList()
    private set
  private val queuedSendResults = ArrayDeque<Result<Unit>>()
  private val pendingSendCallbacks = ArrayDeque<(Result<Unit>) -> Unit>()

  suspend fun emit(event: TransportEvent) {
    mutableEvents.emit(event)
  }

  fun clearSentPayloads() {
    sentPayloads = emptyList()
  }

  fun clearDiscoveryState() {
    discoveryStarted = false
    discoveryStartCount = 0
    discoveryStopped = false
  }

  fun queueSendResult(result: Result<Unit>) {
    queuedSendResults.addLast(result)
  }

  fun completeNextSend(result: Result<Unit>? = null) {
    pendingSendCallbacks.removeFirst().invoke(result ?: nextSendResult())
  }

  override fun startAdvertising(
    displayName: String,
    deviceId: String,
  ) {
    advertisingStarted = true
    advertisingStartCount += 1
  }

  override fun startDiscovery() {
    discoveryStarted = true
    discoveryStartCount += 1
    discoveryStopped = false
  }

  override fun stopDiscovery() {
    discoveryStopped = true
    discoveryStarted = false
  }

  override fun requestConnection(endpoint: NearbyEndpoint, displayName: String) {
    requestedConnections = requestedConnections + endpoint
  }

  override fun acceptConnection(endpointId: String) {
    acceptedConnections = acceptedConnections + endpointId
  }

  override fun rejectConnection(endpointId: String) {
    rejectedConnections = rejectedConnections + endpointId
  }

  override fun send(endpointId: String, bytes: ByteArray, onResult: (Result<Unit>) -> Unit) {
    sentPayloads = sentPayloads + SentPayload(endpointId, bytes)
    if (autoCompleteSends) {
      onResult(nextSendResult())
    } else {
      pendingSendCallbacks.addLast(onResult)
    }
  }

  override fun stopAll() = Unit

  private fun nextSendResult(): Result<Unit> =
    if (queuedSendResults.isEmpty()) {
      Result.success(Unit)
    } else {
      queuedSendResults.removeFirst()
    }
}

private class FakeChatHistoryRepository(
  private val initialMessages: List<ChatMessage> = emptyList(),
) : ChatHistoryRepository {
  var savedMessages: List<List<ChatMessage>> = emptyList()
    private set

  override fun loadMessages(): List<ChatMessage> = initialMessages

  override fun saveMessages(messages: List<ChatMessage>) {
    savedMessages = savedMessages + listOf(messages)
  }
}

package com.example.offlinelink.ui.main

import com.example.offlinelink.model.ConnectionStatus
import com.example.offlinelink.model.MessageKind
import com.example.offlinelink.model.MessageStatus
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.PendingConnection
import com.example.offlinelink.protocol.ChatProtocol
import com.example.offlinelink.protocol.DecodedWireMessage
import com.example.offlinelink.protocol.WireMember
import com.example.offlinelink.transport.ChatTransport
import com.example.offlinelink.transport.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

    viewModel.startDiscovery()

    assertEquals(ConnectionStatus.Discovering, viewModel.uiState.value.status)
    assertTrue(transport.discoveryStarted)
  }

  @Test
  fun connectedEventSendsHelloWithCurrentGroupName() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

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
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "device-a")

    advanceUntilIdle()
    transport.emit(TransportEvent.Connected(NearbyEndpoint("endpoint-b", "Phone B")))
    advanceUntilIdle()

    val hello = ChatProtocol.decode(transport.sentPayloads.single().bytes) as DecodedWireMessage.Hello
    assertEquals(listOf("device-a"), hello.members.map { it.id })
  }

  @Test
  fun setGroupNameWhileConnectedBroadcastsUpdatedHello() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

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
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

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
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "device-a")

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
  fun disconnectedRelayStartsAdvertisingWhenLocalDeviceIsLowestRemainingMember() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "device-b")

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
    assertEquals(ConnectionStatus.Advertising, viewModel.uiState.value.status)
    assertEquals(listOf("Phone C"), viewModel.uiState.value.groupMembers.map { it.displayName })
  }

  @Test
  fun disconnectedRelayStartsDiscoveryAndAutoConnectsWhenLocalDeviceIsNotLowestRemainingMember() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "device-c")

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

    assertTrue(transport.discoveryStarted)
    assertEquals(ConnectionStatus.Connecting, viewModel.uiState.value.status)
    assertEquals(listOf(NearbyEndpoint("endpoint-b", "Phone B")), transport.requestedConnections)
  }

  @Test
  fun recoveryModeAutomaticallyAcceptsIncomingConnectionRequest() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "device-b")

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
  fun duplicateDisconnectEventDoesNotRestartGroupRecovery() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "device-b")

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
    assertEquals(ConnectionStatus.Advertising, viewModel.uiState.value.status)
  }

  @Test
  fun alreadyAdvertisingFailureDoesNotReplaceRecoveryStatus() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "device-b")

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

    assertEquals(ConnectionStatus.Advertising, viewModel.uiState.value.status)
    assertEquals(null, viewModel.uiState.value.lastError)
  }

  @Test
  fun alreadyDiscoveringFailureDoesNotReplaceRecoveryStatus() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "device-c")

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
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

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
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

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
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

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
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

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
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

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
  fun sendMessageBroadcastsPayloadToEveryConnectedEndpoint() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

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
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

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
  fun incomingMessageIsForwardedToOtherConnectedEndpoints() = runTest {
    val transport = FakeChatTransport()
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

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
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")

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
    val viewModel = MainScreenViewModel(transport, requestLocation = { Result.success(com.example.offlinelink.location.DeviceLocation(1.0, 2.0, null)) }, compressImage = { Result.success(com.example.offlinelink.image.CompressedImage("", "image/jpeg", 1, 1)) }, localDeviceId = "local")
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

private class FakeChatTransport : ChatTransport {
  private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)
  override val events: Flow<TransportEvent> = mutableEvents
  var advertisingStarted = false
    private set
  var advertisingStartCount = 0
    private set
  var discoveryStarted = false
    private set
  var sentPayloads: List<SentPayload> = emptyList()
    private set
  var requestedConnections: List<NearbyEndpoint> = emptyList()
    private set
  var acceptedConnections: List<String> = emptyList()
    private set

  suspend fun emit(event: TransportEvent) {
    mutableEvents.emit(event)
  }

  fun clearSentPayloads() {
    sentPayloads = emptyList()
  }

  override fun startAdvertising(displayName: String) {
    advertisingStarted = true
    advertisingStartCount += 1
  }

  override fun startDiscovery() {
    discoveryStarted = true
  }

  override fun requestConnection(endpoint: NearbyEndpoint, displayName: String) {
    requestedConnections = requestedConnections + endpoint
  }

  override fun acceptConnection(endpointId: String) {
    acceptedConnections = acceptedConnections + endpointId
  }

  override fun rejectConnection(endpointId: String) = Unit

  override fun send(endpointId: String, bytes: ByteArray, onResult: (Result<Unit>) -> Unit) {
    sentPayloads = sentPayloads + SentPayload(endpointId, bytes)
    onResult(Result.success(Unit))
  }

  override fun stopAll() = Unit
}

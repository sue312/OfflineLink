package com.example.offlinelink.ui.main

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.offlinelink.audio.RecordedVoiceClip
import com.example.offlinelink.audio.VoicePlayer
import com.example.offlinelink.audio.VoiceRecorder
import com.example.offlinelink.image.ImageCompressor
import com.example.offlinelink.location.LocationHelper
import com.example.offlinelink.model.ChatMessage
import com.example.offlinelink.model.ChatUiState
import com.example.offlinelink.model.ConnectionStatus
import com.example.offlinelink.model.GroupMember
import com.example.offlinelink.model.ImageAttachment
import com.example.offlinelink.model.LocationAttachment
import com.example.offlinelink.model.MessageKind
import com.example.offlinelink.model.MessageStatus
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.VoiceAttachment
import com.example.offlinelink.permissions.requiredNearbyRuntimePermissions
import com.example.offlinelink.theme.MyApplicationTheme
import com.example.offlinelink.transport.NearbyChatTransport
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val locationHelper = remember(context) { LocationHelper(context.applicationContext) }
  val contentResolver = context.applicationContext.contentResolver
  val viewModel: MainScreenViewModel =
    viewModel { MainScreenViewModel(NearbyChatTransport(context.applicationContext), locationHelper::currentLocation, { uri -> ImageCompressor.compress(contentResolver, uri) }) }
  val voiceRecorder = remember(context) { VoiceRecorder(context.applicationContext) }
  val voicePlayer = remember(context) { VoicePlayer(context.applicationContext) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val requiredPermissions = remember { requiredNearbyRuntimePermissions() }
  var hasPermissions by remember {
    mutableStateOf(requiredPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
      hasPermissions = requiredPermissions.all { grants[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
  val imagePickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) viewModel.sendImage(uri) { }
    }
  DisposableEffect(voiceRecorder, voicePlayer) {
    onDispose {
      voiceRecorder.cancel()
      voicePlayer.stop()
    }
  }

  OfflineChatContent(
    state = state,
    hasPermissions = hasPermissions,
    onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
    onDisplayNameChange = viewModel::setDisplayName,
    onGroupNameChange = viewModel::setGroupName,
    onAdvertise = viewModel::startAdvertising,
    onDiscover = viewModel::startDiscovery,
    onConnect = viewModel::connectTo,
    onAccept = viewModel::acceptPendingConnection,
    onReject = viewModel::rejectPendingConnection,
    onSendMessage = viewModel::sendMessage,
    onSendVoiceMessage = viewModel::sendVoiceMessage,
    onSendLocation = viewModel::sendLocation,
    onStartVoiceRecording = voiceRecorder::start,
    onStopVoiceRecording = voiceRecorder::stop,
    onPlayVoice = voicePlayer::play,
    onDisconnect = viewModel::disconnect,
    onPickImage = { imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    modifier = modifier.fillMaxSize(),
  )
}

@Composable
private fun OfflineChatContent(
  state: ChatUiState,
  hasPermissions: Boolean,
  onRequestPermissions: () -> Unit,
  onDisplayNameChange: (String) -> Unit,
  onGroupNameChange: (String) -> Unit,
  onAdvertise: () -> Unit,
  onDiscover: () -> Unit,
  onConnect: (NearbyEndpoint) -> Unit,
  onAccept: () -> Unit,
  onReject: () -> Unit,
  onSendMessage: (String) -> Unit,
  onSendVoiceMessage: (ByteArray, Long, String) -> Unit,
  onSendLocation: ((Result<Unit>) -> Unit) -> Unit,
  onStartVoiceRecording: () -> Result<Unit>,
  onStopVoiceRecording: () -> Result<RecordedVoiceClip>,
  onPlayVoice: (VoiceAttachment) -> Result<Unit>,
  onDisconnect: () -> Unit,
  onPickImage: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var draft by remember { mutableStateOf("") }
  var isRecordingVoice by remember { mutableStateOf(false) }
  var isSendingLocation by remember { mutableStateOf(false) }
  var voiceError by remember { mutableStateOf<String?>(null) }
  val listState = rememberLazyListState()
  val keyboardController = LocalSoftwareKeyboardController.current
  val ctx = LocalContext.current

  LaunchedEffect(state.messageRevision) {
    if (state.messages.isNotEmpty()) {
      listState.animateScrollToItem(state.messages.lastIndex)
    }
  }
  val rootModifier =
    if (shouldApplyRootImePadding(windowResizesForKeyboard = true)) {
      modifier.imePadding()
    } else {
      modifier
    }

  Column(
    modifier = rootModifier,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Header(state = state)

    if (!hasPermissions) {
      PermissionPanel(onRequestPermissions)
    } else {
      ConnectionControls(
        state = state,
        onDisplayNameChange = onDisplayNameChange,
        onGroupNameChange = onGroupNameChange,
        onAdvertise = onAdvertise,
        onDiscover = onDiscover,
        onDisconnect = onDisconnect,
      )
      if (state.connectedEndpoints.isNotEmpty() || state.groupMembers.isNotEmpty()) {
        MembersPanel(
          localDisplayName = state.displayName,
          members = state.groupMembers,
        )
      }
      state.pendingConnection?.let { pending ->
        PendingConnectionPanel(
          title = pending.endpointName,
          token = pending.authenticationToken,
          onAccept = onAccept,
          onReject = onReject,
        )
      }
      if (state.status != ConnectionStatus.Connected) {
        EndpointList(
          endpoints = state.discoveredEndpoints,
          onConnect = onConnect,
        )
      }
      HorizontalDivider()
      MessageList(
        messages = state.messages,
        listState = listState,
        onPlayVoice = { voice ->
          onPlayVoice(voice).onFailure {
            voiceError = "Could not play voice message"
          }
        },
        onOpenMaps = { lat, lng ->
          val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
          ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
        },
        modifier = Modifier.weight(1f).fillMaxWidth(),
      )
      MessageComposer(
        draft = draft,
        enabled = state.status == ConnectionStatus.Connected,
        isRecordingVoice = isRecordingVoice,
        isSendingLocation = isSendingLocation,
        voiceError = voiceError,
        onDraftChange = { draft = it },
        onSend = {
          if (draft.isNotBlank()) {
            onSendMessage(draft)
            draft = ""
            keyboardController?.hide()
          }
        },
        onToggleVoice = {
          if (isRecordingVoice) {
            val result = onStopVoiceRecording()
            isRecordingVoice = false
            result
              .onSuccess { clip ->
                voiceError = null
                onSendVoiceMessage(clip.bytes, clip.durationMs, clip.mimeType)
              }
              .onFailure {
                voiceError = "Could not save voice message"
              }
          } else {
            onStartVoiceRecording()
              .onSuccess {
                isRecordingVoice = true
                voiceError = null
                keyboardController?.hide()
              }
              .onFailure {
                voiceError = "Could not start voice recording"
              }
          }
        },
        onSendLocation = {
          isSendingLocation = true
          onSendLocation { result ->
            isSendingLocation = false
            result.onFailure { e ->
              voiceError = e.message ?: "Could not send location"
            }
          }
        },
        onPickImage = onPickImage,
      )
    }
  }
}

@Composable
private fun Header(state: ChatUiState) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("OfflineLink", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.groupWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    state.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
  }
}

@Composable
private fun PermissionPanel(onRequestPermissions: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.errorContainer,
    shape = RoundedCornerShape(8.dp),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Nearby permissions are required before this phone can advertise or discover devices.")
      Button(onClick = onRequestPermissions) { Text("Grant permissions") }
    }
  }
}

@Composable
private fun ConnectionControls(
  state: ChatUiState,
  onDisplayNameChange: (String) -> Unit,
  onGroupNameChange: (String) -> Unit,
  onAdvertise: () -> Unit,
  onDiscover: () -> Unit,
  onDisconnect: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    OutlinedTextField(
      value = state.displayName,
      onValueChange = onDisplayNameChange,
      label = { Text("Display name") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = state.groupName,
      onValueChange = onGroupNameChange,
      label = { Text("Group name") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      Button(onClick = onAdvertise, enabled = state.status != ConnectionStatus.Connected, modifier = Modifier.weight(1f)) {
        Text("Visible")
      }
      Button(onClick = onDiscover, enabled = state.status != ConnectionStatus.Connected, modifier = Modifier.weight(1f)) {
        Text("Search")
      }
      OutlinedButton(onClick = onDisconnect, enabled = state.status == ConnectionStatus.Connected) {
        Text("Disconnect")
      }
    }
  }
}

@Composable
private fun MembersPanel(
  localDisplayName: String,
  members: List<GroupMember>,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Members (${members.size + 1})", fontWeight = FontWeight.SemiBold)
    Text("You - $localDisplayName", style = MaterialTheme.typography.bodyMedium)
    members.forEach { member ->
      Text(member.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun PendingConnectionPanel(
  title: String,
  token: String,
  onAccept: () -> Unit,
  onReject: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.primaryContainer,
    shape = RoundedCornerShape(8.dp),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Connection request from $title", fontWeight = FontWeight.SemiBold)
      Text("Code: $token", style = MaterialTheme.typography.bodyMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onAccept) { Text("Accept") }
        OutlinedButton(onClick = onReject) { Text("Reject") }
      }
    }
  }
}

@Composable
private fun EndpointList(
  endpoints: List<NearbyEndpoint>,
  onConnect: (NearbyEndpoint) -> Unit,
) {
  if (endpoints.isEmpty()) {
    Text("No nearby devices yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    return
  }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Nearby devices", fontWeight = FontWeight.SemiBold)
    endpoints.forEach { endpoint ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(endpoint.name, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onConnect(endpoint) }) { Text("Connect") }
      }
    }
  }
}

@Composable
private fun MessageList(
  messages: List<ChatMessage>,
  listState: LazyListState,
  onPlayVoice: (VoiceAttachment) -> Unit,
  onOpenMaps: (Double, Double) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier,
    state = listState,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(messages, key = { it.id }) { message ->
      MessageRow(message, onPlayVoice, onOpenMaps)
    }
  }
}

@Composable
private fun MessageRow(
  message: ChatMessage,
  onPlayVoice: (VoiceAttachment) -> Unit,
  onOpenMaps: (Double, Double) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (message.isLocal) Arrangement.End else Arrangement.Start,
  ) {
    Column(
      modifier =
        Modifier
          .background(
            color = if (message.isLocal) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
          )
          .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      when {
        message.kind == MessageKind.Image && message.image != null -> {
          Image(
            bitmap = decodeImageBitmap(message.image.imageBase64),
            contentDescription = "Shared image",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
              .widthIn(max = 280.dp)
              .heightIn(max = 280.dp),
          )
        }
        message.kind == MessageKind.Location && message.location != null -> {
          val loc = message.location
          Text("📍 Shared location", fontWeight = FontWeight.Medium)
          Text(
            "%.6f, %.6f".format(loc.latitude, loc.longitude),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          loc.accuracy?.let {
            Text("Accuracy: ${"%.1f".format(it)}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          OutlinedButton(onClick = { onOpenMaps(loc.latitude, loc.longitude) }) {
            Text("Open in Maps")
          }
        }
        message.voice != null -> {
          OutlinedButton(onClick = { onPlayVoice(message.voice) }) {
            Text(message.text)
          }
        }
        else -> {
          Text(message.text)
        }
      }
      Text(message.status.displayLabel(message.isLocal), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun MessageComposer(
  draft: String,
  enabled: Boolean,
  isRecordingVoice: Boolean,
  isSendingLocation: Boolean,
  voiceError: String?,
  onDraftChange: (String) -> Unit,
  onSend: () -> Unit,
  onToggleVoice: () -> Unit,
  onSendLocation: () -> Unit,
  onPickImage: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
    if (isRecordingVoice) {
      Text("Recording voice...", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
    if (isSendingLocation) {
      Text("Getting location...", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
    voiceError?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        enabled = enabled && !isRecordingVoice,
        placeholder = { Text("Type an offline message") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (enabled && draft.isNotBlank()) onSend() }),
        modifier = Modifier.weight(1f),
        maxLines = 3,
      )
      Spacer(Modifier.width(8.dp))
      OutlinedButton(onClick = onToggleVoice, enabled = enabled || isRecordingVoice) {
        Text(if (isRecordingVoice) "Stop" else "Voice")
      }
      Spacer(Modifier.width(8.dp))
      OutlinedButton(onClick = onSendLocation, enabled = enabled && !isRecordingVoice && !isSendingLocation) {
        Text(if (isSendingLocation) "..." else "📍")
      }
      Spacer(Modifier.width(8.dp))
      OutlinedButton(onClick = onPickImage, enabled = enabled && !isRecordingVoice && !isSendingLocation) {
        Text("🖼")
      }
      Spacer(Modifier.width(8.dp))
      Button(onClick = onSend, enabled = enabled && draft.isNotBlank() && !isRecordingVoice && !isSendingLocation, colors = ButtonDefaults.buttonColors()) {
        Text("Send")
      }
    }
  }
}

internal fun MessageStatus.displayLabel(isLocal: Boolean): String =
  when (this) {
    MessageStatus.Queued -> "queued"
    MessageStatus.Sent -> "sent"
    MessageStatus.Received -> if (isLocal) "delivered" else "received"
    MessageStatus.Failed -> "failed"
  }

@OptIn(ExperimentalEncodingApi::class)
private fun decodeImageBitmap(imageBase64: String): androidx.compose.ui.graphics.ImageBitmap {
  val bytes = Base64.Default.decode(imageBase64)
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
}

internal fun shouldApplyRootImePadding(windowResizesForKeyboard: Boolean): Boolean = !windowResizesForKeyboard

@Preview(showBackground = true)
@Composable
private fun OfflineChatContentPreview() {
  MyApplicationTheme {
    OfflineChatContent(
      state =
        ChatUiState(
          localDeviceId = "local",
          displayName = "Phone A",
          groupName = "Field Team",
          status = ConnectionStatus.Connected,
          statusMessage = "Connected to Phone B",
          groupWarning = "Group mismatch: Phone B uses Rescue Team",
          connectedEndpoints = listOf(NearbyEndpoint("b", "Phone B")),
          groupMembers = listOf(GroupMember("device-b", "Phone B"), GroupMember("device-c", "Phone C")),
          messages =
            listOf(
              ChatMessage("1", "one-to-one", "local", "Hello", 1L, MessageStatus.Received, true),
              ChatMessage("2", "one-to-one", "remote", "Hi from nearby", 2L, MessageStatus.Received, false),
            ),
        ),
      hasPermissions = true,
      onRequestPermissions = {},
      onDisplayNameChange = {},
      onGroupNameChange = {},
      onAdvertise = {},
      onDiscover = {},
      onConnect = {},
      onAccept = {},
      onReject = {},
      onSendMessage = {},
      onSendVoiceMessage = { _, _, _ -> },
      onSendLocation = { it(Result.success(Unit)) },
      onStartVoiceRecording = { Result.success(Unit) },
      onStopVoiceRecording = { Result.success(RecordedVoiceClip(byteArrayOf(1, 2, 3), 1000L)) },
      onPlayVoice = { Result.success(Unit) },
      onDisconnect = {},
      onPickImage = {},
      modifier = Modifier.fillMaxSize().padding(16.dp),
    )
  }
}

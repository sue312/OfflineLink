package com.example.offlinelink.ui.main

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
    viewModel {
      MainScreenViewModel(
        NearbyChatTransport(context.applicationContext),
        locationHelper::currentLocation,
        { uri -> ImageCompressor.compress(contentResolver, uri) },
      )
    }
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

  Surface(modifier = rootModifier, color = MaterialTheme.colorScheme.background) {
    Column(
      modifier = Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Header(state = state)

      if (!hasPermissions) {
        PermissionPanel(onRequestPermissions, modifier = Modifier.fillMaxWidth())
      } else {
        Alerts(groupWarning = state.groupWarning, lastError = state.lastError)
        ConnectionConsole(
          state = state,
          onDisplayNameChange = onDisplayNameChange,
          onGroupNameChange = onGroupNameChange,
          onAdvertise = onAdvertise,
          onDiscover = onDiscover,
          onDisconnect = onDisconnect,
        )
        if (state.connectedEndpoints.isNotEmpty() || state.groupMembers.isNotEmpty()) {
          MembersPanel(localDisplayName = state.displayName, members = state.groupMembers)
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
          EndpointList(endpoints = state.discoveredEndpoints, onConnect = onConnect)
        }
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
}

@Composable
private fun Header(state: ChatUiState) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
      Text("OfflineLink", style = MaterialTheme.typography.headlineMedium)
      Text("Nearby chat without internet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    StatusChip(status = state.status)
  }
}

@Composable
private fun StatusChip(status: ConnectionStatus) {
  val containerColor =
    when (status) {
      ConnectionStatus.Connected -> MaterialTheme.colorScheme.primaryContainer
      ConnectionStatus.Advertising, ConnectionStatus.Discovering, ConnectionStatus.Connecting -> MaterialTheme.colorScheme.secondaryContainer
      ConnectionStatus.Error -> MaterialTheme.colorScheme.errorContainer
      else -> MaterialTheme.colorScheme.surfaceVariant
    }
  val dotColor =
    when (status) {
      ConnectionStatus.Connected -> MaterialTheme.colorScheme.primary
      ConnectionStatus.Advertising, ConnectionStatus.Discovering, ConnectionStatus.Connecting -> MaterialTheme.colorScheme.secondary
      ConnectionStatus.Error -> MaterialTheme.colorScheme.error
      else -> MaterialTheme.colorScheme.outline
    }

  Surface(color = containerColor, shape = CircleShape) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
      horizontalArrangement = Arrangement.spacedBy(7.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(modifier = Modifier.size(7.dp).background(dotColor, CircleShape))
      Text(status.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
  }
}

@Composable
private fun Alerts(
  groupWarning: String?,
  lastError: String?,
) {
  groupWarning?.let { AlertBanner(text = it, isError = false) }
  lastError?.let { AlertBanner(text = it, isError = true) }
}

@Composable
private fun AlertBanner(
  text: String,
  isError: Boolean,
) {
  val container = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
  val content = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
  Surface(
    color = container,
    contentColor = content,
    shape = RoundedCornerShape(8.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp))
      Text(text, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun PermissionPanel(
  onRequestPermissions: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    color = MaterialTheme.colorScheme.errorContainer,
    contentColor = MaterialTheme.colorScheme.onErrorContainer,
    shape = RoundedCornerShape(8.dp),
  ) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Lock, contentDescription = null)
        Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
      }
      Text("Nearby, Bluetooth, location, and microphone access are required before this phone can find devices or send voice messages.")
      Button(onClick = onRequestPermissions, shape = RoundedCornerShape(8.dp)) { Text("Grant permissions") }
    }
  }
}

@Composable
private fun ConnectionConsole(
  state: ChatUiState,
  onDisplayNameChange: (String) -> Unit,
  onGroupNameChange: (String) -> Unit,
  onAdvertise: () -> Unit,
  onDiscover: () -> Unit,
  onDisconnect: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("Link setup", style = MaterialTheme.typography.titleMedium)
          Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
          Text("Group", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
        }
      }

      OutlinedTextField(
        value = state.displayName,
        onValueChange = onDisplayNameChange,
        label = { Text("Display name") },
        leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = state.groupName,
        onValueChange = onGroupNameChange,
        label = { Text("Group name") },
        leadingIcon = { Icon(Icons.Rounded.Groups, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
          onClick = onAdvertise,
          enabled = state.status != ConnectionStatus.Connected,
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        ) {
          Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Visible")
        }
        FilledTonalButton(
          onClick = onDiscover,
          enabled = state.status != ConnectionStatus.Connected,
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        ) {
          Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Search")
        }
        OutlinedButton(
          onClick = onDisconnect,
          enabled = state.status == ConnectionStatus.Connected,
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        ) {
          Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Leave")
        }
      }
    }
  }
}

@Composable
private fun MembersPanel(
  localDisplayName: String,
  members: List<GroupMember>,
) {
  val visibleMembers = members.take(3)
  val overflow = members.size - visibleMembers.size

  Surface(
    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    shape = RoundedCornerShape(8.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(Icons.Rounded.Groups, contentDescription = null, modifier = Modifier.size(20.dp))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("Members (${members.size + 1})", style = MaterialTheme.typography.titleMedium)
        Text("You: $localDisplayName", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Avatar(localDisplayName, color = MaterialTheme.colorScheme.primary)
      visibleMembers.forEach { member ->
        Avatar(member.displayName, color = MaterialTheme.colorScheme.secondary)
      }
      if (overflow > 0) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = CircleShape) {
          Text("+$overflow", modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
        }
      }
    }
  }
}

@Composable
private fun Avatar(
  name: String,
  color: Color,
) {
  Surface(shape = CircleShape, color = color, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp)) {
    Box(contentAlignment = Alignment.Center) {
      Text(name.trim().take(1).ifEmpty { "?" }.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
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
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    shape = RoundedCornerShape(8.dp),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Connection request from $title", style = MaterialTheme.typography.titleMedium)
      Text("Confirm code: $token", style = MaterialTheme.typography.bodyMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onAccept, shape = RoundedCornerShape(8.dp)) {
          Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Accept")
        }
        OutlinedButton(onClick = onReject, shape = RoundedCornerShape(8.dp)) {
          Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Reject")
        }
      }
    }
  }
}

@Composable
private fun EndpointList(
  endpoints: List<NearbyEndpoint>,
  onConnect: (NearbyEndpoint) -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
      if (endpoints.isEmpty()) {
        Text("No devices found yet. Keep this screen open while another phone taps Search or Visible.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else {
        endpoints.forEach { endpoint ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Avatar(endpoint.name, color = MaterialTheme.colorScheme.secondary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
              Text(endpoint.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
              Text("Ready to pair", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { onConnect(endpoint) }, shape = RoundedCornerShape(8.dp)) { Text("Connect") }
          }
        }
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
    contentPadding = PaddingValues(vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (messages.isEmpty()) {
      item { EmptyChatState() }
    } else {
      items(messages, key = { it.id }) { message ->
        MessageRow(message, onPlayVoice, onOpenMaps)
      }
    }
  }
}

@Composable
private fun EmptyChatState() {
  Surface(
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text("No messages yet", style = MaterialTheme.typography.titleMedium)
      Text(
        "Connect nearby phones, then send text, voice, location, or an image.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun MessageRow(
  message: ChatMessage,
  onPlayVoice: (VoiceAttachment) -> Unit,
  onOpenMaps: (Double, Double) -> Unit,
) {
  val isLocal = message.isLocal
  val bubbleColor = if (isLocal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
  val contentColor = if (isLocal) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
  val border = if (isLocal) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
  val attachmentButtonBorder = BorderStroke(1.dp, contentColor.copy(alpha = 0.42f))
  val attachmentButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor)

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isLocal) Arrangement.End else Arrangement.Start,
  ) {
    Surface(
      color = bubbleColor,
      contentColor = contentColor,
      shape = messageBubbleShape(isLocal),
      border = border,
      modifier = Modifier.widthIn(max = 312.dp),
    ) {
      Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
          message.kind == MessageKind.Image && message.image != null -> {
            val imageBitmap = remember(message.image.imageBase64) { decodeImageBitmap(message.image.imageBase64) }
            Image(
              bitmap = imageBitmap,
              contentDescription = "Shared image",
              contentScale = ContentScale.Crop,
              modifier =
                Modifier
                  .widthIn(max = 280.dp)
                  .heightIn(min = 120.dp, max = 260.dp)
                  .clip(RoundedCornerShape(8.dp)),
            )
          }
          message.kind == MessageKind.Location && message.location != null -> {
            val loc = message.location
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Rounded.Place, contentDescription = null, modifier = Modifier.size(18.dp))
              Text("Shared location", fontWeight = FontWeight.Medium)
            }
            Text("%.6f, %.6f".format(loc.latitude, loc.longitude), style = MaterialTheme.typography.bodyMedium)
            loc.accuracy?.let {
              Text("Accuracy: ${"%.1f".format(it)}m", style = MaterialTheme.typography.labelMedium, color = contentColor.copy(alpha = 0.72f))
            }
            OutlinedButton(
              onClick = { onOpenMaps(loc.latitude, loc.longitude) },
              shape = RoundedCornerShape(8.dp),
              border = attachmentButtonBorder,
              colors = attachmentButtonColors,
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
              Icon(Icons.Rounded.Place, contentDescription = null, modifier = Modifier.size(17.dp))
              Spacer(Modifier.width(6.dp))
              Text("Open Maps")
            }
          }
          message.voice != null -> {
            OutlinedButton(
              onClick = { onPlayVoice(message.voice) },
              shape = RoundedCornerShape(8.dp),
              border = attachmentButtonBorder,
              colors = attachmentButtonColors,
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
              Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Voice ${formatDuration(message.voice.durationMs)}")
            }
          }
          else -> {
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
          }
        }
        Text(message.status.displayLabel(message.isLocal), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.68f))
      }
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
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(8.dp)) {
      if (isRecordingVoice) {
        Text("Recording voice...", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      }
      if (isSendingLocation) {
        Text("Getting location...", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      }
      voiceError?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = draft,
          onValueChange = onDraftChange,
          enabled = enabled && !isRecordingVoice,
          placeholder = { Text("Type an offline message") },
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(onSend = { if (enabled && draft.isNotBlank()) onSend() }),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.weight(1f),
          maxLines = 3,
        )
        ComposerActionButton(
          icon = if (isRecordingVoice) Icons.Rounded.Stop else Icons.Rounded.Mic,
          contentDescription = if (isRecordingVoice) "Stop voice recording" else "Record voice",
          enabled = enabled || isRecordingVoice,
          selected = isRecordingVoice,
          onClick = onToggleVoice,
        )
        ComposerActionButton(
          icon = Icons.Rounded.Place,
          contentDescription = "Send location",
          enabled = enabled && !isRecordingVoice && !isSendingLocation,
          onClick = onSendLocation,
        )
        ComposerActionButton(
          icon = Icons.Rounded.Image,
          contentDescription = "Send image",
          enabled = enabled && !isRecordingVoice && !isSendingLocation,
          onClick = onPickImage,
        )
        Button(
          onClick = onSend,
          enabled = enabled && draft.isNotBlank() && !isRecordingVoice && !isSendingLocation,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.height(50.dp),
          contentPadding = PaddingValues(horizontal = 13.dp),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
          Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
        }
      }
    }
  }
}

@Composable
private fun ComposerActionButton(
  icon: ImageVector,
  contentDescription: String,
  enabled: Boolean,
  selected: Boolean = false,
  onClick: () -> Unit,
) {
  val container =
    when {
      selected -> MaterialTheme.colorScheme.errorContainer
      else -> MaterialTheme.colorScheme.surfaceVariant
    }
  val content =
    when {
      !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
      selected -> MaterialTheme.colorScheme.error
      else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

  Surface(shape = CircleShape, color = container, contentColor = content) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(46.dp)) {
      Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(21.dp))
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

private fun ConnectionStatus.label(): String =
  when (this) {
    ConnectionStatus.Idle -> "Ready"
    ConnectionStatus.Advertising -> "Visible"
    ConnectionStatus.Discovering -> "Searching"
    ConnectionStatus.Connecting -> "Pairing"
    ConnectionStatus.Connected -> "Online"
    ConnectionStatus.Disconnected -> "Offline"
    ConnectionStatus.Error -> "Issue"
  }

private fun messageBubbleShape(isLocal: Boolean): RoundedCornerShape =
  RoundedCornerShape(
    topStart = 8.dp,
    topEnd = 8.dp,
    bottomStart = if (isLocal) 8.dp else 2.dp,
    bottomEnd = if (isLocal) 2.dp else 8.dp,
  )

private fun formatDuration(durationMs: Long): String {
  val totalSeconds = (durationMs / 1000L).coerceAtLeast(1L)
  val minutes = totalSeconds / 60L
  val seconds = totalSeconds % 60L
  return if (minutes == 0L) {
    "${seconds}s"
  } else {
    "$minutes:${seconds.toString().padStart(2, '0')}"
  }
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
      modifier = Modifier.fillMaxSize(),
    )
  }
}

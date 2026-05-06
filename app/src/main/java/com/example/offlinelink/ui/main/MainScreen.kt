package com.example.offlinelink.ui.main

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.example.offlinelink.data.JsonChatHistoryRepository
import com.example.offlinelink.image.ImageCompressor
import com.example.offlinelink.location.LocationHelper
import com.example.offlinelink.model.CallState
import com.example.offlinelink.model.CallStatus
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
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.delay

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val locationHelper = remember(context) { LocationHelper(context.applicationContext) }
  val contentResolver = context.applicationContext.contentResolver
  val historyRepository =
    remember(context) {
      JsonChatHistoryRepository(File(context.applicationContext.filesDir, "offline-link-chat-history.json"))
    }
  val defaultDisplayName = remember { defaultDeviceDisplayName(Build.MODEL) }
  val viewModel: MainScreenViewModel =
    viewModel {
      MainScreenViewModel(
        NearbyChatTransport(context.applicationContext),
        locationHelper::currentLocation,
        { uri -> ImageCompressor.compress(contentResolver, uri) },
        defaultDisplayName = defaultDisplayName,
        historyRepository = historyRepository,
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
    onSendCallVoiceMessage = viewModel::sendCallVoiceMessage,
    onSendLocation = viewModel::sendLocation,
    onStartVoiceRecording = voiceRecorder::start,
    onStopVoiceRecording = voiceRecorder::stop,
    onPlayVoice = voicePlayer::play,
    onDisconnect = viewModel::disconnect,
    onStartCall = viewModel::startCall,
    onAcceptCall = viewModel::acceptCall,
    onRejectCall = viewModel::rejectCall,
    onEndCall = viewModel::endCall,
    onFinishCallVoicePlayback = viewModel::finishCallVoicePlayback,
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
  onSendCallVoiceMessage: (ByteArray, Long, String) -> Unit,
  onSendLocation: ((Result<Unit>) -> Unit) -> Unit,
  onStartVoiceRecording: () -> Result<Unit>,
  onStopVoiceRecording: () -> Result<RecordedVoiceClip>,
  onPlayVoice: (VoiceAttachment) -> Result<Unit>,
  onDisconnect: () -> Unit,
  onStartCall: () -> Unit,
  onAcceptCall: () -> Unit,
  onRejectCall: () -> Unit,
  onEndCall: () -> Unit,
  onFinishCallVoicePlayback: (String) -> Unit,
  onPickImage: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var draft by remember { mutableStateOf("") }
  var isRecordingVoice by remember { mutableStateOf(false) }
  var isRecordingCallVoice by remember { mutableStateOf(false) }
  var isSendingLocation by remember { mutableStateOf(false) }
  var voiceError by remember { mutableStateOf<String?>(null) }
  var isSetupExpanded by rememberSaveable { mutableStateOf(defaultSetupExpanded(state.messages.size)) }
  val listState = rememberLazyListState()
  val keyboardController = LocalSoftwareKeyboardController.current
  val ctx = LocalContext.current

  LaunchedEffect(state.messageRevision) {
    if (state.messages.isNotEmpty()) {
      listState.animateScrollToItem(state.messages.lastIndex)
    }
  }

  LaunchedEffect(state.pendingConnection?.endpointId, state.callState.status) {
    if (state.pendingConnection != null || state.callState.status != CallStatus.Idle) {
      isSetupExpanded = true
    }
  }

  LaunchedEffect(state.callPlayback?.clipId) {
    val playback = state.callPlayback ?: return@LaunchedEffect
    onPlayVoice(VoiceAttachment(playback.audioBase64, playback.durationMs, playback.mimeType))
      .onFailure {
        voiceError = "Could not play call voice"
      }
    delay(playback.durationMs.coerceIn(700L, 10_000L) + 250L)
    onFinishCallVoicePlayback(playback.clipId)
  }

  val rootModifier =
    if (shouldApplyRootImePadding(windowResizesForKeyboard = true)) {
      modifier.imePadding()
    } else {
      modifier
    }

  Surface(modifier = rootModifier, color = MaterialTheme.colorScheme.background) {
    Column(
      modifier = Modifier.fillMaxSize().padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Header(state = state)

      if (!hasPermissions) {
        PermissionPanel(onRequestPermissions, modifier = Modifier.fillMaxWidth())
      } else {
        Alerts(groupWarning = state.groupWarning, lastError = state.lastError)
        SetupDisclosure(
          state = state,
          expanded = isSetupExpanded,
          onToggle = { isSetupExpanded = !isSetupExpanded },
          onDisplayNameChange = onDisplayNameChange,
          onGroupNameChange = onGroupNameChange,
          onAdvertise = onAdvertise,
          onDiscover = onDiscover,
          onDisconnect = onDisconnect,
          onStartCall = onStartCall,
          onConnect = onConnect,
          onAccept = onAccept,
          onReject = onReject,
        )
        if (state.callState.status != CallStatus.Idle) {
          CallPanel(
            callState = state.callState,
            isRecordingCallVoice = isRecordingCallVoice,
            recordingEnabled = !isRecordingVoice,
            onAcceptCall = onAcceptCall,
            onRejectCall = onRejectCall,
            onEndCall = {
              if (isRecordingCallVoice) {
                onStopVoiceRecording()
                isRecordingCallVoice = false
              }
              onEndCall()
            },
            onToggleTalk = {
              if (isRecordingCallVoice) {
                val result = onStopVoiceRecording()
                isRecordingCallVoice = false
                result
                  .onSuccess { clip ->
                    voiceError = null
                    onSendCallVoiceMessage(clip.bytes, clip.durationMs, clip.mimeType)
                  }
                  .onFailure {
                    voiceError = "Could not save call voice"
                  }
              } else if (!isRecordingVoice) {
                onStartVoiceRecording()
                  .onSuccess {
                    isRecordingCallVoice = true
                    voiceError = null
                    keyboardController?.hide()
                  }
                  .onFailure {
                    voiceError = "Could not start voice recording"
                  }
              }
            },
          )
        }
        MessageList(
          messages = state.messages,
          localDeviceId = state.localDeviceId,
          localDisplayName = state.displayName,
          groupMembers = state.groupMembers,
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
          enabled = state.status == ConnectionStatus.Connected && !isRecordingCallVoice,
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
    Text("OfflineLink", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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
private fun SetupDisclosure(
  state: ChatUiState,
  expanded: Boolean,
  onToggle: () -> Unit,
  onDisplayNameChange: (String) -> Unit,
  onGroupNameChange: (String) -> Unit,
  onAdvertise: () -> Unit,
  onDiscover: () -> Unit,
  onDisconnect: () -> Unit,
  onStartCall: () -> Unit,
  onConnect: (NearbyEndpoint) -> Unit,
  onAccept: () -> Unit,
  onReject: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().animateContentSize(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    SetupToggleHeader(state = state, expanded = expanded, onToggle = onToggle)
    AnimatedVisibility(visible = expanded) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.status == ConnectionStatus.Connected) {
          ConnectedSummary(state = state, onDisconnect = onDisconnect, onStartCall = onStartCall)
        } else {
          ConnectionConsole(
            state = state,
            onDisplayNameChange = onDisplayNameChange,
            onGroupNameChange = onGroupNameChange,
            onAdvertise = onAdvertise,
            onDiscover = onDiscover,
            onDisconnect = onDisconnect,
          )
        }
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
      }
    }
  }
}

@Composable
private fun SetupToggleHeader(
  state: ChatUiState,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  val dotColor =
    when (state.status) {
      ConnectionStatus.Connected -> MaterialTheme.colorScheme.primary
      ConnectionStatus.Advertising, ConnectionStatus.Discovering, ConnectionStatus.Connecting -> MaterialTheme.colorScheme.secondary
      ConnectionStatus.Error -> MaterialTheme.colorScheme.error
      else -> MaterialTheme.colorScheme.outline
    }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clickable(onClick = onToggle)
          .padding(horizontal = 12.dp, vertical = 9.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("Setup", style = MaterialTheme.typography.titleMedium)
        Text(setupSummaryText(state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
      }
      Icon(
        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
        contentDescription = if (expanded) "Collapse setup" else "Expand setup",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
      )
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
    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("Link setup", style = MaterialTheme.typography.titleMedium)
          Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = state.displayName,
          onValueChange = onDisplayNameChange,
          label = { Text("Name") },
          leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
          singleLine = true,
          textStyle = MaterialTheme.typography.bodyMedium,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          value = state.groupName,
          onValueChange = onGroupNameChange,
          label = { Text("Group") },
          leadingIcon = { Icon(Icons.Rounded.Groups, contentDescription = null, modifier = Modifier.size(18.dp)) },
          singleLine = true,
          textStyle = MaterialTheme.typography.bodyMedium,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.weight(1f),
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        ConnectionActionButton(
          icon = Icons.Rounded.Visibility,
          label = "Visible",
          onClick = onAdvertise,
          enabled = state.status != ConnectionStatus.Connected,
          selected = state.status == ConnectionStatus.Advertising,
          modifier = Modifier.weight(1f),
        )
        ConnectionActionButton(
          icon = Icons.Rounded.Search,
          label = "Search",
          onClick = onDiscover,
          enabled = state.status != ConnectionStatus.Connected,
          selected = state.status == ConnectionStatus.Discovering,
          modifier = Modifier.weight(1f),
        )
        ConnectionActionButton(
          icon = Icons.Rounded.Close,
          label = "Leave",
          onClick = onDisconnect,
          enabled = state.status == ConnectionStatus.Connected,
          selected = false,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun ConnectionActionButton(
  icon: ImageVector,
  label: String,
  enabled: Boolean,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val container =
    when {
      selected -> MaterialTheme.colorScheme.primary
      enabled -> MaterialTheme.colorScheme.surfaceVariant
      else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
  val content =
    when {
      selected -> MaterialTheme.colorScheme.onPrimary
      enabled -> MaterialTheme.colorScheme.onSurfaceVariant
      else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }

  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Surface(color = container, contentColor = content, shape = CircleShape) {
      IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(42.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
      }
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
  }
}

@Composable
private fun ConnectedSummary(
  state: ChatUiState,
  onDisconnect: () -> Unit,
  onStartCall: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary, shape = CircleShape) {
        Icon(Icons.Rounded.Groups, contentDescription = null, modifier = Modifier.padding(8.dp).size(18.dp))
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(state.statusMessage, style = MaterialTheme.typography.titleMedium)
        Text("Group: ${state.groupName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      ComposerActionButton(
        icon = Icons.Rounded.Call,
        contentDescription = "Start call",
        enabled = state.callState.status == CallStatus.Idle,
        primary = state.callState.status == CallStatus.Idle,
        onClick = onStartCall,
      )
      OutlinedButton(
        onClick = onDisconnect,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
      ) {
        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(5.dp))
        Text("Leave")
      }
    }
  }
}

@Composable
private fun CallPanel(
  callState: CallState,
  isRecordingCallVoice: Boolean,
  recordingEnabled: Boolean,
  onAcceptCall: () -> Unit,
  onRejectCall: () -> Unit,
  onEndCall: () -> Unit,
  onToggleTalk: () -> Unit,
) {
  val peerName = callState.peerName ?: "Nearby device"
  val title =
    when (callState.status) {
      CallStatus.Incoming -> "Incoming call"
      CallStatus.Outgoing -> "Calling"
      CallStatus.Active -> "In call"
      CallStatus.Idle -> "Call"
    }
  val subtitle =
    when {
      isRecordingCallVoice -> "Recording..."
      callState.activityLabel != null -> callState.activityLabel
      callState.status == CallStatus.Active -> peerName
      callState.status == CallStatus.Outgoing -> peerName
      callState.status == CallStatus.Incoming -> peerName
      else -> ""
    }
  val container =
    when (callState.status) {
      CallStatus.Incoming -> MaterialTheme.colorScheme.tertiaryContainer
      else -> MaterialTheme.colorScheme.surface
    }
  val accent =
    when (callState.status) {
      CallStatus.Incoming -> MaterialTheme.colorScheme.tertiary
      else -> MaterialTheme.colorScheme.secondary
    }
  val accentContent =
    when (callState.status) {
      CallStatus.Incoming -> MaterialTheme.colorScheme.onTertiary
      else -> MaterialTheme.colorScheme.onSecondary
    }

  Surface(
    color = container,
    contentColor = MaterialTheme.colorScheme.onSurface,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(color = accent, contentColor = accentContent, shape = CircleShape) {
        Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.padding(8.dp).size(18.dp))
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
      }
      when (callState.status) {
        CallStatus.Incoming -> {
          Button(
            onClick = onAcceptCall,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
          ) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text("Accept")
          }
          OutlinedButton(
            onClick = onRejectCall,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
          ) {
            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text("Reject")
          }
        }
        CallStatus.Outgoing -> {
          OutlinedButton(
            onClick = onEndCall,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
          ) {
            Icon(Icons.Rounded.CallEnd, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text("Cancel")
          }
        }
        CallStatus.Active -> {
          ComposerActionButton(
            icon = if (isRecordingCallVoice) Icons.Rounded.Stop else Icons.Rounded.Mic,
            contentDescription = if (isRecordingCallVoice) "Stop call voice" else "Record call voice",
            enabled = recordingEnabled || isRecordingCallVoice,
            selected = isRecordingCallVoice,
            primary = !isRecordingCallVoice,
            onClick = onToggleTalk,
          )
          OutlinedButton(
            onClick = onEndCall,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
          ) {
            Icon(Icons.Rounded.CallEnd, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text("End")
          }
        }
        CallStatus.Idle -> Unit
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
      Text(avatarInitials(name), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
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
  localDeviceId: String,
  localDisplayName: String,
  groupMembers: List<GroupMember>,
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
        val senderName =
          senderDisplayName(
            senderId = message.senderId,
            isLocal = message.isLocal,
            localDeviceId = localDeviceId,
            groupMembers = groupMembers,
          )
        val avatarName = if (message.isLocal) localDisplayName else senderName
        MessageRow(message, senderName, avatarName, onPlayVoice, onOpenMaps)
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
  senderName: String,
  avatarName: String,
  onPlayVoice: (VoiceAttachment) -> Unit,
  onOpenMaps: (Double, Double) -> Unit,
) {
  val isLocal = message.isLocal
  val isAttachment = message.kind != MessageKind.Text
  val bubbleColor =
    when {
      isLocal && isAttachment -> MaterialTheme.colorScheme.surface
      isLocal -> MaterialTheme.colorScheme.primaryContainer
      else -> MaterialTheme.colorScheme.surface
    }
  val contentColor = MaterialTheme.colorScheme.onSurface
  val accentColor = if (isLocal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
  val border = BorderStroke(1.dp, if (isLocal) MaterialTheme.colorScheme.primary.copy(alpha = 0.32f) else MaterialTheme.colorScheme.outlineVariant)
  val attachmentButtonBorder = BorderStroke(1.dp, contentColor.copy(alpha = 0.42f))
  val attachmentButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor)

  if (message.kind == MessageKind.Image && message.image != null) {
    ImageMessageRow(message = message, isLocal = isLocal, senderName = senderName, avatarName = avatarName)
    return
  }

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isLocal) Arrangement.End else Arrangement.Start,
    verticalAlignment = Alignment.Bottom,
  ) {
    if (!isLocal) {
      MessageAvatar(name = avatarName, color = MaterialTheme.colorScheme.secondary)
      Spacer(Modifier.width(7.dp))
    }
    Column(horizontalAlignment = if (isLocal) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(senderName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Surface(
        color = bubbleColor,
        contentColor = contentColor,
        shape = messageBubbleShape(isLocal),
        border = border,
        modifier = Modifier.widthIn(max = 300.dp),
      ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          when {
            message.kind == MessageKind.Location && message.location != null -> {
              LocationMessageContent(
                latitude = message.location.latitude,
                longitude = message.location.longitude,
                accuracy = message.location.accuracy,
                contentColor = contentColor,
                accentColor = accentColor,
                onOpenMaps = onOpenMaps,
              )
            }
            message.voice != null -> {
              VoiceMessageContent(
                durationMs = message.voice.durationMs,
                contentColor = contentColor,
                accentColor = accentColor,
                border = attachmentButtonBorder,
                colors = attachmentButtonColors,
                onPlay = { onPlayVoice(message.voice) },
              )
            }
            else -> {
              Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
          }
          Text(message.status.displayLabel(message.isLocal), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.68f))
        }
      }
    }
    if (isLocal) {
      Spacer(Modifier.width(7.dp))
      MessageAvatar(name = avatarName, color = MaterialTheme.colorScheme.primary)
    }
  }
}

@Composable
private fun ImageMessageRow(
  message: ChatMessage,
  isLocal: Boolean,
  senderName: String,
  avatarName: String,
) {
  val image = message.image ?: return
  val imageBitmap = remember(image.imageBase64) { decodeImageBitmap(image.imageBase64) }
  val sourceWidth = image.width.coerceAtLeast(1)
  val sourceHeight = image.height.coerceAtLeast(1)
  val aspect = sourceWidth.toFloat() / sourceHeight.toFloat()
  val previewWidth: Float
  val previewHeight: Float
  if (aspect < 0.75f) {
    previewWidth = 188f
    previewHeight = 250f
  } else {
    previewWidth = 238f
    previewHeight = (previewWidth / aspect).coerceIn(116f, 250f)
  }

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isLocal) Arrangement.End else Arrangement.Start,
    verticalAlignment = Alignment.Bottom,
  ) {
    if (!isLocal) {
      MessageAvatar(name = avatarName, color = MaterialTheme.colorScheme.secondary)
      Spacer(Modifier.width(7.dp))
    }
    Column(horizontalAlignment = if (isLocal) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(senderName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Image(
        bitmap = imageBitmap,
        contentDescription = "Shared image",
        contentScale = ContentScale.Crop,
        modifier =
          Modifier
            .width(previewWidth.dp)
            .height(previewHeight.dp)
            .clip(messageBubbleShape(isLocal)),
      )
      Text(
        message.status.displayLabel(message.isLocal),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    }
    if (isLocal) {
      Spacer(Modifier.width(7.dp))
      MessageAvatar(name = avatarName, color = MaterialTheme.colorScheme.primary)
    }
  }
}

@Composable
private fun MessageAvatar(
  name: String,
  color: Color,
) {
  Surface(shape = CircleShape, color = color, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp)) {
    Box(contentAlignment = Alignment.Center) {
      Text(avatarInitials(name), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun VoiceMessageContent(
  durationMs: Long,
  contentColor: Color,
  accentColor: Color,
  border: BorderStroke,
  colors: ButtonColors,
  onPlay: () -> Unit,
) {
  OutlinedButton(
    onClick = onPlay,
    shape = RoundedCornerShape(8.dp),
    border = border,
    colors = colors,
    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 7.dp),
    modifier = Modifier.width(194.dp),
  ) {
    Surface(color = accentColor, contentColor = MaterialTheme.colorScheme.onPrimary, shape = CircleShape, modifier = Modifier.size(28.dp)) {
      Box(contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
      }
    }
    Spacer(Modifier.width(9.dp))
    Waveform(color = accentColor, modifier = Modifier.weight(1f))
    Spacer(Modifier.width(9.dp))
    Text(formatDuration(durationMs), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun Waveform(
  color: Color,
  modifier: Modifier = Modifier,
) {
  val heights = listOf(10, 18, 13, 23, 15, 20, 11, 17, 24, 14, 19, 12)
  Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
    heights.forEachIndexed { index, height ->
      Box(
        modifier =
          Modifier
            .width(3.dp)
            .height(height.dp)
            .background(color.copy(alpha = if (index % 3 == 0) 0.82f else 0.52f), CircleShape),
      )
    }
  }
}

@Composable
private fun LocationMessageContent(
  latitude: Double,
  longitude: Double,
  accuracy: Float?,
  contentColor: Color,
  accentColor: Color,
  onOpenMaps: (Double, Double) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(238.dp)) {
    Surface(color = accentColor.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(86.dp)) {
      Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val gridColor = accentColor.copy(alpha = 0.13f)
          val roadColor = accentColor.copy(alpha = 0.24f)
          for (i in 1..3) {
            val x = size.width * i / 4f
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
          }
          for (i in 1..2) {
            val y = size.height * i / 3f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y))
          }
          drawLine(roadColor, Offset(0f, size.height * 0.7f), Offset(size.width, size.height * 0.38f), strokeWidth = 8f)
          drawLine(roadColor, Offset(size.width * 0.22f, 0f), Offset(size.width * 0.68f, size.height), strokeWidth = 5f)
        }
        Surface(color = accentColor, contentColor = MaterialTheme.colorScheme.onPrimary, shape = CircleShape) {
          Icon(Icons.Rounded.Place, contentDescription = null, modifier = Modifier.padding(8.dp).size(20.dp))
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Rounded.Place, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("Shared location", style = MaterialTheme.typography.titleMedium)
        Text("%.5f, %.5f".format(latitude, longitude), style = MaterialTheme.typography.labelMedium, color = contentColor.copy(alpha = 0.75f))
        accuracy?.let {
          Text("Accuracy ${"%.0f".format(it)}m", style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.62f))
        }
      }
    }
    OutlinedButton(
      onClick = { onOpenMaps(latitude, longitude) },
      shape = RoundedCornerShape(8.dp),
      border = BorderStroke(1.dp, accentColor.copy(alpha = 0.42f)),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor),
      contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
    ) {
      Text("Open Maps", style = MaterialTheme.typography.labelMedium)
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
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(6.dp)) {
      if (isRecordingVoice) {
        Text("Recording voice...", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      }
      if (isSendingLocation) {
        Text("Getting location...", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      }
      voiceError?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        CompactMessageField(
          value = draft,
          onValueChange = onDraftChange,
          enabled = enabled && !isRecordingVoice,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
          keyboardActions = KeyboardActions(onSend = { if (enabled && draft.isNotBlank()) onSend() }),
          modifier = Modifier.weight(1f),
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
        ComposerActionButton(
          icon = Icons.AutoMirrored.Rounded.Send,
          contentDescription = "Send message",
          enabled = enabled && draft.isNotBlank() && !isRecordingVoice && !isSendingLocation,
          primary = true,
          onClick = onSend,
        )
      }
    }
  }
}

@Composable
private fun CompactMessageField(
  value: String,
  onValueChange: (String) -> Unit,
  enabled: Boolean,
  keyboardOptions: KeyboardOptions,
  keyboardActions: KeyboardActions,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onSurface,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = modifier.height(42.dp),
  ) {
    BasicTextField(
      value = value,
      onValueChange = onValueChange,
      enabled = enabled,
      singleLine = true,
      textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant),
      keyboardOptions = keyboardOptions,
      keyboardActions = keyboardActions,
      cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
      decorationBox = { innerTextField ->
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 13.dp), contentAlignment = Alignment.CenterStart) {
          if (value.isEmpty()) {
            Text("Message", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f), maxLines = 1)
          }
          innerTextField()
        }
      },
    )
  }
}

@Composable
private fun ComposerActionButton(
  icon: ImageVector,
  contentDescription: String,
  enabled: Boolean,
  selected: Boolean = false,
  primary: Boolean = false,
  onClick: () -> Unit,
) {
  val container =
    when {
      primary && enabled -> MaterialTheme.colorScheme.primary
      selected -> MaterialTheme.colorScheme.errorContainer
      else -> MaterialTheme.colorScheme.surfaceVariant
    }
  val content =
    when {
      !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
      primary -> MaterialTheme.colorScheme.onPrimary
      selected -> MaterialTheme.colorScheme.error
      else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

  Surface(shape = CircleShape, color = container, contentColor = content) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
      Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(17.dp))
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

internal fun senderDisplayName(
  senderId: String,
  isLocal: Boolean,
  localDeviceId: String,
  groupMembers: List<GroupMember>,
): String {
  if (isLocal || senderId == localDeviceId) return "You"
  return groupMembers.firstOrNull { it.id == senderId }?.displayName
    ?: "Device ${senderId.take(4).uppercase().ifBlank { "?" }}"
}

internal fun avatarInitials(name: String): String {
  val cleaned = name.trim()
  if (cleaned.isEmpty()) return "?"
  val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
  return if (words.size >= 2) {
    words.take(2).joinToString("") { it.take(1) }.uppercase()
  } else {
    cleaned.take(1).uppercase()
  }
}

internal fun defaultDeviceDisplayName(modelName: String): String =
  modelName.trim().ifBlank { "OfflineLink" }

internal fun defaultSetupExpanded(messageCount: Int): Boolean = messageCount == 0

internal fun setupSummaryText(state: ChatUiState): String {
  val memberCount = state.groupMembers.size + 1
  return when {
    state.pendingConnection != null -> "Request from ${state.pendingConnection.endpointName}"
    state.status == ConnectionStatus.Connected -> "${state.statusMessage} - ${countLabel(memberCount, "member")}"
    state.discoveredEndpoints.isNotEmpty() -> "${countLabel(state.discoveredEndpoints.size, "nearby device")} - ${state.statusMessage}"
    state.groupMembers.isNotEmpty() -> "${countLabel(memberCount, "member")} - ${state.statusMessage}"
    else -> state.statusMessage
  }
}

private fun countLabel(
  count: Int,
  singular: String,
): String = "$count $singular${if (count == 1) "" else "s"}"

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
      onSendCallVoiceMessage = { _, _, _ -> },
      onSendLocation = { it(Result.success(Unit)) },
      onStartVoiceRecording = { Result.success(Unit) },
      onStopVoiceRecording = { Result.success(RecordedVoiceClip(byteArrayOf(1, 2, 3), 1000L)) },
      onPlayVoice = { Result.success(Unit) },
      onDisconnect = {},
      onStartCall = {},
      onAcceptCall = {},
      onRejectCall = {},
      onEndCall = {},
      onFinishCallVoicePlayback = {},
      onPickImage = {},
      modifier = Modifier.fillMaxSize(),
    )
  }
}

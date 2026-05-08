package com.example.offlinelink.ui.main

import android.bluetooth.BluetoothManager
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.offlinelink.audio.CallAudioFrame
import com.example.offlinelink.audio.CallAudioLinkStats
import com.example.offlinelink.audio.CallAudioProcessingMode
import com.example.offlinelink.audio.CallAudioStream
import com.example.offlinelink.audio.CallTonePlayer
import com.example.offlinelink.audio.RecordedVoiceClip
import com.example.offlinelink.audio.VoicePlayer
import com.example.offlinelink.audio.VoiceRecorder
import com.example.offlinelink.audio.callAudioProcessingModeFromName
import com.example.offlinelink.data.JsonChatHistoryRepository
import com.example.offlinelink.data.PayloadCache
import com.example.offlinelink.image.ImageCompressor
import com.example.offlinelink.location.LocationHelper
import com.example.offlinelink.model.CallAudioPlaybackFrame
import com.example.offlinelink.model.CallState
import com.example.offlinelink.model.CallStatus
import com.example.offlinelink.model.ChatMessage
import com.example.offlinelink.model.ChatUiState
import com.example.offlinelink.model.ConnectionStatus
import com.example.offlinelink.model.GroupMember
import com.example.offlinelink.model.GroupMemberStatus
import com.example.offlinelink.model.MessageKind
import com.example.offlinelink.model.MessageStatus
import com.example.offlinelink.model.NearbyEndpoint
import com.example.offlinelink.model.VoiceAttachment
import com.example.offlinelink.model.callToneModeFor
import com.example.offlinelink.permissions.requiredNearbyRuntimePermissions
import com.example.offlinelink.service.OfflineKeepAliveService
import com.example.offlinelink.theme.MyApplicationTheme
import com.example.offlinelink.transport.NearbyChatTransport
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LocalPayloadCache = androidx.compose.runtime.staticCompositionLocalOf<PayloadCache> {
  error("No PayloadCache provided")
}

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
  val payloadCache = remember(context) { PayloadCache(context.applicationContext.filesDir) }
  val preferences = remember(context) { context.applicationContext.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE) }
  val defaultDisplayName =
    remember(preferences) {
      preferences.getString(KEY_DISPLAY_NAME, null)?.trim()?.takeIf { it.isNotEmpty() }
        ?: defaultDeviceDisplayName(Build.MODEL)
    }
  val defaultAvatarName =
    remember(preferences) {
      preferences.getString(KEY_AVATAR_NAME, null).orEmpty()
    }
  val viewModel: MainScreenViewModel =
    viewModel {
      MainScreenViewModel(
        NearbyChatTransport(context.applicationContext),
        locationHelper::currentLocation,
        { uri -> ImageCompressor.compress(contentResolver, uri) },
        defaultDisplayName = defaultDisplayName,
        defaultAvatarName = defaultAvatarName,
        historyRepository = historyRepository,
        payloadCache = payloadCache,
      )
    }
  val voiceRecorder = remember(context) { VoiceRecorder(context.applicationContext) }
  val voicePlayer = remember(context) { VoicePlayer(context.applicationContext) }
  val callAudioStream = remember(context) { CallAudioStream(context.applicationContext) }
  val callTonePlayer = remember(context) { CallTonePlayer(context.applicationContext) }
  var callAudioProcessingMode by rememberSaveable {
    mutableStateOf(callAudioProcessingModeFromName(preferences.getString(KEY_CALL_AUDIO_PROCESSING_MODE, null)))
  }
  var callAudioDiagnosticsEnabled by rememberSaveable {
    mutableStateOf(preferences.getBoolean(KEY_CALL_AUDIO_DIAGNOSTICS_ENABLED, false))
  }
  val callAudioDiagnosticsPath = remember(callAudioStream) { callAudioStream.diagnosticsDirectoryPath() }
  val callAudioLinkStats = callAudioStream.linkStats()
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
  DisposableEffect(voiceRecorder, voicePlayer, callAudioStream, callTonePlayer) {
    onDispose {
      voiceRecorder.cancel()
      voicePlayer.stop()
      callAudioStream.stop()
      callTonePlayer.release()
    }
  }
  LaunchedEffect(callAudioProcessingMode, callAudioStream) {
    callAudioStream.setProcessingMode(callAudioProcessingMode)
  }
  LaunchedEffect(callAudioDiagnosticsEnabled, callAudioStream) {
    callAudioStream.setDiagnosticsEnabled(callAudioDiagnosticsEnabled)
  }
  LaunchedEffect(state.callState.status) {
    callTonePlayer.play(callToneModeFor(state.callState.status))
  }
  LaunchedEffect(state.connectedEndpoints.isNotEmpty(), state.callState.status) {
    val shouldKeepAlive = state.connectedEndpoints.isNotEmpty() || state.callState.status != CallStatus.Idle
    if (shouldKeepAlive) {
      val message = if (state.callState.status == CallStatus.Idle) "Connected to nearby devices" else "Call active"
      runCatching { OfflineKeepAliveService.start(context.applicationContext, message) }
    } else {
      runCatching { OfflineKeepAliveService.stop(context.applicationContext) }
    }
  }
  DisposableEffect(context) {
    onDispose {
      runCatching { OfflineKeepAliveService.stop(context.applicationContext) }
    }
  }

  androidx.compose.runtime.CompositionLocalProvider(LocalPayloadCache provides payloadCache) {
    OfflineChatContent(
    state = state,
    callAudioFrames = viewModel.callAudioFrames,
    hasPermissions = hasPermissions,
    onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
    onDisplayNameChange = { displayName ->
      preferences.edit().putString(KEY_DISPLAY_NAME, displayName).apply()
      viewModel.setDisplayName(displayName)
    },
    onAvatarNameChange = { avatarName ->
      preferences.edit().putString(KEY_AVATAR_NAME, avatarName).apply()
      viewModel.setAvatarName(avatarName)
    },
    onDiscover = viewModel::startDiscovery,
    onConnect = viewModel::connectTo,
    onAccept = viewModel::acceptPendingConnection,
    onReject = viewModel::rejectPendingConnection,
    onSendMessage = viewModel::sendMessage,
    onSendVoiceMessage = viewModel::sendVoiceMessage,
    onSendCallVoiceMessage = viewModel::sendCallVoiceMessage,
    onStartCallAudio = callAudioStream::start,
    onStopCallAudio = callAudioStream::stop,
    onPlayCallAudio = callAudioStream::play,
    onSetCallMuted = callAudioStream::setMuted,
    onSetSpeakerEnabled = callAudioStream::setSpeakerEnabled,
    onSendLocation = viewModel::sendLocation,
    onStartVoiceRecording = voiceRecorder::start,
    onStopVoiceRecording = voiceRecorder::stop,
    onPlayVoice = { voice ->
      val bytes = payloadCache.get(voice.payloadKey)
      if (bytes != null) voicePlayer.play(bytes)
      else Result.failure(IllegalStateException("Voice payload not found"))
    },
    onDisconnect = viewModel::disconnect,
    onStartCall = viewModel::startCall,
    onAcceptCall = viewModel::acceptCall,
    onRejectCall = viewModel::rejectCall,
    onEndCall = viewModel::endCall,
    onFinishCallVoicePlayback = viewModel::finishCallVoicePlayback,
    onRetryMessage = viewModel::retryMessage,
    onDeleteMessage = viewModel::deleteMessage,
    onClearMessages = viewModel::clearMessages,
    diagnostics =
      diagnosticsFor(
        context = context,
        hasPermissions = hasPermissions,
        state = state,
        callAudioProcessingMode = callAudioProcessingMode,
        callAudioDiagnosticsEnabled = callAudioDiagnosticsEnabled,
        callAudioDiagnosticsPath = callAudioDiagnosticsPath,
        callAudioLinkStats = callAudioLinkStats,
      ),
    callAudioProcessingMode = callAudioProcessingMode,
    onCallAudioProcessingModeChange = { mode ->
      callAudioProcessingMode = mode
      preferences.edit().putString(KEY_CALL_AUDIO_PROCESSING_MODE, mode.name).apply()
      callAudioStream.setProcessingMode(mode)
    },
    callAudioDiagnosticsEnabled = callAudioDiagnosticsEnabled,
    onCallAudioDiagnosticsEnabledChange = { enabled ->
      callAudioDiagnosticsEnabled = enabled
      preferences.edit().putBoolean(KEY_CALL_AUDIO_DIAGNOSTICS_ENABLED, enabled).apply()
      callAudioStream.setDiagnosticsEnabled(enabled)
    },
    callAudioDiagnosticsPath = callAudioDiagnosticsPath,
    onPickImage = { imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    modifier = modifier.fillMaxSize(),
    )
  }
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun OfflineChatContent(
  state: ChatUiState,
  callAudioFrames: Flow<CallAudioPlaybackFrame>,
  hasPermissions: Boolean,
  onRequestPermissions: () -> Unit,
  onDisplayNameChange: (String) -> Unit,
  onAvatarNameChange: (String) -> Unit,
  onDiscover: () -> Unit,
  onConnect: (NearbyEndpoint) -> Unit,
  onAccept: () -> Unit,
  onReject: () -> Unit,
  onSendMessage: (String) -> Unit,
  onSendVoiceMessage: (ByteArray, Long, String) -> Unit,
  onSendCallVoiceMessage: (ByteArray, Long, String) -> Unit,
  onStartCallAudio: ((CallAudioFrame) -> Unit) -> Result<Unit>,
  onStopCallAudio: () -> Unit,
  onPlayCallAudio: (CallAudioFrame) -> Result<Unit>,
  onSetCallMuted: (Boolean) -> Unit,
  onSetSpeakerEnabled: (Boolean) -> Unit,
  onSendLocation: ((Result<Unit>) -> Unit) -> Unit,
  onStartVoiceRecording: () -> Result<Unit>,
  onStopVoiceRecording: () -> Result<RecordedVoiceClip>,
  onPlayVoice: (VoiceAttachment) -> Result<Unit>,
  onDisconnect: () -> Unit,
  onStartCall: (String?) -> Unit,
  onAcceptCall: () -> Unit,
  onRejectCall: () -> Unit,
  onEndCall: () -> Unit,
  onFinishCallVoicePlayback: (String) -> Unit,
  onRetryMessage: (String) -> Unit,
  onDeleteMessage: (String) -> Unit,
  onClearMessages: () -> Unit,
  diagnostics: List<DiagnosticItem>,
  callAudioProcessingMode: CallAudioProcessingMode,
  onCallAudioProcessingModeChange: (CallAudioProcessingMode) -> Unit,
  callAudioDiagnosticsEnabled: Boolean,
  onCallAudioDiagnosticsEnabledChange: (Boolean) -> Unit,
  callAudioDiagnosticsPath: String,
  onPickImage: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var draft by remember { mutableStateOf("") }
  var isRecordingVoice by remember { mutableStateOf(false) }
  var isCallAudioLive by remember { mutableStateOf(false) }
  var isCallMuted by rememberSaveable { mutableStateOf(false) }
  var isSpeakerOn by rememberSaveable { mutableStateOf(true) }
  var isSendingLocation by remember { mutableStateOf(false) }
  var voiceError by remember { mutableStateOf<String?>(null) }
  var isSetupExpanded by rememberSaveable { mutableStateOf(defaultSetupExpanded(state.messages.size)) }
  var showSettings by rememberSaveable { mutableStateOf(false) }
  var previewImageMessage by remember { mutableStateOf<ChatMessage?>(null) }
  val listState = rememberLazyListState()
  val imageBitmapCache = remember { Base64DecodedImageCache<ImageBitmap>(maxEntries = IMAGE_BITMAP_CACHE_SIZE) }
  val keyboardController = LocalSoftwareKeyboardController.current
  val ctx = LocalContext.current
  val coroutineScope = rememberCoroutineScope()

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

  LaunchedEffect(state.callState.status, state.callState.callId) {
    if (state.callState.status == CallStatus.Active) {
      onStartCallAudio { frame ->
        coroutineScope.launch {
          onSendCallVoiceMessage(frame.bytes, frame.durationMs, frame.mimeType)
        }
      }
        .onSuccess {
          isCallAudioLive = true
          voiceError = null
          keyboardController?.hide()
        }
        .onFailure { e ->
          isCallAudioLive = false
          voiceError = e.message ?: "Could not start call audio"
        }
    } else {
      onStopCallAudio()
      isCallAudioLive = false
      isCallMuted = false
    }
  }

  LaunchedEffect(isCallMuted) {
    onSetCallMuted(isCallMuted)
  }

  LaunchedEffect(isSpeakerOn) {
    onSetSpeakerEnabled(isSpeakerOn)
  }

  DisposableEffect(Unit) {
    onDispose {
      onStopCallAudio()
    }
  }

  LaunchedEffect(callAudioFrames) {
    callAudioFrames.collect { frame ->
      val result =
        withContext(Dispatchers.IO) {
          onPlayCallAudio(CallAudioFrame(frame.audioBytes, frame.durationMs, frame.mimeType, frame.sequenceNumber))
        }
      result.onFailure {
        voiceError = "Could not play call voice"
      }
    }
  }

  LaunchedEffect(state.callPlayback?.clipId) {
    val playback = state.callPlayback ?: return@LaunchedEffect
    val callAudioBytes = Base64.Default.decode(playback.audioBase64)
    onPlayCallAudio(CallAudioFrame(callAudioBytes, playback.durationMs, playback.mimeType))
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

  CallFullScreenEffect(enabled = shouldUseFullScreenCallUi(state.callState.status))

  Surface(modifier = rootModifier, color = MaterialTheme.colorScheme.background) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .safeDrawingPadding()
          .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Header(state = state, onOpenSettings = { showSettings = true })

      if (!hasPermissions) {
        PermissionPanel(onRequestPermissions, modifier = Modifier.fillMaxWidth())
      } else {
        Alerts(lastError = state.lastError)
        SetupDisclosure(
          state = state,
          expanded = isSetupExpanded,
          onToggle = { isSetupExpanded = !isSetupExpanded },
          onDiscover = onDiscover,
          onDisconnect = onDisconnect,
          onStartCall = onStartCall,
          onConnect = onConnect,
          onAccept = onAccept,
          onReject = onReject,
        )
        MessageList(
          messages = state.messages,
          localDeviceId = state.localDeviceId,
          localDisplayName = state.displayName,
          localAvatarName = state.avatarName,
          groupMembers = state.groupMembers,
          listState = listState,
          imageBitmapCache = imageBitmapCache,
          onRetryMessage = onRetryMessage,
          onDeleteMessage = onDeleteMessage,
          onPreviewImage = { previewImageMessage = it },
          onPlayVoice = { voice ->
            onPlayVoice(voice).onFailure {
              voiceError = "Could not play voice message"
            }
          },
          onOpenMaps = { lat, lng ->
            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
            runCatching {
              ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }.onFailure {
              voiceError = "Could not open maps"
            }
          },
          modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        MessageComposer(
          draft = draft,
          enabled = state.status == ConnectionStatus.Connected && !isCallAudioLive,
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
    if (state.callState.status != CallStatus.Idle) {
      CallPanel(
        callState = state.callState,
        isCallAudioLive = isCallAudioLive,
        isCallMuted = isCallMuted,
        isSpeakerOn = isSpeakerOn,
        onToggleMute = { isCallMuted = !isCallMuted },
        onToggleSpeaker = { isSpeakerOn = !isSpeakerOn },
        onAcceptCall = onAcceptCall,
        onRejectCall = onRejectCall,
        onEndCall = {
          onStopCallAudio()
          isCallAudioLive = false
          onEndCall()
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
    if (showSettings) {
      SettingsDialog(
        state = state,
        diagnostics = diagnostics,
        callAudioProcessingMode = callAudioProcessingMode,
        onCallAudioProcessingModeChange = onCallAudioProcessingModeChange,
        callAudioDiagnosticsEnabled = callAudioDiagnosticsEnabled,
        onCallAudioDiagnosticsEnabledChange = onCallAudioDiagnosticsEnabledChange,
        callAudioDiagnosticsPath = callAudioDiagnosticsPath,
        onDisplayNameChange = onDisplayNameChange,
        onAvatarNameChange = onAvatarNameChange,
        onClearMessages = onClearMessages,
        onDismiss = { showSettings = false },
      )
    }
    previewImageMessage?.let { message ->
      ImagePreviewDialog(
        message = message,
        imageBitmapCache = imageBitmapCache,
        onDismiss = { previewImageMessage = null },
      )
    }
  }
}

@Composable
private fun Header(
  state: ChatUiState,
  onOpenSettings: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text("OfflineLink", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    IconButton(onClick = onOpenSettings, modifier = Modifier.size(38.dp)) {
      Icon(Icons.Rounded.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp))
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
private fun Alerts(lastError: String?) {
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
private fun SettingsDialog(
  state: ChatUiState,
  diagnostics: List<DiagnosticItem>,
  callAudioProcessingMode: CallAudioProcessingMode,
  onCallAudioProcessingModeChange: (CallAudioProcessingMode) -> Unit,
  callAudioDiagnosticsEnabled: Boolean,
  onCallAudioDiagnosticsEnabledChange: (Boolean) -> Unit,
  callAudioDiagnosticsPath: String,
  onDisplayNameChange: (String) -> Unit,
  onAvatarNameChange: (String) -> Unit,
  onClearMessages: () -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(
      color = MaterialTheme.colorScheme.surface,
      contentColor = MaterialTheme.colorScheme.onSurface,
      shape = RoundedCornerShape(8.dp),
      modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 620.dp),
    ) {
      Column(
        modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
          Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
          IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
          }
        }
        OutlinedTextField(
          value = state.displayName,
          onValueChange = onDisplayNameChange,
          label = { Text("Display name") },
          leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
          singleLine = true,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = state.avatarName,
          onValueChange = onAvatarNameChange,
          label = { Text("Avatar label") },
          leadingIcon = { Avatar(state.avatarName.ifBlank { state.displayName }, color = MaterialTheme.colorScheme.primary) },
          singleLine = true,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.fillMaxWidth(),
        )
        SettingsSection(title = "Call audio") {
          CallAudioModePicker(
            selectedMode = callAudioProcessingMode,
            onModeSelected = onCallAudioProcessingModeChange,
          )
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text("WAV diagnostics", style = MaterialTheme.typography.bodyMedium)
              Text(
                text = if (callAudioDiagnosticsEnabled) "Saving next calls to app files." else "Capture raw, processed, and received call audio.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            OutlinedButton(
              onClick = { onCallAudioDiagnosticsEnabledChange(!callAudioDiagnosticsEnabled) },
              shape = RoundedCornerShape(8.dp),
            ) {
              Text(if (callAudioDiagnosticsEnabled) "On" else "Off")
            }
          }
          Text(
            text = callAudioDiagnosticsPath,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        SettingsSection(title = "Diagnostics") {
          diagnostics.forEach { item ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
              Text(item.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(item.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
          }
        }
        SettingsSection(title = "History") {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text("${state.messages.size} messages", style = MaterialTheme.typography.bodyMedium)
              Text("Clear only this phone's local history.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onClearMessages, shape = RoundedCornerShape(8.dp), enabled = state.messages.isNotEmpty()) {
              Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(5.dp))
              Text("Clear")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun CallAudioModePicker(
  selectedMode: CallAudioProcessingMode,
  onModeSelected: (CallAudioProcessingMode) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    CallAudioProcessingMode.entries.forEach { mode ->
      val selected = mode == selectedMode
      if (selected) {
        Button(
          onClick = { onModeSelected(mode) },
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          CallAudioModeContent(mode = mode, selected = true)
        }
      } else {
        OutlinedButton(
          onClick = { onModeSelected(mode) },
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          CallAudioModeContent(mode = mode, selected = false)
        }
      }
    }
  }
}

@Composable
private fun CallAudioModeContent(
  mode: CallAudioProcessingMode,
  selected: Boolean,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(mode.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
      Text(mode.description, style = MaterialTheme.typography.labelMedium)
    }
    if (selected) {
      Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
    }
  }
}

@Composable
private fun CallFullScreenEffect(enabled: Boolean) {
  val view = LocalView.current
  DisposableEffect(enabled, view) {
    val window = view.context.findActivity()?.window
    if (window == null) {
      onDispose {}
    } else {
      val controller = WindowCompat.getInsetsController(window, view)
      val previousFlags = window.attributes.flags
      val previousCutoutMode = window.attributes.layoutInDisplayCutoutMode
      val previousStatusBarColor = window.statusBarColor
      val previousNavigationBarColor = window.navigationBarColor
      val previousSystemUiVisibility = window.decorView.systemUiVisibility
      val previousLightStatusBars = controller.isAppearanceLightStatusBars
      val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
      if (enabled) {
        val attrs = window.attributes
        attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = attrs
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        window.decorView.systemUiVisibility =
          previousSystemUiVisibility or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
      onDispose {
        if (enabled) {
          controller.show(WindowInsetsCompat.Type.systemBars())
          controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
          controller.isAppearanceLightStatusBars = previousLightStatusBars
          controller.isAppearanceLightNavigationBars = previousLightNavigationBars
          window.statusBarColor = previousStatusBarColor
          window.navigationBarColor = previousNavigationBarColor
          window.decorView.systemUiVisibility = previousSystemUiVisibility
          if (previousFlags and WindowManager.LayoutParams.FLAG_FULLSCREEN == 0) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
          } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
          }
          val attrs = window.attributes
          attrs.layoutInDisplayCutoutMode = previousCutoutMode
          window.attributes = attrs
        }
      }
    }
  }
}

private fun Context.findActivity(): Activity? {
  var current = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return current as? Activity
}

@Composable
private fun SettingsSection(
  title: String,
  content: @Composable () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.background,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      content()
    }
  }
}

@Composable
private fun ImagePreviewDialog(
  message: ChatMessage,
  imageBitmapCache: Base64DecodedImageCache<ImageBitmap>,
  onDismiss: () -> Unit,
) {
  val image = message.image ?: return
  val payloadCache = LocalPayloadCache.current
  val imageBitmap = rememberDecodedImageBitmap(image.payloadKey, payloadCache, imageBitmapCache)
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f), modifier = Modifier.fillMaxSize()) {
      Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Image(
          bitmap = imageBitmap,
          contentDescription = "Shared image preview",
          contentScale = ContentScale.Fit,
          modifier = Modifier.fillMaxSize(),
        )
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.align(Alignment.TopEnd)) {
          IconButton(onClick = onDismiss, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Close preview")
          }
        }
      }
    }
  }
}

@Composable
private fun SetupDisclosure(
  state: ChatUiState,
  expanded: Boolean,
  onToggle: () -> Unit,
  onDiscover: () -> Unit,
  onDisconnect: () -> Unit,
  onStartCall: (String?) -> Unit,
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
          MembersPanel(
            state = state,
            onDisconnect = onDisconnect,
            onStartCall = onStartCall,
          )
        } else {
          ConnectionConsole(
            state = state,
            onDiscover = onDiscover,
          )
          if (state.connectedEndpoints.isNotEmpty() || state.groupMembers.isNotEmpty()) {
            MembersPanel(state = state)
          }
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
        Text(setupHeaderTitle(state), style = MaterialTheme.typography.titleMedium)
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
  onDiscover: () -> Unit,
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

      connectionSetupActionLabels(state.status).forEach { label ->
        if (label == "Search") {
          ConnectionActionButton(
            icon = Icons.Rounded.Search,
            label = label,
            onClick = onDiscover,
            enabled = state.status != ConnectionStatus.Connected,
            selected = state.status == ConnectionStatus.Advertising || state.status == ConnectionStatus.Discovering,
            modifier = Modifier.fillMaxWidth(),
          )
        }
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
  contentDescription: String = label,
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
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
      }
    }
    val labelColor =
      if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
      }
    if (label.isNotEmpty()) {
      Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor, maxLines = 1, softWrap = false)
    }
  }
}

@Composable
private fun CallPanel(
  callState: CallState,
  isCallAudioLive: Boolean,
  isCallMuted: Boolean,
  isSpeakerOn: Boolean,
  onToggleMute: () -> Unit,
  onToggleSpeaker: () -> Unit,
  onAcceptCall: () -> Unit,
  onRejectCall: () -> Unit,
  onEndCall: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val peerName = callState.peerName ?: "Nearby device"
  var now by remember { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(callState.status, callState.startedAt) {
    while (callState.status == CallStatus.Active && callState.startedAt != null) {
      now = System.currentTimeMillis()
      delay(1_000L)
    }
  }
  val durationLabel =
    callState.startedAt
      ?.takeIf { callState.status == CallStatus.Active }
      ?.let { formatCallDuration(now - it) }
  val primaryText = Color(0xFFF4F1EA)
  val secondaryText = Color(0xB8F4F1EA)
  val quietText = Color(0x99F4F1EA)
  val sideButtonColor = Color(0x29F4F1EA)
  val activeSideButtonColor = Color(0x3DF4F1EA)

  Box(
    modifier =
      modifier
        .background(callScreenBackground())
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(horizontal = 34.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(top = 112.dp, bottom = 42.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = peerName,
        style = MaterialTheme.typography.displaySmall,
        color = primaryText,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(10.dp))
      Text(
        text = callScreenStatusLabel(callState.status),
        style = MaterialTheme.typography.titleMedium,
        color = secondaryText,
        textAlign = TextAlign.Center,
      )
      if (callState.status == CallStatus.Active) {
        Spacer(Modifier.height(10.dp))
        Text(
          text = durationLabel ?: "0:00",
          style = MaterialTheme.typography.titleLarge,
          color = primaryText,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(72.dp))
        Row(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.Rounded.Lock, contentDescription = null, tint = quietText, modifier = Modifier.size(22.dp))
          Text(
            text = if (isCallAudioLive) callNetworkQualityLabel() else "OfflineLink / Connecting audio",
            style = MaterialTheme.typography.titleMedium,
            color = quietText,
            textAlign = TextAlign.Center,
          )
        }
      }

      Spacer(Modifier.weight(1f))

      if (callState.status == CallStatus.Incoming) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CallControlButton(
            icon = Icons.Rounded.CallEnd,
            contentDescription = "Reject call",
            containerColor = Color(0xFFE94B4E),
            contentColor = primaryText,
            size = 72.dp,
            iconSize = 30.dp,
            onClick = onRejectCall,
          )
          CallControlButton(
            icon = Icons.Rounded.Call,
            contentDescription = "Accept call",
            containerColor = Color(0xFF25C064),
            contentColor = primaryText,
            size = 72.dp,
            iconSize = 30.dp,
            onClick = onAcceptCall,
          )
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CallControlButton(
            icon = if (isSpeakerOn) Icons.AutoMirrored.Rounded.VolumeUp else Icons.Rounded.Hearing,
            contentDescription = callOutputRouteLabel(isSpeakerOn),
            containerColor = if (isSpeakerOn) activeSideButtonColor else sideButtonColor,
            contentColor = primaryText,
            onClick = onToggleSpeaker,
          )
          CallControlButton(
            icon = Icons.Rounded.CallEnd,
            contentDescription = "End call",
            containerColor = Color(0xFFE94B4E),
            contentColor = primaryText,
            size = 80.dp,
            iconSize = 34.dp,
            onClick = onEndCall,
          )
          CallControlButton(
            icon = if (isCallMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
            contentDescription = if (isCallMuted) "Muted" else "Mic",
            containerColor = if (isCallMuted) activeSideButtonColor else sideButtonColor,
            contentColor = primaryText,
            onClick = onToggleMute,
          )
        }
      }
    }
  }
}

@Composable
private fun CallControlButton(
  icon: ImageVector,
  contentDescription: String,
  containerColor: Color,
  contentColor: Color,
  onClick: () -> Unit,
  size: Dp = 58.dp,
  iconSize: Dp = 27.dp,
) {
  Surface(
    color = containerColor,
    contentColor = contentColor,
    shape = CircleShape,
    modifier = Modifier.size(size),
  ) {
    IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
      Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize))
    }
  }
}

private fun callScreenBackground(): Brush =
  Brush.linearGradient(
    colors =
      listOf(
        Color(0xFF7B766A),
        Color(0xFF626259),
        Color(0xFF444A43),
      ),
    start = Offset(0f, 0f),
    end = Offset(900f, 1600f),
  )

@Composable
private fun MembersPanel(
  state: ChatUiState,
  onDisconnect: (() -> Unit)? = null,
  onStartCall: ((String?) -> Unit)? = null,
) {
  var callMenuExpanded by remember { mutableStateOf(false) }
  val peer = state.connectedEndpoints.firstOrNull()
  val peerName = peer?.name ?: state.groupMembers.firstOrNull()?.displayName ?: "Not connected"
  val canShowActions = onDisconnect != null && onStartCall != null
  val callTargets = if (canShowActions) callTargetOptions(state) else emptyList()
  val canStartCall = canShowActions && state.callState.status == CallStatus.Idle && state.connectedEndpoints.isNotEmpty() && callTargets.isNotEmpty()

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
      Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(20.dp))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("Peer", style = MaterialTheme.typography.titleMedium)
        Text(peerName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
      }
      Avatar(state.avatarName.ifBlank { state.displayName }, color = MaterialTheme.colorScheme.primary)
      peerName.takeIf { it != "Not connected" }?.let {
        Avatar(it, color = MaterialTheme.colorScheme.tertiary)
      }
      if (canShowActions) {
        Box {
          ComposerActionButton(
            icon = Icons.Rounded.Call,
            contentDescription = "Start call",
            enabled = canStartCall,
            primary = canStartCall,
            onClick = {
              if (callTargets.size <= 1) {
                onStartCall(callTargets.firstOrNull()?.id)
              } else {
                callMenuExpanded = true
              }
            },
          )
          DropdownMenu(expanded = callMenuExpanded, onDismissRequest = { callMenuExpanded = false }) {
            callTargets.forEach { target ->
              DropdownMenuItem(
                text = {
                  Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("Call ${target.name}")
                    if (!target.isDirect) {
                      Text("via relay", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                  }
                },
                leadingIcon = { Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = {
                  callMenuExpanded = false
                  onStartCall(target.id)
                },
              )
            }
          }
        }
        ComposerActionButton(
          icon = Icons.Rounded.Close,
          contentDescription = "Leave",
          enabled = true,
          onClick = onDisconnect,
        )
      }
    }
  }
}

@Composable
private fun MemberAvatar(member: GroupMember) {
  val color =
    when (member.status) {
      GroupMemberStatus.Online -> MaterialTheme.colorScheme.secondary
      GroupMemberStatus.Reconnecting -> MaterialTheme.colorScheme.tertiary
      GroupMemberStatus.Offline -> MaterialTheme.colorScheme.outline
  }
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Avatar(member.displayName, color = color)
    groupMemberStatusText(member.status)?.let { statusText ->
      Text(statusText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
        Text(nearbyEmptyStateText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
  localAvatarName: String,
  groupMembers: List<GroupMember>,
  listState: LazyListState,
  imageBitmapCache: Base64DecodedImageCache<ImageBitmap>,
  onRetryMessage: (String) -> Unit,
  onDeleteMessage: (String) -> Unit,
  onPreviewImage: (ChatMessage) -> Unit,
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
      item(contentType = "empty") { EmptyChatState() }
    } else {
      items(
        items = messages,
        key = { it.id },
        contentType = { it.kind },
      ) { message ->
        val senderName =
          senderDisplayName(
            senderId = message.senderId,
            isLocal = message.isLocal,
            localDeviceId = localDeviceId,
            groupMembers = groupMembers,
          )
        val avatarName = if (message.isLocal) localAvatarName.ifBlank { localDisplayName } else senderName
        MessageRow(message, senderName, avatarName, imageBitmapCache, onRetryMessage, onDeleteMessage, onPreviewImage, onPlayVoice, onOpenMaps)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
  message: ChatMessage,
  senderName: String,
  avatarName: String,
  imageBitmapCache: Base64DecodedImageCache<ImageBitmap>,
  onRetryMessage: (String) -> Unit,
  onDeleteMessage: (String) -> Unit,
  onPreviewImage: (ChatMessage) -> Unit,
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
  var menuExpanded by remember { mutableStateOf(false) }
  val clipboardManager = LocalClipboardManager.current

  if (message.kind == MessageKind.Image && message.image != null) {
    ImageMessageRow(
      message = message,
      isLocal = isLocal,
      senderName = senderName,
      avatarName = avatarName,
      imageBitmapCache = imageBitmapCache,
      onRetryMessage = onRetryMessage,
      onDeleteMessage = onDeleteMessage,
      onPreviewImage = onPreviewImage,
    )
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
        modifier =
          Modifier
            .widthIn(max = 300.dp)
            .combinedClickable(
              onClick = {},
              onLongClick = { menuExpanded = true },
            ),
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
          MessageStatusLine(message = message, contentColor = contentColor, onRetryMessage = onRetryMessage)
        }
      }
      MessageActionMenu(
        expanded = menuExpanded,
        message = message,
        onDismiss = { menuExpanded = false },
        onCopy = {
          clipboardManager.setText(AnnotatedString(message.copyText()))
          menuExpanded = false
        },
        onRetry = {
          onRetryMessage(message.id)
          menuExpanded = false
        },
        onDelete = {
          onDeleteMessage(message.id)
          menuExpanded = false
        },
      )
    }
    if (isLocal) {
      Spacer(Modifier.width(7.dp))
      MessageAvatar(name = avatarName, color = MaterialTheme.colorScheme.primary)
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageMessageRow(
  message: ChatMessage,
  isLocal: Boolean,
  senderName: String,
  avatarName: String,
  imageBitmapCache: Base64DecodedImageCache<ImageBitmap>,
  onRetryMessage: (String) -> Unit,
  onDeleteMessage: (String) -> Unit,
  onPreviewImage: (ChatMessage) -> Unit,
) {
  val image = message.image ?: return
  val payloadCache = LocalPayloadCache.current
  val imageBitmap = rememberDecodedImageBitmap(image.payloadKey, payloadCache, imageBitmapCache)
  var menuExpanded by remember { mutableStateOf(false) }
  val clipboardManager = LocalClipboardManager.current
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
            .clip(messageBubbleShape(isLocal))
            .combinedClickable(
              onClick = { onPreviewImage(message) },
              onLongClick = { menuExpanded = true },
            ),
      )
      MessageStatusLine(message = message, contentColor = MaterialTheme.colorScheme.onSurfaceVariant, onRetryMessage = onRetryMessage)
      MessageActionMenu(
        expanded = menuExpanded,
        message = message,
        onDismiss = { menuExpanded = false },
        onCopy = {
          clipboardManager.setText(AnnotatedString(message.copyText()))
          menuExpanded = false
        },
        onRetry = {
          onRetryMessage(message.id)
          menuExpanded = false
        },
        onDelete = {
          onDeleteMessage(message.id)
          menuExpanded = false
        },
      )
    }
    if (isLocal) {
      Spacer(Modifier.width(7.dp))
      MessageAvatar(name = avatarName, color = MaterialTheme.colorScheme.primary)
    }
  }
}

@Composable
private fun MessageStatusLine(
  message: ChatMessage,
  contentColor: Color,
  onRetryMessage: (String) -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(message.status.displayLabel(message.isLocal), style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.68f))
    if (message.isLocal && message.status == MessageStatus.Failed) {
      TextButton(
        onClick = { onRetryMessage(message.id) },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        modifier = Modifier.height(26.dp),
      ) {
        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text("Retry", style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}

@Composable
private fun MessageActionMenu(
  expanded: Boolean,
  message: ChatMessage,
  onDismiss: () -> Unit,
  onCopy: () -> Unit,
  onRetry: () -> Unit,
  onDelete: () -> Unit,
) {
  DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
    DropdownMenuItem(
      text = { Text("Copy") },
      leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
      onClick = onCopy,
    )
    if (message.isLocal && message.status == MessageStatus.Failed) {
      DropdownMenuItem(
        text = { Text("Retry") },
        leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
        onClick = onRetry,
      )
    }
    DropdownMenuItem(
      text = { Text("Delete") },
      leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
      onClick = onDelete,
    )
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

internal fun connectionSetupActionLabels(status: ConnectionStatus): List<String> =
  if (status == ConnectionStatus.Connected) emptyList() else listOf("Search")

internal fun connectionSetupFieldLabels(status: ConnectionStatus): List<String> =
  emptyList()

internal fun connectionRecoveryActionLabels(state: ChatUiState): List<String> =
  emptyList()

internal fun nearbyEmptyStateText(): String =
  "No devices found yet. Keep this screen open while another phone taps Search."

internal fun connectedSummarySubtitle(state: ChatUiState): String =
  state.connectedEndpoints.firstOrNull()?.name ?: state.groupMembers.firstOrNull()?.displayName ?: "Peer"

internal fun connectedSetupSectionLabels(state: ChatUiState): List<String> =
  if (state.status == ConnectionStatus.Connected) listOf("Peer") else emptyList()

internal fun localMemberSubtitle(localDisplayName: String): String = "You: ${localDisplayName.ifBlank { "OfflineLink" }}"

internal fun groupMemberStatusText(status: GroupMemberStatus): String? = null

internal fun setupHeaderTitle(state: ChatUiState): String =
  if (state.status == ConnectionStatus.Connected) "Connection" else "Setup"

internal fun setupSummaryText(state: ChatUiState): String {
  return when {
    state.pendingConnection != null -> "Request from ${state.pendingConnection.endpointName}"
    state.status == ConnectionStatus.Connected -> connectedSummarySubtitle(state)
    state.discoveredEndpoints.isNotEmpty() -> "${countLabel(state.discoveredEndpoints.size, "nearby device")} - ${state.statusMessage}"
    state.groupMembers.isNotEmpty() -> "${connectedSummarySubtitle(state)} - ${state.statusMessage}"
    else -> state.statusMessage
  }
}

private fun countLabel(
  count: Int,
  singular: String,
): String = "$count $singular${if (count == 1) "" else "s"}"

private fun ChatMessage.copyText(): String =
  when (kind) {
    MessageKind.Text -> text
    MessageKind.Voice -> text
    MessageKind.Location -> location?.let { "%.6f, %.6f".format(it.latitude, it.longitude) } ?: text
    MessageKind.Image -> text
  }

private data class DiagnosticItem(
  val label: String,
  val value: String,
)

private fun diagnosticsFor(
  context: Context,
  hasPermissions: Boolean,
  state: ChatUiState,
  callAudioProcessingMode: CallAudioProcessingMode,
  callAudioDiagnosticsEnabled: Boolean,
  callAudioDiagnosticsPath: String,
  callAudioLinkStats: CallAudioLinkStats,
): List<DiagnosticItem> =
  listOf(
    DiagnosticItem("Permissions", if (hasPermissions) "Granted" else "Missing"),
    DiagnosticItem("Bluetooth", bluetoothStatusLabel(context)),
    DiagnosticItem("Connection", state.status.label()),
    DiagnosticItem("Peer", state.connectedEndpoints.firstOrNull()?.name ?: "None"),
    DiagnosticItem("Messages", state.messages.size.toString()),
    DiagnosticItem("Call audio", callAudioProcessingMode.displayName),
    DiagnosticItem("Call RX", "${callAudioLinkStats.receivedFrames} rx / ${callAudioLinkStats.lostFrames} lost / ${callAudioLinkStats.lateFrames} late"),
    DiagnosticItem("Call buffer", "${callAudioLinkStats.bufferedDurationMs} ms / ${callAudioLinkStats.concealedFrames} concealed"),
    DiagnosticItem("Call jitter", "${callAudioLinkStats.averageInterArrivalMs} avg / ${callAudioLinkStats.maxInterArrivalMs} max ms"),
    DiagnosticItem("Audio WAV", if (callAudioDiagnosticsEnabled) "On" else "Off"),
    DiagnosticItem("WAV folder", callAudioDiagnosticsPath),
  )

private fun bluetoothStatusLabel(context: Context): String =
  runCatching {
    val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)
    if (manager?.adapter?.isEnabled == true) "On" else "Off"
  }.getOrDefault("Unknown")

private fun ConnectionStatus.label(): String =
  when (this) {
    ConnectionStatus.Idle -> "Ready"
    ConnectionStatus.Advertising -> "Searching"
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

internal fun formatCallDuration(durationMs: Long): String {
  val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
  val minutes = totalSeconds / 60L
  val seconds = totalSeconds % 60L
  return "$minutes:${seconds.toString().padStart(2, '0')}"
}

internal fun callOutputRouteLabel(isSpeakerOn: Boolean): String =
  if (isSpeakerOn) "Speaker" else "Earpiece"

internal fun callScreenStatusLabel(status: CallStatus): String =
  when (status) {
    CallStatus.Incoming -> "Incoming call"
    CallStatus.Outgoing -> "Calling"
    CallStatus.Active -> "Offline call"
    CallStatus.Idle -> "Call"
  }

internal fun callNetworkQualityLabel(): String = "OfflineLink / Strong signal"

internal fun shouldUseFullScreenCallUi(status: CallStatus): Boolean = status != CallStatus.Idle

internal data class CallTargetOption(
  val id: String,
  val name: String,
  val isDirect: Boolean,
)

internal fun callTargetOptions(state: ChatUiState): List<CallTargetOption> {
  return state.connectedEndpoints
    .filter { endpoint -> endpoint.id.isNotBlank() && endpoint.id != state.localDeviceId }
    .map { endpoint ->
      CallTargetOption(
        id = endpoint.id,
        name = endpoint.name.ifBlank { "Nearby device" },
        isDirect = true,
      )
    }
}

private fun decodeImageBitmap(rawBytes: ByteArray): ImageBitmap {
  return BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size).asImageBitmap()
}

@Composable
private fun rememberDecodedImageBitmap(
  payloadKey: String,
  payloadCache: PayloadCache,
  cache: Base64DecodedImageCache<ImageBitmap>,
): ImageBitmap =
  remember(payloadKey, cache) {
    cache.getOrPut(payloadKey) {
      val bytes = payloadCache.get(payloadKey) ?: ByteArray(0)
      decodeImageBitmap(bytes)
    }
  }

internal class Base64DecodedImageCache<T>(
  private val maxEntries: Int,
) {
  private val values =
    object : LinkedHashMap<String, T>(maxEntries, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean = size > maxEntries
    }

  fun getOrPut(
    key: String,
    decode: () -> T,
  ): T =
    values.getOrPut(key, decode)
}

internal fun shouldApplyRootImePadding(windowResizesForKeyboard: Boolean): Boolean = !windowResizesForKeyboard

private const val IMAGE_BITMAP_CACHE_SIZE = 24
private const val SETTINGS_PREFS_NAME = "offline-link-settings"
private const val KEY_DISPLAY_NAME = "display_name"
private const val KEY_AVATAR_NAME = "avatar_name"
private const val KEY_CALL_AUDIO_PROCESSING_MODE = "call_audio_processing_mode"
private const val KEY_CALL_AUDIO_DIAGNOSTICS_ENABLED = "call_audio_diagnostics_enabled"

@Preview(showBackground = true)
@Composable
private fun OfflineChatContentPreview() {
  MyApplicationTheme {
    OfflineChatContent(
      state =
        ChatUiState(
          localDeviceId = "local",
          displayName = "Phone A",
          avatarName = "A",
          groupName = "Field Team",
          status = ConnectionStatus.Connected,
          statusMessage = "Connected to Phone B",
          connectedEndpoints = listOf(NearbyEndpoint("b", "Phone B")),
          groupMembers = listOf(GroupMember("device-b", "Phone B"), GroupMember("device-c", "Phone C")),
          messages =
            listOf(
              ChatMessage("1", "one-to-one", "local", "Hello", 1L, MessageStatus.Received, true),
              ChatMessage("2", "one-to-one", "remote", "Hi from nearby", 2L, MessageStatus.Received, false),
            ),
        ),
      callAudioFrames = emptyFlow(),
      hasPermissions = true,
      onRequestPermissions = {},
      onDisplayNameChange = {},
      onAvatarNameChange = {},
      onDiscover = {},
      onConnect = {},
      onAccept = {},
      onReject = {},
      onSendMessage = {},
      onSendVoiceMessage = { _, _, _ -> },
      onSendCallVoiceMessage = { _, _, _ -> },
      onStartCallAudio = { Result.success(Unit) },
      onStopCallAudio = {},
      onPlayCallAudio = { Result.success(Unit) },
      onSetCallMuted = {},
      onSetSpeakerEnabled = {},
      onSendLocation = { it(Result.success(Unit)) },
      onStartVoiceRecording = { Result.success(Unit) },
      onStopVoiceRecording = { Result.success(RecordedVoiceClip(byteArrayOf(1, 2, 3), 1000L)) },
      onPlayVoice = { Result.success(Unit) },
      onDisconnect = {},
      onStartCall = { _ -> },
      onAcceptCall = {},
      onRejectCall = {},
      onEndCall = {},
      onFinishCallVoicePlayback = {},
      onRetryMessage = {},
      onDeleteMessage = {},
      onClearMessages = {},
      diagnostics = listOf(DiagnosticItem("Permissions", "Granted"), DiagnosticItem("Bluetooth", "On")),
      callAudioProcessingMode = CallAudioProcessingMode.Default,
      onCallAudioProcessingModeChange = {},
      callAudioDiagnosticsEnabled = false,
      onCallAudioDiagnosticsEnabledChange = {},
      callAudioDiagnosticsPath = "/tmp/offline-link",
      onPickImage = {},
      modifier = Modifier.fillMaxSize(),
    )
  }
}

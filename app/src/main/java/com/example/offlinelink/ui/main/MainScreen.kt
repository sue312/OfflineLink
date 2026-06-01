package com.example.offlinelink.ui.main

import android.app.Activity
import android.bluetooth.BluetoothManager
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
import androidx.compose.material.icons.rounded.AttachFile
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
import androidx.compose.material3.Switch
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
import com.example.offlinelink.audio.initialCallAudioProcessingMode
import com.example.offlinelink.crypto.EncryptedChatTransport
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
import com.example.offlinelink.permissions.requiredBluetoothRuntimePermissions
import com.example.offlinelink.service.OfflineKeepAliveService
import com.example.offlinelink.theme.MyApplicationTheme
import com.example.offlinelink.transport.BluetoothChatTransport
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalPayloadCache = androidx.compose.runtime.staticCompositionLocalOf<PayloadCache> {
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
  val trustedDeviceIds =
    remember(preferences) {
      preferences.getStringSet(KEY_TRUSTED_DEVICE_IDS, emptySet()).orEmpty().toSet()
    }
  val viewModel: MainScreenViewModel =
    viewModel {
      MainScreenViewModel(
        EncryptedChatTransport(BluetoothChatTransport(context.applicationContext)),
        locationHelper::currentLocation,
        { uri -> ImageCompressor.compress(contentResolver, uri) },
        defaultDisplayName = defaultDisplayName,
        defaultAvatarName = defaultAvatarName,
        historyRepository = historyRepository,
        payloadCache = payloadCache,
        initialTrustedDeviceIds = trustedDeviceIds,
        onTrustedDeviceIdsChanged = { ids ->
          preferences.edit().putStringSet(KEY_TRUSTED_DEVICE_IDS, ids).apply()
        },
      )
    }
  val voiceRecorder = remember(context) { VoiceRecorder(context.applicationContext) }
  val voicePlayer = remember(context) { VoicePlayer(context.applicationContext) }
  val callAudioStream = remember(context) { CallAudioStream(context.applicationContext) }
  val callTonePlayer = remember(context) { CallTonePlayer(context.applicationContext) }
  var callAudioProcessingMode by rememberSaveable {
    mutableStateOf(
      initialCallAudioProcessingMode(
        savedName = preferences.getString(KEY_CALL_AUDIO_PROCESSING_MODE, null),
        userSelected = preferences.getBoolean(KEY_CALL_AUDIO_PROCESSING_MODE_USER_SELECTED, false),
      ),
    )
  }
  var callAudioDiagnosticsEnabled by rememberSaveable {
    mutableStateOf(preferences.getBoolean(KEY_CALL_AUDIO_DIAGNOSTICS_ENABLED, false))
  }
  val callAudioDiagnosticsPath = remember(callAudioStream) { callAudioStream.diagnosticsDirectoryPath() }
  val callAudioLinkStats = callAudioStream.linkStats()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val requiredPermissions = remember { requiredBluetoothRuntimePermissions() }
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
    onVisibleToNearbyChange = { isVisible ->
      viewModel.setVisibleToNearby(isVisible)
    },
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
      preferences.edit()
        .putString(KEY_CALL_AUDIO_PROCESSING_MODE, mode.name)
        .putBoolean(KEY_CALL_AUDIO_PROCESSING_MODE_USER_SELECTED, true)
        .apply()
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
  onVisibleToNearbyChange: (Boolean) -> Unit,
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

  LaunchedEffect(state.status, state.connectedEndpoint?.id, state.pendingConnection?.endpointId) {
    if (state.status == ConnectionStatus.Connected && state.pendingConnection == null) {
      isSetupExpanded = false
    }
  }

  LaunchedEffect(state.status) {
    if (state.status != ConnectionStatus.Connected) {
      isSendingLocation = false
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
      HeaderSection(onOpenSettings = { showSettings = true })

      if (!hasPermissions) {
        PermissionPanel(onRequestPermissions, modifier = Modifier.fillMaxWidth())
      } else {
        Alerts(lastError = state.lastError)
        SetupSection(
          state = state,
          expanded = isSetupExpanded,
          onToggle = { isSetupExpanded = !isSetupExpanded },
          onDiscover = onDiscover,
          onVisibleToNearbyChange = onVisibleToNearbyChange,
          onDisconnect = onDisconnect,
          onStartCall = onStartCall,
          onConnect = onConnect,
          onAccept = onAccept,
          onReject = onReject,
        )
        MessageListSection(
          state = state,
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
              result
                .onSuccess {
                  voiceError = null
                }
                .onFailure { e ->
                  voiceError = e.message ?: "Could not send location"
                }
            }
          },
          onPickImage = onPickImage,
        )
      }
    }
    if (state.callState.status != CallStatus.Idle) {
      CallSection(
        callState = state.callState,
        connectedEndpointRssi = state.connectedEndpoint?.rssi,
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

private const val IMAGE_BITMAP_CACHE_SIZE = 24
private const val SETTINGS_PREFS_NAME = "offline-link-settings"
private const val KEY_DISPLAY_NAME = "display_name"
private const val KEY_AVATAR_NAME = "avatar_name"
private const val KEY_CALL_AUDIO_PROCESSING_MODE = "call_audio_processing_mode"
private const val KEY_CALL_AUDIO_PROCESSING_MODE_USER_SELECTED = "call_audio_processing_mode_user_selected"
private const val KEY_CALL_AUDIO_DIAGNOSTICS_ENABLED = "call_audio_diagnostics_enabled"
private const val KEY_TRUSTED_DEVICE_IDS = "trusted_device_ids"

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
      onVisibleToNearbyChange = {},
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

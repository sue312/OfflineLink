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


@Composable
internal fun CallSection(
  callState: CallState,
  connectedEndpointRssi: Int?,
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
            text = if (isCallAudioLive) callNetworkQualityLabel(connectedEndpointRssi) else "OfflineLink / Connecting audio",
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
internal fun CallControlButton(
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

internal fun callScreenBackground(): Brush =
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
internal fun Avatar(
  name: String,
  color: Color,
) {
  Surface(shape = CircleShape, color = color, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp)) {
    Box(contentAlignment = Alignment.Center) {
      Text(avatarInitials(name), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
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

internal fun callNetworkQualityLabel(rssi: Int? = null): String =
  if (rssi == null) {
    "OfflineLink / Strong signal"
  } else {
    "OfflineLink / ${connectedSignalLabel(rssi)}"
  }

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


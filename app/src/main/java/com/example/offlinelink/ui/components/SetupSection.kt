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
internal fun SetupSection(
  state: ChatUiState,
  expanded: Boolean,
  onToggle: () -> Unit,
  onDiscover: () -> Unit,
  onVisibleToNearbyChange: (Boolean) -> Unit,
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
    SetupToggleHeader(
      state = state,
      expanded = expanded,
      onToggle = onToggle,
      onDisconnect = onDisconnect,
      onStartCall = onStartCall,
    )
    AnimatedVisibility(visible = expanded && state.status != ConnectionStatus.Connected) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionConsole(
          state = state,
          onDiscover = onDiscover,
          onVisibleToNearbyChange = onVisibleToNearbyChange,
        )
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
internal fun SetupToggleHeader(
  state: ChatUiState,
  expanded: Boolean,
  onToggle: () -> Unit,
  onDisconnect: () -> Unit,
  onStartCall: (String?) -> Unit,
) {
  val isConnected = state.status == ConnectionStatus.Connected
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
    val rowModifier =
      Modifier
        .fillMaxWidth()
        .then(if (isConnected) Modifier else Modifier.clickable(onClick = onToggle))
        .padding(horizontal = 12.dp, vertical = 9.dp)
    Row(
      modifier = rowModifier,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(setupHeaderTitle(state), style = MaterialTheme.typography.titleMedium)
        Text(setupSummaryText(state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (isConnected) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Signal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EndpointSignalIndicator(state.connectedEndpoint?.rssi)
          }
        }
      }
      if (isConnected) {
        val callTargets = callTargetOptions(state)
        val canStartCall = state.callState.status == CallStatus.Idle && state.connectedEndpoints.isNotEmpty() && callTargets.isNotEmpty()
        ComposerActionButton(
          icon = Icons.Rounded.Call,
          contentDescription = "Start call",
          enabled = canStartCall,
          primary = canStartCall,
          onClick = { onStartCall(callTargets.firstOrNull()?.id) },
        )
        ComposerActionButton(
          icon = Icons.Rounded.Close,
          contentDescription = "Disconnect",
          enabled = true,
          onClick = onDisconnect,
        )
      } else {
        Icon(
          imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
          contentDescription = if (expanded) "Collapse setup" else "Expand setup",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(22.dp),
        )
      }
    }
  }
}

@Composable
internal fun ConnectionConsole(
  state: ChatUiState,
  onDiscover: () -> Unit,
  onVisibleToNearbyChange: (Boolean) -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
          Text("Visible", style = MaterialTheme.typography.labelLarge)
          Text(connectionVisibilityLabel(state.isVisibleToNearby), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
          checked = state.isVisibleToNearby,
          onCheckedChange = onVisibleToNearbyChange,
          enabled = connectionVisibilityEnabled(state.status),
        )
      }
      val searchEnabled = state.status != ConnectionStatus.Connected
      val searching = state.status == ConnectionStatus.Discovering
      if (searching) {
        Button(
          onClick = onDiscover,
          enabled = searchEnabled,
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
          Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(17.dp))
          Spacer(Modifier.width(6.dp))
          Text(connectionSearchLabel(state.status), style = MaterialTheme.typography.labelLarge)
        }
      } else {
        OutlinedButton(
          onClick = onDiscover,
          enabled = searchEnabled,
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
          Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(17.dp))
          Spacer(Modifier.width(6.dp))
          Text(connectionSearchLabel(state.status), style = MaterialTheme.typography.labelLarge)
        }
      }
    }
  }
}

@Composable
internal fun PendingConnectionPanel(
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
internal fun EndpointList(
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
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ready to pair", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EndpointSignalIndicator(endpoint.rssi)
              }
            }
            OutlinedButton(onClick = { onConnect(endpoint) }, shape = RoundedCornerShape(8.dp)) { Text("Connect") }
          }
        }
      }
    }
  }
}

@Composable
internal fun EndpointSignalIndicator(rssi: Int?) {
  val bars = signalBarsFromRssi(rssi)
  Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
    repeat(4) { index ->
      Box(
        modifier =
          Modifier
            .width(3.dp)
            .height((6 + index * 3).dp)
            .background(
              color =
                if (index < bars) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.outlineVariant
                },
              shape = RoundedCornerShape(1.dp),
            ),
      )
    }
    Text(
      text = rssi?.let { "$it dBm" } ?: "-- dBm",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

internal fun defaultSetupExpanded(messageCount: Int): Boolean = messageCount == 0

internal fun connectionSetupActionLabels(status: ConnectionStatus): List<String> =
  if (status == ConnectionStatus.Connected) emptyList() else listOf("Search")

internal fun connectionSearchLabel(status: ConnectionStatus): String =
  if (status == ConnectionStatus.Discovering) "Searching" else "Search"

internal fun connectionVisibilityLabel(isVisible: Boolean): String =
  if (isVisible) "Visible" else "Hidden"

internal fun connectionVisibilityEnabled(status: ConnectionStatus): Boolean =
  status != ConnectionStatus.Connected && status != ConnectionStatus.Connecting

internal fun connectionSetupFieldLabels(status: ConnectionStatus): List<String> =
  emptyList()

internal fun connectionRecoveryActionLabels(state: ChatUiState): List<String> =
  emptyList()

internal fun nearbyEmptyStateText(): String =
  "No visible devices nearby."

internal fun connectedSummarySubtitle(state: ChatUiState): String =
  state.connectedEndpoints.firstOrNull()?.name ?: state.groupMembers.firstOrNull()?.displayName ?: "Peer"

internal fun connectedSignalLabel(rssi: Int?): String =
  "Signal ${rssi?.let { "$it dBm" } ?: "-- dBm"}"

internal fun connectedSetupSectionLabels(state: ChatUiState): List<String> =
  emptyList()

internal fun connectedHeaderActionLabels(state: ChatUiState): List<String> =
  if (state.status == ConnectionStatus.Connected) listOf("Call", "Disconnect") else emptyList()

internal fun localMemberSubtitle(localDisplayName: String): String = "You: ${localDisplayName.ifBlank { "OfflineLink" }}"

internal fun groupMemberStatusText(status: GroupMemberStatus): String? = null

internal fun setupHeaderTitle(state: ChatUiState): String =
  if (state.status == ConnectionStatus.Connected) "Connected" else "Setup"

internal fun setupSummaryText(state: ChatUiState): String {
  return when {
    state.pendingConnection != null -> "Request from ${state.pendingConnection.endpointName}"
    state.status == ConnectionStatus.Connected -> connectedSummarySubtitle(state)
    state.status == ConnectionStatus.Discovering -> "Searching"
    state.status == ConnectionStatus.Advertising || state.isVisibleToNearby -> "Visible"
    state.discoveredEndpoints.isNotEmpty() -> countLabel(state.discoveredEndpoints.size, "nearby device")
    state.groupMembers.isNotEmpty() -> connectedSummarySubtitle(state)
    state.status == ConnectionStatus.Error -> "Issue"
    else -> connectionVisibilityLabel(isVisible = false)
  }
}

internal fun emptyChatTitle(state: ChatUiState): String =
  when (state.status) {
    ConnectionStatus.Connected -> "No messages yet"
    ConnectionStatus.Discovering -> "Searching nearby"
    ConnectionStatus.Advertising -> "Visible to nearby"
    else -> "Ready to link"
  }

internal fun emptyChatSubtitle(state: ChatUiState): String =
  when (state.status) {
    ConnectionStatus.Connected -> "Messages appear here."
    ConnectionStatus.Discovering -> "Visible phones will appear above."
    ConnectionStatus.Advertising -> "Waiting for a nearby phone."
    else -> "Turn on Visible or search nearby."
  }

internal fun countLabel(
  count: Int,
  singular: String,
): String = "$count $singular${if (count == 1) "" else "s"}"

internal fun signalBarsFromRssi(rssi: Int?): Int =
  when {
    rssi == null -> 0
    rssi >= -60 -> 4
    rssi >= -70 -> 3
    rssi >= -80 -> 2
    else -> 1
  }


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
internal fun SettingsDialog(
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
internal fun CallAudioModePicker(
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
internal fun CallAudioModeContent(
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
internal fun CallFullScreenEffect(enabled: Boolean) {
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

internal fun Context.findActivity(): Activity? {
  var current = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return current as? Activity
}

@Composable
internal fun SettingsSection(
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
internal fun ImagePreviewDialog(
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

internal data class DiagnosticItem(
  val label: String,
  val value: String,
)

internal fun diagnosticsFor(
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

internal fun bluetoothStatusLabel(context: Context): String =
  runCatching {
    val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)
    if (manager?.adapter?.isEnabled == true) "On" else "Off"
  }.getOrDefault("Unknown")

internal fun ConnectionStatus.label(): String =
  when (this) {
    ConnectionStatus.Idle -> "Ready"
    ConnectionStatus.Advertising -> "Visible"
    ConnectionStatus.Discovering -> "Searching"
    ConnectionStatus.Connecting -> "Pairing"
    ConnectionStatus.Connected -> "Online"
    ConnectionStatus.Disconnected -> "Offline"
    ConnectionStatus.Error -> "Issue"
  }



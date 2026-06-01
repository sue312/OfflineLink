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
internal fun MessageListSection(
  state: ChatUiState,
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
      item(contentType = "empty") { EmptyChatState(state = state) }
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
internal fun EmptyChatState(state: ChatUiState) {
  Surface(
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    shape = RoundedCornerShape(8.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(emptyChatTitle(state), style = MaterialTheme.typography.titleMedium)
      Text(
        emptyChatSubtitle(state),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageRow(
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
internal fun ImageMessageRow(
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
internal fun MessageStatusLine(
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
internal fun MessageActionMenu(
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
internal fun MessageAvatar(
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
internal fun VoiceMessageContent(
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
internal fun Waveform(
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
internal fun LocationMessageContent(
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
internal fun ChatMessage.copyText(): String =
  when (kind) {
    MessageKind.Text -> text
    MessageKind.Voice -> text
    MessageKind.Location -> location?.let { "%.6f, %.6f".format(it.latitude, it.longitude) } ?: text
    MessageKind.Image -> text
  }
internal fun messageBubbleShape(isLocal: Boolean): RoundedCornerShape =
  RoundedCornerShape(
    topStart = 8.dp,
    topEnd = 8.dp,
    bottomStart = if (isLocal) 8.dp else 2.dp,
    bottomEnd = if (isLocal) 2.dp else 8.dp,
  )

internal fun formatDuration(durationMs: Long): String {
  val totalSeconds = (durationMs / 1000L).coerceAtLeast(1L)
  val minutes = totalSeconds / 60L
  val seconds = totalSeconds % 60L
  return if (minutes == 0L) {
    "${seconds}s"
  } else {
    "$minutes:${seconds.toString().padStart(2, '0')}"
  }
}
internal fun decodeImageBitmap(rawBytes: ByteArray): ImageBitmap {
  return BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size).asImageBitmap()
}

@Composable
internal fun rememberDecodedImageBitmap(
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


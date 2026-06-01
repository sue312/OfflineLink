package com.example.offlinelink

import android.app.Application
import android.content.Context
import com.example.offlinelink.crypto.EncryptedChatTransport
import com.example.offlinelink.transport.BluetoothChatTransport
import com.example.offlinelink.transport.ChatTransport

class OfflineLinkApplication : Application() {
  val offlineLinkSession: OfflineLinkSession by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    OfflineLinkSession(this)
  }
}

class OfflineLinkSession(context: Context) {
  val transport: ChatTransport by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    EncryptedChatTransport(BluetoothChatTransport(context.applicationContext))
  }
}

val Context.offlineLinkSession: OfflineLinkSession
  get() {
    val appContext = applicationContext
    return if (appContext is OfflineLinkApplication) {
      appContext.offlineLinkSession
    } else {
      OfflineLinkSessionFallback.get(appContext)
    }
  }

private object OfflineLinkSessionFallback {
  @Volatile private var session: OfflineLinkSession? = null

  fun get(context: Context): OfflineLinkSession =
    session ?: synchronized(this) {
      session ?: OfflineLinkSession(context.applicationContext).also { session = it }
    }
}

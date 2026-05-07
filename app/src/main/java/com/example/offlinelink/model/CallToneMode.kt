package com.example.offlinelink.model

enum class CallToneMode {
  None,
  Outgoing,
  Incoming,
}

fun callToneModeFor(status: CallStatus): CallToneMode =
  when (status) {
    CallStatus.Outgoing -> CallToneMode.Outgoing
    CallStatus.Incoming -> CallToneMode.Incoming
    CallStatus.Active, CallStatus.Idle -> CallToneMode.None
  }

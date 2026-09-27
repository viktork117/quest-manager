package com.adaptivecodex.quest.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BridgeConfigReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != BridgeProtocol.CONFIG_ACTION) return
    val token = intent.getStringExtra(BridgeProtocol.TOKEN_EXTRA) ?: return
    if (!TOKEN_PATTERN.matches(token)) return
    context.getSharedPreferences(BridgeProtocol.PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(BridgeProtocol.PREF_TOKEN, token)
      .apply()
  }

  private companion object {
    val TOKEN_PATTERN = Regex("[0-9a-f]{32}")
  }
}

package com.pulse.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.discord.socialsdk.rpc.IDiscordRpcCallback
import com.discord.socialsdk.rpc.IDiscordRpcConnection
import com.discord.socialsdk.rpc.IDiscordRpcService
import org.json.JSONObject
import java.util.UUID

/** Publishes the active local track to the installed Discord Android client through its RPC service. */
object DiscordPresence {
    private const val applicationId = 1565066620374876302L
    private const val rpcAction = "com.discord.socialsdk.rpc.IDiscordRpcService"
    private const val discordPackage = "com.discord"
    private const val tag = "DiscordPresence"

    private var connection: IDiscordRpcConnection? = null
    private var isBound = false
    private var pendingFrame: String? = null
    private var appContext: Context? = null

    private val callback = object : IDiscordRpcCallback.Stub() {
        override fun onFrame(frame: String?) {
            Log.d(tag, "Discord RPC: $frame")
        }

        override fun onClose(code: Int, message: String?) {
            Log.d(tag, "Discord RPC closed ($code): $message")
            connection = null
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            connection = try {
                IDiscordRpcService.Stub.asInterface(binder).connect(applicationId, "1", callback)
            } catch (error: Exception) {
                Log.w(tag, "Could not connect to Discord", error)
                null
            }
            sendPendingFrame()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connection = null
        }
    }

    fun update(context: Context, title: String?, artist: String?, playing: Boolean, coverUrl: String? = null) {
        if (!AppPreferences.discordEnabled(context) || !playing || title.isNullOrBlank()) {
            clear(context)
            return
        }
        val activity = JSONObject().apply {
            put("name", "Pulse")
            put("type", 2) // Discord ActivityTypes.Listening
            put("details", title.take(128))
            put("state", artist?.takeIf { it.isNotBlank() }?.take(128) ?: "Listening on Pulse")
            put("buttons", org.json.JSONArray().put(JSONObject().apply {
                put("label", "Download Pulse")
                put("url", "https://github.com/penguinwho/Pulse")
            }))
            coverUrl?.takeIf { it.startsWith("https://") }?.let { url ->
                put("assets", JSONObject().apply {
                    put("large_image", url)
                    put("large_text", title.take(128))
                })
            }
        }
        queue(context, activity)
    }

    fun clear(context: Context) {
        // Do not start Discord just to clear an activity that was never published.
        if (connection == null && !isBound) return
        queue(context, null)
    }

    private fun queue(context: Context, activity: JSONObject?) {
        pendingFrame = JSONObject().apply {
            put("cmd", "SET_ACTIVITY")
            put("nonce", UUID.randomUUID().toString())
            put("args", JSONObject().apply {
                put("pid", Process.myPid())
                put("activity", activity ?: JSONObject.NULL)
            })
        }.toString()
        appContext = context.applicationContext
        if (connection == null) bind() else sendPendingFrame()
    }

    private fun bind() {
        if (isBound) return
        val context = appContext ?: return
        isBound = try {
            context.bindService(
                Intent(rpcAction).setPackage(discordPackage),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        } catch (error: SecurityException) {
            Log.w(tag, "Discord RPC binding was denied", error)
            false
        }
    }

    private fun sendPendingFrame() {
        val frame = pendingFrame ?: return
        val rpc = connection ?: return
        try {
            rpc.sendFrame(frame)
            pendingFrame = null
        } catch (error: Exception) {
            Log.w(tag, "Could not send Discord presence", error)
            connection = null
        }
    }

    fun disconnect(context: Context) {
        clear(context)
        runCatching { connection?.disconnect() }
        if (isBound) runCatching { context.applicationContext.unbindService(serviceConnection) }
        connection = null
        isBound = false
        appContext = null
    }
}

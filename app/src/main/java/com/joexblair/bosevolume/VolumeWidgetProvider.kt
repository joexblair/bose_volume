package com.joexblair.bosevolume

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.joexblair.bosevolume.core.BoseConnectionException
import com.joexblair.bosevolume.core.BoseRequestException
import com.joexblair.bosevolume.core.BoseSpeaker
import com.joexblair.bosevolume.core.NotSignedInException
import com.joexblair.bosevolume.core.VolumeController
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Home-screen widget: [−]  volume  [+]  [mute]. */
class VolumeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        TokenHeartbeatWorker.sync(context)
        run(context) { it.get() }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_UP -> run(context) { it.change(+Bose.step(context)) }
            ACTION_DOWN -> run(context) { it.change(-Bose.step(context)) }
            ACTION_MUTE -> run(context) { it.toggleMute() }
            ACTION_REFRESH -> run(context) { it.get() }
            else -> super.onReceive(context, intent)
        }
    }

    /** Runs [op] in the background (within the ~10 s broadcast budget) and redraws the widget. */
    private fun run(context: Context, op: suspend (VolumeController) -> BoseSpeaker.Volume) {
        val app = context.applicationContext
        val pending = goAsync()
        render(app, State.Busy)
        Bose.scope.launch {
            val state = try {
                State.Ok(withTimeout(9_000) { Bose.command(app, op) })
            } catch (e: NoSpeakerException) {
                State.Error("Pick a speaker")
            } catch (e: NotSignedInException) {
                State.Error("Sign in")
            } catch (e: BoseConnectionException) {
                State.Error("Offline")
            } catch (e: BoseRequestException) {
                State.Error(if (e.status == 401) "Sign in" else "Error ${e.status}")
            } catch (e: Exception) {
                State.Error("Offline")
            }
            render(app, state)
            pending.finish()
        }
    }

    private sealed interface State {
        data object Busy : State
        data class Ok(val volume: BoseSpeaker.Volume) : State
        data class Error(val message: String) : State
    }

    companion object {
        private const val ACTION_UP = "com.joexblair.bosevolume.UP"
        private const val ACTION_DOWN = "com.joexblair.bosevolume.DOWN"
        private const val ACTION_MUTE = "com.joexblair.bosevolume.MUTE"
        private const val ACTION_REFRESH = "com.joexblair.bosevolume.REFRESH"

        @Volatile private var last: BoseSpeaker.Volume? = null

        fun requestRefresh(context: Context) {
            context.sendBroadcast(Intent(context, VolumeWidgetProvider::class.java).setAction(ACTION_REFRESH))
        }

        private fun render(context: Context, state: State) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VolumeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_volume)

            views.setTextViewText(R.id.widget_name, Bose.speaker(context)?.name ?: context.getString(R.string.app_name))
            if (state is State.Ok) last = state.volume
            val volume = last
            when (state) {
                is State.Ok, State.Busy -> {
                    views.setTextViewText(R.id.widget_value, volume?.value?.toString() ?: "…")
                    views.setTextViewText(R.id.widget_status, if (state == State.Busy) "…" else "")
                }
                is State.Error -> {
                    views.setTextViewText(R.id.widget_value, "–")
                    views.setTextViewText(R.id.widget_status, state.message)
                }
            }
            views.setImageViewResource(
                R.id.widget_mute,
                if (volume?.muted == true) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
            )
            views.setProgressBar(R.id.widget_bar, volume?.max ?: 100, volume?.value ?: 0, false)

            views.setOnClickPendingIntent(R.id.widget_down, broadcast(context, ACTION_DOWN, 1))
            views.setOnClickPendingIntent(R.id.widget_up, broadcast(context, ACTION_UP, 2))
            views.setOnClickPendingIntent(R.id.widget_mute, broadcast(context, ACTION_MUTE, 3))
            views.setOnClickPendingIntent(R.id.widget_value, broadcast(context, ACTION_REFRESH, 4))
            views.setOnClickPendingIntent(
                R.id.widget_name,
                PendingIntent.getActivity(
                    context, 5, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            manager.updateAppWidget(ids, views)
        }

        private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, VolumeWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

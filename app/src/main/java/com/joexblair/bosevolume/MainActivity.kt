package com.joexblair.bosevolume

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.joexblair.bosevolume.core.BoseSpeaker
import com.joexblair.bosevolume.core.VolumeController
import kotlinx.coroutines.launch

/** Setup screen: sign in, pick a speaker, set the step size, and try the controls. */
class MainActivity : ComponentActivity() {
    private lateinit var account: TextView
    private lateinit var signIn: Button
    private lateinit var speakerLabel: TextView
    private lateinit var found: LinearLayout
    private lateinit var manualHost: EditText
    private lateinit var step: EditText
    private lateinit var volumeText: TextView
    private lateinit var seek: SeekBar
    private lateinit var status: TextView
    private var discovery: SpeakerDiscovery? = null
    private val seen = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        account = view(R.id.account)
        signIn = view(R.id.sign_in)
        speakerLabel = view(R.id.speaker)
        found = view(R.id.found)
        manualHost = view(R.id.manual_host)
        step = view(R.id.step)
        volumeText = view(R.id.volume)
        seek = view(R.id.seek)
        status = view(R.id.status)

        signIn.setOnClickListener {
            val auth = Bose.auth(this)
            if (auth.isSignedIn) { auth.signOut(); refreshLabels() }
            else startActivity(Intent(this, LoginActivity::class.java))
        }
        view<Button>(R.id.discover).setOnClickListener { discover() }
        view<Button>(R.id.save_manual).setOnClickListener {
            val host = manualHost.text.toString().trim()
            if (host.isNotEmpty()) choose(SpeakerConfig(host, guid = null, name = host))
        }
        step.setText(Bose.step(this).toString())
        view<Button>(R.id.save_step).setOnClickListener {
            step.text.toString().toIntOrNull()?.let { Bose.setStep(this, it) }
            step.setText(Bose.step(this).toString())
        }
        view<Button>(R.id.down).setOnClickListener { act { it.change(-Bose.step(this)) } }
        view<Button>(R.id.up).setOnClickListener { act { it.change(+Bose.step(this)) } }
        view<Button>(R.id.mute).setOnClickListener { act { it.toggleMute() } }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) { act { it.set(bar.progress) } }
        })
    }

    private fun <T : View> view(id: Int): T = requireNotNull(findViewById<T>(id))

    override fun onResume() {
        super.onResume()
        refreshLabels()
        if (Bose.auth(this).isSignedIn && Bose.speaker(this) != null) act { it.get() }
    }

    override fun onPause() {
        super.onPause()
        discovery?.stop()
    }

    private fun refreshLabels() {
        val signedIn = Bose.auth(this).isSignedIn
        account.text = getString(if (signedIn) R.string.signed_in else R.string.signed_out)
        signIn.text = getString(if (signedIn) R.string.sign_out else R.string.sign_in)
        val s = Bose.speaker(this)
        speakerLabel.text = s?.let { "${it.name} (${it.host})" } ?: getString(R.string.no_speaker)
    }

    private fun discover() {
        found.removeAllViews()
        seen.clear()
        status.text = getString(R.string.searching)
        discovery?.stop()
        discovery = SpeakerDiscovery(this) { speaker ->
            if (!seen.add(speaker.host)) return@SpeakerDiscovery
            status.text = ""
            found.addView(Button(this).apply {
                text = "${speaker.name}\n${speaker.host}"
                isAllCaps = false
                setOnClickListener { choose(speaker) }
            })
        }.also { it.start() }
    }

    private fun choose(speaker: SpeakerConfig) {
        Bose.setSpeaker(this, speaker)
        discovery?.stop()
        found.removeAllViews()
        refreshLabels()
        act { it.get() }
    }

    private fun act(op: suspend (VolumeController) -> BoseSpeaker.Volume) {
        status.text = "…"
        lifecycleScope.launch {
            try {
                val v = Bose.command(this@MainActivity, op)
                volumeText.text = if (v.muted) "${v.value} (muted)" else v.value.toString()
                seek.max = v.max
                seek.progress = v.value
                status.text = ""
            } catch (e: Exception) {
                status.text = e.message ?: e.javaClass.simpleName
            }
            VolumeWidgetProvider.requestRefresh(this@MainActivity)
        }
    }
}

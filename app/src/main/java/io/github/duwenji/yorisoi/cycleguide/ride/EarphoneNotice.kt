package io.github.duwenji.yorisoi.cycleguide.ride

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import io.github.duwenji.yorisoi.cycleguide.companion.MotionState

/**
 * Whether to remind the rider to keep their ears free (requirements S-3, AC-8): once a ride, when
 * earphones are connected and the rider has stopped. The sound goes where Android sends it; the
 * app only reminds.
 */
object EarphoneRule {
    fun shouldNotice(connected: Boolean, state: MotionState, noticedThisRide: Boolean): Boolean =
        connected && state != MotionState.MOVING && !noticedThisRide
}

/** Watches for earphones (Bluetooth, wired, USB) while a ride goes on. */
class EarphoneWatch(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    @Volatile
    var connected = false
        private set

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = update()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = update()
    }

    fun start() {
        update()
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun stop() = audio.unregisterAudioDeviceCallback(callback)

    private fun update() {
        connected = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in EARPHONES }
    }

    private companion object {
        val EARPHONES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}

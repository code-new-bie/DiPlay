// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var exportButton: Button? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.home_permission_nearby_devices_title), getString(R.string.home_permission_nearby_devices_message))
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            getString(R.string.home_setup_error)
        }
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { page = "home"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); super.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onResume() {
        super.onResume(); handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && page == "home") render()
        if (initialLaunch) {
            initialLaunch = false
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun render() {
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        val content = column().apply { setPadding(dp(32), dp(24), dp(32), dp(32)) }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_carplay); contentDescription = getString(R.string.home_cd_carplay) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(label(getString(R.string.home_app_name), 26, TEXT, true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(button(if (page == "home") getString(R.string.home_btn_car_home) else getString(R.string.home_btn_back), false) {
            if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            else { page = "home"; render() }
        }, LinearLayout.LayoutParams(dp(130), dp(56)))
        content.addView(header)
        content.addView(space(24))
        when (page) {
            "settings" -> settings(content)
            "about" -> about(content)
            else -> home(content)
        }
        setContentView(scroll)
        refreshStatus()
    }

    private fun home(content: LinearLayout) {
        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        left.addView(label(getString(R.string.home_hero_tagline), 12, ACCENT, true).apply { letterSpacing = .16f })
        left.addView(label(getString(R.string.home_hero_title), if (wide) 42 else 36, TEXT, true).apply { setPadding(0, dp(12), 0, dp(10)) })
        left.addView(label(getString(R.string.home_hero_subtitle), 19, MUTED))
        val card = card()
        card.addView(label(getString(R.string.home_wireless_card_title), 12, ACCENT, true).apply { letterSpacing = .12f })
        status = label(getString(R.string.home_status_ready_when_you_are), 24, TEXT, true).apply { setPadding(0, dp(10), 0, dp(16)) }
        card.addView(status)
        connectButton = button(getString(R.string.home_btn_connect_phone), true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        card.addView(connectButton, matchButton())
        card.addView(label(getString(R.string.home_wireless_hint), 15, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        if (carHotspotOff()) {
            card.addView(label(getString(R.string.home_hotspot_off_warning, AirPlayPersistence.loadManualHotspotSsid(this)), 15, WARNING).apply { setPadding(0, dp(14), 0, 0) })
            card.addView(button(getString(R.string.home_btn_open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(10, 56))
        }
        card.addView(button(getString(R.string.home_btn_choose_iphone), false) { choosePhone() }, matchButton(16, 56))
        disconnectButton = button(getString(R.string.home_btn_disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        card.addView(disconnectButton, matchButton(10, 56))
        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.home_cd_carplay_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        right.addView(button(getString(R.string.home_btn_connect_usb), false) { connect(false) }, matchButton())
        right.addView(label(getString(R.string.home_usb_hint), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24)) })
        right.addView(button(getString(R.string.home_btn_settings), false) { page = "settings"; render() }, matchButton())
        right.addView(label(getString(R.string.home_settings_teaser), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
        right.addView(label(getString(R.string.home_public_preview, version()), 12, MUTED).apply { letterSpacing = .08f })
        if (wide) {
            // Both rows share column widths. The USB button starts at the wireless
            // card's top edge, independently of hero wrapping or font scaling.
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(40), LinearLayout.LayoutParams(dp(40), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            body.addView(left)
            body.addView(space(26))
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        setupError?.let { body.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
        content.addView(body)
    }

    private fun settings(content: LinearLayout) {
        content.addView(label(getString(R.string.home_settings_title), 34, TEXT, true))
        content.addView(label(getString(R.string.home_settings_intro), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.home_section_auto_connect)) { card ->
            toggle(card, getString(R.string.home_toggle_connect_on_open), getString(R.string.home_toggle_connect_on_open_desc), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            toggle(card, getString(R.string.home_toggle_open_after_start), getString(R.string.home_toggle_open_after_start_desc), AirPlayPersistence.loadAutoStartOnBoot(this)) { AirPlayPersistence.saveAutoStartOnBoot(this, it) }
            card.addView(button(getString(R.string.home_btn_choose_iphone_named, DiPlayPreferences.phoneName(this)), false) { choosePhone() }, matchButton(12, 60))
        }
        section(content, getString(R.string.home_section_wireless_connection)) { card -> wirelessLinkControls(card) }
        section(content, getString(R.string.home_section_display_performance)) { card ->
            iconSizeControl(card)
            carPlaySizeControl(card)
            choice(card, getString(R.string.home_choice_resolution), listOf(getString(R.string.home_res_native), getString(R.string.home_res_80), getString(R.string.home_res_60)), listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, getString(R.string.home_choice_music_buffer), listOf(getString(R.string.home_buffer_300), getString(R.string.home_buffer_500), getString(R.string.home_buffer_1000)),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(card, getString(R.string.home_choice_framerate), listOf(getString(R.string.home_fps_30), getString(R.string.home_fps_60)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            toggle(card, getString(R.string.home_toggle_efficient_video), getString(R.string.home_toggle_efficient_video_desc), AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
            toggle(card, getString(R.string.home_toggle_rhd), getString(R.string.home_toggle_rhd_desc), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            toggle(card, getString(R.string.home_toggle_fullscreen), getString(R.string.home_toggle_fullscreen_desc), AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
            }
        }
        if (com.shilapi.xcertplay.hud.BydOutputSettings.available(this)) section(content, getString(R.string.home_section_byd_navigation)) { card ->
            toggle(card, getString(R.string.home_toggle_byd_navigation), getString(R.string.home_toggle_byd_navigation_desc),
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
        }
        section(content, getString(R.string.home_section_audio)) { card ->
            toggle(card, getString(R.string.home_toggle_audio_focus), getString(R.string.home_toggle_audio_focus_desc), AirPlayPersistence.loadAudioFocusEnabled(this)) { AirPlayPersistence.saveAudioFocusEnabled(this, it) }
            mediaChannelControl(card)
            navigationChannelControl(card)
        }
        section(content, getString(R.string.home_section_permissions)) { card ->
            card.addView(label(getString(R.string.home_permissions_note), 16, MUTED))
            card.addView(button(getString(R.string.home_btn_app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button(getString(R.string.home_btn_bluetooth_settings), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
            card.addView(button(getString(R.string.home_btn_wireless_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
        section(content, getString(R.string.home_section_about_diagnostics)) { card ->
            card.addView(button(getString(R.string.home_btn_about), false) { page = "about"; render() }, matchButton(0, 60))
            exportButton = button(if (exportInProgress) getString(R.string.home_btn_saving_report) else getString(R.string.home_btn_save_report), false) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                else chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getString(R.string.home_report_destination_downloads) else getString(R.string.home_report_destination_pick)
            card.addView(label(destination + getString(R.string.home_report_privacy), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
    }

    private fun about(content: LinearLayout) {
        content.addView(label(getString(R.string.home_app_name), 40, TEXT, true))
        content.addView(label(getString(R.string.home_about_tagline), 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.home_about_section_title, version())) { card ->
            card.addView(label(getString(R.string.home_about_body), 17, TEXT))
        }
        section(content, getString(R.string.home_section_open_source)) { card ->
            card.addView(label(getString(R.string.home_open_source_body), 16, MUTED))
        }
    }

    private fun iconSizeControl(parent: LinearLayout) {
        val presets = com.shilapi.xcertplay.airplay.CarPlayUiScale.presets
        fun label(percent: Int) = com.shilapi.xcertplay.airplay.CarPlayUiScale.label(percent)
        val control = button(getString(R.string.home_btn_icon_size, label(AirPlayPersistence.loadUiScalePercent(this))), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadUiScalePercent(this)
            var selection = presets.indexOf(current)
            AlertDialog.Builder(this).setTitle(getString(R.string.home_dialog_icon_size_title))
                .setSingleChoiceItems(arrayOf(getString(R.string.home_scale_75), getString(R.string.home_scale_85), getString(R.string.home_scale_default), getString(R.string.home_scale_115)), selection) { _, index -> selection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.home_btn_apply_reconnect) else getString(R.string.home_btn_save)) { _, _ ->
                    val selected = presets[selection]
                    DisplayDiagnosticSnapshot.selection(this, current, selected,
                        selected != current && CarPlayBackgroundSession.hasSession())
                    AirPlayPersistence.saveUiScalePercent(this, selected)
                    control.text = getString(R.string.home_btn_icon_size, label(selected))
                    if (selected != current && CarPlayBackgroundSession.hasSession()) {
                        connect(AirPlayPersistence.loadWirelessEnabled(this))
                    }
                }.setNegativeButton(getString(R.string.home_btn_cancel), null).show()
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(label(getString(R.string.home_icon_size_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    // The car hotspot link needs the hotspot on; DiPlay only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle(getString(R.string.home_hotspot_off_title))
            .setMessage(getString(R.string.home_hotspot_off_message, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.home_btn_open_car_hotspot_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.home_btn_connect_phone)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.home_btn_cancel), null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    // Wi-Fi Direct is the default link. The car's own hotspot is an alternative when Wi-Fi Direct is unstable.
    // The runtime config rejects manual mode without valid credentials, so it is only saved together with them.
    private fun wirelessLinkControls(parent: LinearLayout) {
        val carHotspot = AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL
        val options = arrayOf(getString(R.string.home_wireless_link_direct), getString(R.string.home_wireless_link_car_hotspot))
        val control = button(getString(R.string.home_wireless_link_choice, options[if (carHotspot) 1 else 0]), false) {}
        control.setOnClickListener {
            var selection = if (carHotspot) 1 else 0
            AlertDialog.Builder(this).setTitle(getString(R.string.home_wireless_link_title))
                .setSingleChoiceItems(options, selection) { _, index -> selection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.home_btn_apply_reconnect) else getString(R.string.home_btn_save)) { _, _ ->
                    when {
                        (selection == 1) == carHotspot -> Unit
                        selection == 0 -> applyWirelessLink(WirelessHotspotMode.WIFI_P2P)
                        hotspotError(storedSsid(), storedPassword()) == null -> applyWirelessLink(WirelessHotspotMode.MANUAL)
                        else -> askHotspotCredentials { ssid, password ->
                            saveHotspotCredentials(ssid, password)
                            applyWirelessLink(WirelessHotspotMode.MANUAL)
                        }
                    }
                }.setNegativeButton(getString(R.string.home_btn_cancel), null).show()
        }
        parent.addView(control, matchButton(0, 60)); parent.addView(space(12))
        if (!carHotspot) {
            parent.addView(label(getString(R.string.home_wireless_direct_note), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            return
        }
        val ssid = storedSsid()
        val password = storedPassword()
        parent.addView(button(getString(R.string.home_hotspot_name_value, ssid), false) {
            textInput(getString(R.string.home_hotspot_name), ssid, secret = false) { value ->
                hotspotError(value, password)?.let { toast(it); return@textInput }
                saveHotspotCredentials(value, password)
                render()
            }
        }, matchButton(0, 60))
        parent.addView(space(12))
        parent.addView(button(getString(R.string.home_hotspot_password_value, if (password.isEmpty()) getString(R.string.home_hotspot_password_none) else "•".repeat(8)), false) {
            textInput(getString(R.string.home_hotspot_password), password, secret = true) { value ->
                hotspotError(ssid, value)?.let { toast(it); return@textInput }
                saveHotspotCredentials(ssid, value)
                render()
            }
        }, matchButton(0, 60))
        parent.addView(label(getString(R.string.home_hotspot_setup_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    /** Media output: the full 1-40 channel range is selectable, never probed. */
    private fun mediaChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.home_choice_summary, getString(R.string.home_media_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadMediaAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.home_media_channel_label),
                current = current,
                onApply = { value -> applyMediaChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(0, 60))
    }

    /** Navigation guidance output: the full 1-40 channel range is selectable, never probed. */
    private fun navigationChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.home_choice_summary, getString(R.string.home_nav_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadNavigationAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.home_nav_channel_label),
                current = current,
                onApply = { value -> applyNavigationChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(label(getString(R.string.home_nav_channel_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    /**
     * Offers the whole 1-40 channel range (0 = automatic) without any probing. The numbers
     * are head-unit-defined channels routed by the vehicle's audio policy; probing cannot
     * see them, and a full list is the only way not to hide a channel the car really has.
     */
    private fun showChannelDialog(title: String, current: Int, onApply: (Int) -> Unit) {
        val streamNames = mapOf(
            1 to getString(R.string.home_ch_stream_system),
            2 to getString(R.string.home_ch_stream_ring),
            3 to getString(R.string.home_ch_stream_music),
            4 to getString(R.string.home_ch_stream_alarm),
            5 to getString(R.string.home_ch_stream_notification),
            6 to getString(R.string.home_ch_stream_sco),
            8 to getString(R.string.home_ch_stream_dtmf),
            9 to getString(R.string.home_ch_stream_tts),
            10 to getString(R.string.home_ch_stream_accessibility),
        )
        val labels = (0..40).map { id ->
            when {
                id == 0 -> getString(R.string.home_nav_channel_auto)
                streamNames.containsKey(id) -> "$id · ${streamNames[id]}"
                else -> id.toString()
            }
        }
        var selection = current.coerceIn(0, 40)
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selection) { _, which -> selection = which }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.home_btn_apply_reconnect) else getString(R.string.home_btn_save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(getString(R.string.home_btn_cancel), null).show()
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun channelLabel(value: Int): String =
        if (value == 0) getString(R.string.home_nav_channel_auto)
        else getString(R.string.home_nav_channel_value, value)

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String): String? =
        when (com.shilapi.xcertplay.orchestration.ManualHotspotValidation.validate(ssid, password)) {
            com.shilapi.xcertplay.orchestration.ManualHotspotValidationError.NAME_REQUIRED -> getString(R.string.home_hotspot_error_name_required)
            com.shilapi.xcertplay.orchestration.ManualHotspotValidationError.NAME_TOO_LONG -> getString(R.string.home_hotspot_error_name_too_long)
            com.shilapi.xcertplay.orchestration.ManualHotspotValidationError.INVALID_CHARACTER -> getString(R.string.home_hotspot_error_invalid_character)
            com.shilapi.xcertplay.orchestration.ManualHotspotValidationError.PASSWORD_LENGTH -> getString(R.string.home_hotspot_error_password_length)
            null -> null
        }

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        textInput(getString(R.string.home_hotspot_name), storedSsid(), secret = false) { ssid ->
            textInput(getString(R.string.home_hotspot_password), storedPassword(), secret = true) { password ->
                val error = hotspotError(ssid, password)
                if (error != null) toast(error) else done(ssid, password)
            }
        }
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        if (CarPlayBackgroundSession.hasSession()) connect(true)
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.home_btn_save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.home_btn_cancel), null).show()
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.home_carplay_size), listOf(
            getString(R.string.home_carplay_size_large),
            getString(R.string.home_carplay_size_medium),
            getString(R.string.home_carplay_size_small),
        ), sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.home_carplay_size_note), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun connect(wireless: Boolean) {
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle(getString(R.string.home_dialog_turn_on_bluetooth_title))
                .setMessage(getString(R.string.home_dialog_enable_bluetooth_message))
                .setPositiveButton(getString(R.string.home_btn_open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.home_btn_later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(getString(R.string.home_dialog_pair_iphone_title))
                .setMessage(getString(R.string.home_dialog_pair_iphone_message))
                .setPositiveButton(getString(R.string.home_btn_open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.home_btn_got_it), null).show(); return
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.home_dialog_choose_iphone_title))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.home_fallback_paired_device)
                if (devices.count { it.name == device.name } > 1) getString(R.string.home_device_name_with_suffix, name, device.address.takeLast(5)) else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: getString(R.string.home_fallback_iphone_name))
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.home_btn_pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.home_btn_cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.home_btn_wireless_help))
            .setMessage(getString(R.string.home_wireless_help_message))
            .setPositiveButton(getString(R.string.home_btn_got_it), null)
            .setNeutralButton(getString(R.string.home_btn_reset_carplay_wifi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle(getString(R.string.home_dialog_reset_wifi_title))
            .setMessage(getString(R.string.home_dialog_reset_wifi_message))
            .setPositiveButton(getString(R.string.home_btn_reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.home_btn_cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.home_toast_no_wifi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast(getString(R.string.home_toast_wifi_direct_busy))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast(getString(R.string.home_toast_reset_failed)) }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp(getString(R.string.home_permission_wireless_title), getString(R.string.home_permission_wireless_message))
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> getString(R.string.home_status_setup_attention)
            CarPlayBackgroundSession.active -> getString(R.string.home_status_connected)
            running -> getString(R.string.home_status_connecting)
            DiPlayPreferences.phoneAddress(this) != null -> getString(R.string.home_status_ready_for_phone, DiPlayPreferences.phoneName(this))
            else -> getString(R.string.home_status_ready_when_you_are)
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) getString(R.string.home_btn_open_carplay) else getString(R.string.home_btn_connect_phone)
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
    }
    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getString(R.string.home_toast_save_location_failed)
                else getString(R.string.home_toast_no_file_picker))
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.home_btn_saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("DiPlay ${version()} · private beta diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else error("A save location is required")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.home_btn_save_report) }
                if (result.isSuccess) {
                    AlertDialog.Builder(this).setTitle(getString(R.string.home_dialog_report_saved_title))
                        .setMessage(if (uri == null) getString(R.string.home_dialog_report_saved_downloads, fileName) else getString(R.string.home_dialog_report_saved_location))
                        .setPositiveButton(getString(R.string.home_btn_done), null).show()
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.home_dialog_report_failed_title))
                        .setMessage(getString(R.string.home_dialog_report_failed_message))
                        .setPositiveButton(getString(R.string.home_btn_choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.home_btn_close), null).show()
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.home_btn_app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.home_btn_later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.home_toast_open_setting_hint)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun section(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val card = card(); card.addView(label(title, 22, TEXT, true).apply { setPadding(0, 0, 0, dp(16)) }); build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit) {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(Switch(this).apply { contentDescription = title; isChecked = value; minHeight = dp(56); buttonTintList = ColorStateList.valueOf(ACCENT); setOnCheckedChangeListener { _, checked -> save(checked) } })
        parent.addView(line)
    }
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, save: (Int) -> Unit) {
        var selection = current
        val button = button(getString(R.string.home_choice_summary, title, options[selection]), false) {}
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.home_btn_apply_reconnect) else getString(R.string.home_btn_save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = getString(R.string.home_choice_summary, title, options[selection])
                        if (CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.home_btn_cancel), null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(24), dp(24), dp(24), dp(24)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 18f; setTextColor(if (primary) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER), null)
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56); stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val BG = Color.rgb(12, 17, 27)
        private val SURFACE = Color.rgb(21, 30, 44)
        private val BORDER = Color.rgb(42, 56, 75)
        private val ACCENT = Color.rgb(166, 200, 255)
        private val TEXT = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(168, 182, 202)
        private val WARNING = Color.rgb(255, 196, 128)
    }
}

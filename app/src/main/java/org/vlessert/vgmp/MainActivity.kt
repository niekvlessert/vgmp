package org.vlessert.vgmp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.vlessert.vgmp.databinding.ActivityMainBinding
import org.vlessert.vgmp.engine.VgmEngine
import org.vlessert.vgmp.service.VgmPlaybackService
import org.vlessert.vgmp.ui.LibraryFragment
import org.vlessert.vgmp.ui.NowPlayingFragment
import org.vlessert.vgmp.settings.SettingsManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var playbackService: VgmPlaybackService? = null
    private var serviceBound = false
    private var isAnalyzerVisible = false
    
    // Auto-hide for main screen
    private var lastInteractionTime = System.currentTimeMillis()
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? VgmServiceBinder ?: return
            val svc = localBinder.getService()
            playbackService = svc
            serviceBound = true
            lifecycleScope.launch {
                svc.playbackInfo.collectLatest {
                    updateMiniPlayer()
                }
            }
            supportFragmentManager.fragments.filterIsInstance<LibraryFragment>()
                .forEach { it.onServiceConnected(playbackService!!) }
            supportFragmentManager.fragments.filterIsInstance<NowPlayingFragment>()
                .forEach { it.onServiceConnected(playbackService!!) }
            
            // Start observing spectrum for kaleidoscope
            startSpectrumObserver()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        requestPermissionsIfNeeded()
        startPlaybackService()

        if (savedInstanceState == null) {
            showFragment(LibraryFragment.newInstance())
        }

        binding.miniPlayer.root.setOnClickListener {
            showNowPlayingSheet()
        }
        binding.miniPlayer.btnMiniPrev.setOnClickListener {
            playbackService?.previousTrack()
        }
        binding.miniPlayer.btnMiniPlayPause.setOnClickListener {
            val svc = playbackService ?: return@setOnClickListener
            if (svc.playing) svc.getMediaSession().controller.transportControls.pause()
            else svc.getMediaSession().controller.transportControls.play()
        }
        binding.miniPlayer.btnMiniNext.setOnClickListener {
            playbackService?.nextTrack()
        }
        
        // Setup analyzer touch listener
        binding.kaleidoscopeView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (isAnalyzerVisible) {
                    hideAnalyzer()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        binding.spectrumBarsView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (isAnalyzerVisible) {
                    hideAnalyzer()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        
        // Setup auto-hide for main screen
        setupMainAutoHide()
    }
    
    private fun setupMainAutoHide() {
        // Track touches on main content
        binding.mainContent.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                lastInteractionTime = System.currentTimeMillis()
                if (isAnalyzerVisible) {
                    hideAnalyzer()
                }
            }
            false
        }
        binding.mainContent.isClickable = true
        
        // Start auto-hide check
        autoHideRunnable = object : Runnable {
            override fun run() {
                val timeout = SettingsManager.getFadeTimeout(this@MainActivity) * 1000L
                val isPlayerOpen = supportFragmentManager.findFragmentByTag("now_playing")?.isVisible == true
                val isSettingsOpen = supportFragmentManager.findFragmentByTag("settings")?.isVisible == true
                val isDownloadOpen = supportFragmentManager.findFragmentByTag("download")?.isVisible == true
                val isVgmRipsOpen = supportFragmentManager.findFragmentByTag("vgmrips_search")?.isVisible == true
                val isPlaying = playbackService?.playing == true
                
                // Only trigger kaleidoscope if:
                // - Timeout is set (> 0)
                // - Kaleidoscope not already visible
                // - No dialogs are open (player, settings, download, vgmrips)
                // - Music is actually playing
                // - Inactivity timeout reached
                val anyDialogOpen = isPlayerOpen || isSettingsOpen || isDownloadOpen || isVgmRipsOpen
                if (timeout > 0 && !isAnalyzerVisible && !anyDialogOpen && isPlaying && System.currentTimeMillis() - lastInteractionTime >= timeout) {
                    showAnalyzer()
                }
                autoHideHandler.postDelayed(this, 1000)
            }
        }
        autoHideHandler.postDelayed(autoHideRunnable!!, 1000)
    }
    
    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteractionTime = System.currentTimeMillis()
    }
    
    private fun startSpectrumObserver() {
        val svc = playbackService ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                svc.spectrum.collect { magnitudes ->
                    if (isAnalyzerVisible) {
                        // Hide analyzer if music stopped
                        if (!svc.playing) {
                            hideAnalyzer()
                        } else {
                            when (SettingsManager.getAnalyzerStyle(this@MainActivity)) {
                                SettingsManager.ANALYZER_STYLE_BARS -> binding.spectrumBarsView.updateFFT(magnitudes)
                                else -> binding.kaleidoscopeView.updateFFT(magnitudes)
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun showAnalyzer() {
        // Only show if enabled and music is playing
        if (!SettingsManager.isAnalyzerEnabled(this)) return
        if (playbackService?.playing != true) return
        
        isAnalyzerVisible = true
        
        // Dismiss any open bottom sheet (NowPlayingFragment)
        supportFragmentManager.findFragmentByTag("now_playing")?.let { fragment ->
            (fragment as? NowPlayingFragment)?.dismiss()
        }
        
        // Hide system UI for true fullscreen
        hideSystemUI()
        
        val style = SettingsManager.getAnalyzerStyle(this)
        val showKaleidoscope = style == SettingsManager.ANALYZER_STYLE_KALEIDOSCOPE
        binding.kaleidoscopeView.visibility = if (showKaleidoscope) View.VISIBLE else View.GONE
        binding.spectrumBarsView.visibility = if (showKaleidoscope) View.GONE else View.VISIBLE

        val targetView = if (showKaleidoscope) binding.kaleidoscopeView else binding.spectrumBarsView
        targetView.alpha = 0f
        targetView.animate().alpha(1f).setDuration(500).start()
        // Hide main content
        binding.mainContent.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction {
                binding.mainContent.visibility = View.GONE
            }
            .start()
    }
    
    private fun hideAnalyzer() {
        if (!isAnalyzerVisible) return
        isAnalyzerVisible = false
        
        // Restore system UI
        showSystemUI()
        
        lastInteractionTime = System.currentTimeMillis()
        binding.mainContent.visibility = View.VISIBLE
        binding.mainContent.alpha = 0f
        binding.mainContent.animate()
            .alpha(1f)
            .setDuration(500)
            .start()
        binding.kaleidoscopeView.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction { binding.kaleidoscopeView.visibility = View.GONE }
            .start()
        binding.spectrumBarsView.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction { binding.spectrumBarsView.visibility = View.GONE }
            .start()
    }
    
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }
    
    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
    
    fun isKaleidoscopeShowing() = isAnalyzerVisible
    
    fun resetAutoHideTimer() {
        lastInteractionTime = System.currentTimeMillis()
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        }
        perms.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val notGranted = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 42)
        }
    }

    private fun startPlaybackService() {
        val intent = Intent(this, VgmPlaybackService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun showFragment(fragment: Fragment, addToBack: Boolean = false) {
        val tx = supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
        if (addToBack) tx.addToBackStack(null)
        tx.commit()
    }

    fun showNowPlayingSheet() {
        val sheet = NowPlayingFragment.newInstance()
        playbackService?.let { sheet.onServiceConnected(it) }
        sheet.show(supportFragmentManager, "now_playing")
    }

    fun updateMiniPlayer() {
        val svc = playbackService ?: return
        val track = svc.currentTrack
        val game = svc.currentGame
        binding.miniPlayer.tvMiniTitle.text = track?.title ?: getString(R.string.no_track_playing)
        binding.miniPlayer.tvMiniGame.text = game?.name ?: ""
        val isPlaying = svc.playing
        binding.miniPlayer.btnMiniPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        if (game?.artPath?.isNotEmpty() == true) {
            try {
                val bm = android.graphics.BitmapFactory.decodeFile(game.artPath)
                binding.miniPlayer.ivMiniArt.setImageBitmap(bm)
            } catch (e: Exception) {
                binding.miniPlayer.ivMiniArt.setImageResource(R.drawable.vgmp_logo)
            }
        } else {
            binding.miniPlayer.ivMiniArt.setImageResource(R.drawable.vgmp_logo)
        }
    }

    fun getService() = playbackService
    
    fun refreshLibrary() {
        supportFragmentManager.fragments.filterIsInstance<LibraryFragment>()
            .forEach { fragment ->
                lifecycleScope.launch {
                    fragment.refreshView()
                }
            }
        // Refresh playback service's game list
        playbackService?.refreshGames()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        // Update the checked state of bass and reverb menu items
        lifecycleScope.launch {
            try {
                val bassItem = menu.findItem(R.id.action_bass)
                val reverbItem = menu.findItem(R.id.action_reverb)
                val isBassEnabled = VgmEngine.getBassEnabled()
                val isReverbEnabled = VgmEngine.getReverbEnabled()
                
                if (bassItem != null) {
                    bassItem.isChecked = isBassEnabled
                    bassItem.icon?.setTint(if (isBassEnabled) Color.GREEN else Color.WHITE)
                }
                if (reverbItem != null) {
                    reverbItem.isChecked = isReverbEnabled
                    reverbItem.icon?.setTint(if (isReverbEnabled) Color.GREEN else Color.WHITE)
                }
            } catch (e: Exception) {
                // Engine not initialized yet, ignore
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_download -> {
                org.vlessert.vgmp.ui.DownloadDialogFragment.newInstance()
                    .show(supportFragmentManager, "download")
                true
            }
            R.id.action_settings -> {
                org.vlessert.vgmp.ui.SettingsDialogFragment.newInstance()
                    .show(supportFragmentManager, "settings")
                true
            }
            R.id.action_bass -> {
                // Toggle bass
                lifecycleScope.launch {
                    val currentBass = VgmEngine.getBassEnabled()
                    VgmEngine.setBassEnabled(!currentBass)
                    val newBass = !currentBass
                    item.isChecked = newBass
                    item.icon?.setTint(if (newBass) Color.GREEN else Color.WHITE)
                }
                true
            }
            R.id.action_reverb -> {
                // Toggle reverb
                lifecycleScope.launch {
                    val currentReverb = VgmEngine.getReverbEnabled()
                    VgmEngine.setReverbEnabled(!currentReverb)
                    val newReverb = !currentReverb
                    item.isChecked = newReverb
                    item.icon?.setTint(if (newReverb) Color.GREEN else Color.WHITE)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}

class VgmServiceBinder(private val service: VgmPlaybackService) : android.os.Binder() {
    fun getService() = service
}

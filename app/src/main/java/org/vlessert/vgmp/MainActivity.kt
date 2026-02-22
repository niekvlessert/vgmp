package org.vlessert.vgmp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.vlessert.vgmp.databinding.ActivityMainBinding
import org.vlessert.vgmp.service.VgmPlaybackService
import org.vlessert.vgmp.ui.LibraryFragment
import org.vlessert.vgmp.ui.NowPlayingFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var playbackService: VgmPlaybackService? = null
    private var serviceBound = false

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
                binding.miniPlayer.ivMiniArt.setImageResource(R.drawable.ic_album_placeholder)
            }
        } else {
            binding.miniPlayer.ivMiniArt.setImageResource(R.drawable.ic_album_placeholder)
        }
    }

    fun getService() = playbackService

    fun refreshLibrary() {
        supportFragmentManager.fragments.filterIsInstance<LibraryFragment>()
            .forEach { fragment ->
                lifecycleScope.launch {
                    fragment.performSearch("")
                }
            }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
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
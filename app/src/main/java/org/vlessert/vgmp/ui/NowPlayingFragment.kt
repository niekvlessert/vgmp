package org.vlessert.vgmp.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.vlessert.vgmp.R
import org.vlessert.vgmp.databinding.FragmentNowPlayingBinding
import org.vlessert.vgmp.engine.VgmEngine
import org.vlessert.vgmp.library.GameLibrary
import org.vlessert.vgmp.service.VgmPlaybackService
import java.io.File

class NowPlayingFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!
    private val service: VgmPlaybackService? get() = (activity as? org.vlessert.vgmp.MainActivity)?.getService()
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false

    companion object {
        fun newInstance() = NowPlayingFragment()
    }

    override fun getTheme() = R.style.BottomSheetStyle

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        setupSeekbar()
        updateUI()
        startPositionUpdater()
        observePlaybackInfo()
        
        // Set navigation bar color on the dialog's window to match player background
        dialog?.window?.navigationBarColor = requireContext().getColor(R.color.vgmp_background)
    }

    fun onServiceConnected(svc: VgmPlaybackService) {
        observePlaybackInfo()
    }

    private fun observePlaybackInfo() {
        val svc = service ?: return
        val view = view ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            svc.playbackInfo.collectLatest {
                updateUI()
            }
        }
    }

    private fun setupButtons() {
        binding.btnPrev.setOnClickListener { service?.previousTrack() }
        binding.btnPlayPause.setOnClickListener {
            service?.let { svc ->
                if (svc.playing) svc.getMediaSession().controller.transportControls.pause()
                else svc.getMediaSession().controller.transportControls.play()
            }
            updatePlayPauseButton()
        }
        binding.btnNext.setOnClickListener { service?.nextTrack() }
        binding.btnRandom.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val nextMode = when (svc.getShuffle()) {
                VgmPlaybackService.ShuffleMode.OFF -> VgmPlaybackService.ShuffleMode.GAME
                VgmPlaybackService.ShuffleMode.GAME -> VgmPlaybackService.ShuffleMode.ALL
                VgmPlaybackService.ShuffleMode.ALL -> VgmPlaybackService.ShuffleMode.OFF
            }
            svc.setShuffle(nextMode)
            updateModeButtons()
        }
        binding.btnLoop.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            svc.setLoop(!svc.getLoop())
            updateModeButtons()
        }
        binding.btnTrackFavorite.setOnClickListener {
            val track = service?.currentTrack ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                GameLibrary.toggleTrackFavorite(track.id)
                updateTrackFavoriteButton()
            }
        }
    }

    private fun setupSeekbar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val durMs = service?.currentTrack?.durationSamples?.times(1000L)
                        ?.div(VgmPlaybackService.SAMPLE_RATE) ?: 0L
                    binding.tvCurrentTime.text = formatTime(progress * durMs / 100L)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                val durMs = service?.currentTrack?.durationSamples?.times(1000L)
                    ?.div(VgmPlaybackService.SAMPLE_RATE) ?: return
                val seekMs = (seekBar?.progress ?: 0) * durMs / 100L
                service?.getMediaSession()?.controller?.transportControls?.seekTo(seekMs)
            }
        })
    }

    private fun updateUI() {
        val binding = _binding ?: return
        val svc = service ?: return
        val game = svc.currentGame
        val track = svc.currentTrack
        if (game == null || track == null) {
            binding.tvTitle.text = getString(R.string.no_track_playing)
            binding.tvGame.text = ""
            binding.tvSystem.text = ""
            binding.tvAuthor.text = ""
            binding.tvCreatorDate.text = ""
            binding.tvNotes.text = ""
            binding.ivArt.setImageResource(R.drawable.ic_album_placeholder)
            return
        }

        // Album art
        if (game.artPath.isNotEmpty() && File(game.artPath).exists()) {
            try {
                binding.ivArt.setImageBitmap(BitmapFactory.decodeFile(game.artPath))
            } catch (e: Exception) {
                binding.ivArt.setImageResource(R.drawable.ic_album_placeholder)
            }
        } else {
            binding.ivArt.setImageResource(R.drawable.ic_album_placeholder)
        }

        val durSamples = track.durationSamples
        if (durSamples > 0) {
            val durSecs = durSamples / VgmPlaybackService.SAMPLE_RATE.toLong()
            binding.tvTotalTime.text = formatTime(durSecs * 1000L)
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            val rawTags = VgmEngine.getTags()
            val tags = VgmEngine.parseTags(rawTags)
            binding.tvTitle.text = tags.displayTitle
            binding.tvGame.text = tags.displayGame
            binding.tvSystem.text = tags.displaySystem
            binding.tvAuthor.text = tags.displayAuthor
            
            val creatorDate = listOf(tags.creator, tags.date).filter { it.isNotEmpty() }.joinToString(" | ")
            binding.tvCreatorDate.text = creatorDate
            binding.tvNotes.text = tags.notes
            
            updateVolumeSliders()
        }
        
        updatePlayPauseButton()
        updateModeButtons()
    }

    private fun updateModeButtons() {
        val binding = _binding ?: return
        val svc = service ?: return
        
        // Random button color/icon
        when (svc.getShuffle()) {
            VgmPlaybackService.ShuffleMode.OFF -> {
                binding.btnRandom.setColorFilter(resources.getColor(R.color.vgmp_text_secondary, null))
                binding.btnRandom.alpha = 0.5f
            }
            VgmPlaybackService.ShuffleMode.GAME -> {
                binding.btnRandom.setColorFilter(resources.getColor(R.color.vgmp_accent, null))
                binding.btnRandom.alpha = 1.0f
                // Maybe change icon or show a "G" badge? For now just color.
            }
            VgmPlaybackService.ShuffleMode.ALL -> {
                binding.btnRandom.setColorFilter(resources.getColor(R.color.white, null))
                binding.btnRandom.alpha = 1.0f
            }
        }
        
        // Loop button color
        if (svc.getLoop()) {
            binding.btnLoop.setColorFilter(resources.getColor(R.color.vgmp_accent, null))
            binding.btnLoop.alpha = 1.0f
        } else {
            binding.btnLoop.setColorFilter(resources.getColor(R.color.vgmp_text_secondary, null))
            binding.btnLoop.alpha = 0.5f
        }
        
        // Update track favorite button
        updateTrackFavoriteButton()
    }

    private fun updateTrackFavoriteButton() {
        val binding = _binding ?: return
        val track = service?.currentTrack
        if (track == null) {
            binding.btnTrackFavorite.visibility = View.GONE
            return
        }
        binding.btnTrackFavorite.visibility = View.VISIBLE
        binding.btnTrackFavorite.setImageResource(
            if (track.isFavorite) R.drawable.ic_star else R.drawable.ic_star_border
        )
    }

    private suspend fun updateVolumeSliders() {
        val binding = _binding ?: return
        val container = binding.volumesContainer
        
        val count = VgmEngine.getDeviceCount()
        // Only clear and rebuild if count changed or container is empty
        if (container.childCount != count) {
            container.removeAllViews()
            for (i in 0 until count) {
                val name = VgmEngine.getDeviceName(i)
                val vol = VgmEngine.getDeviceVolume(i)
                
                val view = LayoutInflater.from(context).inflate(R.layout.item_chip_volume, container, false)
                view.findViewById<android.widget.TextView>(R.id.tv_chip_name).text = name
                val sb = view.findViewById<SeekBar>(R.id.sb_chip_volume)
                sb.progress = vol
                sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                VgmEngine.setDeviceVolume(i, p)
                            }
                        }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
                container.addView(view)
            }
        }
    }

    private fun updatePlayPauseButton() {
        val binding = _binding ?: return
        val playing = service?.playing ?: false
        binding.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun startPositionUpdater() {
        handler.post(object : Runnable {
            override fun run() {
                val binding = _binding ?: return
                if (!isAdded || isRemoving) return
                val svc = service ?: run { handler.postDelayed(this, 500); return }
                val playbackState = svc.getMediaSession().controller.playbackState
                val posMs = playbackState?.position ?: 0L
                val track = svc.currentTrack
                val durSamples = track?.durationSamples ?: 0L
                if (durSamples > 0 && !isSeeking) {
                    val durMs = durSamples * 1000L / VgmPlaybackService.SAMPLE_RATE.toLong()
                    val pct = (posMs * 100L / durMs).toInt().coerceIn(0, 100)
                    binding.seekBar.progress = pct
                    binding.tvCurrentTime.text = formatTime(posMs)
                }
                updatePlayPauseButton()
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun formatTime(ms: Long): String {
        val secs = ms / 1000
        return "%d:%02d".format(secs / 60, secs % 60)
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        (activity as? org.vlessert.vgmp.MainActivity)?.resetAutoHideTimer()
        super.onDestroyView()
        _binding = null
    }
}

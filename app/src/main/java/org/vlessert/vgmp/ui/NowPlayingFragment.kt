package org.vlessert.vgmp.ui

import android.graphics.BitmapFactory
import android.media.audiofx.Visualizer
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
import org.vlessert.vgmp.engine.VgmTags
import org.vlessert.vgmp.service.VgmPlaybackService
import java.io.File

class NowPlayingFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!
    private val service: VgmPlaybackService? get() = (activity as? org.vlessert.vgmp.MainActivity)?.getService()
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private var visualizer: Visualizer? = null

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
    }

    fun onServiceConnected(svc: VgmPlaybackService) {
        observePlaybackInfo()
        startVisualizer()
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
            binding.btnRandom.isSelected = !binding.btnRandom.isSelected
            svc.setRandom(binding.btnRandom.isSelected)
        }
        binding.btnLoop.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            binding.btnLoop.isSelected = !binding.btnLoop.isSelected
            svc.setLoop(binding.btnLoop.isSelected)
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
        startVisualizer() // Refresh if session changed
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

    private fun startVisualizer() {
        if (visualizer != null) return
        val sessionId = service?.audioSessionId ?: 0
        if (sessionId == 0) return
        
        try {
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, s: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, s: Int) {
                        fft?.let { _binding?.spectrumView?.updateFFT(it) }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (e: Exception) {
            android.util.Log.e("NowPlaying", "Visualizer fail", e)
        }
    }

    private fun stopVisualizer() {
        visualizer?.enabled = false
        visualizer?.release()
        visualizer = null
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
        stopVisualizer()
        super.onDestroyView()
        _binding = null
    }
}

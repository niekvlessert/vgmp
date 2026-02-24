package org.vlessert.vgmp.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
    
    // Playback speed options: 100%, 75%, 50%, 25%
    private val speedOptions = doubleArrayOf(1.0, 0.75, 0.5, 0.25)
    private var currentSpeedIndex = 0

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
    
    // Helper extension for building colored spanned strings
    private fun buildSpannedString(builderAction: SpannableStringBuilder.() -> Unit): Spanned {
        val builder = SpannableStringBuilder()
        builder.builderAction()
        return builder
    }
    
    private fun SpannableStringBuilder.colorSpan(text: String, color: Int): SpannableStringBuilder {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return this
    }

    fun onServiceConnected(svc: VgmPlaybackService) {
        observePlaybackInfo()
    }

    private fun observePlaybackInfo() {
        val svc = service ?: return
        val view = view ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            svc.playbackInfo.collectLatest { info ->
                updateUI()
                // Update duration display from live duration
                if (info.endlessLoop) {
                    binding.tvTotalTime.text = "∞"
                } else if (info.durationMs > 0) {
                    binding.tvTotalTime.text = formatTime(info.durationMs)
                }
                updateEndlessLoopButton()
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
            // Show tooltip
            val tooltipText = when (nextMode) {
                VgmPlaybackService.ShuffleMode.GAME -> getString(R.string.random_current_game)
                VgmPlaybackService.ShuffleMode.ALL -> getString(R.string.random_library)
                VgmPlaybackService.ShuffleMode.OFF -> getString(R.string.random_off)
            }
            showStyledToast(tooltipText)
        }
        binding.btnLoop.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val nextMode = when (svc.getLoop()) {
                VgmPlaybackService.LoopMode.OFF -> VgmPlaybackService.LoopMode.TRACK
                VgmPlaybackService.LoopMode.TRACK -> VgmPlaybackService.LoopMode.GAME
                VgmPlaybackService.LoopMode.GAME -> VgmPlaybackService.LoopMode.OFF
            }
            svc.setLoop(nextMode)
            updateModeButtons()
            // Show tooltip
            val tooltipText = when (nextMode) {
                VgmPlaybackService.LoopMode.TRACK -> getString(R.string.loop_current_track)
                VgmPlaybackService.LoopMode.GAME -> getString(R.string.loop_current_game)
                VgmPlaybackService.LoopMode.OFF -> getString(R.string.loop_off)
            }
            showStyledToast(tooltipText)
        }
        binding.btnTrackFavorite.setOnClickListener {
            val track = service?.currentTrack ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch {
                GameLibrary.toggleTrackFavorite(track.id)
                // Reload track to get updated favorite status
                val updatedTrack = GameLibrary.getTrackById(track.id)
                if (updatedTrack != null) {
                    service?.updateCurrentTrackFavorite(updatedTrack.isFavorite)
                }
                updateTrackFavoriteButton()
                // Refresh the library to show updated favorite status
                (activity as? org.vlessert.vgmp.MainActivity)?.refreshLibrary()
            }
        }
        binding.btnEndlessLoop.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            val newMode = !svc.getEndlessLoop()
            svc.setEndlessLoop(newMode)
            updateEndlessLoopButton()
            showStyledToast(if (newMode) "Endless loop enabled" else "Endless loop disabled")
        }
        binding.btnSpeed.setOnClickListener {
            // Cycle through speed options: 100% -> 75% -> 50% -> 25% -> 100%
            currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
            val speed = speedOptions[currentSpeedIndex]
            viewLifecycleOwner.lifecycleScope.launch {
                VgmEngine.setPlaybackSpeed(speed)
                updateSpeedButton()
                val speedPercent = (speed * 100).toInt()
                showStyledToast("Speed: $speedPercent%")
            }
        }
    }

    private fun setupSeekbar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Don't allow seeking in endless loop mode
                if (service?.getEndlessLoop() == true) return
                if (fromUser) {
                    val durMs = service?.playbackInfo?.value?.durationMs ?: 0L
                    if (durMs > 0) {
                        binding.tvCurrentTime.text = formatTime(progress * durMs / 100L)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { 
                // Don't allow seeking in endless loop mode
                if (service?.getEndlessLoop() == true) return
                isSeeking = true 
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Don't allow seeking in endless loop mode
                if (service?.getEndlessLoop() == true) return
                isSeeking = false
                val durMs = service?.playbackInfo?.value?.durationMs ?: 0L
                if (durMs > 0) {
                    val seekMs = (seekBar?.progress ?: 0) * durMs / 100L
                    service?.getMediaSession()?.controller?.transportControls?.seekTo(seekMs)
                }
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
            binding.tvCreator.text = ""
            binding.tvDate.text = ""
            binding.tvNotes.text = ""
            binding.ivArt.setImageResource(R.drawable.vgmp_logo)
            return
        }

        // Album art
        if (game.artPath.isNotEmpty() && File(game.artPath).exists()) {
            try {
                binding.ivArt.setImageBitmap(BitmapFactory.decodeFile(game.artPath))
            } catch (e: Exception) {
                binding.ivArt.setImageResource(R.drawable.vgmp_logo)
            }
        } else {
            binding.ivArt.setImageResource(R.drawable.vgmp_logo)
        }

        // Duration is now updated via observePlaybackInfo() using live duration from engine
        
        viewLifecycleOwner.lifecycleScope.launch {
            // Use the service's currentTags which has fallback to database values
            val tags = svc.getCurrentTags()
            binding.tvTitle.text = tags.displayTitle
            binding.tvGame.text = tags.displayGame
            
            // System with white value
            if (tags.displaySystem.isNotEmpty()) {
                binding.tvSystem.text = buildSpannedString {
                    colorSpan("System: ", requireContext().getColor(R.color.vgmp_text_secondary))
                    colorSpan(tags.displaySystem, requireContext().getColor(R.color.vgmp_text_primary))
                }
                binding.tvSystem.visibility = View.VISIBLE
            } else {
                binding.tvSystem.visibility = View.GONE
            }
            
            // Composers with white value
            if (tags.displayAuthor.isNotEmpty()) {
                binding.tvAuthor.text = buildSpannedString {
                    colorSpan("Composers: ", requireContext().getColor(R.color.vgmp_text_secondary))
                    colorSpan(tags.displayAuthor, requireContext().getColor(R.color.vgmp_text_primary))
                }
                binding.tvAuthor.visibility = View.VISIBLE
            } else {
                binding.tvAuthor.visibility = View.GONE
            }
            
            // Pack creator on separate line
            if (tags.creator.isNotEmpty()) {
                binding.tvCreator.text = buildSpannedString {
                    colorSpan("Creator: ", requireContext().getColor(R.color.vgmp_text_secondary))
                    colorSpan(tags.creator, requireContext().getColor(R.color.vgmp_text_primary))
                }
                binding.tvCreator.visibility = View.VISIBLE
            } else {
                binding.tvCreator.visibility = View.GONE
            }
            
            // Release year on separate line
            if (tags.date.isNotEmpty()) {
                binding.tvDate.text = buildSpannedString {
                    colorSpan("Year: ", requireContext().getColor(R.color.vgmp_text_secondary))
                    colorSpan(tags.date, requireContext().getColor(R.color.vgmp_text_primary))
                }
                binding.tvDate.visibility = View.VISIBLE
            } else {
                binding.tvDate.visibility = View.GONE
            }
            
            // Notes with label
            if (tags.notes.isNotEmpty()) {
                binding.tvNotes.text = buildSpannedString {
                    colorSpan("Notes: ", requireContext().getColor(R.color.vgmp_text_secondary))
                    colorSpan(tags.notes, requireContext().getColor(R.color.vgmp_text_primary))
                }
                binding.tvNotes.visibility = View.VISIBLE
            } else {
                binding.tvNotes.visibility = View.GONE
            }
            
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
            }
            VgmPlaybackService.ShuffleMode.ALL -> {
                binding.btnRandom.setColorFilter(resources.getColor(R.color.white, null))
                binding.btnRandom.alpha = 1.0f
            }
        }
        
        // Loop button color - different colors for TRACK vs GAME mode
        when (svc.getLoop()) {
            VgmPlaybackService.LoopMode.OFF -> {
                binding.btnLoop.setColorFilter(resources.getColor(R.color.vgmp_text_secondary, null))
                binding.btnLoop.alpha = 0.5f
            }
            VgmPlaybackService.LoopMode.TRACK -> {
                binding.btnLoop.setColorFilter(resources.getColor(R.color.vgmp_accent, null))
                binding.btnLoop.alpha = 1.0f
            }
            VgmPlaybackService.LoopMode.GAME -> {
                binding.btnLoop.setColorFilter(resources.getColor(R.color.white, null))
                binding.btnLoop.alpha = 1.0f
            }
        }
        
        // Update track favorite button
        updateTrackFavoriteButton()
        
        // Update endless loop button
        updateEndlessLoopButton()
        
        // Update speed button
        updateSpeedButton()
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

    private fun updateEndlessLoopButton() {
        val binding = _binding ?: return
        val svc = service ?: return
        val endlessLoop = svc.getEndlessLoop()
        
        binding.btnEndlessLoop.visibility = View.VISIBLE
        
        if (endlessLoop) {
            binding.btnEndlessLoop.setColorFilter(resources.getColor(R.color.vgmp_accent, null))
            binding.btnEndlessLoop.alpha = 1.0f
            // Show infinity symbol instead of duration
            binding.tvTotalTime.text = "∞"
            // Disable seekbar
            binding.seekBar.isEnabled = false
            binding.seekBar.alpha = 0.5f
        } else {
            binding.btnEndlessLoop.setColorFilter(resources.getColor(R.color.vgmp_text_secondary, null))
            binding.btnEndlessLoop.alpha = 0.5f
            // Re-enable seekbar
            binding.seekBar.isEnabled = true
            binding.seekBar.alpha = 1.0f
        }
    }

    private fun updateSpeedButton() {
        val binding = _binding ?: return
        val svc = service
        
        // Hide speed button for KSS and tracker formats
        if (svc != null && !svc.isSpeedControlSupported()) {
            binding.btnSpeed.visibility = View.GONE
            return
        }
        
        binding.btnSpeed.visibility = View.VISIBLE
        val speed = speedOptions[currentSpeedIndex]
        val speedPercent = (speed * 100).toInt()
        
        if (speedPercent == 100) {
            // Normal speed - dimmed appearance
            binding.btnSpeed.setColorFilter(resources.getColor(R.color.vgmp_text_secondary, null))
            binding.btnSpeed.alpha = 0.5f
        } else {
            // Reduced speed - highlighted
            binding.btnSpeed.setColorFilter(resources.getColor(R.color.vgmp_accent, null))
            binding.btnSpeed.alpha = 1.0f
        }
    }

    private fun showStyledToast(message: String) {
        val context = context ?: return
        val inflater = LayoutInflater.from(context)
        val layout = inflater.inflate(R.layout.custom_toast, null)
        val textView = layout.findViewById<TextView>(R.id.toast_text)
        textView.text = message
        
        val toast = Toast(context)
        toast.duration = Toast.LENGTH_SHORT
        toast.view = layout
        toast.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, 100)
        toast.show()
    }

    private suspend fun updateVolumeSliders() {
        val binding = _binding ?: return
        val container = binding.volumesContainer
        val header = binding.tvSoundChipsHeader
        
        val count = VgmEngine.getDeviceCount()
        
        // Show/hide header based on whether there are chips
        header.visibility = if (count > 0) android.view.View.VISIBLE else android.view.View.GONE
        
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
                // Use direct position from service instead of MediaSession's static position
                val posMs = svc.getCurrentPositionMs()
                val durMs = svc.playbackInfo.value.durationMs
                val endlessLoop = svc.getEndlessLoop()
                
                // In endless loop mode, just update current time, not the seekbar
                if (endlessLoop) {
                    binding.tvCurrentTime.text = formatTime(posMs)
                } else if (durMs > 0 && !isSeeking) {
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

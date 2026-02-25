package org.vlessert.vgmp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.DialogFragment
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.R
import org.vlessert.vgmp.databinding.FragmentSettingsBinding
import org.vlessert.vgmp.settings.SettingsManager

class SettingsDialogFragment : DialogFragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    companion object {
        fun newInstance() = SettingsDialogFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { 
            (activity as? MainActivity)?.resetAutoHideTimer()
            dismissAllowingStateLoss() 
        }
        
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val context = requireContext()
        
        // Analyzer enabled
        binding.switchAnalyzerEnabled.isChecked = SettingsManager.isAnalyzerEnabled(context)

        // Analyzer style
        when (SettingsManager.getAnalyzerStyle(context)) {
            SettingsManager.ANALYZER_STYLE_BARS -> binding.radioBars.isChecked = true
            else -> binding.radioKaleidoscope.isChecked = true
        }
        
        // Fade timeout
        val fadeTimeout = SettingsManager.getFadeTimeout(context)
        binding.seekbarFadeTimeout.progress = fadeTimeout
        binding.tvFadeTimeoutValue.text = "${fadeTimeout}s"
        
        // Favorites only mode
        binding.switchFavoritesOnly.isChecked = SettingsManager.isFavoritesOnlyMode(context)

        // VGM types
        val enabledGroups = SettingsManager.getEnabledTypeGroups(context)
        binding.checkTypeVgm.isChecked = enabledGroups.contains(SettingsManager.TYPE_GROUP_VGM)
        binding.checkTypeGme.isChecked = enabledGroups.contains(SettingsManager.TYPE_GROUP_GME)
        binding.checkTypeKss.isChecked = enabledGroups.contains(SettingsManager.TYPE_GROUP_KSS)
        binding.checkTypeTracker.isChecked = enabledGroups.contains(SettingsManager.TYPE_GROUP_TRACKER)
        binding.checkTypeMidi.isChecked = enabledGroups.contains(SettingsManager.TYPE_GROUP_MIDI)
        binding.checkTypeMus.isChecked = enabledGroups.contains(SettingsManager.TYPE_GROUP_MUS)
        binding.checkTypeRsn.isChecked = enabledGroups.contains(SettingsManager.TYPE_GROUP_RSN)
    }

    private fun setupListeners() {
        val context = requireContext()
        
        binding.switchAnalyzerEnabled.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setAnalyzerEnabled(context, isChecked)
        }

        binding.radioAnalyzerStyle.setOnCheckedChangeListener { _, checkedId ->
            val style = when (checkedId) {
                R.id.radio_bars -> SettingsManager.ANALYZER_STYLE_BARS
                else -> SettingsManager.ANALYZER_STYLE_KALEIDOSCOPE
            }
            SettingsManager.setAnalyzerStyle(context, style)
        }
        
        binding.switchFavoritesOnly.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setFavoritesOnlyMode(context, isChecked)
        }

        val updateTypes = {
            val groups = mutableSetOf<String>()
            if (binding.checkTypeVgm.isChecked) groups.add(SettingsManager.TYPE_GROUP_VGM)
            if (binding.checkTypeGme.isChecked) groups.add(SettingsManager.TYPE_GROUP_GME)
            if (binding.checkTypeKss.isChecked) groups.add(SettingsManager.TYPE_GROUP_KSS)
            if (binding.checkTypeTracker.isChecked) groups.add(SettingsManager.TYPE_GROUP_TRACKER)
            if (binding.checkTypeMidi.isChecked) groups.add(SettingsManager.TYPE_GROUP_MIDI)
            if (binding.checkTypeMus.isChecked) groups.add(SettingsManager.TYPE_GROUP_MUS)
            if (binding.checkTypeRsn.isChecked) groups.add(SettingsManager.TYPE_GROUP_RSN)
            SettingsManager.setEnabledTypeGroups(context, groups)
            (activity as? MainActivity)?.refreshLibrary()
        }

        binding.checkTypeVgm.setOnCheckedChangeListener { _, _ -> updateTypes() }
        binding.checkTypeGme.setOnCheckedChangeListener { _, _ -> updateTypes() }
        binding.checkTypeKss.setOnCheckedChangeListener { _, _ -> updateTypes() }
        binding.checkTypeTracker.setOnCheckedChangeListener { _, _ -> updateTypes() }
        binding.checkTypeMidi.setOnCheckedChangeListener { _, _ -> updateTypes() }
        binding.checkTypeMus.setOnCheckedChangeListener { _, _ -> updateTypes() }
        binding.checkTypeRsn.setOnCheckedChangeListener { _, _ -> updateTypes() }
        
        binding.seekbarFadeTimeout.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvFadeTimeoutValue.text = "${progress}s"
                if (fromUser) {
                    SettingsManager.setFadeTimeout(context, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.resetAutoHideTimer()
        super.onDestroyView()
        _binding = null
    }
}

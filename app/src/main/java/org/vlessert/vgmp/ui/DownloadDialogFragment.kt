package org.vlessert.vgmp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.R
import org.vlessert.vgmp.download.DownloadSources
import org.vlessert.vgmp.download.DownloadWorker
import org.vlessert.vgmp.databinding.FragmentDownloadBinding

class DownloadDialogFragment : DialogFragment() {

    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!

    companion object {
        fun newInstance() = DownloadDialogFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { 
            (activity as? MainActivity)?.resetAutoHideTimer()
            dismissAllowingStateLoss() 
        }
        
        // Add VGM Rips search button
        binding.btnVgmripsSearch.setOnClickListener {
            VgmRipsSearchFragment.newInstance().show(parentFragmentManager, "vgmrips_search")
        }
        
        populateSources()
    }

    private fun populateSources() {
        DownloadSources.presets.forEach { source ->
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_download_source, binding.sourcesContainer, false)
            itemView.findViewById<TextView>(R.id.tv_source_name).text = source.name
            itemView.findViewById<TextView>(R.id.tv_source_desc).text = source.description
            itemView.findViewById<TextView>(R.id.tv_source_type).text = source.fileType
            val btnDownload = itemView.findViewById<Button>(R.id.btn_download_source)
            val progressBar = itemView.findViewById<ProgressBar>(R.id.progress_source)
            val tvStatus = itemView.findViewById<TextView>(R.id.tv_download_status)

            if (source.fileType == ".7z") {
                btnDownload.text = "⚠ 7z (not directly supported)"
                btnDownload.isEnabled = false
                tvStatus.text = "Use a .zip URL instead"
                tvStatus.visibility = View.VISIBLE
            } else {
                btnDownload.setOnClickListener {
                    btnDownload.isEnabled = false
                    progressBar.visibility = View.VISIBLE
                    tvStatus.text = "Starting download..."
                    tvStatus.visibility = View.VISIBLE

                    val workRequest = DownloadWorker.enqueue(
                        requireContext(), source.url, source.name + source.fileType
                    )
                    WorkManager.getInstance(requireContext()).enqueue(workRequest)
                    WorkManager.getInstance(requireContext())
                        .getWorkInfoByIdLiveData(workRequest.id)
                        .observe(viewLifecycleOwner) { info ->
                            if (info == null) return@observe
                            val pct = info.progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
                            val status = info.progress.getString(DownloadWorker.KEY_STATUS) ?: ""
                            progressBar.progress = pct
                            tvStatus.text = if (status.isNotEmpty()) status else "Progress: $pct%"
                            if (info.state.isFinished) {
                                progressBar.visibility = View.GONE
                                btnDownload.isEnabled = true
                                if (info.state.name == "SUCCEEDED") {
                                    val gameName = info.outputData.getString(DownloadWorker.KEY_GAME_NAME) ?: ""
                                    tvStatus.text = "✓ Downloaded: $gameName"
                                } else {
                                    tvStatus.text = "✗ Failed: ${info.outputData.getString(DownloadWorker.KEY_STATUS)}"
                                }
                            }
                        }
                }
            }
            binding.sourcesContainer.addView(itemView)
        }
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.resetAutoHideTimer()
        super.onDestroyView()
        _binding = null
    }
}

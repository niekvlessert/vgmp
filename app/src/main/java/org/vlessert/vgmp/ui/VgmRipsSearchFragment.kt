package org.vlessert.vgmp.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.R
import org.vlessert.vgmp.databinding.FragmentVgmripsSearchBinding
import org.vlessert.vgmp.download.DownloadWorker
import org.vlessert.vgmp.library.GameLibrary
import org.vlessert.vgmp.vgmrips.VgmRipsPack
import org.vlessert.vgmp.vgmrips.VgmRipsRepository
import java.net.URL

private const val TAG = "VgmRipsSearch"

class VgmRipsSearchFragment : DialogFragment() {

    private var _binding: FragmentVgmripsSearchBinding? = null
    private val binding get() = _binding!!
    private var searchResults = listOf<VgmRipsPack>()
    private var selectedChip = "All Chips"

    companion object {
        fun newInstance() = VgmRipsSearchFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVgmripsSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { 
            (activity as? MainActivity)?.resetAutoHideTimer()
            dismissAllowingStateLoss() 
        }
        
        // Setup chip spinner
        val chipAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, VgmRipsRepository.SOUND_CHIPS)
        chipAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.chipSpinner.adapter = chipAdapter
        binding.chipSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedChip = VgmRipsRepository.SOUND_CHIPS[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        binding.btnSearch.setOnClickListener {
            val query = binding.searchInput.text?.toString() ?: ""
            if (query.isNotBlank() || selectedChip != "All Chips") {
                performSearch(query, selectedChip)
            }
        }
        
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.searchInput.text?.toString() ?: ""
                if (query.isNotBlank() || selectedChip != "All Chips") {
                    performSearch(query, selectedChip)
                }
                true
            } else false
        }
    }

    private fun performSearch(query: String, chipFilter: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.resultsContainer.removeAllViews()
        
        viewLifecycleOwner.lifecycleScope.launch {
            searchResults = VgmRipsRepository.search(requireContext(), query, chipFilter)
            binding.progressBar.visibility = View.GONE
            
            if (searchResults.isEmpty()) {
                binding.emptyText.visibility = View.VISIBLE
                val filterDesc = if (chipFilter != "All Chips") " with chip \"$chipFilter\"" else ""
                binding.emptyText.text = "No results found for \"$query\"$filterDesc"
            } else {
                binding.emptyText.visibility = View.GONE
                displayResults()
            }
        }
    }

    private fun displayResults() {
        binding.resultsContainer.removeAllViews()
        
        for (pack in searchResults) {
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_vgmrips_pack, binding.resultsContainer, false)
            
            val ivArt = itemView.findViewById<ImageView>(R.id.iv_pack_art)
            val tvTitle = itemView.findViewById<TextView>(R.id.tv_pack_title)
            val tvInfo = itemView.findViewById<TextView>(R.id.tv_pack_info)
            val tvChips = itemView.findViewById<TextView>(R.id.tv_pack_chips)
            val btnDownload = itemView.findViewById<Button>(R.id.btn_download_pack)
            val progressBar = itemView.findViewById<ProgressBar>(R.id.progress_pack)
            val tvStatus = itemView.findViewById<TextView>(R.id.tv_pack_status)
            
            tvTitle.text = pack.title
            tvInfo.text = pack.displayInfo
            tvChips.text = pack.soundChips
            
            // Load image on IO thread
            val imageUrl = pack.imageUrl
            Log.d(TAG, "Loading image for ${pack.title}: $imageUrl")
            if (imageUrl != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val url = URL(imageUrl)
                            Log.d(TAG, "Opening connection to: $imageUrl")
                            val conn = url.openConnection()
                            conn.connectTimeout = 10000
                            conn.readTimeout = 10000
                            val bitmap = BitmapFactory.decodeStream(conn.getInputStream())
                            withContext(Dispatchers.Main) {
                                if (bitmap != null) {
                                    ivArt.setImageBitmap(bitmap)
                                    Log.d(TAG, "Image loaded successfully for ${pack.title}")
                                } else {
                                    ivArt.setImageResource(R.drawable.vgmp_logo)
                                    Log.w(TAG, "Failed to decode bitmap for ${pack.title}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading image for ${pack.title}: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            ivArt.setImageResource(R.drawable.vgmp_logo)
                        }
                    }
                }
            } else {
                ivArt.setImageResource(R.drawable.vgmp_logo)
            }
            
            // Download button - use safeZipUrl for HTTPS
            btnDownload.setOnClickListener {
                btnDownload.isEnabled = false
                progressBar.visibility = View.VISIBLE
                tvStatus.text = "Starting download..."
                tvStatus.visibility = View.VISIBLE
                
                val zipName = pack.safeZipUrl.substringAfterLast('/')
                val workRequest = DownloadWorker.enqueue(requireContext(), pack.safeZipUrl, zipName)
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
                                // Refresh the library and service games list
                                viewLifecycleOwner.lifecycleScope.launch {
                                    try {
                                        GameLibrary.init(requireContext())
                                        (activity as? MainActivity)?.getService()?.refreshGames()
                                        (activity as? MainActivity)?.refreshLibrary()
                                        Log.d(TAG, "Library refreshed after download")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to refresh library", e)
                                    }
                                }
                            } else {
                                tvStatus.text = "✗ Failed: ${info.outputData.getString(DownloadWorker.KEY_STATUS)}"
                            }
                        }
                    }
            }
            
            // Click on item also starts download
            itemView.setOnClickListener {
                btnDownload.performClick()
            }
            
            binding.resultsContainer.addView(itemView)
        }
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.resetAutoHideTimer()
        super.onDestroyView()
        _binding = null
    }
}

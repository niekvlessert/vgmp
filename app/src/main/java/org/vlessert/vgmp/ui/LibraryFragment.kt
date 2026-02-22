package org.vlessert.vgmp.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.R
import org.vlessert.vgmp.databinding.FragmentLibraryBinding
import org.vlessert.vgmp.engine.VgmEngine
import org.vlessert.vgmp.library.Game
import org.vlessert.vgmp.library.GameLibrary
import org.vlessert.vgmp.library.TrackEntity
import org.vlessert.vgmp.service.VgmPlaybackService

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private var service: VgmPlaybackService? = null
    private lateinit var adapter: GameAdapter
    private var searchJob: Job? = null
    private var isLoading = false
    private var currentQuery = ""
    private val pageSize = 20

    companion object {
        fun newInstance() = LibraryFragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = GameAdapter(
            onTrackClick = { game, track, gameIdx, trackIdx ->
                hideKeyboard()
                service?.playTrack(game, trackIdx)
                // Removed auto-show of now playing sheet
            },
            onFavoriteClick = { game ->
                lifecycleScope.launch {
                    GameLibrary.toggleFavorite(game.id)
                    performSearch(binding.searchInput.text?.toString() ?: "")
                }
            },
            getCurrentlyPlayingTrack = { service?.currentTrack }
        )
        val layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerGames.layoutManager = layoutManager
        binding.recyclerGames.adapter = adapter

        // Implement lazy loading with scroll listener
        binding.recyclerGames.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                if (!isLoading && lastVisibleItem >= totalItemCount - 5) {
                    loadMoreGames()
                }
            }
        })

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300) // debounce
                    currentQuery = s?.toString() ?: ""
                    performSearch(currentQuery)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else false
        }

        // Initial load (all games)
        lifecycleScope.launch {
            performSearch("")
        }
        observePlaybackInfo()
    }

    fun onServiceConnected(svc: VgmPlaybackService) {
        service = svc
        observePlaybackInfo()
    }

    private fun observePlaybackInfo() {
        val svc = service ?: return
        val view = view ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            svc.playbackInfo.collectLatest {
                adapter.notifyNowPlaying()
            }
        }
        // Observe library ready state to refresh when downloads complete
        viewLifecycleOwner.lifecycleScope.launch {
            svc.libraryReady.collectLatest { ready ->
                if (ready) {
                    performSearch(binding.searchInput.text?.toString() ?: "")
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch { performSearch(binding.searchInput.text?.toString() ?: "") }
    }

    suspend fun performSearch(query: String) {
        binding.progressBar.visibility = View.VISIBLE
        val results = GameLibrary.search(query)
        binding.progressBar.visibility = View.GONE
        if (results.isEmpty()) {
            binding.emptyText.visibility = View.VISIBLE
            binding.emptyText.text = if (query.isBlank()) {
                "No games in library.\nDownload some from the menu!"
            } else {
                getString(R.string.empty_library)
            }
            binding.recyclerGames.visibility = View.GONE
        } else {
            binding.emptyText.visibility = View.GONE
            binding.recyclerGames.visibility = View.VISIBLE
        }
        val allGames = service?.getAllLoadedGames() ?: emptyList()
        adapter.submitList(results, allGames)
    }

    suspend fun refreshView() {
        performSearch(binding.searchInput.text?.toString() ?: "")
    }

    private fun loadMoreGames() {
        // For now, we're loading all games at once since the dataset is typically small
        // This can be enhanced later for very large libraries
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class GameAdapter(
    private val onTrackClick: (game: Game, track: TrackEntity, gameIdx: Int, trackIdx: Int) -> Unit,
    private val onFavoriteClick: (game: Game) -> Unit,
    private val getCurrentlyPlayingTrack: () -> TrackEntity?
) : RecyclerView.Adapter<GameAdapter.GameViewHolder>() {

    private var games: List<Game> = emptyList()
    private var allServiceGames: List<Game> = emptyList()
    private val expandedGames = mutableSetOf<Long>()

    fun submitList(newGames: List<Game>, allGames: List<Game>) {
        games = newGames
        allServiceGames = allGames
        notifyDataSetChanged()
    }

    fun notifyNowPlaying() = notifyDataSetChanged()

    override fun getItemCount() = games.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false)
        return GameViewHolder(view)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        val game = games[position]
        val globalIdx = allServiceGames.indexOfFirst { it.id == game.id }
        holder.bind(game, globalIdx, expandedGames.contains(game.id), getCurrentlyPlayingTrack())
        holder.itemView.setOnClickListener {
            if (expandedGames.contains(game.id)) expandedGames.remove(game.id)
            else expandedGames.add(game.id)
            notifyItemChanged(position)
        }
        holder.btnFavorite.setOnClickListener {
            onFavoriteClick(game)
        }
        holder.setTrackClickListener { track, trackIdx ->
            val gIdx = if (globalIdx >= 0) globalIdx else position
            onTrackClick(game, track, gIdx, trackIdx)
        }
    }

    inner class GameViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val artView: ImageView = itemView.findViewById(R.id.iv_art)
        private val nameView: TextView = itemView.findViewById(R.id.tv_game_name)
        private val systemView: TextView = itemView.findViewById(R.id.tv_system)
        val btnFavorite: android.widget.ImageButton = itemView.findViewById(R.id.btn_favorite)
        private val tracksContainer: ViewGroup = itemView.findViewById(R.id.tracks_container)
        private var trackClickListener: ((TrackEntity, Int) -> Unit)? = null

        fun setTrackClickListener(l: (TrackEntity, Int) -> Unit) { trackClickListener = l }

        fun bind(game: Game, globalIdx: Int, expanded: Boolean, nowPlayingTrack: TrackEntity?) {
            nameView.text = game.name
            systemView.text = game.system
            btnFavorite.setImageResource(
                if (game.entity.isFavorite) R.drawable.ic_star else R.drawable.ic_star_border
            )

            if (game.artPath.isNotEmpty()) {
                try {
                    val bm = BitmapFactory.decodeFile(game.artPath)
                    artView.setImageBitmap(bm)
                } catch (e: Exception) {
                    artView.setImageResource(R.drawable.vgmp_logo)
                }
            } else {
                artView.setImageResource(R.drawable.vgmp_logo)
            }

            tracksContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            tracksContainer.removeAllViews()

            if (expanded) {
                game.tracks.forEachIndexed { idx, track ->
                    val trackView = LayoutInflater.from(itemView.context)
                        .inflate(R.layout.item_track, tracksContainer, false)
                    val tvTitle = trackView.findViewById<TextView>(R.id.tv_track_title)
                    val tvDuration = trackView.findViewById<TextView>(R.id.tv_track_duration)
                    val ivFavorite = trackView.findViewById<ImageView>(R.id.iv_track_favorite)
                    
                    // For multi-track files, compare both filePath and subTrackIndex
                    val isActive = if (track.subTrackIndex >= 0) {
                        nowPlayingTrack != null && 
                        nowPlayingTrack.filePath == track.filePath && 
                        nowPlayingTrack.subTrackIndex == track.subTrackIndex
                    } else {
                        nowPlayingTrack?.filePath == track.filePath
                    }
                    
                    tvTitle.text = track.title
                    tvTitle.isSelected = isActive
                    // Show favorite star if track is favorite
                    ivFavorite.visibility = if (track.isFavorite) View.VISIBLE else View.GONE
                    if (isActive) {
                        trackView.setBackgroundResource(R.drawable.track_active_bg)
                        tvTitle.setTextColor(0xFF00FF66.toInt())
                    } else {
                        trackView.setBackgroundResource(R.drawable.track_normal_bg)
                        tvTitle.setTextColor(0xFFEEEEEE.toInt())
                    }
                    if (track.durationSamples > 0) {
                        val secs = track.durationSamples / VgmPlaybackService.SAMPLE_RATE
                        tvDuration.text = "%d:%02d".format(secs / 60, secs % 60)
                    } else {
                        tvDuration.text = ""
                    }
                    trackView.setOnClickListener { trackClickListener?.invoke(track, idx) }
                    tracksContainer.addView(trackView)
                }
            }
        }
    }
}

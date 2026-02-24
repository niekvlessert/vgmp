package org.vlessert.vgmp.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vlessert.vgmp.MainActivity
import org.vlessert.vgmp.R
import org.vlessert.vgmp.library.GameLibrary
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class SnesMusicWebViewFragment : DialogFragment() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var isDownloading = false

    companion object {
        private const val INITIAL_URL = "http://snesmusic.org/v2/select.php?view=sets&char=A&limit=0"
        
        fun newInstance() = SnesMusicWebViewFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val layout = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Progress bar
        progressBar = ProgressBar(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        }
        layout.addView(progressBar)

        // Toolbar with close button
        val toolbar = androidx.appcompat.widget.Toolbar(requireContext()).apply {
            id = View.generateViewId()
            title = "SNES Music Archive"
            setTitleTextColor(resources.getColor(R.color.vgmp_accent, null))
            setBackgroundColor(resources.getColor(R.color.vgmp_surface, null))
            navigationIcon = resources.getDrawable(android.R.drawable.ic_menu_close_clear_cancel, null)
            setNavigationOnClickListener { 
                (activity as? MainActivity)?.resetAutoHideTimer()
                dismissAllowingStateLoss() 
            }
            elevation = 4f
        }
        layout.addView(toolbar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // WebView
        webView = WebView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = resources.getDimensionPixelSize(android.R.dimen.app_icon_size) + 32
            }
            
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            
            // Allow HTTP (non-HTTPS) connections
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            webViewClient = SnesMusicWebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress == 100) {
                        progressBar.visibility = View.GONE
                    } else {
                        progressBar.visibility = View.VISIBLE
                    }
                }
            }
            
            // Handle downloads (RSN files) - download directly to app
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                if (!isDownloading) {
                    downloadAndImportRsN(url, userAgent)
                }
            }
        }
        layout.addView(webView)

        return layout
    }

    private fun downloadAndImportRsN(url: String, userAgent: String) {
        isDownloading = true
        progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Download the RSN file
                val fileName = URLUtil.guessFileName(url, null, ".rsn")
                android.util.Log.d("SnesMusicWebView", "Downloading RSN: $fileName from $url")
                
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", userAgent)
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                
                val responseCode = connection.responseCode
                android.util.Log.d("SnesMusicWebView", "Response code: $responseCode")
                
                if (responseCode !in 200..299) {
                    throw Exception("Server returned error: $responseCode")
                }
                
                val tempFile = File(requireContext().cacheDir, fileName)
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                android.util.Log.d("SnesMusicWebView", "Downloaded ${tempFile.length()} bytes to ${tempFile.absolutePath}")
                
                // Import the RSN file into the library
                GameLibrary.init(requireContext())
                val game = tempFile.inputStream().use { 
                    GameLibrary.importRsn(BufferedInputStream(it), fileName)
                }
                
                // Clean up temp file
                tempFile.delete()
                
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    isDownloading = false
                    
                    if (game != null) {
                        Toast.makeText(requireContext(), "✓ Downloaded: ${game.name}", Toast.LENGTH_LONG).show()
                        // Refresh the library
                        (activity as? MainActivity)?.getService()?.refreshGames()
                        (activity as? MainActivity)?.refreshLibrary()
                    } else {
                        Toast.makeText(requireContext(), "Failed to import RSN: No SPC files found or extraction failed", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SnesMusicWebView", "Download/Import failed", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    isDownloading = false
                    Toast.makeText(requireContext(), "Error: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webView.loadUrl(INITIAL_URL)
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.resetAutoHideTimer()
        super.onDestroyView()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        webView.onResume()
        super.onResume()
    }

    /**
     * Custom WebViewClient that injects CSS to hide the right sidebar
     */
    private inner class SnesMusicWebViewClient : WebViewClient() {
        @SuppressLint("RequiresFeature")
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            
            // Inject CSS to hide the right sidebar
            val css = """
                (function() {
                    // Hide right sidebar - common patterns
                    var style = document.createElement('style');
                    style.innerHTML = `
                        #rightbar, .rightbar, #sidebar, .sidebar, 
                        #right-bar, .right-bar, #rightPanel, .rightPanel,
                        [id*="right"], [class*="rightbar"], [class*="right-bar"],
                        td[width="200"], td[width="180"], td[width="160"],
                        .column-right, #column-right, aside, .aside {
                            display: none !important;
                            visibility: hidden !important;
                            width: 0 !important;
                            height: 0 !important;
                            overflow: hidden !important;
                        }
                        body, #content, .content, #main, .main {
                            width: 100% !important;
                            margin-right: 0 !important;
                            padding-right: 0 !important;
                        }
                        table {
                            width: 100% !important;
                        }
                    `;
                    document.head.appendChild(style);
                    
                    // Also try to remove specific elements
                    var rightbar = document.getElementById('rightbar');
                    if (rightbar) rightbar.style.display = 'none';
                    
                    var elements = document.querySelectorAll('[class*="right"], [id*="right"]');
                    for (var i = 0; i < elements.length; i++) {
                        elements[i].style.display = 'none';
                    }
                })();
            """
            view?.evaluateJavascript(css, null)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url.toString()
            // Handle RSN file downloads
            if (url.endsWith(".rsn", ignoreCase = true)) {
                return false // Let the DownloadListener handle it
            }
            return false
        }
    }
}

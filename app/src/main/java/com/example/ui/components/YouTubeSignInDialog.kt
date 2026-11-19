package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.engine.EmbeddedPlayerManager
import com.example.ui.theme.LocalStudioColors

private const val YOUTUBE_GOOGLE_SIGNIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26hl%3Den%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"

// Clean Mobile Chrome UA without "; wv)" so Google Accounts never blocks with 403 disallowed_useragent
private const val SIGNIN_CHROME_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"

/**
 * Interactive popup window for signing into YouTube with a Google email account.
 * Shares cookies via Android's global CookieManager so all embedded 1080p mini-players
 * immediately bypass "Sign in to confirm you're not a bot" and play cleanly on any Wi-Fi.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignInDialog(
    onDismiss: () -> Unit,
    onSignedIn: () -> Unit
) {
    val studio = LocalStudioColors.current
    var loadProgress by remember { mutableIntStateOf(15) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var detectedAuth by remember { mutableStateOf(EmbeddedPlayerManager.hasYouTubeAuthCookies()) }
    var hasAutoCompleted by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { CookieManager.getInstance().flush() }
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 18.dp)
                .imePadding()
                .testTag("youtube_signin_popup"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, studio.velocityCyan.copy(alpha = 0.55f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Popup Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(studio.rackSurface)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (canGoBack) {
                        IconButton(
                            onClick = { webViewRef?.goBack() },
                            modifier = Modifier.testTag("signin_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(studio.velocityCyanContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = studio.velocityCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.youtube_signin_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.youtube_signin_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("signin_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isLoading && loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = studio.velocityCyan,
                        trackColor = studio.velocityCyanContainer
                    )
                }

                // Embedded Google/YouTube Login WebView (shares CookieManager with all 1080p players)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Color.White)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                setBackgroundColor(AndroidColor.WHITE)
                                isFocusable = true
                                isFocusableInTouchMode = true
                                cm.setAcceptThirdPartyCookies(this, true)

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    javaScriptCanOpenWindowsAutomatically = true
                                    setSupportMultipleWindows(false)
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    userAgentString = SIGNIN_CHROME_MOBILE_UA
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress
                                        isLoading = newProgress < 100
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val urlStr = request?.url?.toString().orEmpty()
                                        // Keep all Google & YouTube sign-in / 2FA redirects inside this popup
                                        return !(urlStr.startsWith("https://") || urlStr.startsWith("http://"))
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        isLoading = true
                                        canGoBack = view?.canGoBack() == true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        isLoading = false
                                        canGoBack = view?.canGoBack() == true
                                        runCatching { CookieManager.getInstance().flush() }

                                        val signedInNow = EmbeddedPlayerManager.hasYouTubeAuthCookies()
                                        detectedAuth = signedInNow
                                        val currentUrl = url.orEmpty().lowercase()
                                        val returnedToYouTube =
                                            (currentUrl.contains("youtube.com") && !currentUrl.contains("accounts.google.com") && !currentUrl.contains("/signin"))

                                        if (signedInNow && returnedToYouTube && !hasAutoCompleted) {
                                            hasAutoCompleted = true
                                            onSignedIn()
                                        }
                                    }
                                }

                                webViewRef = this
                                requestFocus()
                                loadUrl(YOUTUBE_GOOGLE_SIGNIN_URL)
                            }
                        }
                    )
                }

                // Popup Footer Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(studio.rackSurface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = if (detectedAuth) studio.targetEmerald else studio.mutedText,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (detectedAuth) {
                                "Login verified! Tap Done to play 1080p video"
                            } else {
                                "Sign in with Google email, then video plays automatically"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (detectedAuth) studio.targetEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = {
                            runCatching { CookieManager.getInstance().flush() }
                            onSignedIn()
                        },
                        modifier = Modifier.testTag("signin_done_play_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (detectedAuth) studio.targetEmerald else studio.velocityCyan,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.youtube_signin_done),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

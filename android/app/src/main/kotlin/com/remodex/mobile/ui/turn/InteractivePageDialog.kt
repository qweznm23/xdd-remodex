package com.remodex.mobile.ui.turn

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.remodex.mobile.ui.theme.RemodexPreviewDialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.remodex.mobile.R
import com.remodex.mobile.services.*
import com.remodex.mobile.ui.LocalCodexRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@Composable
internal fun InteractivePageCard(reference: InteractivePageReference, onOpen: () -> Unit) {
    TimelineActionRow(
        onClick = onOpen,
        leading = { Icon(Icons.Outlined.WebAsset, null, Modifier.size(22.dp)) },
        trailing = { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp)) },
    ) {
        Column {
            Text(reference.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.interactive_page_open), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val MAX_HTML_BYTES = 8 * 1024 * 1024

@Composable
internal fun InteractivePageDialog(reference: InteractivePageReference, cwd: String?, onDismiss: () -> Unit) {
    val repository = LocalCodexRepository.current
    val service = remember(repository) { WorkspaceFileTransferService(repository) }
    val boundCwd = RepoMarkdownFileLink.previewWorkspaceCwd(reference.path) ?: cwd
    FileHtmlPreview(reference, boundCwd, service, visualize = true, onDismiss = onDismiss)
}

@Composable
internal fun FileHtmlPreview(reference: InteractivePageReference, cwd: String?, service: WorkspaceFileTransferService,
    visualize: Boolean, onDismiss: () -> Unit) {
    key(reference, cwd, service) {
        HtmlPreviewDialog(reference.title, reference.path, onDismiss, actions = {
            PreviewFileActions(resolve = {
                val info = service.info(reference.path, cwd)
                PreviewOriginal(info.fileName, "text/html") { service.copyTo(info, cwd, it) {} }
            })
        }) { progress ->
            val info = service.info(reference.path, cwd)
            if (info.byteLength > MAX_HTML_BYTES) throw FileTransferFailure("interactive_page", "page_too_large", "Interactive page exceeds 8 MiB")
            val output = ByteArrayOutputStream()
            progress(0, info.byteLength)
            service.copyTo(info, cwd, output) { progress(it, info.byteLength) }
            val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString() }
            catch (cause: java.nio.charset.CharacterCodingException) { throw FileTransferFailure("interactive_decode", "invalid_utf8", "Invalid HTML encoding", cause) }
            if (visualize) InteractivePagePolicy.document(text) else text
        }
    }
}

@Composable
internal fun InlineHtmlPreview(html: String, onDismiss: () -> Unit) {
    HtmlPreviewDialog("HTML", "conversation HTML block", onDismiss, actions = {
        PreviewFileActions(resolve = { PreviewOriginal("preview.html", "text/html") { output ->
            withContext(Dispatchers.IO) { output.write(html.toByteArray(Charsets.UTF_8)) }
        } })
    }) {
        if (html.length > MAX_HTML_BYTES || html.toByteArray(Charsets.UTF_8).size > MAX_HTML_BYTES)
            throw FileTransferFailure("interactive_page", "page_too_large", "Interactive page exceeds 8 MiB")
        html
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreviewDialog(title: String, diagnosticPath: String, onDismiss: () -> Unit,
    actions: @Composable () -> Unit, loadDocument: suspend ((Long, Long) -> Unit) -> String) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var attempt by remember { mutableIntStateOf(0) }
    var pageRouter by remember(attempt) { mutableStateOf<InteractivePageRequestRouter?>(null) }
    var failure by remember(attempt) { mutableStateOf<Throwable?>(null) }
    var transferred by remember(attempt) { mutableLongStateOf(0) }
    var total by remember(attempt) { mutableLongStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    fun fail(error: Throwable) {
        failure = error
        recordFileTransferError(context, "interactive_page", diagnosticPath, transferred, error)
    }
    LaunchedEffect(attempt) {
        try {
            pageRouter = withContext(Dispatchers.Default) {
                InteractivePageRequestRouter(loadDocument { bytes, size -> transferred = bytes; total = size })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail(error) }
    }
    DisposableEffect(lifecycle, webView) {
        val view = webView
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) view?.onPause()
            if (event == Lifecycle.Event.ON_START) view?.onResume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    RemodexPreviewDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    actions()
                    IconButton(onClick = { attempt++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.interactive_page_reload)) }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.turn_preview_close)) }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    val router = pageRouter
                    if (failure != null) Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.interactive_page_failed))
                        Text((failure as? FileTransferFailure)?.code ?: "unknown", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.workspace_text_preview_retry)) }
                    } else if (router == null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(if (total > 0) "${transferred * 100 / total}%" else stringResource(R.string.interactive_page_loading), Modifier.padding(12.dp))
                    } else key(router, attempt) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                            WebView(ctx).apply {
                                // Chromium resolves viewport-height units against the host's layout mode.
                                // A wrap-content WebView can report 0svh even inside a full-size Compose box.
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                webView = this
                                settings.javaScriptEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.domStorageEnabled = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                settings.javaScriptCanOpenWindowsAutomatically = false
                                settings.setSupportMultipleWindows(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                        val response = router.intercept(request.url.toString(), request.isForMainFrame, request.method) ?: return null
                                        return WebResourceResponse(response.mime, "UTF-8", response.status, response.reason,
                                            mapOf("Content-Security-Policy" to InteractivePagePolicy.contentSecurityPolicy, "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(response.bytes))
                                    }
                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (request.isForMainFrame) fail(FileTransferFailure("interactive_webview", "webview_${error.errorCode}", error.description.toString()))
                                    }
                                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                        if (request.isForMainFrame) fail(FileTransferFailure("interactive_webview_http", "http_${response.statusCode}",
                                            "Main document HTTP ${response.statusCode}: ${response.reasonPhrase}; method=${request.method}"))
                                    }
                                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                        fail(FileTransferFailure("interactive_webview", "renderer_gone", "WebView renderer exited: crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}"))
                                        return true
                                    }
                                }
                                // Do not use loadDataWithBaseURL: its internal data: main request can
                                // enter interception on Android and was rejected by our resource policy.
                                loadUrl(InteractivePagePolicy.URL)
                            }
                        }, onRelease = { view ->
                            if (webView === view) webView = null
                            view.stopLoading(); view.onPause(); view.removeAllViews(); view.destroy()
                        })
                    }
                }
            }
        }
    }
}

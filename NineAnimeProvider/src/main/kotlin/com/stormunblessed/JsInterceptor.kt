package com.stormunblessed


import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.lagradost.cloudstream3.AcraApplication.Companion.context
import com.lagradost.cloudstream3.utils.Coroutines
import com.lagradost.cloudstream3.utils.Coroutines.main
import okhttp3.*
import java.util.concurrent.CountDownLatch



class JsInterceptor(private val serverid: String, private val lang:String) : Interceptor {

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    class JsObject(var payload: String = "") {
        @JavascriptInterface
        fun passPayload(passedPayload: String) {
            payload = passedPayload
        }
    }


    override fun intercept(chain: Interceptor.Chain): Response {

        val mess = if (serverid == "41") "Vidstream" else if (serverid == "28") "Mcloud" else ""
        val originalRequest = chain.request()
        handler.post {
            context.let { Toast.makeText(it, "Getting $mess link, please wait", Toast.LENGTH_LONG).show() }
        }
        val newRequest = resolveWithWebView(originalRequest) ?: throw Exception("Someting went wrong")

        return chain.proceed(newRequest)
    }


    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(request: Request): Request? {
        val latch = CountDownLatch(1)

        var webView: WebView? = null

        val origRequestUrl = request.url.toString()

        val jsinterface = JsObject()

        fun destroyWebView() {
            Coroutines.main {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                println("Destroyed webview")
            }
        }


        // JavaSrcipt gets the Dub or Sub link of vidstream
        val jsScript = """
                (function() {
                    let jqclk = jQuery.Event('click');
                    jqclk.isTrusted = true;
                    jqclk.originalEvent = {
                        isTrusted: true
                    };
                    jQuery('div[data-type=\"$lang\"] ul li[data-sv-id=\"$serverid\"]')[0].click();;
                    let intervalId = setInterval(() => {
                        let element = document.querySelector("#player iframe");
                        if (element) {
                            clearInterval(intervalId);
                            window.android.passPayload(element.src);
                        }
                    }, 500);
                })();
        """

        val headers = request.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        var newRequest: Request? = null

        handler.post {
            val webview = WebView(context!!)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:106.0) Gecko/20100101 Firefox/106.0"
                blockNetworkImage = true
                webview.addJavascriptInterface(jsinterface, "android")
                webview.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {

                        if (serverid == "41") {
                            if (!request?.url.toString().contains("vidstream") &&
                                !request?.url.toString().contains("vizcloud")
                            ) return null
                        }
                        if (serverid == "28") {
                            if (!request?.url.toString().contains("mcloud")
                            ) return null
                        }

                        if (request?.url.toString().contains(Regex("list.m3u8|/simple/"))) {
                            newRequest = JsGET(
                                request?.url.toString(),
                                Headers.headersOf("referer", "/orp.maertsdiv//:sptth".reversed())
                            )
                            latch.countDown()
                            return null
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(jsScript) {}
                    }
                }
                webView?.loadUrl(origRequestUrl, headers)
            }
        }

        latch.await()

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
            context.let { Toast.makeText(it, "Success!", Toast.LENGTH_SHORT).show()}
        }

        var loop = 0
        val totalTime = 60000L

        val delayTime = 100L

        while (loop < totalTime / delayTime) {
            if (newRequest != null) return newRequest
            loop += 1
        }

        println("Web-view timeout after ${totalTime / 1000}s")
        destroyWebView()
        return newRequest
    }
}

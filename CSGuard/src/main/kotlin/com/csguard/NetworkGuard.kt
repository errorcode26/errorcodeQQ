package com.csguard

import android.util.Log
import java.io.IOException

object NetworkGuard {

    private const val TAG = "CSGuard"

    fun install() {
        try {
            val appObj = getCloudStreamApp() ?: return
            val clientField = findOkHttpClientField(appObj) ?: return

            clientField.isAccessible = true
            val original = clientField.get(appObj) ?: return

            val interceptorClass = Class.forName("okhttp3.Interceptor")
            val guardInterceptor = java.lang.reflect.Proxy.newProxyInstance(
                interceptorClass.classLoader,
                arrayOf(interceptorClass)
            ) { _, _, args ->
                val chain = args?.getOrNull(0) ?: return@newProxyInstance null
                val requestMethod = chain.javaClass.getMethod("request")
                val request = requestMethod.invoke(chain)
                val urlMethod = request.javaClass.getMethod("url")
                val urlObj = urlMethod.invoke(request)
                val urlStr = urlObj?.toString() ?: ""

                if (isBlockedNetworkCall(urlStr)) {
                    Log.w(TAG, "NetworkGuard: blocked outbound request to $urlStr")
                    throw IOException("CSGuard blocked network request from plugin: $urlStr")
                }

                val proceedMethod = chain.javaClass.getMethod("proceed", request.javaClass)
                proceedMethod.invoke(chain, request)
            }

            val newClient = rebuildClientWithInterceptor(original, guardInterceptor) ?: return
            clientField.set(appObj, newClient)
            Log.i(TAG, "NetworkGuard: OkHttp interceptor installed")
        } catch (t: Throwable) {
            Log.w(TAG, "NetworkGuard install failed (non-critical): ${t.message}")
        }
    }

    private fun isBlockedNetworkCall(url: String): Boolean {
        val trace = Thread.currentThread().stackTrace
        for (element in trace) {
            val cls = element.className
            if (cls.startsWith("com.csguard")) continue
            if (cls.startsWith("com.lagradost")) continue
            if (cls.startsWith("android.")) continue
            if (cls.startsWith("java.")) continue
            if (cls.startsWith("javax.")) continue
            if (cls.startsWith("kotlin.")) continue
            if (cls.startsWith("dalvik.")) continue
            if (cls.startsWith("okhttp3.")) continue
            if (cls.startsWith("okio.")) continue

            // Caller is from outside core — check if it's a blocked provider
            try {
                val providers = com.lagradost.cloudstream3.APIHolder.allProviders
                for (provider in providers) {
                    val pkg = provider.javaClass.`package`?.name ?: continue
                    if (cls.startsWith(pkg) && AllowlistStore.blockedProviders().contains(provider.name)) {
                        return true
                    }
                }
            } catch (_: Throwable) {}
        }
        return false
    }

    private fun getCloudStreamApp(): Any? {
        return try {
            val holderClass = Class.forName("com.lagradost.cloudstream3.AcraApplication")
            val field = holderClass.getDeclaredField("app")
            field.isAccessible = true
            field.get(null)
        } catch (_: Throwable) {
            try {
                val appField = Class.forName("com.lagradost.cloudstream3.utils.Coroutines")
                    .getDeclaredField("app")
                appField.isAccessible = true
                appField.get(null)
            } catch (_: Throwable) { null }
        }
    }

    private fun findOkHttpClientField(obj: Any): java.lang.reflect.Field? {
        var klass: Class<*>? = obj.javaClass
        while (klass != null && klass != Any::class.java) {
            for (field in klass.declaredFields) {
                if (field.type.name == "okhttp3.OkHttpClient") return field
            }
            klass = klass.superclass
        }
        return null
    }

    private fun rebuildClientWithInterceptor(client: Any, interceptor: Any): Any? {
        return try {
            val builderMethod = client.javaClass.getMethod("newBuilder")
            val builder = builderMethod.invoke(client)
            val addInterceptorMethod = builder.javaClass.getMethod(
                "addInterceptor",
                Class.forName("okhttp3.Interceptor")
            )
            addInterceptorMethod.invoke(builder, interceptor)
            val buildMethod = builder.javaClass.getMethod("build")
            buildMethod.invoke(builder)
        } catch (_: Throwable) { null }
    }
}

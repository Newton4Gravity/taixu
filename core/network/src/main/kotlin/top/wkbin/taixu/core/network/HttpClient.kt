package top.wkbin.taixu.core.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP client configuration for network operations.
 */
object HttpClient {

    private const val DEFAULT_TIMEOUT_SECONDS = 30
    private const val CONNECT_TIMEOUT_SECONDS = 15
    private const val READ_TIMEOUT_SECONDS = 60
    private const val WRITE_TIMEOUT_SECONDS = 60

    /**
     * Creates a standard OkHttpClient with logging and reasonable timeouts.
     */
    fun create(
        loggingLevel: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.BASIC,
        addUnsafeTrustManager: Boolean = false
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)

        // Add logging interceptor
        val loggingInterceptor = HttpLoggingInterceptor().setLevel(loggingLevel)
        builder.addNetworkInterceptor(loggingInterceptor)

        // Add user agent interceptor
        builder.addInterceptor(UserAgentInterceptor())

        // Optionally add unsafe trust manager for development
        if (addUnsafeTrustManager) {
            builder.sslSocketFactory(createUnsafeSslSocketFactory(), createUnsafeTrustManager())
            builder.hostnameVerifier { _, _ -> true }
        }

        return builder.build()
    }

    /**
     * Creates a Moshi instance for JSON serialization.
     */
    fun createMoshi(): Moshi {
        return Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private class UserAgentInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "TaiXu/1.0 (Android; Linux)")
                .build()
            return chain.proceed(request)
        }
    }

    private fun createUnsafeSslSocketFactory() = (try {
        val trustManager = createUnsafeTrustManager()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)
        sslContext.socketFactory
    } catch (e: Exception) {
        throw RuntimeException("Failed to create unsafe SSL socket factory", e)
    })

    private fun createUnsafeTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
    }
}

/**
 * Network utilities.
 */
object NetworkUtils {
    fun isValidUrl(url: String): Boolean {
        return try {
            java.net.URL(url).let { true }
        } catch (e: Exception) {
            false
        }
    }

    fun getHost(url: String): String? {
        return try {
            java.net.URL(url).host
        } catch (e: Exception) {
            null
        }
    }
}
package com.xnet.pulse

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PulseApp : Application(), ImageLoaderFactory {
  override fun onCreate() {
    super.onCreate()
    com.xnet.pulse.feature.chat.engine.DirectoryManager.init(this)
  }
  override fun newImageLoader() = ImageLoader.Builder(this)
    .okHttpClient {
      okhttp3.OkHttpClient.Builder()
        .addInterceptor { chain ->
          chain.proceed(chain.request().newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0")
            .header("Accept", "image/*,*/*")
            .build())
        }
        .build()
    }
    .components { add(SvgDecoder.Factory()) }
    .build()
}

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
    .components { add(SvgDecoder.Factory()) }
    .build()
}

package com.xnet.pulse.feature.chat.theme

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter

@Composable
fun ChatBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val theme = LocalThemeState.current
  Box(modifier.fillMaxSize()) {
    if (theme.useBackground && theme.backgroundUri != null) {
      when (theme.backgroundMediaType) {
        "image" -> Image(
          painter = rememberAsyncImagePainter(Uri.parse(theme.backgroundUri)),
          contentDescription = null,
          modifier = Modifier.fillMaxSize().alpha(theme.backgroundOpacity),
          contentScale = ContentScale.Crop,
        )
        "video" -> { /* VideoBackground handled separately with ExoPlayer if needed */ }
      }
    }
    content()
  }
}

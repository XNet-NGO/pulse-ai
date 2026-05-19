package com.xnet.pulse.feature.chat.theme

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter

@Composable
fun ChatBackground(theme: ThemeState, modifier: Modifier = Modifier) {
  if (!theme.useBackground || theme.backgroundUri.isNullOrBlank()) return

  val rotMod = Modifier.fillMaxSize().graphicsLayer {
    rotationZ = theme.videoRotation.toFloat()
    if (theme.videoRotation == 90 || theme.videoRotation == 270) {
      val scale = maxOf(size.width / size.height, size.height / size.width)
      scaleX = scale
      scaleY = scale
    }
  }

  Box(modifier.fillMaxSize()) {
    Image(
      painter = rememberAsyncImagePainter(Uri.parse(theme.backgroundUri)),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = rotMod.alpha(theme.backgroundOpacity),
    )
  }
}

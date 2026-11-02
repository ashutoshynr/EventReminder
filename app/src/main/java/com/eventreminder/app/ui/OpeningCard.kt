package com.eventreminder.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eventreminder.app.data.Category
import kotlinx.coroutines.delay

private val Ink = Color(0xFF1B1B1F)

/** Animated welcome card shown on every start. Tap anywhere to skip. */
@Composable
fun OpeningCard(vm: MainViewModel, showSummary: Boolean, onDone: () -> Unit) {
    val summary by produceState<String?>(null, showSummary) { value = if (showSummary) vm.summary() else null }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        delay(700)
        onDone()
    }
    val pulse by rememberInfiniteTransition(label = "pulse")
        .animateFloat(1f, 1.1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale")

    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF5B4BDB), Color(0xFF8E24AA), Color(0xFFE91E63))))
            .pointerInput(Unit) { detectTapGestures { onDone() } },
        contentAlignment = Alignment.Center
    ) {
        Card(
            Modifier.padding(28.dp).fillMaxWidth().graphicsLayer {
                alpha = appear.value
                val s = 0.85f + 0.15f * appear.value
                scaleX = s; scaleY = s
                translationY = (1f - appear.value) * 80f
            },
            shape = RoundedCornerShape(30.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(16.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier.size(104.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF5B4BDB), Color(0xFFE91E63)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🎈", fontSize = 54.sp, modifier = Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse })
                }
                Text("Event Reminder", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                Text(
                    "Never miss a moment that matters",
                    fontSize = 15.sp, color = Ink.copy(alpha = 0.7f), textAlign = TextAlign.Center
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Category.all.forEach { c ->
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(Color(c.color).copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) { Text(c.emoji, fontSize = 22.sp) }
                    }
                }
                Text(
                    summary ?: " ", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF5B4BDB), textAlign = TextAlign.Center
                )
                Text("Tap to continue", fontSize = 12.sp, color = Ink.copy(alpha = 0.45f))
            }
        }
    }
}

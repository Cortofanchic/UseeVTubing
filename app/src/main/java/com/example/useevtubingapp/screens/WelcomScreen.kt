package com.example.useevtubingapp.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.example.useevtubingapp.ui.theme.blueDark7
import kotlinx.coroutines.delay


@Composable
fun WelcomScreen(navController: NavHostController) {
    val startAnimation = remember { mutableStateOf(false) }
    val alphaAnim = animateFloatAsState(
        targetValue = if (startAnimation.value) 1f else 0f,
        animationSpec = tween(durationMillis = 1000), label = ""
    )

    LaunchedEffect(key1 = true) {
        startAnimation.value = true
        delay(4000)
        navController.popBackStack()
        navController.navigate("characterScreen")
    }
    WelcomScreenAnimation(alphaAnim = alphaAnim.value)
}

@Composable
fun WelcomScreenAnimation(alphaAnim: Float) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            modifier = Modifier
                .size(120.dp)
                .alpha(alpha = alphaAnim),
            imageVector = Icons.Default.Accessibility,
            contentDescription = "Logo icon",
            tint = blueDark7
        )
    }
}
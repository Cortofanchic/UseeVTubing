package com.example.useevtubingapp.utils

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

object Constants {
    val BottomNavItems = listOf(
        BottomNavItemModel(
            label = "Camera",
            icon = Icons.Filled.CameraAlt,
            route = "cameraScreen"
        ),
//        BottomNavItemModel(
//            label = "Gallery",
//            icon = Icons.Filled.Image,
//            route = "galleryScreen"
//        ),
        BottomNavItemModel(
            label = "Character",
            icon = Icons.Filled.Person,
            route = "characterScreen"
        ),

    )
}
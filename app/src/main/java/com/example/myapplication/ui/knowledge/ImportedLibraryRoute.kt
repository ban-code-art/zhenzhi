package com.example.myapplication.ui.knowledge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.myapplication.ui.navigation.BackDecision
import com.example.myapplication.ui.navigation.BackPage
import com.example.myapplication.ui.navigation.BackState
import com.example.myapplication.ui.navigation.isBackImeVisible
import com.example.myapplication.ui.navigation.unifiedBackRequest

@Composable
fun ImportedLibraryRoute(
    onOpenReader: (String) -> Unit,
    onBack: () -> Unit,
    initialPosition: String = "{}",
    onPosition: (String) -> Unit = {},
) {
    val requestBack = unifiedBackRequest(BackState(page = BackPage.CHILD, imeVisible = isBackImeVisible())) {
        if (it == BackDecision.PopPage) onBack()
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        ShelfScreen(
            onOpenReader = onOpenReader,
            importedOnly = true,
            initialPosition = initialPosition,
            onPosition = onPosition,
            onBack = requestBack,
        )
    }
}

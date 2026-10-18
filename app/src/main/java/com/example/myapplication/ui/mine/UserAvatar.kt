package com.example.myapplication.ui.mine

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.data.avatar.AvatarStorage

data class AvatarOption(
    val id: Int,
    val icon: ImageVector,
    val tint: Color,
    val background: Color
)

val AvatarOptions = listOf(
    AvatarOption(1, Icons.Filled.Person, Color(0xFF3F8F76), Color(0xFFCFE9DE)),
    AvatarOption(2, Icons.Filled.Face, Color(0xFF5B8DEF), Color(0xFFE8F0FE)),
    AvatarOption(3, Icons.Filled.Star, Color(0xFFE8894C), Color(0xFFFCEFE0)),
    AvatarOption(4, Icons.Filled.Favorite, Color(0xFFD9534F), Color(0xFFFDECEA)),
    AvatarOption(5, Icons.Filled.AccountCircle, Color(0xFF8A6FD1), Color(0xFFEFEAFB)),
    AvatarOption(6, Icons.Filled.CheckCircle, Color(0xFF4FA98C), Color(0xFFE4F3EC))
)

@Composable
fun UserAvatar(
    avatarId: Int,
    name: String?,
    size: Dp = 76.dp,
    avatarPath: String? = null,
    modifier: Modifier = Modifier
) {
    val option = AvatarOptions.firstOrNull { it.id == avatarId }
    val context = LocalContext.current
    val bitmap = remember(avatarPath) {
        avatarPath?.let { stored ->
            val file = AvatarStorage.resolve(context, stored)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            } else {
                null
            }
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(option?.background ?: Color(0xFFCFE9DE)),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            option != null -> Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = option.tint,
                modifier = Modifier.size(size * 0.5f)
            )
            else -> Text(
                text = (name ?: "我").take(1),
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF3F8F76)
            )
        }
    }
}

@Composable
fun AvatarPickerDialog(
    selectedId: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        title = { Text("切换头像") },
        text = {
            Column {
                Text(
                    text = "选择一个头像",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        AvatarChoice(
                            avatarId = 0,
                            name = "我",
                            selected = selectedId == 0,
                            onClick = { onSelect(0) }
                        )
                    }
                    items(AvatarOptions, key = { it.id }) { option ->
                        AvatarChoice(
                            avatarId = option.id,
                            name = "",
                            selected = selectedId == option.id,
                            onClick = { onSelect(option.id) }
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun AvatarChoice(
    avatarId: Int,
    name: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.border(2.dp, PrimaryGreen, CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        UserAvatar(avatarId = avatarId, name = name, size = 52.dp)
    }
}

package com.example.myapplication.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class BottomNavItem(val label: String, val icon: ImageVector)

private val BarWhite = Color(0xFFFFFFFF)
private val ActiveGreen = Color(0xFF4FA98C)
private val InactiveGray = Color(0xFF8A94A6)

@Composable
fun AppBottomBar(
    items: List<BottomNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            )
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(BarWhite)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val color = if (selected) ActiveGreen else InactiveGray
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(index) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = color,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.label,
                        color = color,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(if (selected) 20.dp else 0.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (selected) ActiveGreen else Color.Transparent)
                    )
                }
            }
        }
    }
}

package com.example.myapplication.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.data.local.entity.UserEntity

@Composable
fun MineScreen(
    onNavigate: (MineDestination) -> Unit
) {
    val context = LocalContext.current
    val factory = remember { MineViewModelFactory(context.applicationContext) }
    val viewModel: MineViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground)
            .verticalScroll(rememberScrollState())
    ) {
        ProfileHeader(
            user = state.user,
            onClick = { onNavigate(MineDestination.EditProfile) }
        )
        Spacer(Modifier.height(4.dp))
        StatsCard(state)
        MenuCard(
            state = state,
            onNavigate = onNavigate
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProfileHeader(user: UserEntity?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFDCEFE6), PageBackground)
                )
            )
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(
                avatarId = user?.avatarId ?: 0,
                name = user?.nickname ?: user?.username,
                size = 76.dp,
                avatarPath = user?.avatarPath
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = user?.nickname ?: user?.username ?: "未登录",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "针灸学习者",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFF9AA7A1)
            )
        }
    }
}

@Composable
private fun StatsCard(state: MineUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(
                icon = Icons.Filled.DateRange,
                tint = PrimaryGreen,
                background = GreenBg,
                value = state.learnedDays,
                label = "学习天数",
                modifier = Modifier.weight(1f)
            )
            VerticalDivider(
                modifier = Modifier.height(48.dp),
                color = Color(0xFFEDF1EF)
            )
            StatItem(
                icon = Icons.Filled.Star,
                tint = Orange,
                background = OrangeBg,
                value = state.streakDays,
                label = "连续打卡天数",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    tint: Color,
    background: Color,
    value: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(background),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun MenuCard(
    state: MineUiState,
    onNavigate: (MineDestination) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            MenuRow(
                icon = Icons.Filled.PlayArrow,
                tint = PrimaryGreen,
                background = GreenBg,
                title = "我的课程",
                subtitle = "已学习 ${state.completedChapters} 个章节",
                onClick = { onNavigate(MineDestination.StudyProgress) }
            )
            MenuDivider()
            MenuRow(
                icon = Icons.Filled.Star,
                tint = Blue,
                background = BlueBg,
                title = "收藏题目",
                subtitle = "已收藏 ${state.favoriteCount} 道题目",
                onClick = { onNavigate(MineDestination.Favorites) }
            )
            MenuDivider()
            MenuRow(
                icon = Icons.Filled.List,
                tint = PrimaryGreen,
                background = GreenBg,
                title = "学习记录",
                subtitle = "查看你的学习轨迹",
                onClick = { onNavigate(MineDestination.Scores) }
            )
            MenuDivider()
            MenuRow(
                icon = Icons.Filled.Warning,
                tint = Orange,
                background = OrangeBg,
                title = "错题本",
                subtitle = "共 ${state.wrongCount} 道错题",
                onClick = { onNavigate(MineDestination.WrongQuestions) }
            )
            MenuDivider()
            MenuRow(
                icon = Icons.Filled.Notifications,
                tint = Blue,
                background = BlueBg,
                title = "消息通知",
                subtitle = "暂无新消息",
                onClick = { onNavigate(MineDestination.Notifications) }
            )
            MenuDivider()
            MenuRow(
                icon = Icons.Filled.Settings,
                tint = Gray,
                background = GrayBg,
                title = "设置",
                subtitle = "账号与偏好设置",
                onClick = { onNavigate(MineDestination.Settings) }
            )
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    tint: Color,
    background: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(background),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color(0xFFB6BFBB)
        )
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 70.dp),
        color = Color(0xFFF0F3F1)
    )
}

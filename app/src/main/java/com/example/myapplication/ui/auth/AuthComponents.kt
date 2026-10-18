package com.example.myapplication.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.R

internal val AuthGreen = Color(0xFF6FBF9E)
internal val AuthDarkGreen = Color(0xFF2E5B4E)
internal val AuthText = Color(0xFF38423D)
internal val AuthHint = Color(0xFF9AA7A1)
internal val AuthFieldBg = Color(0xFFF6FAF8)
internal val AuthLine = Color(0xFFBFDCCB)

@Composable
internal fun AuthBackground(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFE6F4EC),
                        Color(0xFFF3FAF6),
                        Color(0xFFFFFFFF)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Composable
internal fun AuthLogo() {
    Box(
        modifier = Modifier
            .size(124.dp)
            .clip(CircleShape)
            .background(Color(0xFFDDF0E6)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.login_logo),
            contentDescription = "针灸学习",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(124.dp)
                .clip(CircleShape)
        )
    }
}

@Composable
internal fun AuthTitle(title: String, subtitle: String) {
    Text(
        text = title,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        color = AuthDarkGreen
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = subtitle,
        fontSize = 13.sp,
        color = AuthHint
    )
}

@Composable
internal fun AuthDivider() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(
            modifier = Modifier.width(58.dp),
            color = AuthLine
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(AuthGreen)
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(
            modifier = Modifier.width(58.dp),
            color = AuthLine
        )
    }
}

@Composable
internal fun AuthCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
internal fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(text = placeholder, color = AuthHint, fontSize = 14.sp)
        },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = Color(0xFF8FC7AE)
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = AuthFieldBg,
            focusedBorderColor = AuthGreen,
            unfocusedBorderColor = Color(0xFFE8EFEA),
            cursorColor = AuthGreen
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun AuthPrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AuthGreen,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFB9DCCC),
            disabledContentColor = Color.White
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

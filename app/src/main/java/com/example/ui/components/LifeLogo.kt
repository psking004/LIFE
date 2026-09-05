package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GbAccentPurple
import com.example.ui.theme.GbPrimary
import com.example.ui.theme.GbPrimaryDark
import com.example.ui.theme.LifeRefinement

@Composable
fun LifeLogo(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    showTagline: Boolean = false,
    fontSize: Int = 22
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            LifeTreeIcon(size = size)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "LIFE",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = fontSize.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Black
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (showTagline) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Ideas evolve here.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun LifeTreeIcon(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        val root = Offset(w * 0.5f, h * 0.22f)
        val junction = Offset(w * 0.5f, h * 0.52f)
        val leftChild = Offset(w * 0.22f, h * 0.78f)
        val rightChild = Offset(w * 0.78f, h * 0.78f)
        val subChild = Offset(w * 0.88f, h * 0.92f)

        val stroke = w * 0.08f

        // Branch lines
        drawLine(
            color = GbPrimary,
            start = root,
            end = junction,
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = GbPrimary,
            start = junction,
            end = leftChild,
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = GbPrimary,
            start = junction,
            end = rightChild,
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = LifeRefinement,
            start = rightChild,
            end = subChild,
            strokeWidth = stroke * 0.8f,
            cap = StrokeCap.Round
        )

        // Nodes
        drawCircle(color = GbPrimary, radius = w * 0.14f, center = root)
        drawCircle(color = Color.White, radius = w * 0.06f, center = root)

        drawCircle(color = GbAccentPurple, radius = w * 0.11f, center = junction)

        drawCircle(color = GbPrimaryDark, radius = w * 0.12f, center = leftChild)
        drawCircle(color = Color.White, radius = w * 0.05f, center = leftChild)

        drawCircle(color = LifeRefinement, radius = w * 0.12f, center = rightChild)
        drawCircle(color = Color.White, radius = w * 0.05f, center = rightChild)

        drawCircle(color = Color(0xFF1E8E3E), radius = w * 0.08f, center = subChild)
    }
}


package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.NodeType
import com.example.ui.theme.LifeImprovement
import com.example.ui.theme.LifeImprovementBg
import com.example.ui.theme.LifeImprovementBorder
import com.example.ui.theme.LifeProblem
import com.example.ui.theme.LifeProblemBg
import com.example.ui.theme.LifeProblemBorder
import com.example.ui.theme.LifeRefinement
import com.example.ui.theme.LifeRefinementBg
import com.example.ui.theme.LifeRefinementBorder

@Composable
fun NodeTypeBadge(
    type: NodeType,
    modifier: Modifier = Modifier,
    isSmall: Boolean = false
) {
    val isDark = isSystemInDarkTheme()

    val (bg, textColor, borderColor, label) = when (type) {
        NodeType.PROBLEM -> {
            val bgCol = if (isDark) Color(0xFF450A0A) else LifeProblemBg
            val txtCol = if (isDark) Color(0xFFFCA5A5) else LifeProblem
            val bdrCol = if (isDark) Color(0xFF7F1D1D) else LifeProblemBorder
            Quad(bgCol, txtCol, bdrCol, "PROBLEM")
        }
        NodeType.IMPROVEMENT -> {
            val bgCol = if (isDark) Color(0xFF082F49) else LifeImprovementBg
            val txtCol = if (isDark) Color(0xFF7DD3FC) else LifeImprovement
            val bdrCol = if (isDark) Color(0xFF075985) else LifeImprovementBorder
            Quad(bgCol, txtCol, bdrCol, "IMPROVEMENT")
        }
        NodeType.REFINEMENT -> {
            val bgCol = if (isDark) Color(0xFF451A03) else LifeRefinementBg
            val txtCol = if (isDark) Color(0xFFFCD34D) else LifeRefinement
            val bdrCol = if (isDark) Color(0xFF78350F) else LifeRefinementBorder
            Quad(bgCol, txtCol, bdrCol, "REFINEMENT")
        }
    }

    val hPadding = if (isSmall) 6.dp else 10.dp
    val vPadding = if (isSmall) 2.dp else 4.dp
    val fontSize = if (isSmall) 9.sp else 11.sp

    Box(
        modifier = modifier
            .testTag("badge_${type.name.lowercase()}")
            .background(bg, shape = RoundedCornerShape(6.dp))
            .border(1.dp, borderColor, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = hPadding, vertical = vPadding)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = textColor
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

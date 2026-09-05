package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.North
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GbBorderDark
import com.example.ui.theme.GbBorderLight
import com.example.ui.theme.GbOnPrimaryContainer
import com.example.ui.theme.GbPrimary
import com.example.ui.theme.GbPrimaryContainer
import com.example.ui.theme.GbPrimaryDark

@Composable
fun VoteButton(
    votesCount: Int,
    isVoted: Boolean,
    onVoteToggle: () -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    enabled: Boolean = true
) {
    val isDark = isSystemInDarkTheme()

    val targetBgColor = when {
        isVoted && isDark -> GbPrimaryDark.copy(alpha = 0.25f)
        isVoted && !isDark -> GbPrimaryContainer
        isDark -> Color(0xFF1E293B)
        else -> Color.White
    }

    val targetBorderColor = when {
        isVoted -> if (isDark) GbPrimaryDark else GbPrimary
        isDark -> GbBorderDark
        else -> GbBorderLight
    }

    val targetContentColor = when {
        isVoted -> if (isDark) GbPrimaryDark else GbOnPrimaryContainer
        isDark -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val animatedBg by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "voteBg"
    )

    val animatedBorder by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "voteBorder"
    )

    val animatedContent by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "voteContent"
    )

    val minH = if (isCompact) 36.dp else 44.dp
    val hPad = if (isCompact) 10.dp else 14.dp

    Row(
        modifier = modifier
            .testTag("vote_button")
            .height(minH)
            .background(animatedBg, shape = RoundedCornerShape(24.dp))
            .border(1.dp, animatedBorder, shape = RoundedCornerShape(24.dp))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 24.dp)
            ) { onVoteToggle() }
            .padding(horizontal = hPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.North,
            contentDescription = if (isVoted) "Remove upvote" else "Upvote",
            tint = animatedContent,
            modifier = Modifier.size(if (isCompact) 14.dp else 16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = votesCount.toString(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = if (isCompact) 13.sp else 14.sp,
                fontWeight = if (isVoted) FontWeight.Bold else FontWeight.SemiBold
            ),
            color = animatedContent
        )
    }
}


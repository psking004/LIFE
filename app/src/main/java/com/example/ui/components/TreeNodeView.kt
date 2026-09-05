package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.NodeType
import com.example.domain.model.TreeDisplayNode
import com.example.ui.theme.GbPrimary
import com.example.ui.theme.GbPrimaryContainer
import com.example.ui.theme.GbTreeRailDark
import com.example.ui.theme.GbTreeRailLight
import com.example.ui.theme.LifeImprovement
import com.example.ui.theme.LifeProblem
import com.example.ui.theme.LifeRefinement

@Composable
fun TreeNodeView(
    displayNode: TreeDisplayNode,
    onNodeClick: () -> Unit,
    onVoteToggle: () -> Unit,
    onToggleExpand: () -> Unit,
    onRefineClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val node = displayNode.node
    val isDark = isSystemInDarkTheme()
    val railColor = if (isDark) GbTreeRailDark else GbTreeRailLight

    val nodeColor = when (node.type) {
        NodeType.PROBLEM -> LifeProblem
        NodeType.IMPROVEMENT -> LifeImprovement
        NodeType.REFINEMENT -> LifeRefinement
    }

    Row(
        modifier = modifier
            .testTag("tree_node_${node.id}")
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 4.dp)
    ) {
        // Ancestor guide rail columns (for hierarchical tree layout)
        displayNode.ancestorLineGuides.forEach { showLine ->
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                if (showLine) {
                    Canvas(modifier = Modifier.fillMaxHeight().width(2.dp)) {
                        drawLine(
                            color = railColor,
                            start = Offset(size.width / 2, 0f),
                            end = Offset(size.width / 2, size.height),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }
            }
        }

        // Current Level Branch Connector Column
        Box(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter
        ) {
            Canvas(modifier = Modifier.fillMaxHeight().fillMaxWidth()) {
                val midX = size.width / 2
                val nodeCenterY = 24.dp.toPx()

                // Vertical incoming branch from top
                drawLine(
                    color = railColor,
                    start = Offset(midX, 0f),
                    end = Offset(midX, if (displayNode.isLastChild) nodeCenterY else size.height),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Horizontal branch to node card
                drawLine(
                    color = railColor,
                    start = Offset(midX, nodeCenterY),
                    end = Offset(size.width, nodeCenterY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Bullet Node Ring + Core
                drawCircle(
                    color = nodeColor,
                    radius = 4.5.dp.toPx(),
                    center = Offset(midX, nodeCenterY)
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.dp.toPx(),
                    center = Offset(midX, nodeCenterY)
                )
            }
        }

        // Main Node Card
        Card(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true)
                ) { onNodeClick() },
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    if (node.isVotedByCurrentUser) GbPrimary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline
                )
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                // Top row: Type badge + Expand/Collapse Button (if has children)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NodeTypeBadge(type = node.type, isSmall = true)

                    if (displayNode.hasChildren) {
                        Row(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true)
                                ) { onToggleExpand() }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${displayNode.childCount} refined",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                imageVector = if (displayNode.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (displayNode.isExpanded) "Collapse" else "Expand",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Title
                Text(
                    text = node.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Short preview
                Text(
                    text = node.description,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom row: Author, Vote button, and "Refine" CTA
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VoteButton(
                            votesCount = node.votesCount,
                            isVoted = node.isVotedByCurrentUser,
                            onVoteToggle = onVoteToggle,
                            isCompact = true
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = node.authorRegisterNumber,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Direct "Refine" quick action
                    Row(
                        modifier = Modifier
                            .background(
                                color = GbPrimaryContainer,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true)
                            ) { onRefineClick() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = GbPrimary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Refine",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = GbPrimary
                        )
                    }
                }
            }
        }
    }
}


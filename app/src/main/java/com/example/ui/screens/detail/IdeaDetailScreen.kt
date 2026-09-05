package com.example.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.IdeaNode
import com.example.domain.model.NodeType
import com.example.domain.model.TreeDisplayNode
import com.example.domain.model.User
import com.example.ui.components.NodeTypeBadge
import com.example.ui.components.ParentContextCard
import com.example.ui.components.TreeNodeView
import com.example.ui.components.VoteButton
import com.example.ui.components.formatTimeAgo
import com.example.ui.theme.GbPrimary
import com.example.ui.theme.GbPrimaryContainer
import com.example.ui.theme.LifeProblem
import com.example.ui.theme.LifeRefinement

@Composable
fun IdeaDetailScreen(
    node: IdeaNode?,
    treeNodes: List<TreeDisplayNode>,
    currentUser: User?,
    onVoteToggle: (String) -> Unit,
    onToggleExpand: (String) -> Unit,
    onRefineNode: (IdeaNode) -> Unit,
    onEditNode: (IdeaNode) -> Unit,
    onDeleteNode: (IdeaNode) -> Unit,
    onParentClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (node == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Idea not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val isAuthor = currentUser?.id == node.authorId
    val isEditable = isAuthor && (System.currentTimeMillis() - node.createdAt) <= (30 * 60 * 1000)

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("idea_detail_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Context header if this node improves or refines a parent
            if (node.parentId != null && node.parentTitle != null) {
                item(key = "parent_context") {
                    ParentContextCard(
                        parentTitle = node.parentTitle,
                        parentType = if (node.type == NodeType.REFINEMENT) NodeType.REFINEMENT else NodeType.PROBLEM,
                        onParentClick = { onParentClick?.invoke(node.parentId) },
                        isRefinementNotice = (node.type == NodeType.REFINEMENT)
                    )
                }
            }

            // Main Idea Card
            item(key = "main_idea_card") {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("detail_main_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        // Top row: Type badge + Author actions (Edit / Delete)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NodeTypeBadge(type = node.type)

                            if (isAuthor) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isEditable) {
                                        IconButton(
                                            onClick = { onEditNode(node) },
                                            modifier = Modifier.size(36.dp).testTag("edit_node_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit Idea",
                                                tint = GbPrimary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onDeleteNode(node) },
                                        modifier = Modifier.size(36.dp).testTag("delete_node_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete Idea",
                                            tint = LifeProblem,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Full Title
                        Text(
                            text = node.title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                lineHeight = 26.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Description
                        Text(
                            text = node.description,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Footer info: Author, date, score, and Vote button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            VoteButton(
                                votesCount = node.votesCount,
                                isVoted = node.isVotedByCurrentUser,
                                onVoteToggle = { onVoteToggle(node.id) }
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(14.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = node.authorRegisterNumber,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = formatTimeAgo(node.createdAt),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 12.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Primary Refine Action CTA Card
            item(key = "refine_cta_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = GbPrimaryContainer.copy(alpha = 0.6f)
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(GbPrimary.copy(alpha = 0.3f))
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (node.type == NodeType.PROBLEM) "Propose an Improvement" else "Propose a Refinement",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (node.type == NodeType.PROBLEM) "Post an improvement to this problem (+5 pts)" else "Refine this idea with enhanced techniques (+10 pts)",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Button(
                            onClick = { onRefineNode(node) },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GbPrimary,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.testTag("refine_node_button")
                        ) {
                            Icon(
                                imageVector = if (node.type == NodeType.PROBLEM) Icons.Default.Add else Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (node.type == NodeType.PROBLEM) "Improve" else "Refine",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            // Section Header: Evolution Tree
            item(key = "evolution_tree_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = null,
                            tint = GbPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Idea Evolution Tree",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = "${treeNodes.size} nodes total",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Tree hierarchy items
            if (treeNodes.isEmpty()) {
                item(key = "empty_tree") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No refinements yet. Be the first to branch this idea!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(
                    items = treeNodes,
                    key = { it.node.id }
                ) { displayNode ->
                    TreeNodeView(
                        displayNode = displayNode,
                        onNodeClick = { /* already in detail or select branch */ },
                        onVoteToggle = { onVoteToggle(displayNode.node.id) },
                        onToggleExpand = { onToggleExpand(displayNode.node.id) },
                        onRefineClick = { onRefineNode(displayNode.node) }
                    )
                }
            }
        }
    }
}

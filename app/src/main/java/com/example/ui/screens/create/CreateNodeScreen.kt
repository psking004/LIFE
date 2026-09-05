package com.example.ui.screens.create

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.IdeaNode
import com.example.domain.model.NodeType
import com.example.ui.components.NodeTypeBadge
import com.example.ui.components.ParentContextCard
import com.example.ui.theme.GbPrimary
import com.example.ui.theme.GbPrimaryContainer
import com.example.ui.theme.LifeImprovement
import com.example.ui.theme.LifeProblem
import com.example.ui.theme.LifeRefinement

@Composable
fun CreateNodeScreen(
    targetType: NodeType,
    parentNode: IdeaNode? = null,
    availableProblems: List<IdeaNode> = emptyList(),
    onSubmitProblem: (title: String, description: String) -> Unit,
    onSubmitImprovement: (title: String, description: String, parentProblemId: String?) -> Unit,
    onSubmitRefinement: (title: String, description: String, parentNodeId: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedParentProblem by remember { mutableStateOf<IdeaNode?>(if (targetType == NodeType.IMPROVEMENT) parentNode else null) }
    var parentDropdownExpanded by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    fun validateAndSubmit() {
        val t = title.trim()
        val d = description.trim()

        if (t.length < 5) {
            errorText = "Title must be at least 5 characters"
            return
        }
        if (d.length < 15) {
            errorText = "Description must be at least 15 characters to provide clarity"
            return
        }

        errorText = null

        when (targetType) {
            NodeType.PROBLEM -> onSubmitProblem(t, d)
            NodeType.IMPROVEMENT -> onSubmitImprovement(t, d, selectedParentProblem?.id)
            NodeType.REFINEMENT -> {
                if (parentNode == null) {
                    errorText = "Refinement requires a parent idea"
                    return
                }
                onSubmitRefinement(t, d, parentNode.id)
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("create_node_screen"),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(20.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = when (targetType) {
                            NodeType.PROBLEM -> "Post a Campus Problem"
                            NodeType.IMPROVEMENT -> "Propose Improvement"
                            NodeType.REFINEMENT -> "Refine Existing Idea"
                        },
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = when (targetType) {
                            NodeType.PROBLEM -> "Earn +5 score for highlighting challenges"
                            NodeType.IMPROVEMENT -> "Earn +5 score for constructive improvements"
                            NodeType.REFINEMENT -> "Earn +10 score for enhancing ideas"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("create_cancel_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // If Refinement: show locked parent context card
            if (targetType == NodeType.REFINEMENT && parentNode != null) {
                ParentContextCard(
                    parentTitle = parentNode.title,
                    parentType = parentNode.type,
                    isRefinementNotice = true
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            // If Improvement: optional parent Problem selector
            if (targetType == NodeType.IMPROVEMENT) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Linked Problem (Optional)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Is this improvement addressing an existing problem?",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { parentDropdownExpanded = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = selectedParentProblem?.title ?: "Standalone Idea (No linked problem)",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = if (selectedParentProblem != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (selectedParentProblem != null) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select parent problem",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            DropdownMenu(
                                expanded = parentDropdownExpanded,
                                onDismissRequest = { parentDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("None (Standalone Improvement)") },
                                    onClick = {
                                        selectedParentProblem = null
                                        parentDropdownExpanded = false
                                    }
                                )
                                availableProblems.forEach { p ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = p.title,
                                                maxLines = 1,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        },
                                        onClick = {
                                            selectedParentProblem = p
                                            parentDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Input Fields Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // Title Field
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            if (it.length <= 120) {
                                title = it
                                errorText = null
                            }
                        },
                        label = { Text("Idea Title") },
                        placeholder = {
                            Text(
                                when (targetType) {
                                    NodeType.PROBLEM -> "e.g. Wi-Fi drops frequently in Engineering block"
                                    NodeType.IMPROVEMENT -> "e.g. Peer-to-Peer textbook swap program"
                                    NodeType.REFINEMENT -> "e.g. Add QR code verification to book exchange"
                                }
                            )
                        },
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Be concise and specific")
                                Text("${title.length}/120")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("create_title_input"),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GbPrimary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Description Field
                    OutlinedTextField(
                        value = description,
                        onValueChange = {
                            if (it.length <= 800) {
                                description = it
                                errorText = null
                            }
                        },
                        label = { Text("Detailed Explanation") },
                        placeholder = {
                            Text(
                                when (targetType) {
                                    NodeType.PROBLEM -> "Describe when this happens, who is affected, and why it matters..."
                                    NodeType.IMPROVEMENT -> "Explain how this improvement works, required steps, and expected impact..."
                                    NodeType.REFINEMENT -> "Detail what specific adjustments you are adding to improve the base idea..."
                                }
                            )
                        },
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Minimum 20 characters")
                                Text("${description.length}/800")
                            }
                        },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth().testTag("create_description_input"),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GbPrimary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    if (errorText != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = errorText ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.testTag("create_error_text")
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Submit Pill Button
                    Button(
                        onClick = { validateAndSubmit() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("create_submit_button"),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GbPrimary,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (targetType) {
                                NodeType.PROBLEM -> "Publish Problem (+5 pts)"
                                NodeType.IMPROVEMENT -> "Share Improvement (+10 pts)"
                                NodeType.REFINEMENT -> "Submit Refinement (+15 pts)"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

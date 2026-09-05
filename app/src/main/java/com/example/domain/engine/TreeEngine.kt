package com.example.domain.engine

import com.example.domain.model.IdeaNode
import com.example.domain.model.TreeDisplayNode

object TreeEngine {

    /**
     * Internal tree representation node for hierarchical operations.
     */
    data class InternalTreeNode(
        val node: IdeaNode,
        val depth: Int,
        val children: MutableList<InternalTreeNode> = mutableListOf()
    )

    /**
     * Builds an internal tree starting from a root node using all available nodes.
     * Includes cycle protection.
     */
    fun buildHierarchy(
        rootNode: IdeaNode,
        allNodes: List<IdeaNode>
    ): InternalTreeNode {
        val childrenMap = allNodes
            .filter { !it.isDeleted && it.parentId != null }
            .groupBy { it.parentId!! }

        val visited = mutableSetOf<String>()

        fun buildRecursive(current: IdeaNode, currentDepth: Int): InternalTreeNode {
            visited.add(current.id)
            val treeNode = InternalTreeNode(node = current, depth = currentDepth)
            val directChildren = childrenMap[current.id] ?: emptyList()
            for (child in directChildren) {
                if (!visited.contains(child.id)) {
                    treeNode.children.add(buildRecursive(child, currentDepth + 1))
                }
            }
            return treeNode
        }

        return buildRecursive(rootNode, 0)
    }

    /**
     * Flattens the tree into a list of TreeDisplayNode, respecting expandedNodeIds.
     * By default, root (depth 0) and first-level children (depth 1) are expanded.
     * Deeper descendants (depth >= 2) require their parent ID to be in expandedNodeIds.
     */
    fun flattenTree(
        rootNode: IdeaNode,
        allNodes: List<IdeaNode>,
        expandedNodeIds: Set<String>
    ): List<TreeDisplayNode> {
        val rootInternal = buildHierarchy(rootNode, allNodes)
        val result = mutableListOf<TreeDisplayNode>()

        fun traverse(
            item: InternalTreeNode,
            isLastChild: Boolean,
            ancestorGuides: List<Boolean>
        ) {
            val childCount = item.children.size
            val hasChildren = childCount > 0
            val isExpanded = if (item.depth == 0) {
                true // Root is always expanded
            } else if (item.depth == 1) {
                // First level is expanded unless explicitly collapsed, or if present in explicit set
                !expandedNodeIds.contains("collapsed_${item.node.id}")
            } else {
                expandedNodeIds.contains(item.node.id)
            }

            // Add display item for current node (skip depth 0 if root is shown in hero header)
            if (item.depth > 0) {
                result.add(
                    TreeDisplayNode(
                        node = item.node,
                        depth = item.depth,
                        hasChildren = hasChildren,
                        isExpanded = isExpanded,
                        childCount = childCount,
                        isLastChild = isLastChild,
                        ancestorLineGuides = ancestorGuides
                    )
                )
            }

            if (hasChildren && (item.depth == 0 || isExpanded)) {
                val nextGuides = if (item.depth > 0) ancestorGuides + (!isLastChild) else emptyList()
                item.children.forEachIndexed { index, child ->
                    val isLast = index == item.children.size - 1
                    traverse(child, isLast, nextGuides)
                }
            }
        }

        traverse(rootInternal, isLastChild = true, ancestorGuides = emptyList())
        return result
    }

    /**
     * Counts all descendants of a node.
     */
    fun countDescendants(nodeId: String, allNodes: List<IdeaNode>): Int {
        val childrenMap = allNodes
            .filter { !it.isDeleted && it.parentId != null }
            .groupBy { it.parentId!! }

        var count = 0
        val queue = ArrayDeque<String>()
        queue.add(nodeId)
        val visited = mutableSetOf(nodeId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = childrenMap[current] ?: emptyList()
            for (child in children) {
                if (visited.add(child.id)) {
                    count++
                    queue.add(child.id)
                }
            }
        }
        return count
    }
}

package com.example.domain.model

data class TreeDisplayNode(
    val node: IdeaNode,
    val depth: Int,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
    val childCount: Int,
    val isLastChild: Boolean,
    val ancestorLineGuides: List<Boolean> = emptyList()
)

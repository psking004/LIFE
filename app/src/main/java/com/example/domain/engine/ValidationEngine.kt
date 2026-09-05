package com.example.domain.engine

import com.example.domain.model.NodeType

object ValidationEngine {
    const val MIN_TITLE_LENGTH = 10
    const val MIN_DESCRIPTION_LENGTH = 20
    const val EDIT_WINDOW_MILLIS = 30 * 60 * 1000L // 30 minutes

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()

        val isValid: Boolean get() = this is Valid
    }

    fun validateTitle(title: String): ValidationResult {
        val trimmed = title.trim()
        return when {
            trimmed.isEmpty() -> ValidationResult.Invalid("Title cannot be empty")
            trimmed.length < MIN_TITLE_LENGTH -> ValidationResult.Invalid("Title must be at least $MIN_TITLE_LENGTH characters (currently ${trimmed.length})")
            else -> ValidationResult.Valid
        }
    }

    fun validateDescription(description: String): ValidationResult {
        val trimmed = description.trim()
        return when {
            trimmed.isEmpty() -> ValidationResult.Invalid("Description cannot be empty")
            trimmed.length < MIN_DESCRIPTION_LENGTH -> ValidationResult.Invalid("Description must be at least $MIN_DESCRIPTION_LENGTH characters (currently ${trimmed.length})")
            else -> ValidationResult.Valid
        }
    }

    fun validateNodeCreation(
        title: String,
        description: String,
        type: NodeType,
        parentType: NodeType?
    ): ValidationResult {
        val titleResult = validateTitle(title)
        if (!titleResult.isValid) return titleResult

        val descResult = validateDescription(description)
        if (!descResult.isValid) return descResult

        // Relationship validation
        when (type) {
            NodeType.PROBLEM -> {
                if (parentType != null) {
                    return ValidationResult.Invalid("Problems cannot have a parent idea")
                }
            }
            NodeType.IMPROVEMENT -> {
                if (parentType != null && parentType != NodeType.PROBLEM) {
                    return ValidationResult.Invalid("Improvements can only branch from Problems or be standalone")
                }
            }
            NodeType.REFINEMENT -> {
                if (parentType == null) {
                    return ValidationResult.Invalid("Refinements must have a parent idea")
                }
                // Refinements can refine Problems, Improvements, or other Refinements
            }
        }

        return ValidationResult.Valid
    }

    fun canEditNode(createdAt: Long, currentTime: Long = System.currentTimeMillis()): Boolean {
        val age = currentTime - createdAt
        return age in 0..EDIT_WINDOW_MILLIS
    }

    fun getRemainingEditTimeMinutes(createdAt: Long, currentTime: Long = System.currentTimeMillis()): Long {
        val remainingMillis = EDIT_WINDOW_MILLIS - (currentTime - createdAt)
        return if (remainingMillis > 0) (remainingMillis / 60000) + 1 else 0
    }

    fun canDeleteNode(authorId: String, currentUserId: String, childCount: Int): ValidationResult {
        if (authorId != currentUserId) {
            return ValidationResult.Invalid("You can only delete your own ideas")
        }
        if (childCount > 0) {
            return ValidationResult.Invalid("If this idea has already been refined, it cannot be deleted.")
        }
        return ValidationResult.Valid
    }
}

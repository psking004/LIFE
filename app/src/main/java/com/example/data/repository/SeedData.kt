package com.example.data.repository

import com.example.data.local.entities.IdeaNodeEntity
import com.example.data.local.entities.UserEntity
import com.example.data.local.entities.UserScoreEntity
import com.example.data.local.entities.VoteEntity
import com.example.domain.model.NodeType

object SeedData {
    // Initial pilot and demo users
    val initialUsers = listOf(
        UserEntity(
            id = "user_23cse001",
            registerNumber = "23CSE001",
            name = "Aarav Sharma",
            phoneNumber = "9876541234",
            department = "Computer Science & Engineering",
            email = "23cse001@campus.edu",
            accountStatus = "ACTIVE",
            isFirstLogin = false,
            isSuspended = false,
            isAdmin = false
        ),
        UserEntity(
            id = "user_23cse002",
            registerNumber = "23CSE002",
            name = "Diya Patel",
            phoneNumber = "9876545678",
            department = "Computer Science & Engineering",
            email = "23cse002@campus.edu",
            accountStatus = "ACTIVE",
            isFirstLogin = false,
            isSuspended = false,
            isAdmin = false
        ),
        UserEntity(
            id = "user_admin",
            registerNumber = "ADMIN01",
            name = "Campus Administrator",
            phoneNumber = "9876549999",
            department = "Administration",
            email = "admin01@campus.edu",
            accountStatus = "ACTIVE",
            isFirstLogin = false,
            isSuspended = false,
            isAdmin = true
        ),
        UserEntity(
            id = "user_23cse042",
            registerNumber = "23CSE042",
            name = "Rohan Verma",
            phoneNumber = "9876544242",
            department = "Information Technology",
            email = "23cse042@campus.edu",
            accountStatus = "ACTIVE",
            isFirstLogin = false,
            isSuspended = false,
            isAdmin = false
        ),
        UserEntity(
            id = "user_23cse018",
            registerNumber = "23CSE018",
            name = "Priya Sundaram",
            phoneNumber = "9876541818",
            department = "Computer Science & Engineering",
            email = "23cse018@campus.edu",
            accountStatus = "ACTIVE",
            isFirstLogin = false,
            isSuspended = false,
            isAdmin = false
        ),
        UserEntity(
            id = "user_23cse031",
            registerNumber = "23CSE031",
            name = "Karthik Raj",
            phoneNumber = "9876543131",
            department = "Artificial Intelligence & Data Science",
            email = "23cse031@campus.edu",
            accountStatus = "ACTIVE",
            isFirstLogin = false,
            isSuspended = false,
            isAdmin = false
        ),
        UserEntity(
            id = "user_23cse077",
            registerNumber = "23CSE077",
            name = "Ananya Iyer",
            phoneNumber = "9876547777",
            department = "Computer Science & Engineering",
            email = "23cse077@campus.edu",
            accountStatus = "ACTIVE",
            isFirstLogin = false,
            isSuspended = false,
            isAdmin = false
        ),
        UserEntity(
            id = "user_23cse015",
            registerNumber = "23CSE015",
            name = "Siddharth Nair",
            phoneNumber = "9876541515",
            department = "Electrical & Electronics Engineering",
            email = "23cse015@campus.edu",
            accountStatus = "ACTIVE",
            isFirstLogin = false,
            isSuspended = false,
            isAdmin = false
        ),
        UserEntity(
            id = "user_new_student",
            registerNumber = "23CSE099",
            name = "Tanvi Reddy",
            phoneNumber = "9876549911",
            department = "Computer Science & Engineering",
            email = "23cse099@campus.edu",
            accountStatus = "PENDING", // Pending student awaiting admin approval
            isFirstLogin = true,
            isSuspended = false,
            isAdmin = false
        ),
        UserEntity(
            id = "user_suspended_student",
            registerNumber = "23CSE088",
            name = "Vikram Joshi",
            phoneNumber = "9876548822",
            department = "Electronics & Communication",
            email = "23cse088@campus.edu",
            accountStatus = "SUSPENDED", // Suspended student for testing
            isFirstLogin = false,
            isSuspended = true,
            isAdmin = false
        )
    )

    // Baseline nodes matching the LIFE spec example
    val nodeProblem1Id = "node_prob_1"
    val nodeImp1Id = "node_imp_1"
    val nodeRef1Id = "node_ref_1"
    val nodeRef2Id = "node_ref_2"
    val nodeRef3Id = "node_ref_3"
    val nodeRefDirectId = "node_ref_direct"

    val nodeProblem2Id = "node_prob_2"
    val nodeImp2Id = "node_imp_2"
    val nodeRef4Id = "node_ref_4"

    val nodeStandaloneImpId = "node_imp_standalone"

    val initialNodes = listOf(
        // Problem 1: Slow Wi-Fi in Block A
        IdeaNodeEntity(
            id = nodeProblem1Id,
            type = NodeType.PROBLEM.name,
            authorId = "user_23cse042",
            authorRegisterNumber = "23CSE042",
            parentId = null,
            title = "Slow Wi-Fi in Block A classrooms",
            description = "Connection drops continuously near Room 301 and latency spikes significantly during afternoon lecture sessions.",
            createdAt = System.currentTimeMillis() - (3 * 3600 * 1000L),
            updatedAt = System.currentTimeMillis() - (3 * 3600 * 1000L),
            votesCount = 14,
            directScore = 5.0 + 14.0,
            totalScore = 19.0
        ),
        // Improvement 1 (child of Problem 1)
        IdeaNodeEntity(
            id = nodeImp1Id,
            type = NodeType.IMPROVEMENT.name,
            authorId = "user_23cse018",
            authorRegisterNumber = "23CSE018",
            parentId = nodeProblem1Id,
            title = "Install dedicated dual-band access point in central corridor",
            description = "Placing a ceiling-mounted PoE access point at the mid-junction will evenly distribute signal across Rooms 301-306 without structural dead zones.",
            createdAt = System.currentTimeMillis() - (2 * 3600 * 1000L),
            updatedAt = System.currentTimeMillis() - (2 * 3600 * 1000L),
            votesCount = 12,
            directScore = 5.0 + 12.0,
            totalScore = 17.0
        ),
        // Refinement 1 (child of Improvement 1)
        IdeaNodeEntity(
            id = nodeRef1Id,
            type = NodeType.REFINEMENT.name,
            authorId = "user_23cse031",
            authorRegisterNumber = "23CSE031",
            parentId = nodeImp1Id,
            title = "Mount device directly outside Room 301 line-of-sight",
            description = "Mounting at height 2.8m facing the concrete pillars reduces reflection and avoids student pedestrian interference.",
            createdAt = System.currentTimeMillis() - (90 * 60 * 1000L),
            updatedAt = System.currentTimeMillis() - (90 * 60 * 1000L),
            votesCount = 8,
            directScore = 10.0 + 8.0,
            totalScore = 18.0
        ),
        // Refinement 2 (child of Refinement 1)
        IdeaNodeEntity(
            id = nodeRef2Id,
            type = NodeType.REFINEMENT.name,
            authorId = "user_23cse077",
            authorRegisterNumber = "23CSE077",
            parentId = nodeRef1Id,
            title = "Pilot installation on 3rd floor for one academic week first",
            description = "Run pilot during mid-semester examinations week to benchmark bandwidth under peak student load before campus-wide rollout.",
            createdAt = System.currentTimeMillis() - (45 * 60 * 1000L),
            updatedAt = System.currentTimeMillis() - (45 * 60 * 1000L),
            votesCount = 6,
            directScore = 10.0 + 6.0,
            totalScore = 16.0
        ),
        // Refinement 3 (child of Improvement 1)
        IdeaNodeEntity(
            id = nodeRef3Id,
            type = NodeType.REFINEMENT.name,
            authorId = "user_23cse015",
            authorRegisterNumber = "23CSE015",
            parentId = nodeImp1Id,
            title = "Implement 802.11k/v fast roaming mesh protocol",
            description = "Enable seamless handoff so students moving between adjacent lecture halls do not experience dropped authentication sessions.",
            createdAt = System.currentTimeMillis() - (30 * 60 * 1000L),
            updatedAt = System.currentTimeMillis() - (30 * 60 * 1000L),
            votesCount = 5,
            directScore = 10.0 + 5.0,
            totalScore = 15.0
        ),
        // Direct Refinement on Problem 1
        IdeaNodeEntity(
            id = nodeRefDirectId,
            type = NodeType.REFINEMENT.name,
            authorId = "user_23cse042",
            authorRegisterNumber = "23CSE042",
            parentId = nodeProblem1Id,
            title = "Perform automated packet loss audit before purchasing hardware",
            description = "Have student network lab team run 48-hour iperf telemetry to verify whether bottleneck is RF attenuation or uplink switch throttling.",
            createdAt = System.currentTimeMillis() - (20 * 60 * 1000L),
            updatedAt = System.currentTimeMillis() - (20 * 60 * 1000L),
            votesCount = 7,
            directScore = 10.0 + 7.0,
            totalScore = 17.0
        ),

        // Problem 2: Lab equipment queue
        IdeaNodeEntity(
            id = nodeProblem2Id,
            type = NodeType.PROBLEM.name,
            authorId = "user_23cse031",
            authorRegisterNumber = "23CSE031",
            parentId = null,
            title = "Hardware bench shortages during capstone project deadlines",
            description = "Embedded systems laboratory runs out of oscilloscope benches because teams hold stations all day without active testing.",
            createdAt = System.currentTimeMillis() - (5 * 3600 * 1000L),
            updatedAt = System.currentTimeMillis() - (5 * 3600 * 1000L),
            votesCount = 11,
            directScore = 5.0 + 11.0,
            totalScore = 16.0
        ),
        // Improvement 2 (child of Problem 2)
        IdeaNodeEntity(
            id = nodeImp2Id,
            type = NodeType.IMPROVEMENT.name,
            authorId = "user_23cse042",
            authorRegisterNumber = "23CSE042",
            parentId = nodeProblem2Id,
            title = "Department reservation calendar with QR scan check-in",
            description = "A 90-minute digital booking slot system where students confirm physical arrival via QR badge on bench.",
            createdAt = System.currentTimeMillis() - (4 * 3600 * 1000L),
            updatedAt = System.currentTimeMillis() - (4 * 3600 * 1000L),
            votesCount = 9,
            directScore = 5.0 + 9.0,
            totalScore = 14.0
        ),
        // Refinement 4 (child of Improvement 2)
        IdeaNodeEntity(
            id = nodeRef4Id,
            type = NodeType.REFINEMENT.name,
            authorId = "user_23cse018",
            authorRegisterNumber = "23CSE018",
            parentId = nodeImp2Id,
            title = "Automatic 15-minute grace period release for no-shows",
            description = "If reserved station is not checked in within 15 minutes, system automatically releases the bench to next waitlisted student team.",
            createdAt = System.currentTimeMillis() - (10 * 60 * 1000L),
            updatedAt = System.currentTimeMillis() - (10 * 60 * 1000L),
            votesCount = 4,
            directScore = 10.0 + 4.0,
            totalScore = 14.0
        ),

        // Standalone Improvement
        IdeaNodeEntity(
            id = nodeStandaloneImpId,
            type = NodeType.IMPROVEMENT.name,
            authorId = "user_23cse015",
            authorRegisterNumber = "23CSE015",
            parentId = null,
            title = "Campus Peer Tutoring and Study Group Matcher",
            description = "Organize structured study circles matching senior students with junior batches for difficult core curriculum subjects.",
            createdAt = System.currentTimeMillis() - (6 * 3600 * 1000L),
            updatedAt = System.currentTimeMillis() - (6 * 3600 * 1000L),
            votesCount = 10,
            directScore = 5.0 + 10.0,
            totalScore = 15.0
        )
    )

    val initialVotes = listOf(
        VoteEntity("v1", "user_23cse042", nodeImp1Id),
        VoteEntity("v2", "user_23cse031", nodeImp1Id),
        VoteEntity("v3", "user_23cse077", nodeProblem1Id),
        VoteEntity("v4", "user_23cse018", nodeProblem1Id),
        VoteEntity("v5", "user_23cse042", nodeRef1Id),
        VoteEntity("v6", "user_23cse015", nodeRef1Id),
        VoteEntity("v7", "user_admin", nodeProblem1Id),
        VoteEntity("v8", "user_admin", nodeImp1Id),
        VoteEntity("v9", "user_23cse018", nodeProblem2Id),
        VoteEntity("v10", "user_23cse077", nodeImp2Id)
    )

    val initialScores = listOf(
        UserScoreEntity(
            userId = "user_23cse042",
            registerNumber = "23CSE042",
            problemScore = 19.0,
            improvementScore = 14.0,
            refinementScore = 17.0 + 2.0, // direct + parent credit
            totalScore = 52.0,
            problemsCount = 1,
            improvementsCount = 1,
            refinementsCount = 1
        ),
        UserScoreEntity(
            userId = "user_23cse018",
            registerNumber = "23CSE018",
            problemScore = 0.0,
            improvementScore = 17.0 + 2.0, // includes parent credit from ref1
            refinementScore = 14.0,
            totalScore = 33.0,
            problemsCount = 0,
            improvementsCount = 1,
            refinementsCount = 1
        ),
        UserScoreEntity(
            userId = "user_23cse031",
            registerNumber = "23CSE031",
            problemScore = 16.0,
            improvementScore = 0.0,
            refinementScore = 18.0 + 1.5, // includes parent credit from ref2
            totalScore = 35.5,
            problemsCount = 1,
            improvementsCount = 0,
            refinementsCount = 1
        ),
        UserScoreEntity(
            userId = "user_23cse077",
            registerNumber = "23CSE077",
            problemScore = 0.0,
            improvementScore = 0.0,
            refinementScore = 16.0,
            totalScore = 16.0,
            problemsCount = 0,
            improvementsCount = 0,
            refinementsCount = 1
        ),
        UserScoreEntity(
            userId = "user_23cse015",
            registerNumber = "23CSE015",
            problemScore = 0.0,
            improvementScore = 15.0,
            refinementScore = 15.0,
            totalScore = 30.0,
            problemsCount = 0,
            improvementsCount = 1,
            refinementsCount = 1
        ),
        UserScoreEntity(
            userId = "user_admin",
            registerNumber = "23CSE001",
            problemScore = 0.0,
            improvementScore = 0.0,
            refinementScore = 0.0,
            totalScore = 0.0,
            problemsCount = 0,
            improvementsCount = 0,
            refinementsCount = 0
        ),
        UserScoreEntity(
            userId = "user_new_student",
            registerNumber = "23CSE099",
            problemScore = 0.0,
            improvementScore = 0.0,
            refinementScore = 0.0,
            totalScore = 0.0,
            problemsCount = 0,
            improvementsCount = 0,
            refinementsCount = 0
        )
    )
}

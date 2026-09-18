package com.example.ui.screens.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.domain.model.AccountStatus
import com.example.domain.model.LoginEvent
import com.example.domain.model.User
import com.example.ui.theme.GbPrimary
import com.example.ui.theme.LifeProblem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AdminManagementDialog(
    users: List<User>,
    loginEvents: List<LoginEvent>,
    onCreateStudent: (name: String, reg: String, phone: String, dept: String, email: String, password: String) -> Unit,
    onApproveStudent: (userId: String) -> Unit,
    onRejectStudent: (userId: String) -> Unit,
    onSuspendStudent: (userId: String) -> Unit,
    onRestoreStudent: (userId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMainTab by remember { mutableIntStateOf(0) } // 0: Student Management, 1: Login Activity
    var selectedStatusFilter by remember { mutableStateOf<AccountStatus?>(null) } // null for All or specific status

    // Add Student Form State
    var showAddForm by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newRegNum by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var newEmail by remember { mutableStateOf("") }
    var newDept by remember { mutableStateOf("Computer Science & Engineering") }
    var formError by remember { mutableStateOf<String?>(null) }

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.testTag("admin_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Default.AdminPanelSettings,
                contentDescription = null,
                tint = GbPrimary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Administrator Control Center",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
            ) {
                // Navigation Tabs
                TabRow(
                    selectedTabIndex = selectedMainTab,
                    modifier = Modifier.fillMaxWidth().testTag("admin_tabs")
                ) {
                    Tab(
                        selected = selectedMainTab == 0,
                        onClick = { selectedMainTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Students", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                    Tab(
                        selected = selectedMainTab == 1,
                        onClick = { selectedMainTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Login Activity", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedMainTab == 0) {
                    // --- TAB 1: STUDENT MANAGEMENT ---

                    // Status Filter Chips: All, Pending, Active, Suspended, Rejected
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StatusFilterChip(
                            label = "All",
                            count = users.size,
                            isSelected = selectedStatusFilter == null,
                            onClick = { selectedStatusFilter = null }
                        )
                        StatusFilterChip(
                            label = "Pending",
                            count = users.count { it.accountStatus == AccountStatus.PENDING },
                            isSelected = selectedStatusFilter == AccountStatus.PENDING,
                            color = Color(0xFFD97706),
                            onClick = { selectedStatusFilter = AccountStatus.PENDING }
                        )
                        StatusFilterChip(
                            label = "Active",
                            count = users.count { it.accountStatus == AccountStatus.ACTIVE },
                            isSelected = selectedStatusFilter == AccountStatus.ACTIVE,
                            color = Color(0xFF16A34A),
                            onClick = { selectedStatusFilter = AccountStatus.ACTIVE }
                        )
                        StatusFilterChip(
                            label = "Suspended",
                            count = users.count { it.accountStatus == AccountStatus.SUSPENDED },
                            isSelected = selectedStatusFilter == AccountStatus.SUSPENDED,
                            color = LifeProblem,
                            onClick = { selectedStatusFilter = AccountStatus.SUSPENDED }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Toggle Add Student Form Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedStatusFilter != null) "${selectedStatusFilter?.name} Students" else "All Students (${users.size})",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )

                        TextButton(
                            onClick = { showAddForm = !showAddForm },
                            modifier = Modifier.testTag("admin_toggle_add_form")
                        ) {
                            Icon(
                                imageVector = if (showAddForm) Icons.Default.Close else Icons.Default.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (showAddForm) "Cancel" else "Add New")
                        }
                    }

                    if (showAddForm) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .testTag("admin_add_student_form"),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Text("New Student Details (Starts PENDING)", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                Spacer(modifier = Modifier.height(6.dp))

                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it; formError = null },
                                    label = { Text("Full Name") },
                                    modifier = Modifier.fillMaxWidth().testTag("admin_new_name_input"),
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = newRegNum,
                                        onValueChange = { newRegNum = it.uppercase(); formError = null },
                                        label = { Text("Reg No (e.g. 23CSE011)") },
                                        modifier = Modifier.weight(1f).testTag("admin_new_reg_input"),
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    OutlinedTextField(
                                        value = newPhone,
                                        onValueChange = { newPhone = it; formError = null },
                                        label = { Text("Phone") },
                                        modifier = Modifier.weight(1f).testTag("admin_new_phone_input"),
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))

                                OutlinedTextField(
                                    value = newPassword,
                                    onValueChange = { newPassword = it; formError = null },
                                    label = { Text("Password") },
                                    modifier = Modifier.fillMaxWidth().testTag("admin_new_password_input"),
                                    singleLine = true,
                                    trailingIcon = {
                                        androidx.compose.material3.IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                            androidx.compose.material3.Icon(
                                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = null
                                            )
                                        }
                                    },
                                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                OutlinedTextField(
                                    value = newEmail,
                                    onValueChange = { newEmail = it; formError = null },
                                    label = { Text("Email") },
                                    modifier = Modifier.fillMaxWidth().testTag("admin_new_email_input"),
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                OutlinedTextField(
                                    value = newDept,
                                    onValueChange = { newDept = it; formError = null },
                                    label = { Text("Department") },
                                    modifier = Modifier.fillMaxWidth().testTag("admin_new_dept_input"),
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )

                                if (formError != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(formError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Button(
                                    onClick = {
                                        val reg = newRegNum.trim().uppercase()
                                        val name = newName.trim()
                                        val phone = newPhone.trim()
                                        val email = newEmail.trim()
                                        if (reg.isEmpty()) {
                                            formError = "Enter register number"
                                            return@Button
                                        }
                                        if (email.isBlank()) {
                                            formError = "Email is required"
                                            return@Button
                                        }
                                        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                                            formError = "Enter a valid email address"
                                            return@Button
                                        }
                                        if (email.lowercase().endsWith("@life.edu") || email.lowercase().endsWith("@campus.edu")) {
                                            formError = "Use the student's real email, not a synthetic campus address"
                                            return@Button
                                        }
                                        onCreateStudent(name, reg, phone, newDept.trim(), email, newPassword)
                                        newName = ""
                                        newRegNum = ""
                                        newPhone = ""
                                        newEmail = ""
                                        showAddForm = false
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("admin_submit_student_button"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = GbPrimary)
                                ) {
                                    Text("Create Pending Account")
                                }
                            }
                        }
                    }

                    val filteredUsers = users.filter { u ->
                        selectedStatusFilter == null || u.accountStatus == selectedStatusFilter
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f).testTag("admin_student_list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = filteredUsers, key = { it.id }) { u ->
                            StudentManagementCard(
                                user = u,
                                onApprove = { onApproveStudent(u.id) },
                                onReject = { onRejectStudent(u.id) },
                                onSuspend = { onSuspendStudent(u.id) },
                                onRestore = { onRestoreStudent(u.id) }
                            )
                        }
                    }

                } else {
                    // --- TAB 2: LOGIN ACTIVITY ---
                    Text(
                        text = "Recent Successful Logins (${loginEvents.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (loginEvents.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No login activity recorded yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).testTag("admin_login_activity_list"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(items = loginEvents, key = { it.id }) { event ->
                                LoginActivityCard(event = event, dateFormat = dateFormat)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("admin_close_button")
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
fun StudentManagementCard(
    user: User,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onSuspend: () -> Unit,
    onRestore: () -> Unit
) {
    val cleanPhone = user.phoneNumber.filter { it.isDigit() }
    val maskedPhone = if (cleanPhone.length >= 4) "******${cleanPhone.takeLast(4)}" else "******1234"

    Card(
        modifier = Modifier.fillMaxWidth().testTag("student_card_${user.registerNumber}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = user.name.ifEmpty { "Student ${user.registerNumber}" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        if (user.isAdmin) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "(Admin)",
                                style = MaterialTheme.typography.labelSmall.copy(color = GbPrimary)
                            )
                        }
                    }
                    Text(
                        text = "${user.registerNumber} • ${user.department}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Text(
                        text = "Phone: $maskedPhone",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                // Account Status Tag
                val statusColor = when (user.accountStatus) {
                    AccountStatus.ACTIVE -> Color(0xFF16A34A)
                    AccountStatus.PENDING -> Color(0xFFD97706)
                    AccountStatus.SUSPENDED -> LifeProblem
                    AccountStatus.REJECTED -> Color(0xFF6B7280)
                }
                Box(
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = user.accountStatus.name,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            fontSize = 11.sp
                        )
                    )
                }
            }

            // Action Buttons
            if (!user.isAdmin) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (user.accountStatus) {
                        AccountStatus.PENDING -> {
                            OutlinedButton(
                                onClick = onReject,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(34.dp).testTag("reject_student_${user.registerNumber}")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reject", fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = onApprove,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                modifier = Modifier.height(34.dp).testTag("approve_student_${user.registerNumber}")
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Approve", fontSize = 12.sp)
                            }
                        }
                        AccountStatus.ACTIVE -> {
                            OutlinedButton(
                                onClick = onSuspend,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(34.dp).testTag("suspend_student_${user.registerNumber}")
                            ) {
                                Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(14.dp), tint = LifeProblem)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Suspend", fontSize = 12.sp, color = LifeProblem)
                            }
                        }
                        AccountStatus.SUSPENDED -> {
                            Button(
                                onClick = onRestore,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GbPrimary),
                                modifier = Modifier.height(34.dp).testTag("restore_student_${user.registerNumber}")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Restore", fontSize = 12.sp)
                            }
                        }
                        AccountStatus.REJECTED -> {
                            Button(
                                onClick = onApprove,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GbPrimary),
                                modifier = Modifier.height(34.dp).testTag("approve_student_${user.registerNumber}")
                            ) {
                                Text("Authorize", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LoginActivityCard(event: LoginEvent, dateFormat: SimpleDateFormat) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("login_event_${event.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFF16A34A).copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF16A34A),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "${event.registerNumber} • ${event.userName}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = dateFormat.format(Date(event.loginAt)),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = event.deviceType,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
fun StatusFilterChip(
    label: String,
    count: Int,
    isSelected: Boolean,
    color: Color = GbPrimary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                color = if (isSelected) color.copy(alpha = 0.15f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$label ($count)",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        )
    }
}

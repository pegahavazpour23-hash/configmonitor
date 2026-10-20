package app.configmonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Clickable area with a spoken description (for icon-only buttons). */
fun Modifier.clickableBox(description: String, onClick: () -> Unit): Modifier =
    this.semantics { contentDescription = description }.clickable(role = Role.Button, onClick = onClick)

package app.atlas.ui.go

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** A clickable that is a button named [description] for TalkBack (its only words when it shows a symbol alone). */
internal fun Modifier.button(description: String?, onClick: () -> Unit): Modifier =
    clickable(role = Role.Button, onClick = onClick).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)

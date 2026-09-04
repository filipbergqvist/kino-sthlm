package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.CinemaDarkBorder
import com.example.ui.theme.CinemaDarkSurface
import com.example.ui.theme.CinemaGold
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ImdbImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var imdbInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CinemaDarkSurface,
            border = BorderStroke(1.dp, CinemaDarkBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("imdb_import_dialog")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = CinemaGold)
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text(
                            text = "Connect IMDb Watchlist",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "💡 How to sync your IMDb watchlist:",
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaGold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1. Open IMDb and ensure your Watchlist is set to 'Public' (in Edit list > Settings).\n" +
                                    "2. Paste your User ID (e.g. ur12345678) or the full Watchlist URL below.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = imdbInput,
                    onValueChange = { imdbInput = it },
                    label = { Text("IMDb Watchlist URL or User ID") },
                    placeholder = { Text("e.g. https://www.imdb.com/user/ur12345678/watchlist") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("imdb_input_field"),
                    singleLine = false,
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (imdbInput.isNotBlank()) {
                            onImport(imdbInput)
                            onDismiss()
                        }
                    },
                    enabled = imdbInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_imdb_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = CinemaGold)
                ) {
                    Text("Sync Watchlist Now", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun GoogleTvImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var textInput by remember {
        mutableStateOf(
            "The Substance\nDune: Part Two\nAnora\nPast Lives\nNosferatu\nPoor Things\nBlade Runner"
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CinemaDarkSurface,
            border = BorderStroke(1.dp, CinemaDarkBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("google_tv_import_dialog")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tv, contentDescription = null, tint = CinemaGold)
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text(
                            text = "Import Google TV Watchlist",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "📺 Google TV & Google Watchlist Import:",
                            fontWeight = FontWeight.SemiBold,
                            color = CinemaGold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Paste movies from your Google TV watchlist (one per line). The app will automatically track them across all Stockholm cinema schedules!",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    label = { Text("Movie Titles (one per line)") },
                    placeholder = { Text("e.g.\nThe Substance\nDune: Part Two\nPast Lives") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .testTag("google_tv_input_field"),
                    maxLines = 8
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            onImport(textInput)
                            onDismiss()
                        }
                    },
                    enabled = textInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_google_tv_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = CinemaGold)
                ) {
                    Text("Import to Watchlist", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

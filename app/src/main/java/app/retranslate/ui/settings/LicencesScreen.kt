package app.retranslate.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One open-source component credited on the Licences screen. */
data class LibraryCredit(
    val name: String,
    val licence: String,
    val usedFor: String
)

/**
 * The direct open-source components shipped inside the APK (PLAN.md
 * §2.6/§11 P8). This is deliberately a small, auditable list rather
 * than a generated wall of transitive package names.
 */
object LicencesCatalog {
    val libraries: List<LibraryCredit> = listOf(
        LibraryCredit(
            "AndroidX Activity, Lifecycle & Navigation",
            "Apache License 2.0",
            "App screens, ViewModels and navigation."
        ),
        LibraryCredit(
            "Jetpack Compose & Material 3",
            "Apache License 2.0",
            "The user interface and Material You theming."
        ),
        LibraryCredit(
            "AndroidX Room",
            "Apache License 2.0",
            "The on-device book, chapter and Knowledge Base database."
        ),
        LibraryCredit(
            "AndroidX DataStore",
            "Apache License 2.0",
            "Settings and encrypted-key storage support."
        ),
        LibraryCredit(
            "AndroidX WorkManager",
            "Apache License 2.0",
            "Background book-translation and study jobs."
        ),
        LibraryCredit(
            "Kotlin, kotlinx.coroutines & kotlinx.serialization",
            "Apache License 2.0",
            "The app language, background work and JSON handling."
        ),
        LibraryCredit(
            "OkHttp",
            "Apache License 2.0",
            "Network calls to translation providers."
        ),
        LibraryCredit(
            "PDFBox Android (Apache PDFBox)",
            "Apache License 2.0",
            "Reading text PDFs."
        ),
        LibraryCredit(
            "KXml2",
            "BSD-style licence / public-domain components",
            "Android's built-in XML pull parser (the KXml2 implementation) " +
                "reads EPUB files."
        )
    )
}

/** Licences (PLAN.md §9.2): plain in-app credits, reachable from More. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Licences") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "ReTranslate's own code was written fresh for this app.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "These open-source libraries are included in the app. " +
                            "Their licences require credit, and their authors " +
                            "deserve it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(LicencesCatalog.libraries, key = { it.name }) { library ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            library.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            library.licence,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            library.usedFor,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

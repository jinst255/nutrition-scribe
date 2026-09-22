package com.nutritionscribe.wear.ui

import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.ui.text.style.TextAlign
import com.nutritionscribe.wear.data.WearRepository
import kotlinx.coroutines.launch

private object WearRoutes {
    const val HOME = "home"
    const val LISTENING = "listening"
    const val CONFIRM = "confirm/{reached}"
    fun confirm(reachedPhone: Boolean) = "confirm/$reachedPhone"
}

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = WearRepository(applicationContext)
        setContent {
            MaterialTheme {
                WearNavHost(repository)
            }
        }
    }
}

@Composable
private fun WearNavHost(repository: WearRepository) {
    val navController = rememberNavController()

    // Opening the app is the cheap moment to notice the phone is back in range.
    LaunchedEffect(Unit) { repository.flushPending() }

    NavHost(navController, startDestination = WearRoutes.HOME) {
        composable(WearRoutes.HOME) {
            WearHomeScreen(
                repository = repository,
                onStartListening = { navController.navigate(WearRoutes.LISTENING) },
            )
        }
        composable(WearRoutes.LISTENING) {
            WearListeningScreen(
                repository = repository,
                onDone = { reached ->
                    navController.navigate(WearRoutes.confirm(reached)) { popUpTo(WearRoutes.HOME) }
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(WearRoutes.CONFIRM) { backStackEntry ->
            WearConfirmScreen(
                repository = repository,
                reachedPhone = backStackEntry.arguments?.getString("reached")?.toBoolean() ?: true,
                onDone = { navController.popBackStack(WearRoutes.HOME, inclusive = false) },
                onRedo = { navController.navigate(WearRoutes.LISTENING) { popUpTo(WearRoutes.HOME) } },
            )
        }
    }
}

@Composable
private fun WearHomeScreen(repository: WearRepository, onStartListening: () -> Unit) {
    val summary by repository.todaySummary.collectAsState(initial = null)

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val progress = summary?.let { (it.proteinGrams / it.goalGrams).toFloat().coerceIn(0f, 1f) } ?: 0f
        CircularProgressIndicator(progress = progress, modifier = Modifier.size(64.dp))
        Text(
            summary?.let { "${it.proteinGrams.toInt()}/${it.goalGrams.toInt()}g" } ?: "--/--g",
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onStartListening, modifier = Modifier.padding(top = 12.dp)) {
            Icon(Icons.Filled.Mic, contentDescription = "Log entry")
        }
    }
}

@Composable
private fun WearListeningScreen(
    repository: WearRepository,
    onDone: (reachedPhone: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(true) }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        isListening = false
        if (spoken.isNullOrBlank()) {
            onCancel()
        } else {
            (context as ComponentActivity).lifecycleScope.launch {
                // The result used to be thrown away, so a failed relay looked
                // exactly like a successful one and the next screen waited for a
                // reply that was never coming.
                val reached = repository.relayToPhone(spoken)
                onDone(reached)
            }
        }
    }

    LaunchedEffect(Unit) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        speechLauncher.launch(intent)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Text("Listening...", modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onCancel, modifier = Modifier.padding(top = 12.dp)) { Text("Cancel") }
    }
}

@Composable
private fun WearConfirmScreen(
    repository: WearRepository,
    reachedPhone: Boolean,
    onDone: () -> Unit,
    onRedo: () -> Unit,
) {
    val context = LocalContext.current
    val confirmed by repository.lastConfirmed.collectAsState()
    var timedOut by remember { mutableStateOf(false) }

    LaunchedEffect(confirmed) {
        if (confirmed != null) {
            val vibrator = context.getSystemService(Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // The phone can accept the message and still never answer — it may be asleep,
    // or the app may not be running. Waiting forever is not an option on a wrist.
    LaunchedEffect(reachedPhone) {
        if (reachedPhone) {
            kotlinx.coroutines.delay(10_000)
            timedOut = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (confirmed == null && !reachedPhone) {
            // Saved on the watch, not lost. It goes up next time the app is
            // opened near the phone.
            Icon(Icons.Filled.Check, contentDescription = null)
            Text("Saved on your watch", modifier = Modifier.padding(top = 4.dp))
            Text(
                "Your phone wasn't in range. It'll sync next time you open this.",
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Button(onClick = onDone, modifier = Modifier.padding(top = 12.dp)) { Text("Done") }
        } else if (confirmed == null && timedOut) {
            Icon(Icons.Filled.Check, contentDescription = null)
            Text("Sent to your phone", modifier = Modifier.padding(top = 4.dp))
            Text(
                "It'll appear once your phone processes it.",
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Button(onClick = onDone, modifier = Modifier.padding(top = 12.dp)) { Text("Done") }
        } else if (confirmed == null) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Text("Sending to phone...", modifier = Modifier.padding(top = 8.dp))
        } else {
            Icon(Icons.Filled.Check, contentDescription = null)
            Text(confirmed!!.name, modifier = Modifier.padding(top = 4.dp))
            Text("${confirmed!!.proteinGrams.toInt()}g protein")
            Row {
                Button(onClick = onRedo, modifier = Modifier.padding(top = 12.dp, end = 8.dp)) { Text("Redo") }
                Button(onClick = onDone, modifier = Modifier.padding(top = 12.dp)) { Text("Done") }
            }
        }
    }
}

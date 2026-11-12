// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.plugins.sync.carspeech

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import kotlinx.coroutines.flow.StateFlow

/**
 * Choose the car and test the voice.
 *
 * Like xDrip+ `SelectAudioDevice`: connect the phone to the car, and the device that connected
 * last can be made the car. The screen follows the connections live.
 */
@Composable
fun CarSpeechSetupScreen(
    lastConnected: StateFlow<VehicleDetector.AudioDevice?>,
    inVehicle: StateFlow<Boolean>,
    carName: () -> String,
    onChooseCar: (VehicleDetector.AudioDevice) -> Unit,
    onForgetCar: () -> Unit,
    onTest: () -> Unit,
    onBack: () -> Unit,
) {
    val last by lastConnected.collectAsState()
    val inCar by inVehicle.collectAsState()
    // carName() reads the preferences: read again after a change made on this screen
    var car by remember { mutableStateOf(carName()) }
    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(SyncStrings.car_speech_setup)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiStrings.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)
        ) {
            Text(stringResource(SyncStrings.car_speech_car_section), style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (car.isEmpty()) stringResource(SyncStrings.car_speech_no_car) else stringResource(SyncStrings.car_speech_current_car, car),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(stringResource(SyncStrings.car_speech_choose_explanation), style = MaterialTheme.typography.bodyMedium)
            val device = last
            if (device == null) {
                Text(stringResource(SyncStrings.car_speech_no_device_seen), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedButton(onClick = {
                    onChooseCar(device)
                    car = carName()
                }) { Text(stringResource(SyncStrings.car_speech_use_as_car, device.name)) }
            }
            if (car.isNotEmpty()) {
                TextButton(onClick = {
                    onForgetCar()
                    car = carName()
                }) { Text(stringResource(SyncStrings.car_speech_forget_car)) }
            }

            Text(stringResource(SyncStrings.car_speech_state_section), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(if (inCar) SyncStrings.car_speech_in_car else SyncStrings.car_speech_not_in_car),
                style = MaterialTheme.typography.bodyLarge
            )
            OutlinedButton(onClick = onTest) { Text(stringResource(SyncStrings.car_speech_test)) }
        }
    }
}

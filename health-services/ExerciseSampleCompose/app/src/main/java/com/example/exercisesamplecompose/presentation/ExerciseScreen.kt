/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.exercisesamplecompose.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.health.services.client.data.DataPoint
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.ExperimentalLifecycleComposeApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.example.exercisesamplecompose.R
import com.example.exercisesamplecompose.Screens
import com.example.exercisesamplecompose.presentation.component.CaloriesText
import com.example.exercisesamplecompose.presentation.component.DistanceText
import com.example.exercisesamplecompose.presentation.component.HRText
import com.example.exercisesamplecompose.presentation.component.TopOfScreenTime
import com.example.exercisesamplecompose.presentation.component.formatCalories
import com.example.exercisesamplecompose.presentation.component.formatDistanceKm
import com.example.exercisesamplecompose.presentation.component.formatElapsedTime
import com.example.exercisesamplecompose.service.ExerciseStateChange
import com.example.exercisesamplecompose.theme.ExerciseSampleTheme
import java.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


/**
 * Shows while an exercise is in progress
 */
@OptIn(ExperimentalLifecycleComposeApi::class)
@Composable
fun ExerciseScreen(
    onPauseClick: () -> Unit = {},
    onEndClick: () -> Unit = {},
    onResumeClick: () -> Unit = {},
    onStartClick: () -> Unit = {},
    serviceState: ServiceState,
    navController: NavHostController,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val chronoTickJob = remember { mutableStateOf<Job?>(null) }
    /** Only collect metrics while we are connected to the Foreground Service. **/
    when (serviceState) {
        is ServiceState.Connected -> {
            val getExerciseMetrics by remember {
                mutableStateOf(serviceState.exerciseMetrics)
            }
            val baseActiveDuration = remember { mutableStateOf(Duration.ZERO) }
            var activeDuration by remember { mutableStateOf(Duration.ZERO) }
            val exerciseStateChange by serviceState.exerciseStateChange.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()

            /** Collect [DataPoint]s from the aggregate and exercise metric flows. Because
             * collectAsStateWithLifecycle() is asynchronous, store the last known value from each flow,
             * and update the value on screen only when the flow re-connects. **/
            val tempHeartRate = remember { mutableStateOf(0.0) }
            if (getExerciseMetrics.collectAsStateWithLifecycle().value?.getData(DataType.HEART_RATE_BPM)
                    ?.isNotEmpty() == true
            ) tempHeartRate.value =
                getExerciseMetrics.collectAsStateWithLifecycle().value?.getData(DataType.HEART_RATE_BPM)
                    ?.last()?.value!!
            else tempHeartRate.value = tempHeartRate.value

            val distance =
                getExerciseMetrics.collectAsStateWithLifecycle().value?.getData(DataType.DISTANCE_TOTAL)?.total
            val tempDistance = remember { mutableStateOf(0.0) }

            val calories =
                getExerciseMetrics.collectAsStateWithLifecycle().value?.getData(DataType.CALORIES_TOTAL)?.total
            val tempCalories = remember { mutableStateOf(0.0) }

            val averageHeartRate =
                getExerciseMetrics.collectAsStateWithLifecycle().value?.getData(DataType.HEART_RATE_BPM_STATS)?.average
            val tempAverageHeartRate = remember { mutableStateOf(0.0) }

            val laps by serviceState.exerciseLaps.collectAsStateWithLifecycle()


            /** Update the Pause and End buttons according to [ExerciseState].**/
            val pauseOrResume = when (exerciseStateChange.exerciseState.isPaused) {
                true -> painterResource(id = R.drawable.ic_baseline_play_arrow_24)
                false -> painterResource(id = R.drawable.ic_baseline_pause_24)
            }
            val startOrEnd =
                when (exerciseStateChange.exerciseState.isEnded || exerciseStateChange.exerciseState.isEnding) {
                    true -> painterResource(id = R.drawable.ic_baseline_play_arrow_24)
                    false -> painterResource(id = R.drawable.ic_baseline_stop_24)

                }

            // The ticker coroutine updates activeDuration, but the ticker fires more often than
            // once a second, so we use derivedStateOf to update the elapsedTime state only when
            // the string representing the time on the screen changes. Recomposition then only
            // happens when elapsedTime changes, so once a second.
            val elapsedTime = remember {
                derivedStateOf {
                    formatElapsedTime(
                        activeDuration.toKotlinDuration(), true
                    ).toString()
                }
            }
            // When the screen comes on, or goes off, then the ticker coroutine needs to either be
            // started or stopped, if the exercise is ACTIVE. This is done by observing lifecycle
            // events
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START && exerciseStateChange is ExerciseStateChange.ActiveStateChange) {
                        val activeStateChange =
                            exerciseStateChange as ExerciseStateChange.ActiveStateChange
                        val timeOffset =
                            (System.currentTimeMillis() - activeStateChange.durationCheckPoint.time.toEpochMilli())
                        baseActiveDuration.value =
                            activeStateChange.durationCheckPoint.activeDuration.plusMillis(
                                timeOffset
                            )
                        chronoTickJob.value = startTick(chronoTickJob.value, scope) { tickerTime ->
                            activeDuration = baseActiveDuration.value.plusMillis(tickerTime)

                        }
                    } else if (event == Lifecycle.Event.ON_STOP) {
                        chronoTickJob.value?.cancel()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            // Instead of watching the ExerciseState state, or active duration, I've defined a
            // ExerciseStateChange object in the service (and exposed it in the view), which is only
            // updated in the service when it transitions from one State to another.
            // This means that when the exercise state changes to ACTIVE, on that first transition
            // then again, the ticker is started, or if the state is no longer ACTIVE, the ticker is
            // stopped.
            // Also, the ExerciseStateChange object, when active, has an ActiveDurationCheckpoint
            // which allows the base ActiveDuration to be set. This is again set in the service and
            // avoids exposing ActiveDuration as state to compose, which could cause recomposition,
            // and you want the ActiveDurationCheckpoint to be associated with exactly when the
            // state changed to active.
            LaunchedEffect(exerciseStateChange) {
                if (exerciseStateChange is ExerciseStateChange.ActiveStateChange) {
                    val activeStateChange =
                        exerciseStateChange as ExerciseStateChange.ActiveStateChange
                    val timeOffset =
                        (System.currentTimeMillis() - activeStateChange.durationCheckPoint.time.toEpochMilli())
                    baseActiveDuration.value =
                        activeStateChange.durationCheckPoint.activeDuration.plusMillis(timeOffset)
                    chronoTickJob.value = startTick(chronoTickJob.value, scope) { tickerTime ->
                        activeDuration = baseActiveDuration.value.plusMillis(tickerTime)
                    }
                } else {
                    chronoTickJob.value?.cancel()
                }
            }

            ExerciseSampleTheme {
                Scaffold(timeText = { TopOfScreenTime() }) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colors.background),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_outline_watch_later_24),
                                contentDescription = stringResource(id = R.string.duration)
                            )
                            Text(elapsedTime.value)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Favorite,
                                contentDescription = stringResource(id = R.string.heart_rate)
                            )
                            HRText(
                                hr = tempHeartRate.value
                            )
                            Icon(
                                painter = painterResource(id = R.drawable.ic_baseline_local_fire_department_24),
                                contentDescription = stringResource(id = R.string.calories)
                            )
                            if (calories != null) {

                                CaloriesText(
                                    calories
                                )
                                tempCalories.value = calories
                            } else {
                                CaloriesText(
                                    tempCalories.value
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_baseline_trending_up_24),
                                contentDescription = stringResource(id = R.string.distance)
                            )
                            if (distance != null) {
                                DistanceText(
                                    distance
                                )
                                tempDistance.value = distance
                            } else {
                                DistanceText(
                                    tempDistance.value
                                )
                            }
                            Icon(
                                painter = painterResource(id = R.drawable.ic_baseline_360_24),
                                contentDescription = stringResource(id = R.string.laps)
                            )
                            Text(text = laps.toString())
                            if (averageHeartRate != null) {
                                tempAverageHeartRate.value = averageHeartRate
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            if (exerciseStateChange.exerciseState.isEnding) {

                                //In a production fitness app, you might upload workout metrics to your app
                                // either via network connection or to your mobile app via the Data Layer API.
                                navController.navigate(
                                    Screens.SummaryScreen.route + "/${tempAverageHeartRate.value.toInt()}/${
                                        formatDistanceKm(
                                            tempDistance.value
                                        )
                                    }/${formatCalories(tempCalories.value)}/" + formatElapsedTime(
                                        activeDuration.toKotlinDuration(), true
                                    ).toString()
                                )

                                Button(onClick = { onStartClick() }) {
                                    // Text(text = startOrEnd)
                                    Icon(
                                        painter = startOrEnd, contentDescription = stringResource(
                                            id = R.string.startOrEnd
                                        )
                                    )
                                }

                            } else {
                                Button(onClick = { onEndClick() }) {
                                    Icon(
                                        painter = startOrEnd, contentDescription = stringResource(
                                            id = R.string.startOrEnd
                                        )
                                    )
                                }

                            }
                            if (exerciseStateChange.exerciseState.isPaused) {
                                Button(onClick = {
                                    onResumeClick()
                                }) {
                                    Icon(
                                        painter = pauseOrResume,
                                        contentDescription = stringResource(id = R.string.pauseOrResume)
                                    )
                                }
                            } else {
                                Button(onClick = {
                                    onPauseClick()
                                }) {
                                    Icon(
                                        painter = pauseOrResume,
                                        contentDescription = stringResource(id = R.string.pauseOrResume)
                                    )
                                }

                            }
                        }
                    }
                }
            }
        }

        else -> {}
    }
}

// A coroutine is used to update a ticker whilst the exercise is active. This is necessary because
// WHS might not give us ExerciseUpdates every second on some devices. So the transition to the
// Active state is used to start the ticker, but once started, delivery of ExerciseUpdates shouldn't
// be relied on to make each tick, instead using the coroutine.
private fun startTick(
    chronoTickJob: Job?, scope: CoroutineScope, block: (tickTime: Long) -> Unit
): Job? {
    if (chronoTickJob == null || !chronoTickJob.isActive) {
        return scope.launch {
            val tickStart = System.currentTimeMillis()
            while (isActive) {
                val tickSpan = System.currentTimeMillis() - tickStart
                block(tickSpan)
                delay(CHRONO_TICK_MS)
            }
        }
    }
    return null
}

const val CHRONO_TICK_MS = 200L

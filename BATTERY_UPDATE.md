# Light Alarm 0.1.2

- Track ringing duration with Android's elapsed clock and a boot identifier. Moving the wall clock backward or forward cannot extend or prematurely shorten an active ring.
- Preserve elapsed deadlines across process restarts and bound migration of ringing events from earlier releases.
- Give the vibrator a finite waveform with the same pattern and selected duration. Silent reminders without a pending retry no longer hold the application's CPU wake lock while vibrating.
- Reconcile expired events when the service or UI resumes. Release audio, vibration, notifications and wake locks when ringing finishes.

The grayscale interface, alarm schedules, pre-alarms, snooze limits, retries, backup alarms and 24-hour history remain available. Exact user-visible alarms retain Android's alarm-clock scheduling so closely spaced events keep their timing. That scheduling can wake Android before an alarm, and chosen audio/vibration still uses energy. Removing those costs would change timing or behavior.

## Validation

Scheduling tests and ARM64 Android 14 emulator engine checks cover recurrence, pre-alarms, overlap, snooze limits, retry, backup cancellation, persistence, reboot, 24-hour retention, both directions of clock adjustment, legacy migration and finite vibration. A PIN-locked emulator displayed the ringing screen. A silent 20-second reminder had no LightAlarm CPU wake lock and its vibration finished at approximately 20 seconds.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb shell am instrument -w sg.lightalarm.test/sg.lightalarm.EngineChecks
```

A physical Light Phone III battery comparison is still needed. Earlier physical lock-screen validation applies to 0.1.1, not a measurement of this update's battery savings.

# CallTracker — Android Call-Capture POC

CallTracker is the **Track A technical-validation scaffold** for a Callyzer-like B2B call analytics product.

The goal of this repository is deliberately narrow:

> **Prove that an Android app, deployed privately to company-managed/company-owned devices, can reliably detect and read completed call-log entries in the background, filter personal numbers on-device, and queue the remaining business-call metadata for synchronization.**

This repository is a **source scaffold / proof of concept, not a production application and not a prebuilt APK**.

> **This POC is for technical feasibility testing and is not legal advice or a guarantee of Google Play approval.**

The question the code exists to answer, in one sentence:

> Can a privately distributed Android app, **without replacing the user's default dialer**, detect completed phone calls through `CallLog.Calls.CONTENT_URI`, process them with privacy filtering, persist them locally, and prepare them for synchronization?

Nothing in this repository is production-ready, and nothing here should be read as a claim that it is.

---

## 1. Product direction

The planned product is a B2B SaaS platform for sales teams:

```text
Employee Android phone
        │
        ▼
  CallTracker app
        │
        ├── Call state signal
        ├── Call-log observer
        ├── Privacy filter
        ├── Local queue
        └── Background sync
                │
                ▼
           Backend API
                │
                ▼
           PostgreSQL
                │
                ▼
        React/Vite dashboard
                │
                ▼
     Calls → Leads → Outcomes → Follow-ups
```

The intended V1 stack is:

| Layer | Technology |
|---|---|
| Android | Kotlin + native Android APIs |
| Android UI | Jetpack Compose |
| Local Android DB | Room |
| Background work | WorkManager + foreground service |
| Web | React + Vite + TypeScript |
| Web state | TanStack Query |
| Charts | Recharts |
| Backend | NestJS + TypeScript |
| API | REST, versioned under `/api/v1/` |
| Database | PostgreSQL |
| Later, only if needed | Redis/BullMQ, object storage, billing, AI |

**None of the backend or web layers exist, and none are required to validate this POC.** The app runs, captures, filters and persists entirely offline.

---

## 2. What this POC is testing

There are three separate validation layers:

### Layer A — Android OS

Can the application obtain the required permissions and read the call-log provider on the target Android versions?

### Layer B — OEM/device behavior

Does the capture mechanism remain reliable when the app is backgrounded, the screen is off, the device is idle, and OEM battery-management policies are active?

### Layer C — Distribution/deployment

Can the app be deployed and managed on the target company-owned/company-managed devices through the chosen private distribution channel?

**Do not treat any one successful emulator run as proof of production feasibility.**

**Final validation requires physical devices.** An emulator will happily show the ContentObserver firing and rows being captured, because an emulator has no OEM battery manager, no Doze pressure worth the name, no dual SIM, and no vendor call-log provider quirks. Every failure mode this POC exists to find lives outside the emulator. Emulator runs are useful for checking that the app builds and the pipeline is wired up, and for nothing else.

> Important: private distribution does not automatically make every use of restricted Android data appropriate for every deployment channel or jurisdiction. Validate the final permission/distribution model against current Android behavior, Google Play/enterprise requirements if Play-managed distribution is used, device-management policies, and applicable privacy/legal requirements before a real customer rollout.

---

## 3. Current POC flow

The service intentionally uses two signals, plus a periodic safety net:

```text
                 Phone call
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
 TelephonyCallback       ContentObserver          WorkManager
  CallStateListener      CallLog.Calls           periodic catch-up
          │              CONTENT_URI                    │
     OFFHOOK / IDLE            │                        │
          │                    │                        │
          └────────────┬───────┴────────────────────────┘
                       ▼
              conflated scan channel
                       ▼
               CallCaptureEngine.scan()
                       │
                       ▼
              Android CallLog rows
                       │
                       ▼
          already processed?  ──── yes ──▶ SKIP_DUPLICATE
                       │ no
                       ▼
              normalize phone number
                       │
                       ▼
                 PrivacyFilter
                 ┌─────┴─────┐
              excluded     allowed
                 │           │
      marker row only        ▼
      (id + EXCLUDED,   Room `calls`
       no number)       syncStatus = PENDING
                             │
                             ▼
                        WorkManager
                             │
                     network available
                             │
                             ▼
                      CallSyncWorker
                    (CallSyncClient —
                     local, no server)
```

### Why two signals?

`TelephonyCallback.CallStateListener` (API 31+) reports `CALL_STATE_RINGING`, `CALL_STATE_OFFHOOK` and `CALL_STATE_IDLE`. It is fast, but at the instant `IDLE` fires the call-log row usually does **not** exist yet. It is therefore used strictly as a nudge: on `IDLE` the service waits ~1.5 s and then asks for a scan.

`ContentObserver` on `CallLog.Calls.CONTENT_URI` is the **source-of-truth trigger**. The call-log provider is the only thing that knows a completed call record exists.

The service never treats a telephony callback alone as proof that a new call-log row exists.

`PhoneStateListener` is used **only below API 31**, where `TelephonyCallback` does not exist. minSdk is 26, so the branch is required; it is never taken on Android 12+ (the entire target range of the OEM matrix).

### Why a third path?

The foreground service can stop for reasons that are not bugs: an OEM battery manager kills the process, the user restricts background activity, or Android 15 ends the `dataSync` foreground service at its time budget. With no process there is no `ContentObserver`, and calls made in that window would be lost entirely.

`CallLogCatchUpWorker` is a periodic WorkManager job (15 minutes, WorkManager's floor) that re-scans the call log ignoring the incremental watermark. It cannot create duplicates — the same idempotency guarantees apply — so calls made during a kill window are still captured, just later. **Capture latency after an OEM kill is therefore up to ~15 minutes.** That is a limitation to record in the matrix, not a fix.

---

## 4. Repository structure

```text
CallTracker/
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   └── java/com/calltracker/app/
        │       ├── CallTrackerApp.kt
        │       ├── MainActivity.kt
        │       ├── call/
        │       │   ├── BootReceiver.kt
        │       │   ├── CallCaptureEngine.kt
        │       │   ├── CallLogCatchUpWorker.kt
        │       │   ├── CallLogMapping.kt
        │       │   ├── CallLogReader.kt
        │       │   ├── CallMonitorService.kt
        │       │   ├── CapturePipeline.kt
        │       │   ├── ContactResolver.kt
        │       │   ├── PhoneNumbers.kt
        │       │   ├── PrivacyFilter.kt
        │       │   └── SimResolver.kt
        │       ├── database/
        │       │   ├── AppDatabase.kt
        │       │   ├── Dao.kt
        │       │   └── Entities.kt
        │       ├── diagnostics/
        │       │   ├── DeviceInfo.kt
        │       │   ├── DiagnosticEvents.kt
        │       │   └── DiagnosticsStore.kt
        │       ├── sync/
        │       │   ├── CallSyncClient.kt
        │       │   ├── CallSyncScheduler.kt
        │       │   ├── CallSyncWorker.kt
        │       │   └── SyncStateMachine.kt
        │       └── ui/
        │           ├── DiagnosticsRepository.kt
        │           └── Screens.kt
        └── test/java/com/calltracker/app/
            ├── call/
            │   ├── CallLogMappingTest.kt
            │   ├── DeduplicationTest.kt
            │   ├── PhoneNumbersTest.kt
            │   └── PrivacyRulesTest.kt
            └── sync/
                └── SyncStateMachineTest.kt
```

### Important files

#### `call/CallMonitorService.kt`

Foreground service. Registers the two signals, funnels every scan request through a **conflated channel** consumed by a single coroutine (so an observer burst collapses into one scan), and stops cleanly when Android 15 ends its foreground-service budget.

#### `call/CallCaptureEngine.kt`

The pipeline itself: read → dedupe → normalize → privacy filter → persist. Every scan runs under a `Mutex`, so overlapping notifications cannot interleave a read-then-write.

#### `call/CapturePipeline.kt`

The ordering rule as a pure function, so it can be unit-tested. Two properties are pinned there: the duplicate check runs **before** the privacy check, and an excluded row is still marked as processed.

#### `call/CallLogReader.kt`

The `CallLog.Calls` query and projection, in one place. Bounded twice (SQL `LIMIT` plus a read-loop cap) so a provider that ignores the limit hint cannot make the app walk a 5000-row call history.

#### `call/PhoneNumbers.kt`

Dependency-free normalization. Its job is that the same number, written four different ways by four different dialers, collapses to one value — otherwise de-duplication and privacy matching both break. It is **not** a substitute for libphonenumber.

#### `call/PrivacyFilter.kt`

Applies the exclusion list **before the call is persisted**, and therefore before it can ever enter the pending-sync queue.

```text
CallLog
   ↓
normalize
   ↓
PrivacyFilter
   ↓
Allowed  → Room `calls`, syncStatus = PENDING
Excluded → marker row holding the call-log id and the word EXCLUDED_PRIVACY.
           No number. No name. No call timestamp. Nothing to upload.
```

The exclusion list is read fresh on every scan rather than cached: a stale cache here means a personal call gets uploaded.

#### `call/SimResolver.kt`

Dual-SIM resolution, isolated. See section 8 — it returns `null` rather than guessing.

#### `sync/CallSyncClient.kt`

The entire network surface, behind one interface. The only implementation is `LocalLoopbackSyncClient`, which performs no I/O.

#### `ui/Screens.kt` + `MainActivity.kt`

Consent explanation → per-permission rationale → permission request → diagnostics.

---

## 5. Permissions used by the POC

Every permission below is mapped to the code that needs it. Nothing is requested "just in case".

| Permission | Runtime? | Required? | Used by |
|---|---|---|---|
| `READ_CALL_LOG` | yes | **required** | `CallLogReader` query + `ContentObserver` registration |
| `READ_PHONE_STATE` | yes | **required** | `TelephonyCallback.CallStateListener`, `SubscriptionManager` in `SimResolver` |
| `READ_CONTACTS` | yes | optional | `ContactResolver` — a local display name on the diagnostics screen only |
| `POST_NOTIFICATIONS` | yes (API 33+) | optional | makes the foreground-service notification visible |
| `INTERNET`, `ACCESS_NETWORK_STATE` | no | — | WorkManager's `NetworkType.CONNECTED` constraint. **No network call is made.** |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | no | — | `CallMonitorService` |
| `RECEIVE_BOOT_COMPLETED` | no | — | `BootReceiver` |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | no | — | the battery-exemption prompt, so the matrix can compare optimized vs exempted |

The app **explains each permission before requesting it**. The permission screen lists every permission with the specific thing it is used for, and marks the optional ones as optional. Denying `READ_CONTACTS` or `POST_NOTIFICATIONS` changes nothing about capture.

### On `READ_CONTACTS`

It is requested because it is genuinely used — `ContactsContract.PhoneLookup` puts a saved contact name next to a captured call so a tester can identify their own test calls at a glance. The name is stored locally and is **explicitly excluded from `CallSyncPayload`**. If contact matching is ever dropped, drop the permission with it.

### Deliberately not requested

`RECORD_AUDIO`, `CALL_PHONE`, `ANSWER_PHONE_CALLS`, `PROCESS_OUTGOING_CALLS`, and any `ROLE_DIALER` / default-dialer intent filter.

There is intentionally **no default-dialer implementation in this POC**. The hypothesis under test is that a privately distributed enterprise application on company-managed devices can read the call log as a normal runtime permission, without taking on the complexity of replacing the native dialer.

That hypothesis is a **validation target, not a guarantee**, and these permissions do **not** guarantee Google Play approval. This POC is intended for private/enterprise distribution testing. Confirm behavior and distribution requirements against the exact Android/device-management environment selected for the pilot.

---

## 6. Privacy design

Call logs are not inherently "business calls." A device may contain calls to family members, doctors, banks, friends. The product must not be designed around indiscriminately uploading every call.

### POC behavior

The onboarding screen explains, before any permission dialog appears:

- what call metadata is collected
- why it is collected
- that call audio is **not** collected and no microphone permission is requested
- that personal numbers can be excluded and never leave the device
- that in this build there is no server at all

Excluded numbers are filtered locally **before** entering the pending-sync queue, and are never persisted.

### What an excluded call leaves behind

One row in `processed_call_log_rows`:

```text
deviceCallId        = "1234"          (the Android CallLog._ID)
outcome             = EXCLUDED_PRIVACY
processedAtEpochMs  = <when we looked at it>
```

No phone number, no contact name, no call time, no duration. The row exists only so the same call is not re-evaluated and re-counted on the next notification.

The diagnostic event log records `CALL_EXCLUDED_PRIVACY callLogId=1234 result=EXCLUDED fp=9f2c1a7b` — the call-log id and a fingerprint, not the number. Numbers that **pass** the filter are logged masked (`num=********3210`) plus the same fingerprint.

### Current limitation — exclusion-list UI

The diagnostics screen has the **smallest possible** exclusion control: one text field, an Exclude button, and a list with Remove. It exists so the privacy filter is testable on a physical device at all.

This is **not** the employee/admin experience a real deployment needs. A real pilot needs a proper settings screen with import from contacts, admin-managed lists, and an audit trail:

```text
Privacy / Personal numbers

[ + Add personal number ]      [ Import from contacts ]

Excluded numbers
----------------
+91XXXXXXXXXX   (personal)     [Remove]
+91XXXXXXXXXX   (family)       [Remove]
```

The production product also needs a proper privacy notice, retention/deletion policy, access controls, auditability, and a legal/privacy review appropriate to the customer and deployment model. The onboarding copy in this POC is **not legal advice and is not a complete consent mechanism by itself**.

---

## 7. Android local data model

Room, schema version 2. Three tables plus the diagnostic log.

### `calls` — accepted (privacy-filtered) calls

```text
id                        autoGenerate primary key
deviceCallId              Android CallLog._ID, UNIQUE index  ← de-dupe key
phoneNumber               raw, as the provider wrote it
normalizedNumber          output of normalizePhoneNumber()
contactName               local only, never synced, null if READ_CONTACTS denied
direction                 INCOMING | OUTGOING | UNKNOWN
status                    ANSWERED | NOT_CONNECTED | MISSED | REJECTED |
                          BLOCKED | VOICEMAIL | ANSWERED_ELSEWHERE | UNKNOWN
startedAtEpochMs          CallLog.Calls.DATE
durationSeconds           CallLog.Calls.DURATION, clamped at 0
endedAtEpochMs            derived: started + duration
simSlot                   null unless the device actually told us
simSubscriptionId         null unless resolved
simCarrierName            null unless resolved
phoneAccountId            raw CallLog.Calls.PHONE_ACCOUNT_ID, kept verbatim
syncStatus                PENDING | SYNCED | FAILED
syncAttempts              int
lastSyncAttemptAtEpochMs  nullable
capturedAtEpochMs         when this app stored it
```

**Direction and status are separate axes on purpose.** The Android call log conflates them into one `TYPE` column — `MISSED_TYPE` is an incoming call that was not answered. Modelling MISSED as a *direction*, as the earlier scaffold did, makes "how many incoming calls did we see" unanswerable.

### `processed_call_log_rows` — the deduplication ledger

```text
deviceCallId        primary key
outcome             STORED | EXCLUDED_PRIVACY
processedAtEpochMs
```

### `excluded_numbers`

```text
normalizedNumber    primary key
label
addedAtEpochMs
```

Read by the privacy filter, never read by the sync worker.

### `diagnostic_events`

Append-only local event log, trimmed to the most recent 1000 rows.

### Offline behavior

The app never needs a network. Capture, normalization, privacy filtering and persistence are entirely local. Only `CallSyncWorker` carries a `NetworkType.CONNECTED` constraint, and with no connectivity it simply waits while capture continues. Airplane-mode testing is a valid and expected part of the matrix.

---

## 8. Deduplication

The same call must not become two local records when the `ContentObserver` fires several times, when the telephony nudge lands on top of that burst, when the app restarts and re-scans, or when a sync retries.

The mechanism is explicit and layered:

**Layer 1 — SQLite constraints.** `calls.deviceCallId` carries a `UNIQUE` index; `processed_call_log_rows.deviceCallId` is a `PRIMARY KEY`. A second insert for the same `CallLog._ID` is rejected by the database, not merely by an application check.

**Layer 2 — transactional check-then-insert.** `CallDao.recordAccepted` and `recordExcluded` are `@Transaction` methods that check the ledger and insert in one atomic step, and return `false` when the row was already handled. This is what keeps the *counters* honest, not just the table.

**Layer 3 — a scan mutex.** Every scan in `CallCaptureEngine` runs under a `Mutex`, and every scan request passes through a conflated channel consumed by one coroutine. Two notifications cannot both pass the "already processed?" check for the same row.

**Layer 4 — the ledger covers excluded calls too.** An excluded call is never written to `calls`, so `calls` alone could not de-duplicate it. Without the ledger, one excluded personal call would inflate the "calls excluded" counter on every observer callback and on every app restart.

### The watermark is not the deduplication mechanism

`DiagnosticsStore.lastProcessedCallLogId` exists only so an incremental scan does not re-read the whole call log. It is a **performance hint**. It moves forward only, only past rows a scan actually finished deciding on, and if it is wrong or lost, the catch-up scan finds the rows anyway and the constraints still prevent duplicates.

This matters because "latest call timestamp" de-duplication — which this POC deliberately does not use — breaks the moment two calls share a second, a call log row is inserted out of order, or the device clock moves.

### `_ID` reuse

`CallLog.Calls._ID` is stable for the life of a row, but ids **can** be reused after the user clears their call log. In that situation a new call could collide with an old ledger entry and be skipped. For a POC measured in weeks this is acceptable and is called out here rather than hidden; a production build should use a composite key (`_ID` + `DATE` + normalized number) or clear the ledger when the provider's row count drops.

---

## 9. Background behavior

The POC is built so all seven states in the test plan can actually be observed:

| State | Mechanism | Notes |
|---|---|---|
| App open | FGS + ContentObserver | fastest path, seconds |
| App backgrounded | FGS + ContentObserver | unchanged; the service is what matters, not the Activity |
| Screen locked | FGS + ContentObserver | main OEM failure point |
| Removed from recents | `START_STICKY` + periodic catch-up | some OEMs kill the process anyway; catch-up recovers within ~15 min |
| Device reboot | `BootReceiver` | see below |
| Battery optimization on | catch-up worker only, most likely | expect delayed capture |
| Battery optimization exempted | FGS survives | the comparison run |

### Reboot

`BootReceiver` handles `BOOT_COMPLETED`, and does **not** assume the foreground-service start will succeed. Android 15 restricts which foreground-service types may be started from `BOOT_COMPLETED`, and `dataSync` is among the restricted ones. So the receiver:

1. re-arms the periodic catch-up worker **first** (it does not depend on a permission-sensitive foreground start),
2. enqueues an immediate catch-up scan,
3. *then* attempts the service start inside `runCatching`, recording the outcome in the event log either way.

Whether the service start is refused on a given device is exactly the sort of thing the matrix should record.

### Android 15 foreground-service time limit

Android 15 caps a `dataSync` foreground service at roughly 6 hours per 24 hours. When the budget runs out, `Service.onTimeout()` fires and the service must stop. The POC handles this: it logs `SERVICE_TIMEOUT`, stops cleanly, stamps the diagnostics screen with the timeout time, and lets the periodic catch-up carry capture.

This is a real design limitation, not a bug. If it proves fatal for the product, the fix is a different foreground-service type (`specialUse`, with the required subtype property and justification) or a different architecture — **not** something to paper over. Decide that after the matrix, with data.

### OEM restrictions

The diagnostics screen shows the manufacturer-specific opt-in that a device is likely to need (MIUI Autostart, One UI "never sleeping apps", ColorOS auto-launch, and so on) as a **status item to be granted by hand**.

The app makes no attempt to defeat OEM security or battery policies, does not launch hidden vendor settings activities, and does not retry in a loop to force a refused foreground-service start. If an OEM requires manual permission, that is a finding to record, not an obstacle to route around.

---

## 10. Dual SIM

`SimResolver` is deliberately isolated in one class, and **SIM identification is not solved**.

The honest state of the world: `CallLog.Calls.PHONE_ACCOUNT_ID` is not standardised. On stock Android it is usually the subscription id as a string. On several OEM builds it is the ICCID, a `PhoneAccountHandle` id, or empty. There is no API that guarantees a mapping.

`SimResolver` attempts exactly the two mappings that are observable, and reports which one (if any) worked:

| `SimResolution` | Meaning |
|---|---|
| `RESOLVED` | `PHONE_ACCOUNT_ID` matched an active subscription (by subscription id, or by ICCID) |
| `SINGLE_SIM` | only one active SIM, so the slot is unambiguous without matching |
| `NO_PHONE_ACCOUNT` | the row carried no `PHONE_ACCOUNT_ID` and there is more than one SIM |
| `UNRESOLVED` | a `PHONE_ACCOUNT_ID` was present but matched nothing |
| `UNAVAILABLE` | `READ_PHONE_STATE` denied, or the platform threw |

There is deliberately **no** "just pick slot 0" fallback. When resolution fails, `simSlot` is `null` and the raw `PHONE_ACCOUNT_ID` is stored verbatim and shown in the event log — that raw value is the data the matrix needs.

**Do not build business logic that assumes SIM resolution works until the "SIM identified?" column is full.**

---

## 11. Diagnostics screen

The diagnostics screen is the actual product of this POC. A tester should be able to fill in a matrix row from it without `adb`.

### Pre-flight

The top of the screen is a pre-flight gate. Every row is PASS / WARN / FAIL with a one-line observation and, for anything not PASS, why it matters and an action button:

| Check | PASS when | Otherwise |
|---|---|---|
| `READ_CALL_LOG` | granted | **FAIL** → Open app settings |
| `READ_PHONE_STATE` | granted | **FAIL** → Open app settings |
| `READ_CONTACTS` (optional) | granted | WARN → Open app settings |
| `POST_NOTIFICATIONS` (API 33+) | granted | WARN → Open notification settings |
| Monitoring service | process reports the service running **and** `monitoringActive` | FAIL; a stale flag (flag set, service gone) is called out explicitly — that is an OEM kill |
| ContentObserver | registered while the service runs | FAIL |
| TelephonyCallback | registered while the service runs | FAIL |
| Room database | opens; reports schema version | FAIL |
| Catch-up worker | WorkManager reports ENQUEUED/RUNNING; shows last run | WARN → Start monitoring |
| Exclusion list | at least one number | WARN (privacy filter cannot be exercised) |
| Device / Android / App version | informational | Android 15+ carries a note about the dataSync time limit |
| Background restriction | not restricted | **FAIL** → Open app settings |
| Battery optimization | exempted | WARN — *"background reliability must be validated"* |
| OEM background policy | — | always WARN on OEMs known to need a manual opt-in |
| SIM / subscriptions | one active SIM | WARN for dual SIM (mapping unverified), for none, or when the platform will not say |

**A full column of PASS means the pipeline is wired. It does not mean background capture will survive this OEM's battery manager — nothing observable from inside the process can prove that, which is why the battery and OEM rows are WARN with "must be validated" wording.**

The pre-flight never requests a permission on its own. Every action opens the relevant Android settings screen or starts the existing monitoring flow; permission dialogs are only ever launched from the permission screen the user walked through at onboarding.

### Status rows

```text
Permissions      call log / phone state / contacts / notifications
Monitoring       ACTIVE/INACTIVE, last started, observer registered, telephony registered,
                 last observer event (+count), last telephony state, last scan (+reason, +result),
                 highest call-log id scanned, last catch-up worker run, FGS timeout (if it happened)
Calls            stored, excluded, duplicates detected, pending, synced, failed,
                 sync runs succeeded / failed, last stored call, last sync
Last call trace  every event carrying the most recently evaluated call-log id, oldest first
Device           manufacturer, model, Android/API, app version, battery optimization,
                 background restricted, active SIMs, OEM background-policy note
```

### Actions

`Start` / `Stop` / `Rescan` / `Sync now` / `Retry failed` / `Battery settings` / `Clear diagnostics`.

**Clear diagnostics** empties the event log and resets the diagnostic counters (observer count, duplicates, sync counts, last-seen timestamps). It does **not** touch stored calls, the processed-row ledger, the scan watermark, the exclusion list, or consent — clearing diagnostics never changes what the pipeline does next.

### Privacy exclusions

One field, `Exclude`, a list with `Remove`, and `Dry-run check`. Dry-run runs the typed number through normalize → privacy filter against the real on-device list and writes one `SIMULATED` line to the event log. It stores nothing, touches no ledger, queues nothing, and is never a call.

### Event log

Every event is one row: timestamp, type, and a detail string of `key=value` pairs. Phone numbers never appear in full — they are rendered as a mask (`num=********3210`) plus a stable fingerprint (`fp=9f2c1a7b`, first 8 hex of SHA-256 of the normalized number) so a tester can tell "these events were the same number" without the number ever being logged. Nothing is written to Logcat.

A real outgoing call reads top to bottom like this:

```text
14:31:02  TELEPHONY_STATE           state=OFFHOOK
14:34:18  TELEPHONY_STATE           state=IDLE
14:34:19  CALL_LOG_CHANGE_DETECTED  selfChange=false
14:34:19  SCAN_STARTED              reason=CALL_LOG_CHANGED after=1233 limit=50
14:34:19  CALL_LOG_ROWS_FOUND       reason=CALL_LOG_CHANGED rows=1
14:34:19  CALL_ROW_EVALUATED        callLogId=1234 type=2 durationS=41 num=********3210 fp=9f2c1a7b
14:34:19  CALL_ALLOWED              callLogId=1234 result=ALLOW
14:34:19  SIM_RESOLUTION            callLogId=1234 result=RESOLVED slot=0 subId=1 phoneAccountId=1
14:34:19  CALL_STORED               callLogId=1234 direction=OUTGOING status=ANSWERED durationS=41 sim=RESOLVED syncStatus=PENDING
14:34:19  SCAN_FINISHED             reason=CALL_LOG_CHANGED rows=1 stored=1 excluded=0 duplicates=0
14:34:19  CALL_SYNC_QUEUED          reason=CALL_LOG_CHANGED stored=1
14:34:20  WORKER_STARTED            worker=CallSyncWorker attempt=0
14:34:20  SYNC_STARTED              batch=1 client=LocalLoopbackSyncClient callLogIds=1234
14:34:20  SYNC_SUCCEEDED            batch=1 outcome=SUCCESS status=SYNCED attempts=1 willRetry=false
14:34:20  WORKER_FINISHED           worker=CallSyncWorker result=SYNCED
```

The same call re-notified a second later produces `CALL_LOG_CHANGE_DETECTED` → `SCAN_STARTED` → `CALL_LOG_ROWS_FOUND rows=0` (watermark) or `CALL_DUPLICATE callLogId=1234 reason=already_in_processed_ledger` (catch-up) — and nothing else.

Full vocabulary:

| Type | Fields |
|---|---|
| `SERVICE_STARTED` / `SERVICE_STOPPED` / `SERVICE_TIMEOUT` | observer, telephony / reason / fgsType, fallback |
| `BOOT_RECEIVED` | action, catchUp, serviceStart |
| `WORKER_STARTED` / `WORKER_FINISHED` | worker, attempt / result, rows, stored, excluded, duplicates, serviceRestart |
| `PERMISSION_CHANGED` | permission+state+effect, or a snapshot of all four |
| `TELEPHONY_STATE` | state |
| `CALL_LOG_CHANGE_DETECTED` | selfChange |
| `SCAN_STARTED` / `CALL_LOG_ROWS_FOUND` / `SCAN_FINISHED` | reason, after, limit / rows / stored, excluded, duplicates |
| `CALL_ROW_EVALUATED` | callLogId, type, durationS, num (masked), fp |
| `CALL_ALLOWED` / `CALL_EXCLUDED_PRIVACY` / `CALL_DUPLICATE` | callLogId, result / fp / reason |
| `SIM_RESOLUTION` | callLogId, result, slot, subId, phoneAccountId |
| `CALL_STORED` | callLogId, direction, status, durationS, sim, syncStatus |
| `CALL_SYNC_QUEUED` | reason, stored |
| `SYNC_STARTED` / `SYNC_SUCCEEDED` / `SYNC_FAILED` | batch, client, callLogIds / outcome, status, attempts, willRetry |
| `SIMULATED` | what, num, fp, result, stored=false |
| `ERROR` | where, exception, message, plus context |

---

## 12. Sync — and why there is no backend

There is **no requirement for a live server to validate this POC**, and no server exists.

`CallSyncClient` is the entire network surface. The only implementation is `LocalLoopbackSyncClient`, which performs no I/O and always accepts the batch. It exercises the local `PENDING → SYNCED` transition so the queue can be observed draining. **It is not a mock of a real backend and must not be mistaken for evidence that synchronization works.** It only proves the local queue drains.

`SyncStateMachine` holds the complete set of legal transitions as a pure function:

```text
PENDING + SUCCESS                     → SYNCED
PENDING + TRANSIENT (attempts < 5)    → PENDING   (retry)
PENDING + TRANSIENT (attempts >= 5)   → FAILED
PENDING + PERMANENT                   → FAILED
FAILED  + SUCCESS                     → SYNCED    (after manual requeue)
SYNCED  + anything                    → SYNCED    (terminal)
```

`SYNCED` is terminal so a retry that re-delivers an already-accepted batch cannot move a call backwards into the queue.

`CallSyncPayload` is built explicitly rather than by serializing `CallEntity`, so what would leave the device is a deliberate choice. `contactName` is not in it.

When a backend exists, add a `RetrofitCallSyncClient` and change one construction site in `CallSyncWorker`. The eventual contract is `POST /api/v1/calls/sync` (section 23), and **server-side idempotency is a separate problem** — the local guarantees described in section 8 stop local duplicates, not server ones.

---

## 13. Testing

### Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

These run on the JVM with no device and cover the logic that is worth pinning:

| File | Covers |
|---|---|
| `PhoneNumbersTest` | normalization across `+91…`, `91…`, `09…`, `00 91…`, formatted, short-code, withheld and blank inputs; that all renderings of one number collapse to one value; diagnostic masking |
| `PrivacyRulesTest` | allow/exclude, empty list, cross-format matching, near-miss numbers not colliding, withheld caller ids flagged; and the input-format matrix: exact, `+91`, local `0`-prefixed, spaces, hyphens, parentheses, leading/trailing whitespace, unknown/withheld |
| `CallLogMappingTest` | direction vs status separation, every `CallLog.Calls.*_TYPE`, unknown types, negative-duration clamping, `endedAt` arithmetic; representative rows for the Phase 2/3/4 test calls |
| `DeduplicationTest` | the pure ordering rule (`CapturePipeline`): one call notified five times yields one store; restart re-scan stores nothing; catch-up stores only new rows; excluded rows not re-counted; watermark monotonicity |
| `DeduplicationLedgerTest` | the **real `CallDao.recordAccepted` / `recordExcluded` bodies** run against an in-memory fake of the abstract Room methods (`FakeCallDao`): A. same id twice; B. 50 near-simultaneous transactions → one store, and the same with serialization deliberately removed → uniqueness alone still yields one row; C. observer scan and catch-up scan racing over overlapping rows; D. excluded row seen three times, and cannot later be stored; E. allowed row seen five times; a lost watermark causes no duplicates |
| `SyncQueueFlowTest` | stored → `PENDING` → loopback → `SYNCED`; synced calls are not re-sent; transient failures retry; budget exhaustion → `FAILED` → `Retry failed` → `SYNCED`; permanent failure; throwing client; payload carries no contact name and no excluded rows |
| `SyncStateMachineTest` | every legal transition, attempt-budget exhaustion, `SYNCED` terminality, payload excludes contact name |
| `DiagnosticsFormatTest` | `key=value` rendering, whitespace collapsing, mask shows last 4 only, fingerprint is stable across renderings and never contains the number, last-call-trace derivation ignores the plural `callLogIds=` key |

`FakeCallDao` subclasses the real abstract `CallDao`, so the `@Transaction` helper logic under test is the production code. Room's transaction serialization is modelled by a mutex and its UNIQUE index by `putIfAbsent`; this verifies the logic we wrote, not Room's implementation of it.

### What unit tests do not prove

They do not prove capture works. They cover pure logic only. The `ContentObserver`, the `TelephonyCallback`, the foreground service, the Room constraints, OEM battery behavior, reboot behavior and dual-SIM resolution are **not** covered by any automated test in this repository.

**Emulator testing is not sufficient.** An emulator has no OEM battery manager, no realistic Doze pressure, no dual SIM, and no vendor call-log provider. **Final validation requires physical devices**, and a device is only signed off after repeated calls over hours — see section 15.

---

## 14. How to build and install

### Requirements

- Android Studio Koala or newer
- JDK 17
- Android SDK with API 35 installed
- A physical Android device with USB debugging enabled

The project uses `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`, JVM target 17.

### Exact Android Studio steps

1. **File → Open**, select the `CallTracker/` directory (the one containing `settings.gradle.kts`), and open it.
2. Android Studio will report a missing Gradle wrapper — this repository is a source scaffold created outside the IDE. Accept the prompt to use a local/wrapper Gradle distribution, or run **File → Sync Project with Gradle Files** and let Studio generate `gradle/wrapper/`.
3. If prompted, install the missing SDK platform (**API 35**) and build tools via the notification link.
4. Wait for **Gradle sync** to finish with no errors.
5. Connect the physical device by USB and accept the *Allow USB debugging* prompt on the phone.
6. Choose the device in the target dropdown next to the Run button.
7. Select the **app** run configuration and press **Run** (Shift+F10). This builds and installs the debug APK.

To produce the APK without installing: **Build → Build Bundle(s)/APK(s) → Build APK(s)**. The result lands at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it manually with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests from the IDE by right-clicking `app/src/test` → **Run 'Tests in ...'**, or from the terminal with `./gradlew :app:testDebugUnitTest` once the wrapper exists.

### Signing

The debug build is signed with the local Android Studio development key. There is **no `signingConfigs` block** in `app/build.gradle.kts` and **no credential of any kind in this repository**. `.gitignore` excludes `*.jks`, `*.keystore`, `key.properties` and `keystore.properties`. Never commit signing material.

---

## 15. Physical-device test procedure

Do not open the app, make one call, and declare success. Work through the phases in order; each phase has an explicit expected result, and the event log is the evidence.

Bring: the test phone, a second phone, a USB cable, and the matrix row for this device.

### Phase 1 — install, permissions, pre-flight

1. Install the debug APK (`adb install -r app/build/outputs/apk/debug/app-debug.apk` or Run from Android Studio) and open it.
2. Read the privacy explanation and continue.
3. Grant the **required** permissions (call log, phone state). Grant or explicitly deny the **optional** ones (contacts, notifications) and note which.
4. On the diagnostics screen, read the **Pre-flight** section top to bottom.
   - Every FAIL must be fixed before continuing; each row has a button that opens the right settings screen.
   - Every WARN must be read and noted. `Battery optimization` and `OEM background policy` WARNs are expected — they are the thing Phases 7–9 measure, not something to silence.
5. Press **Start** if Monitoring is not ACTIVE. Confirm the foreground notification appears.
6. Confirm `ContentObserver registered = YES` and `TelephonyCallback registered = YES`.
7. Record in the matrix: device/model, Android version, default dialer app (confirm CallTracker is **not** it), app version, which permissions were granted, battery-optimization state.

**Expected:** pre-flight shows no FAIL; `SERVICE_STARTED observer=true telephony=true` in the event log, followed by a `SCAN_STARTED reason=CATCH_UP` / `SCAN_FINISHED`.

### Phase 2 — one outgoing answered call

1. Note **Stored** and **Pending sync**.
2. From the phone's normal dialer, call the second phone. Answer it there. Talk for ~40 seconds. Hang up from the test phone.
3. Within a few seconds, **Last call trace** should show, in order:
   `CALL_ROW_EVALUATED` → `CALL_ALLOWED result=ALLOW` → `SIM_RESOLUTION` → `CALL_STORED direction=OUTGOING status=ANSWERED`, and the full log should also show `TELEPHONY_STATE state=OFFHOOK` → `state=IDLE` → `CALL_LOG_CHANGE_DETECTED` → `SCAN_STARTED` before them, and `CALL_SYNC_QUEUED` → `SYNC_STARTED` → `SYNC_SUCCEEDED` after.
4. **Stored** increased by exactly 1. **Synced** increased by 1 (the loopback client). **Pending sync** is back to 0.
5. In **Recent stored calls**: `OUTGOING / ANSWERED`, duration within **±2 s** of the phone's own call-log entry, start time matching.

**Record:** ContentObserver fired? TelephonyCallback fired? direction correct? status correct? duration delta in seconds. SIM `result=` value.

### Phase 3 — one incoming answered call

1. Have the second phone call the test phone. Answer. Talk ~30 s. Hang up.
2. Same event chain, ending `CALL_STORED direction=INCOMING status=ANSWERED`.
3. **Stored** +1, duration within ±2 s.

### Phase 4 — one missed call

1. Have the second phone call the test phone. Do **not** answer. Let it ring out.
2. Expect `TELEPHONY_STATE state=RINGING` → `state=IDLE` → … → `CALL_STORED direction=INCOMING status=MISSED durationS=0`.
3. In Recent stored calls: duration `0` and `ended == started`. That is correct, not a bug — the call never connected.
4. **Stored** +1.

After Phases 2–4: **Stored increased by exactly 3**, three distinct `callLogId` values in Recent stored calls, **Duplicates detected** is whatever it was (it counts re-notifications that were correctly skipped, and may legitimately be > 0), and **no `callLogId` appears twice in Recent stored calls.**

### Phase 5 — repeat, verify zero duplicates

1. Repeat Phases 2–4 twice more, spaced at least a few minutes apart (six more calls).
2. Press **Rescan** twice after the last one.
3. Press **Sync now** twice.

**Expected:** Stored increased by exactly 6 more. Rescan produces `SCAN_STARTED reason=MANUAL` … `SCAN_FINISHED stored=0`. Each `CALL_DUPLICATE` line names a `callLogId` that already exists in Recent stored calls. Sync now produces `WORKER_FINISHED result=NOTHING_PENDING`. **Any `callLogId` appearing twice in Recent stored calls is a duplication FAIL and stops the session.**

### Phase 6 — excluded personal number

1. In **Privacy exclusions**, type the second phone's number in any format and press **Dry-run check**. Expect `SIMULATED … result=ALLOW stored=false` (it is not excluded yet).
2. Press **Exclude**. The normalized number appears in the list; pre-flight `Exclusion list` becomes PASS on Re-run.
3. Press **Dry-run check** again with the number in a *different* format (e.g. with spaces, or without `+91`). Expect `SIMULATED … result=EXCLUDED`.
4. Note **Stored**, **Excluded by privacy filter**, **Pending sync**.
5. Make one call to, and one call from, the second phone (answered, ~10 s each).
6. **Expected per call:** `CALL_ROW_EVALUATED` → `CALL_EXCLUDED_PRIVACY callLogId=… result=EXCLUDED`. No `CALL_STORED`, no `CALL_SYNC_QUEUED`, no `SYNC_STARTED`.
7. **Excluded** +2. **Stored** unchanged. **Pending sync** unchanged. The number is not in Recent stored calls.
8. Press **Rescan** twice. **Excluded** must not change (`CALL_DUPLICATE … reason=already_in_processed_ledger` or `rows=0`).
9. Press **Remove** on the number. Press **Rescan**. **Stored** must still not change — the ledger already decided those rows.

### Phase 7 — screen locked

1. Lock the screen. Wait 30 s.
2. Have the second phone (now removed from the exclusion list, or use a third number) call the test phone. Answer from the locked screen, talk ~20 s, hang up. Leave the screen locked for a further 60 s.
3. Unlock, open the app.

**Expected:** `CALL_STORED` with a timestamp within seconds of hang-up. If instead the only capture is via `WORKER_STARTED worker=CallLogCatchUpWorker` up to 15 min later, the foreground service did not survive screen-off — record that, it is a finding not a failure of the test.

### Phase 8 — removed from recents

1. Open the app, then swipe it out of Recents.
2. Make one outgoing call (~20 s).
3. Re-open the app **immediately** and note whether the call is already stored and whether pre-flight shows `Monitoring service` PASS or the stale-flag FAIL.
4. If not captured: wait 20 minutes without opening the app, then open it.

**Expected / record:** captured immediately (service survived), captured by catch-up within ~15 min (service killed, worker recovered), or not captured (both failed — record `WORKER_*` lines and any `ERROR`).

### Phase 9 — reboot

1. Reboot the device. Do **not** open the app.
2. After boot completes, wait 2 minutes, then make one incoming call (~20 s).
3. Wait a further 20 minutes. Then open the app.

**Expected / record:** a `BOOT_RECEIVED action=… serviceStart=requested` or `serviceStart=<ExceptionName>` line. On Android 15 `serviceStart` is expected to be refused for a `dataSync` service; capture then depends on the catch-up worker. Record whether the call was stored, and whether pre-flight shows Monitoring service PASS after the app was opened.

### Phase 10 — dual SIM (if supported)

1. Confirm pre-flight `SIM / subscriptions` reports 2 active SIMs.
2. Place one outgoing call on SIM 1 and one on SIM 2 (use the dialer's SIM chooser).
3. For each, read the `SIM_RESOLUTION` line: `result=RESOLVED slot=N` means the mapping worked; `result=UNRESOLVED phoneAccountId=…` means it did not — copy the raw `phoneAccountId` value into the matrix.

**Do not build anything on top of SIM resolution until this column is filled for every pilot device.**

### Battery-optimization comparison

Run Phases 7–9 once with battery optimization **on** (the default), then press **Battery settings**, grant the exemption, confirm pre-flight shows `Battery optimization: exempted`, and run them again. Record both.

### Volume

Aim for **15–20 real calls per device spread across several hours**, not back-to-back. Include incoming, outgoing, missed, rejected, and at least one very short (<5 s) and one long (>5 min) call.

---

## 16. OEM test matrix

Fill this out with actual observations. One row per device, per test session.

Target Android 13–15 initially.

| Field | Notes |
|---|---|
| Device / model | e.g. Samsung Galaxy S23, SM-S911B |
| Android version | e.g. Android 14 (API 34) |
| Default dialer | which app; confirm CallTracker is **not** it |
| App version | from the diagnostics screen |
| Permissions granted | call log / phone state / contacts |
| ContentObserver fired? | Y/N — event log `CALL_LOG_CHANGED` |
| TelephonyCallback fired? | Y/N — event log `TELEPHONY OFFHOOK` / `IDLE` |
| Call captured? | Y/N per call |
| Correct direction? | Y/N |
| Correct duration? | within ±2 s of the phone's own log |
| Duplicate created? | **must be N** |
| Privacy filter worked? | Y/N — excluded call never persisted |
| Background capture worked? | Y/N + delay |
| Screen-off capture worked? | Y/N + delay |
| Battery optimization state | OPTIMIZED / EXEMPTED |
| SIM identified? | slot number, or `unknown` + the raw `PHONE_ACCOUNT_ID` |
| Notes / issues | free text |

| OEM | Android | Calls captured | Background | Screen-off | After reboot | Dual-SIM | Duplicates | Notes |
|---|---:|---|---|---|---|---|---|---|
| Samsung | 13 | | | | | | | |
| Samsung | 14 | | | | | | | |
| Samsung | 15 | | | | | | | |
| Xiaomi | 13 | | | | | | | |
| Xiaomi | 14 | | | | | | | |
| Xiaomi | 15 | | | | | | | |
| OnePlus | 13 | | | | | | | |
| OnePlus | 14 | | | | | | | |
| OnePlus | 15 | | | | | | | |
| Pixel | 13 | | | | | | | |
| Pixel | 14 | | | | | | | |
| Pixel | 15 | | | | | | | |

The matrix is a guide, not a promise that every listed OEM/version combination is equally available or representative of every device model.

### What counts as PASS / FAIL

**Do not mark a device PASS based on one successful call.**

A device is **PASS** only when, across at least 15 real calls over several hours:

- every completed call produced exactly one local record,
- **zero** duplicate records were created,
- direction and status were correct for every call,
- duration was within ±2 s of the phone's own call log for every connected call,
- calls were captured with the app backgrounded and with the screen off,
- excluded numbers never entered `calls` or the pending queue, and re-scanning never re-counted them,
- reboot behavior is understood and recorded (it does not have to succeed; it has to be *known*),
- the SIM column is filled in with a real answer, including `unknown`.

A device is **FAIL** if any completed call was missed with the app merely backgrounded, if any duplicate was created, if an excluded number was persisted, or if capture required opening the app.

A device is **CONDITIONAL** if capture works only after a manufacturer-specific opt-in, or only with the battery-optimization exemption granted. Record exactly which setting was required — a conditional pass is a deployment requirement, not a failure.

---

## 17. Known POC limitations

These are intentional and must not be mistaken for production-ready features.

### Phone-number normalization

Hand-rolled and India-defaulted (`DEFAULT_COUNTRY_CODE = "+91"`). Good enough to make de-duplication and privacy matching consistent; **not** good enough for real employee data. Replace with libphonenumber before a pilot.

### Withheld caller ids

A call with no caller id is normalized to the sentinel `UNKNOWN` and is **stored**, flagged as `ALLOW_UNRESOLVED_NUMBER`, because proving the row was captured is the experiment. It cannot be checked against the exclusion list. If a deployment needs withheld numbers dropped instead, that is a one-line change in `PrivacyRules` — and a decision to make deliberately.

### Dual-SIM resolution

Unsolved. See section 10.

### Android 15 foreground-service time limit

`dataSync` is capped at ~6 h/24 h. See section 9.

### Reboot on Android 15

`dataSync` foreground services are restricted from `BOOT_COMPLETED`. Capture after reboot may depend entirely on the 15-minute catch-up worker until the app is next opened.

### Capture latency after an OEM kill

Up to ~15 minutes (WorkManager's periodic floor).

### `_ID` reuse after a call-log wipe

See section 8.

### Exclusion-list UI

Minimal by design. See section 6.

### Room migrations

Schema v1 → v2 uses `fallbackToDestructiveMigration()`. Nothing has shipped and POC capture data is disposable. **Write a real `Migration` before any pilot install.**

### Backend

None. See section 12.

### CRM

Not implemented: lead matching, contacts UI, call outcomes, follow-ups, conversion tracking.

### Call recording

Not implemented, no microphone permission requested, and intentionally excluded from V1 scope.

### iOS

Not part of this POC.

### Production distribution

The APK is not packaged as a production enterprise deployment. Select and validate the final MDM/private-distribution mechanism separately.

### Android Studio project files

This repository is a source scaffold created outside Android Studio. It intentionally does not include generated project artifacts such as the Gradle wrapper, launcher icons and a complete resource setup.

---

## 18. What counts as a technical GO?

The POC should not be considered successful because "the call log can be read."

A technical GO means that, on the target pilot devices, all of the following are demonstrated with repeatable tests:

- [ ] Required permissions can be granted in the intended deployment model.
- [ ] Incoming calls are captured.
- [ ] Outgoing calls are captured.
- [ ] Missed calls are captured.
- [ ] Direction and status are correct.
- [ ] Duration is correct within ±2 s.
- [ ] New call-log entries trigger processing without repeatedly opening the app.
- [ ] Capture survives normal background use.
- [ ] Capture survives screen-off periods.
- [ ] Capture survives the target OEM's battery-management behavior after proper configuration.
- [ ] Reboot behavior is understood.
- [ ] Duplicate records are not created.
- [ ] Excluded numbers never enter the pending-sync queue.
- [ ] Dual-SIM behavior is understood for the pilot devices.

If any of the above fails consistently on the devices the customer actually wants to deploy, stop and solve that problem before building the SaaS dashboard.

Again: **this POC is for technical feasibility testing and is not legal advice or a guarantee of Google Play approval.**

---

## 19. Track B — customer validation

Technical feasibility is **not** product-market fit.

Run customer validation in parallel with the POC.

Talk to at least 3–5 sales/operations managers and ask:

1. How do you currently track employee calls?
2. Do you use Callyzer or another call-analytics product?
3. What do you actually look at every day?
4. What is frustrating about your current solution?
5. How do you verify that assigned leads were actually contacted?
6. Do you need call activity connected directly to CRM leads?
7. How important are missed calls and follow-up reminders?
8. Are employee phones company-owned, company-managed, or BYOD?
9. Would employees accept a company app that tracks business-call metadata?
10. What should happen to personal calls?
11. What reports would you pay for?
12. What do you currently pay for the problem?
13. What would make you switch from the product you use today?
14. Would you run a two-week pilot with five employees?
15. What would make you say “this is worth paying for” after the pilot?

Do not optimize the conversation for compliments. Look for evidence of a painful problem and willingness to run a pilot/pay.

---

## 20. Product validation gates

The project has two independent gates.

### Gate 1 — technical

```text
Android POC
   ↓
Real OEM testing
   ↓
Reliable call capture?
```

If **no**, redesign the collection/distribution approach.

If **yes**, continue.

### Gate 2 — commercial

```text
3–5 manager conversations
          ↓
Pain confirmed?
          ↓
Clear reason to switch?
          ↓
Pilot company?
          ↓
Willingness to pay?
```

If there is no compelling customer problem, do not build six weeks of features just because the technology works.

---

## 21. Planned V1 after both gates pass

### Android

```text
✓ Authentication
✓ Device registration
✓ Permission onboarding
✓ CallLog observation
✓ TelephonyCallback
✓ Incoming/outgoing/missed detection
✓ Duration
✓ Dual-SIM support for validated devices
✓ Contact matching
✓ Room local queue
✓ Offline retry
✓ Background synchronization
✓ Privacy exclusions
✓ Device heartbeat
```

### Backend

```text
✓ Authentication
✓ Multi-tenancy
✓ Admin / Manager / Employee roles
✓ Employees
✓ Devices
✓ Calls
✓ Contacts
✓ Leads
✓ Call outcomes
✓ Follow-ups
✓ Deduplication
✓ Audit logs
```

### Web

```text
✓ Login
✓ Dashboard
✓ Employees
✓ Devices
✓ Call history
✓ Call details
✓ Date filters
✓ Employee analytics
✓ Lead activity
✓ Follow-ups
```

### Explicitly out of V1

```text
✗ Call recording
✗ AI analysis
✗ iOS
✗ WhatsApp integration
✗ Complex billing
✗ Campaign engine
✗ Large CRM integration catalog
✗ Microservices
✗ Kubernetes
✗ Redis unless an actual workload requires it
```

---

## 22. Planned V1 backend schema

The initial PostgreSQL schema is intentionally small:

```text
organizations
users
employees
devices
calls
contacts
leads
call_outcomes
followups
audit_logs
```

All organization-owned records should carry `organization_id` for tenant isolation.

The core call relationship is:

```text
Organization
    │
    ├── Employee
    │      │
    │      └── Device
    │
    ├── Lead
    │      │
    │      ├── Calls
    │      └── Followups
    │
    └── Calls
```

Do not add subscriptions, payments, campaigns, webhooks, AI-analysis tables, etc. until an actual product requirement exists.

---

## 23. Planned call-sync API

The first backend endpoint is expected to be:

```http
POST /api/v1/calls/sync
```

Example payload:

```json
{
  "device_id": "DEVICE_UUID",
  "sync_id": "SYNC_UUID",
  "calls": [
    {
      "device_call_id": "12345",
      "phone_number": "+9198XXXXXXXX",
      "direction": "OUTGOING",
      "duration_seconds": 492,
      "started_at": "2026-09-04T11:32:10+05:30"
    }
  ]
}
```

The server should make the operation idempotent so a retry cannot create duplicate calls.

A typical response:

```json
{
  "success": true,
  "accepted": 1,
  "duplicates": 0,
  "failed": 0
}
```

---

## 24. Development sequence after a technical + commercial GO

```text
Phase 0
────────────────────────
Customer interviews + Android POC

Phase 1
────────────────────────
Android capture reliability

Phase 2
────────────────────────
NestJS + PostgreSQL + authentication

Phase 3
────────────────────────
Device registration + call sync

Phase 4
────────────────────────
React/Vite dashboard

Phase 5
────────────────────────
CRM: leads + outcomes + follow-ups

Phase 6
────────────────────────
One real company pilot
5 employees / 5 phones / ~2 weeks

Phase 7
────────────────────────
Fix reliability + privacy + UX
based on real pilot data

Phase 8
────────────────────────
Only then consider paid SaaS rollout
```

The phases are effort stages, **not six guaranteed calendar weeks**. This is a side project alongside full-time work and should be paced accordingly.

---

## 25. Product principle

The product should not be sold as:

> “We collect all your employees' calls.”

The stronger product proposition is:

> **“See whether your sales team actually contacted the leads you assigned, connect call activity to those leads, and make follow-up measurable.”**

The eventual flow is:

```text
Lead assigned
      ↓
Employee calls
      ↓
Call automatically captured
      ↓
Call matched to lead
      ↓
Outcome recorded
      ↓
Follow-up created
      ↓
Manager sees activity
      ↓
Conversion measured
```

That is the product we are validating — not merely a call-log viewer.

---

## 26. Current status

**Technical POC:** capture pipeline implemented (two signals, dedupe, privacy filter, Room, diagnostics, pre-flight). `./gradlew :app:testDebugUnitTest` (88 tests) and `./gradlew :app:assembleDebug` both pass on the development machine. **Not yet run on any physical device; nothing about real-device behaviour is validated.**

**Proven so far:** the code compiles, the unit tests pass, a debug APK builds, and the diagnostic instrumentation exists.

**Not yet proven:** that the ContentObserver fires reliably on physical devices; that TelephonyCallback behaves on every target device; background capture; screen-off capture; reboot recovery; OEM battery behaviour; dual-SIM mapping; ±2 s duration accuracy on real devices; that no duplicates occur under real-world event timing.

**Production readiness:** not claimed, and not close.

**Backend:** not started.

**Web dashboard:** not started.

**CRM:** not started.

**Customer validation:** must run in parallel with hardware testing.

### First milestone

> **One real device → one real call → one correctly captured call-log record → one correctly privacy-filtered record → repeat reliably in the background.**

### First business milestone

> **3–5 real sales managers → a specific painful problem → at least one company willing to run a five-person pilot.**

Only when both milestones are positive should serious MVP development begin.

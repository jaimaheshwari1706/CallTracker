# CallTracker — Android Call-Capture POC

CallTracker is the **Track A technical-validation scaffold** for a Callyzer-like B2B call analytics product.

The goal of this repository is deliberately narrow:

> **Prove that an Android app, deployed privately to company-managed/company-owned devices, can reliably detect and read completed call-log entries in the background, filter personal numbers on-device, and queue the remaining business-call metadata for synchronization.**

This repository is a **source scaffold / proof of concept, not a production application and not a prebuilt APK**.

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

---

## 2. What this POC is testing

There are three separate validation layers:

### Layer A — Android OS

Can the application obtain the required permissions and read the call-log provider on the target Android versions?

### Layer B — OEM/device behavior

Does the capture mechanism remain reliable when the app is backgrounded, the screen is off, the device is idle, and OEM battery-management policies are active?

### Layer C — Distribution/deployment

Can the app be deployed and managed on the target company-owned/company-managed devices through the chosen private distribution channel?

**Do not treat any one successful emulator run as proof of production feasibility.** Real physical devices are required.

> Important: private distribution does not automatically make every use of restricted Android data appropriate for every deployment channel or jurisdiction. Validate the final permission/distribution model against current Android behavior, Google Play/enterprise requirements if Play-managed distribution is used, device-management policies, and applicable privacy/legal requirements before a real customer rollout.

---

## 3. Current POC flow

The service intentionally uses two signals:

```text
                 Phone call
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
 TelephonyCallback       ContentObserver
          │                   │
     call state          CallLog changed
          │                   │
          └─────────┬─────────┘
                    ▼
          processNewCallLogEntries()
                    │
                    ▼
             Android CallLog
                    │
                    ▼
              Deduplication
                    │
                    ▼
              PrivacyFilter
                    │
              ┌─────┴─────┐
              │           │
           excluded     allowed
              │           │
           discard        ▼
                    Room local DB
                          │
                          ▼
                    WorkManager
                          │
                    network available
                          │
                          ▼
                    Sync worker
```

### Why two signals?

`TelephonyCallback` / the pre-Android-12 `PhoneStateListener` path provides a useful call-state nudge, particularly when a call transitions to `IDLE`.

`ContentObserver` watches `CallLog.Calls.CONTENT_URI` and is treated as the source-of-truth trigger that the call-log provider changed.

The implementation intentionally does **not** assume that `CALL_STATE_IDLE` and the call-log write happen at exactly the same time. The service waits briefly after `IDLE` and also reacts directly to the provider change.

---

## 4. Repository structure

```text
CallTracker/
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/calltracker/app/
            ├── MainActivity.kt
            ├── database/
            │   ├── AppDatabase.kt
            │   ├── Dao.kt
            │   └── Entities.kt
            ├── call/
            │   ├── BootReceiver.kt
            │   ├── CallMonitorService.kt
            │   └── PrivacyFilter.kt
            └── sync/
                └── CallSyncWorker.kt
```

### Important files

#### `CallMonitorService.kt`

The core capture logic:

- starts a foreground service
- registers `TelephonyCallback` on API 31+
- uses `PhoneStateListener` on older supported APIs
- registers a `ContentObserver` on `CallLog.Calls.CONTENT_URI`
- reads new call-log rows
- deduplicates using Android `CallLog._ID`
- passes numbers through `PrivacyFilter`
- writes allowed calls to Room
- schedules background synchronization

#### `PrivacyFilter.kt`

Applies the privacy exclusion list **before the call is persisted to the pending-sync queue**.

This is intentionally device-side filtering:

```text
CallLog
   ↓
PrivacyFilter
   ↓
Allowed → local pending queue
Excluded → never enters sync queue
```

#### `MainActivity.kt`

The POC UI deliberately follows this order:

```text
Privacy explanation
        ↓
Permission request
        ↓
Monitoring status
```

It also exposes basic captured/synced counters and requests a battery-optimization exemption for testing.

#### `CallSyncWorker.kt`

The backend does not exist yet. The worker currently exercises the local pending → synced flow rather than making a real network call.

The eventual API contract is expected to be:

```text
POST /api/v1/calls/sync
```

---

## 5. Permissions used by the POC

The manifest currently requests:

```xml
READ_CALL_LOG
READ_PHONE_STATE
READ_CONTACTS
INTERNET
ACCESS_NETWORK_STATE
FOREGROUND_SERVICE
FOREGROUND_SERVICE_DATA_SYNC
POST_NOTIFICATIONS
RECEIVE_BOOT_COMPLETED
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```

The call-log, phone-state and contacts permissions are requested at runtime.

There is intentionally **no default-dialer / `ROLE_DIALER` implementation in this POC**. The current hypothesis is that the product should first be tested as a privately distributed enterprise application on appropriate company-managed/company-owned devices rather than immediately taking on the complexity of replacing the native dialer.

That hypothesis is a **validation target, not a guarantee**. Confirm the behavior and distribution requirements on the exact Android/device-management environment selected for the pilot.

---

## 6. Privacy design

Call logs are not inherently “business calls.” A device may contain calls to family members, doctors, banks, friends, etc. The product therefore must not be designed around indiscriminately uploading every call.

### POC behavior

The onboarding screen explains:

- what call metadata is collected
- why it is collected
- that call audio is not collected by this POC
- that personal numbers can be excluded

Excluded numbers are filtered locally before entering the pending-sync queue.

### Current limitation

The DAO can add/remove excluded numbers, but the POC does **not yet expose an employee/admin UI for managing them**.

Before a real pilot, add a clear settings screen for:

```text
Privacy / Personal numbers

[ + Add personal number ]

Excluded numbers
----------------
+91XXXXXXXXXX
+91XXXXXXXXXX
```

The final production product should also have a proper privacy notice, retention/deletion policy, access controls, auditability, and a legal/privacy review appropriate to the customer and deployment model. The onboarding copy in this POC is **not legal advice and is not a complete consent mechanism by itself**.

---

## 7. Android local data model

The POC uses Room with these local entities:

### `calls`

```text
id
 deviceCallId
phoneNumber
normalizedNumber
direction
startedAtEpochMs
durationSeconds
simSlot
syncStatus
capturedAtEpochMs
```

`deviceCallId` maps to the Android call-log `_ID` and is used as the current deduplication key.

### `excluded_numbers`

```text
normalizedNumber
label
addedAtEpochMs
```

These records are used by the privacy filter and are never sent by the current sync worker.

---

## 8. Known POC limitations

These are intentional and should not be mistaken for production-ready features.

### Dual-SIM resolution

`resolveSimSlot()` currently returns `null`.

The POC deliberately leaves this unresolved because `PHONE_ACCOUNT_ID` / subscription mapping behavior needs to be tested on actual dual-SIM devices and OEM builds.

**Do not build business logic that assumes SIM resolution works until the test matrix proves it.**

### Backend

There is no real backend connection yet. The sync worker currently marks local records as synced to exercise the queue/retry path.

### CRM

Not implemented:

- lead matching
- contacts UI
- call outcomes
- follow-ups
- conversion tracking

### Call recording

Not implemented and intentionally excluded from V1 scope.

### iOS

Not part of this POC.

### Production distribution

The APK is not packaged as a production enterprise deployment. Select and validate the final MDM/private-distribution mechanism separately.

### Android Studio project files

This repository is a source scaffold created outside Android Studio. It intentionally does not include generated project artifacts such as the Gradle wrapper, launcher icons and complete resource setup.

---

## 9. Local setup

### Requirements

- Android Studio Koala or newer
- JDK 17
- Android SDK with API 35 installed
- A physical Android device
- USB debugging enabled

The project currently uses:

```text
compileSdk = 35
targetSdk  = 35
minSdk     = 26
JVM        = 17
```

### Open the project

Open the `CallTracker/` directory in Android Studio.

Because this is a source scaffold, Android Studio may need to generate/restore project files and resources before the first build.

### Install on a real device

For the POC, use a physical device. Do not rely on an emulator for OEM/background testing.

A direct development installation can be performed with ADB after the project builds:

```bash
adb install <path-to-debug-apk>
```

The exact APK path depends on the Android Studio/Gradle setup generated locally.

---

## 10. Test protocol

Do not just open the app, make one call, and declare success.

For every physical device:

1. Install the app.
2. Open it.
3. Read the privacy explanation.
4. Grant the required permissions.
5. Accept the battery-optimization prompt where appropriate.
6. Confirm the foreground notification appears.
7. Put the app in the background.
8. Turn the screen off.
9. Make and receive real calls at different times.
10. Include incoming, outgoing, missed and rejected calls where supported.
11. Leave the device idle for extended periods.
12. Reboot the device and repeat background tests.
13. Inspect the local captured/synced counters.
14. Check for duplicate call records.
15. Test excluded personal numbers.
16. Test a dual-SIM call where applicable.

Aim for approximately **15–20 real calls per device across several hours**, rather than making all calls back-to-back.

---

## 11. OEM test matrix

Fill this out with actual observations.

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
| Pixel | 14 | | | | | | | |
| Pixel | 15 | | | | | | | |
| Pixel | 16 | | | | | | | |

The matrix is a guide, not a promise that every listed OEM/version combination is equally available or representative of every device model.

---

## 12. What counts as a technical GO?

The POC should not be considered successful because “the call log can be read.”

A technical GO means that, on the target pilot devices, we can demonstrate all of the following with repeatable tests:

- [ ] Required permissions can be granted in the intended deployment model.
- [ ] Incoming calls are captured.
- [ ] Outgoing calls are captured.
- [ ] Missed calls are captured.
- [ ] Duration is correct within an acceptable tolerance.
- [ ] New call-log entries trigger processing without repeatedly opening the app.
- [ ] Capture survives normal background use.
- [ ] Capture survives screen-off periods.
- [ ] Capture survives the target OEM's battery-management behavior after proper configuration.
- [ ] Reboot behavior is understood.
- [ ] Duplicate records are not created.
- [ ] Excluded numbers never enter the pending-sync queue.
- [ ] Dual-SIM behavior is understood for the pilot devices.

If any of the above fails consistently on the devices the customer actually wants to deploy, stop and solve that problem before building the SaaS dashboard.

---

## 13. Track B — customer validation

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

## 14. Product validation gates

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

## 15. Planned V1 after both gates pass

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

## 16. Planned V1 backend schema

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

## 17. Planned call-sync API

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

## 18. Development sequence after a technical + commercial GO

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

## 19. Product principle

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

## 20. Current status

**Technical POC:** scaffolded, not yet validated on the full hardware matrix.

**Backend:** not started.

**Web dashboard:** not started.

**CRM:** not started.

**Customer validation:** must run in parallel with hardware testing.

**Production readiness:** not claimed.

### First milestone

> **One real device → one real call → one correctly captured call-log record → one correctly privacy-filtered record → repeat reliably in the background.**

### First business milestone

> **3–5 real sales managers → a specific painful problem → at least one company willing to run a five-person pilot.**

Only when both milestones are positive should serious MVP development begin.

# Firebase Database Schema

## Overview

Master2 uses Firebase Realtime Database for all parent-child communication and data storage.

---

## Schema

### `/users/{userId}`

Stores user profile information.

```json
{
  "name": "John Doe",
  "email": "john@example.com",
  "phone": "1234567890",
  "userType": "parent",
  "userId": "abc123",
  "createdAt": 1704700000000
}
```

---

### `/qr_share_codes/{shareKey}`

QR code pairing data.

```json
{
  "parentDeviceId": "parent_device_123",
  "parentName": "Dad's Phone",
  "timestamp": 1704700000000,
  "expiresAt": 1704786400000,
  "scanned_devices": {
    "child_device_456": {
      "deviceName": "Kid's Tablet",
      "appCount": 45,
      "connectedAt": 1704700500000,
      "apps": {
        "com_youtube": {
          "appName": "YouTube",
          "packageName": "com.youtube",
          "iconBase64": "..."
        }
      }
    }
  }
}
```

---

### `/parent_devices/{parentDeviceId}`

Parent-child device mapping.

```json
{
  "parentEmail": "parent@email.com",
  "children": {
    "child_device_456": {
      "deviceName": "Kid's Tablet",
      "connectedAt": 1704700500000,
      "lastSeen": 1704750000000
    }
  }
}
```

---

### `/device_status/{childDeviceId}`

Child device connection status.

```json
{
  "connected": true,
  "parentDeviceId": "parent_device_123",
  "parentName": "Dad's Phone",
  "lastSeen": 1704750000000,
  "deviceName": "Kid's Tablet"
}
```

---

### `/blocked_apps/{childDeviceId}`

Blocked apps list.

```json
{
  "packages": {
    "com_youtube": {
      "packageName": "com.youtube",
      "appName": "YouTube",
      "blockedAt": 1704700000000,
      "blockedBy": "parent"
    }
  },
  "focusModeActive": false
}
```

---

### `/smart_timers/{childDeviceId}`

Timer configuration.

```json
{
  "active": true,
  "dailyDurationMs": 7200000,
  "remainingTimeMs": 3600000,
  "startedAt": 1704700000000,
  "lastUpdated": 1704710000000,
  "lastResetDate": "2026-01-07",
  "assignedApps": {
    "com_youtube": true,
    "com_instagram": true
  },
  "expired": false,
  "pausedByParent": false
}
```

---

### `/daily_usage_limits/{childDeviceId}`

Per-app daily limits.

```json
{
  "apps": {
    "com_youtube": {
      "packageName": "com.youtube",
      "appName": "YouTube",
      "dailyLimitMs": 3600000,
      "usedTodayMs": 1800000,
      "lastResetDate": "2026-01-07",
      "blocked": false
    }
  }
}
```

---

### `/susage_stats/{childDeviceId}/{date}`

Daily usage statistics.

```json
{
  "date": "2026-01-07",
  "totalScreenTimeMs": 14400000,
  "apps": {
    "com_youtube": {
      "packageName": "com.youtube",
      "appName": "YouTube",
      "usageTimeMs": 3600000,
      "category": "Entertainment",
      "iconBase64": "..."
    }
  }
}
```

---

### `/logout_commands/{childDeviceId}`

Remote logout commands.

```json
{
  "logout": true,
  "timestamp": 1704750000000,
  "reason": "Parent initiated disconnect"
}
```

---

## Security Rules (Recommended)

```json
{
  "rules": {
    "users": {
      "$userId": {
        ".read": "$userId === auth.uid",
        ".write": "$userId === auth.uid"
      }
    },
    "parent_devices": {
      "$deviceId": {
        ".read": true,
        ".write": true
      }
    },
    "blocked_apps": {
      "$childDeviceId": {
        ".read": true,
        ".write": true
      }
    }
  }
}
```

> ⚠️ Note: The above rules are permissive for development. Implement proper authentication-based rules for production.

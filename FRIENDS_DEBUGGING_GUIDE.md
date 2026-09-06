# Friends System Debugging Guide

## 🐛 Issue: Friend Request Not Working Between Two Chrome Instances

### Symptoms
- User A and User B both open Moodify in different Chrome instances (same machine, different tabs/windows)
- User A sends friend request to User B
- User B accepts the request
- **Problem:** One user can't see the friend or send messages

### Root Causes & Solutions

---

## Issue #1: Both Using Same Peer ID ❌

**Problem:** If both Chrome instances are on `localhost:8080`, they might be trying to use the SAME localStorage, which means they share the same Peer ID!

**Check:**
1. Open Chrome DevTools (F12) in both instances
2. Go to Application > Local Storage > `http://localhost:8080`
3. Look for key `moodify_my_peer_id`
4. **If both have the SAME value = BUG!**

**Solution:**
```javascript
// Open browser console and run:
localStorage.clear();
location.reload();
```

**Better Solution:** Use **Incognito Mode**
- Open instance 1 in **normal Chrome window**
- Open instance 2 in **Incognito window** (Ctrl+Shift+N)
- This ensures separate localStorage and separate Peer IDs

**Best Solution:** Use different browsers
- Instance 1: Chrome
- Instance 2: Firefox or Edge
- Completely isolated

---

## Issue #2: PeerJS Server Connection Failed ⚠️

**Problem:** Can't connect to PeerJS cloud server

**Check Console:**
```
Look for these errors in console:
- "PeerJS: ERROR WebSocket closed"
- "Could not connect to peer"
- "peer-unavailable"
```

**Solution 1:** Wait and retry
- PeerJS free cloud server can be slow/busy
- Wait 10-20 seconds for peer to initialize
- Look for: `🎵 Friends P2P initialized: moodify_xxx`

**Solution 2:** Use custom PeerJS server
Update `src/hooks/useFriends.ts` line 192:
```typescript
const peer = new Peer(peerId, {
  host: 'localhost',  // Your own PeerJS server
  port: 9000,
  path: '/myapp',
  config: {
    iceServers: [
      { urls: 'stun:stun.l.google.com:19302' },
      { urls: 'stun:global.stun.twilio.com:3478' },
    ],
  },
});
```

---

## Issue #3: React Strict Mode Double Init 🔄

**Problem:** React 18 in dev mode mounts components twice, causing Peer ID conflicts

**Check Console:**
```
Look for:
- "⚠️ Peer ID 'moodify_xxx' is taken"
- "ID taken" errors
- Multiple "🔌 Connecting with peer ID" logs
```

**Temporary Workaround:**
In `src/main.tsx`, remove `<React.StrictMode>`:
```typescript
// BEFORE:
<React.StrictMode>
  <App />
</React.StrictMode>

// AFTER (for testing):
<App />
```

**Note:** This is already handled in the code with global instance tracking, but may still have edge cases.

---

## Issue #4: Friend Code Format Wrong 📝

**Problem:** Friend codes must be EXACT format

**Correct Format:**
```
ABC-DEF-123
- 9 characters + 2 hyphens
- Uppercase letters and numbers only
- Exactly 3-3-3 pattern
```

**Common Mistakes:**
- ❌ `abc-def-123` (lowercase)
- ❌ `ABCDEF123` (no hyphens)
- ❌ `ABC-DE-1234` (wrong pattern)
- ❌ Extra spaces

**Check:**
1. User A: Click "Copy" button next to their friend code
2. Send to User B via external method (WhatsApp, Discord, etc.)
3. User B: Paste EXACT code (including hyphens)

---

## Issue #5: Firewall/Network Blocking WebRTC 🔥

**Problem:** Corporate firewall or antivirus blocks P2P connections

**Check:**
```
Console errors:
- "network error"
- "ice failed"
- Connection stays "connecting" forever
```

**Solution:**
1. **Try different network:**
   - Mobile hotspot
   - Home Wi-Fi (not corporate)
   - VPN (some VPNs block WebRTC)

2. **Check browser permissions:**
   - Settings > Privacy > Site Settings
   - Allow WebRTC connections

3. **Test ICE connectivity:**
   Open: https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/
   Should show "completed" status

---

## Issue #6: Browser Tab Backgrounded 🌙

**Problem:** Chrome throttles background tabs

**Solution:**
- Keep BOTH Chrome tabs/windows visible
- Don't minimize or switch away during connection
- Pin the tabs to prevent closing

---

## 🔍 Step-by-Step Debugging

### Step 1: Check Peer Initialization
**In BOTH instances**, open Console and look for:
```
✅ GOOD:
"🎵 Friends P2P initialized: moodify_abc123xyz"
"Your friend code: ABC-123-XYZ"

❌ BAD:
"⚠️ Peer ID is taken"
"Error: Could not connect to peer server"
```

### Step 2: Verify Friend Codes Are Different
**Instance 1:**
```javascript
// In console:
localStorage.getItem('moodify_my_peer_id')
// Should output: "moodify_abc123def"
```

**Instance 2:**
```javascript
// In console:
localStorage.getItem('moodify_my_peer_id')
// Should output: "moodify_xyz789ghi"  ← DIFFERENT!
```

**If they're the SAME → Clear localStorage in one instance!**

### Step 3: Test Friend Request Flow

**User A (Requester):**
1. Copy friend code (e.g., `XYZ-789-GHI`)
2. Click "Add Friend"
3. Paste User B's code
4. Click "Send Friend Request"
5. **Expected Console:**
   ```
   📤 Sending friend request to: moodify_xyz789ghi
   ✅ Friend request connection opened
   ✅ Friend Request Sent
   ```

**User B (Accepter):**
1. Should see notification or friend request in "Requests" tab
2. **Expected Console:**
   ```
   📥 Incoming connection from: moodify_abc123def
   👋 Incoming friend request from: [Name]
   ✅ Adding friend request to list
   ```
3. Click "Accept"
4. **Expected Console:**
   ```
   🤝 Accepting friend request from: [Name]
   ✅ Friend acceptance connection opened
   ✅ Friend Added
   ```

**User A (After Acceptance):**
Should receive:
```
🎉 Friend accepted our request: [Name]
✅ Adding new friend
✅ Friend Added!
```

### Step 4: Verify Both Have Each Other

**BOTH instances:**
```javascript
// In console:
JSON.parse(localStorage.getItem('moodify_friends'))
// Should show an array with 1 friend object
```

### Step 5: Test Messaging

**User A:**
1. Click "Chat" button
2. Type message
3. Press Enter
4. **Expected Console:**
   ```
   ✅ Message sent to [Friend Name]
   ```

**User B:**
Should receive message with console:
```
📨 Received data from [peerId] type: message
```

---

## 🛠️ Emergency Fixes

### Fix 1: Complete Reset
```javascript
// In BOTH browser consoles:
localStorage.clear();
location.reload();
// Start friend request process from scratch
```

### Fix 2: Force Reconnection
```javascript
// In console of user who can't see friend:
location.reload();
// The auto-reconnect logic should kick in
```

### Fix 3: Check Friend List Manually
```javascript
// In console:
const friends = JSON.parse(localStorage.getItem('moodify_friends') || '[]');
console.table(friends);
// Shows all friends with their status
```

### Fix 4: Manual Friend Addition (Developer Only)
```javascript
// ONLY FOR TESTING - adds friend manually
const friendCode = 'ABC-123-XYZ'; // Friend's code
const friendPeerId = `moodify_${friendCode.replace(/-/g, '').toLowerCase()}`;

const friends = JSON.parse(localStorage.getItem('moodify_friends') || '[]');
friends.push({
  id: friendPeerId,
  peerId: friendPeerId,
  peerCode: friendCode,
  displayName: 'Test Friend',
  avatar: 'test123',
  addedAt: Date.now(),
  lastSeen: Date.now(),
  isOnline: false
});
localStorage.setItem('moodify_friends', JSON.stringify(friends));
location.reload();
```

---

## 📊 Expected Console Flow (Success Case)

### User A (Sends Request)
```
1. 🔌 Connecting with peer ID: moodify_abc123def
2. 🎵 Friends P2P initialized: moodify_abc123def
3. ✅ Friends System Ready - Your friend code: ABC-123-DEF
4. 📤 Sending friend request to: moodify_xyz789ghi
5. ✅ Friend request connection opened, sending request...
6. ✅ Friend Request Sent
...
[Wait for User B to accept]
...
7. 📨 Received data from moodify_xyz789ghi type: friend_accept
8. 🎉 Friend accepted our request: UserB
9. ✅ Adding new friend
10. ✅ Friend Added! (toast notification)
```

### User B (Receives & Accepts)
```
1. 🔌 Connecting with peer ID: moodify_xyz789ghi
2. 🎵 Friends P2P initialized: moodify_xyz789ghi
3. ✅ Friends System Ready - Your friend code: XYZ-789-GHI
4. 📥 Incoming connection from: moodify_abc123def
5. ✅ Connection opened with: moodify_abc123def
6. 📨 Received data from moodify_abc123def type: friend_request
7. 👋 Incoming friend request from: UserA
8. ✅ Adding friend request to list
9. [User clicks Accept]
10. 🤝 Accepting friend request from: UserA
11. 🔌 Reconnecting to friend: UserA (moodify_abc123def)
12. ✅ Friend acceptance connection opened
13. ✅ Adding friend to list: UserA
14. 🗑️ Removing friend request: moodify_abc123def
15. ✅ Friend Added (toast notification)
```

---

## 🎯 Most Common Solution

**90% of cases:** Use Incognito mode!

**User A:** Normal Chrome window
```bash
http://localhost:8080
```

**User B:** Incognito window (Ctrl+Shift+N)
```bash
http://localhost:8080
```

This ensures:
- ✅ Separate localStorage
- ✅ Separate Peer IDs
- ✅ No conflicts
- ✅ Clean state

---

## 📞 Still Not Working?

Check these in order:
1. ✅ Both instances have DIFFERENT Peer IDs?
2. ✅ Console shows "Friends P2P initialized" in BOTH?
3. ✅ Friend codes are correctly formatted (XXX-XXX-XXX)?
4. ✅ No firewall/VPN blocking WebRTC?
5. ✅ Both tabs are visible (not background)?
6. ✅ Tried incognito mode?
7. ✅ Waited 20+ seconds after page load?

If YES to all → Check browser console for specific error messages and search in code.


# Moodify Friends & Sharing System Documentation

## 📋 Table of Contents
- [Overview](#overview)
- [Architecture](#architecture)
- [Features](#features)
- [Usage Guide](#usage-guide)
- [API Reference](#api-reference)
- [Privacy & Security](#privacy--security)
- [Troubleshooting](#troubleshooting)

---

## 🎯 Overview

Moodify now includes two major privacy-first features:
1. **Password-Protected Playlist Sharing** - Share playlists via URL with optional encryption
2. **P2P Friends & Chat System** - Connect with friends and chat directly without a server

Both features maintain Moodify's core principle: **No backend required, privacy preserved**.

---

## 🏗️ Architecture

### Technology Stack
- **PeerJS** - WebRTC wrapper for P2P connections
- **Web Crypto API** - Client-side AES-256 encryption
- **localStorage** - Persistent data storage
- **LZ-String** - URL compression

### Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│                   CLIENT-SIDE ONLY                          │
│  ┌──────────────┐      ┌──────────────┐                    │
│  │  localStorage │◄────►│  React State │                    │
│  └──────────────┘      └──────────────┘                    │
│         ▲                      ▲                            │
│         │                      │                            │
│         ▼                      ▼                            │
│  ┌──────────────┐      ┌──────────────┐                    │
│  │  Encryption  │      │    PeerJS    │                    │
│  │  (AES-256)   │      │  (WebRTC)    │                    │
│  └──────────────┘      └──────────────┘                    │
│         ▲                      ▲                            │
│         │                      │                            │
│         └──────────┬───────────┘                            │
│                    ▼                                        │
│            ┌──────────────┐                                 │
│            │  Public URL  │                                 │
│            │  or P2P Conn │                                 │
│            └──────────────┘                                 │
└─────────────────────────────────────────────────────────────┘
```

---

## ✨ Features

### 1. Password-Protected Playlist Sharing

#### Public Sharing (Default)
- ✅ Data encoded in URL (no server storage)
- ✅ Works immediately on any platform
- ✅ Limited to 20 tracks per playlist
- ✅ ~500-2000 characters per URL

#### Protected Sharing (with Password)
- 🔒 AES-256-GCM encryption
- 🔒 PBKDF2 key derivation (100k iterations)
- 🔒 Unique salt + IV per share
- 🔒 Password never leaves your device
- 🔒 Recipients need password to decrypt

**URL Format:**
```
https://yourdomain.com/#/shared/{base64-encoded-data}

Public:    /shared/N4IghgDg...
Encrypted: /shared/ENC:N4IghgDg...
```

### 2. P2P Friends System

#### Friend Management
- 👥 Add friends via unique codes (e.g., `ABC-XYZ-123`)
- 👥 Send/accept/reject friend requests
- 👥 Persistent friends list in localStorage
- 👥 Real-time online/offline status
- 👥 Geometric avatar generation from seed

#### Chat System
- 💬 Direct P2P messaging via WebRTC DataChannels
- 💬 End-to-end encrypted (WebRTC native)
- 💬 Persistent chat history (localStorage)
- 💬 Read receipts & delivery indicators
- 💬 Offline message queueing
- 💬 Unread message tracking

---

## 📖 Usage Guide

### Sharing a Playlist

#### Public Share (No Password)
1. Open playlist in Moodify
2. Click **Share** button
3. Click **Generate Share Link**
4. Copy URL or scan QR code
5. Share via any platform

#### Protected Share (With Password)
1. Open playlist in Moodify
2. Click **Share** button
3. Toggle **Password Protection** ON
4. Enter password (min 4 characters)
5. Click **🔒 Generate Protected Link**
6. Share URL + password separately (for security)

#### Importing a Shared Playlist
1. Open shared URL in browser
2. If encrypted: Enter password when prompted
3. Playlist automatically imports to library
4. URL clears from address bar

**Limitations:**
- Maximum 20 tracks per shared playlist
- Longer playlists use JSON export/import instead

---

### Adding Friends

#### Your Friend Code
1. Click **Friends** tab in navigation
2. Your unique code displays at top (e.g., `K7X-9M2-P4Q`)
3. Click **Copy** button
4. Share code with friends via any platform

#### Adding a Friend
1. Get friend's code from them
2. Click **Add Friend** button
3. Enter their code
4. Click **Send Friend Request**
5. Wait for them to accept

#### Accepting Friend Requests
1. Friend requests appear in **Requests** tab
2. Shows friend's name, avatar, and code
3. Click **Accept** to add them
4. Click **X** to reject

#### Managing Friends
- **Online Status** - Green dot = online, timestamp = last seen
- **Chat** - Click **Chat** button to message
- **Unread Badges** - Red badge shows unread count
- **Remove** - Click **X** to unfriend

---

### Using Chat

#### Starting a Conversation
1. Go to **Friends** tab
2. Find friend in list
3. Click **Chat** button
4. Chat dialog opens

#### Sending Messages
1. Type message in input field
2. Press **Enter** or click **Send**
3. Message sends instantly if friend is online
4. Message queues if friend is offline

#### Message Features
- **Timestamps** - Smart formatting (Today, Yesterday, date)
- **Delivery Status** - ✓ = sent, ✓✓ = delivered
- **Offline Warning** - Alert shows when friend is offline
- **Auto-scroll** - Chat scrolls to latest message
- **Persistent History** - Messages saved in localStorage

---

## 🔧 API Reference

### useFriends Hook

```typescript
const {
  myPeerCode,           // Your friend code (e.g., "ABC-XYZ-123")
  friends,              // Array of Friend objects
  friendRequests,       // Array of pending requests
  sendFriendRequest,    // (code: string) => Promise<void>
  acceptFriendRequest,  // (requestId: string) => Promise<void>
  rejectFriendRequest,  // (requestId: string) => Promise<void>
  removeFriend,         // (friendId: string) => Promise<void>
  sendMessage,          // (friendId: string, text: string) => Promise<void>
  getChatHistory,       // (friendId: string) => ChatMessage[]
  getUnreadCount,       // (friendId: string) => number
  markMessagesAsRead,   // (friendId: string) => void
} = useFriends(identity);
```

### usePlaylistSharing Hook

```typescript
const {
  createURLShare,       // (playlist: Playlist, password?: string) => Promise<Result>
  importFromURLShare,   // (encodedData: string, password?: string) => Promise<Playlist | null>
  canShareViaURL,       // (playlist: Playlist) => ValidationResult
  MAX_TRACKS_FOR_URL_SHARE, // 20
} = usePlaylistSharing(user);
```

### Data Types

```typescript
interface Friend {
  id: string;           // Unique friend ID
  peerId: string;       // PeerJS peer ID
  peerCode: string;     // Human-readable code
  displayName: string;  // Friend's name
  avatar: string;       // Avatar seed
  addedAt: number;      // Timestamp
  lastSeen: number;     // Last online timestamp
  isOnline: boolean;    // Current status
}

interface ChatMessage {
  id: string;           // Message ID
  senderId: string;     // Sender's anonId
  friendId: string;     // Friend's ID
  text: string;         // Message content
  timestamp: number;    // When sent
  delivered: boolean;   // Delivery status
}

interface FriendRequest {
  id: string;           // Request ID
  peerCode: string;     // Requester's code
  displayName: string;  // Requester's name
  avatar: string;       // Avatar seed
  timestamp: number;    // When sent
}
```

---

## 🔐 Privacy & Security

### What's Private

✅ **No Server Storage**
- Playlists encoded in URL itself
- Friends list stored only on your device
- Chat history only in your localStorage
- No centralized database

✅ **End-to-End Encryption**
- WebRTC encrypts all P2P data (DTLS-SRTP)
- Password-protected shares use AES-256-GCM
- Encryption keys never transmitted
- Each share has unique salt + IV

✅ **No Tracking**
- No analytics
- No user accounts
- No IP logging
- No behavioral data collection

✅ **Data Portability**
- Export identity at any time
- Clear localStorage to delete all data
- No vendor lock-in

### What's NOT Private

⚠️ **Public Information**
- Shared playlist URLs are public (if no password)
- Friend codes are shared by you
- PeerJS uses STUN servers (for NAT traversal)
- Your IP address visible to direct peers

⚠️ **Limitations**
- Chat history not synced across devices
- Friends must be online for initial connection
- No group chats (P2P topology limits)
- Browser cache may retain data

### Security Best Practices

1. **Use passwords for sensitive playlists**
2. **Don't share friend codes publicly**
3. **Clear browser data when using shared computers**
4. **Verify friend codes before accepting**
5. **Use HTTPS in production**

---

## 🔍 Troubleshooting

### Playlist Sharing Issues

**❌ "Cannot Share - Too Many Tracks"**
- **Cause:** Playlist has > 20 tracks
- **Solution:** Reduce to 20 tracks or use JSON export

**❌ "Password Required" error on import**
- **Cause:** Playlist is encrypted but no password provided
- **Solution:** Enter the password shared with you

**❌ "Invalid or corrupted playlist link"**
- **Cause:** URL is incomplete or corrupted
- **Solution:** Request a fresh share link

**❌ QR code not working**
- **Cause:** QR API timeout or URL too long
- **Solution:** Use copy-paste instead of QR

### Friends & Chat Issues

**❌ "ID taken" error**
- **Cause:** Dev mode hot-reload conflict (rare in production)
- **Solution:** Refresh page, new ID auto-generated
- **Prevention:** Timestamp suffix added in dev mode

**❌ Friend code not displaying**
- **Cause:** PeerJS initialization failed
- **Solution:** Check console for errors, refresh page
- **Debug:** Look for "Friends P2P initialized" in console

**❌ Cannot connect to friend**
- **Cause:** Friend is offline OR firewall/NAT issues
- **Solution:** 
  - Ensure both users are online
  - Check firewall settings (allow WebRTC)
  - Try different network (mobile hotspot)

**❌ Messages not sending**
- **Cause:** Friend went offline
- **Solution:** Messages queue and send when they're back online
- **Check:** Look for offline warning in chat dialog

**❌ Friend shows as offline but they're online**
- **Cause:** Connection dropped, reconnecting
- **Solution:** Wait 30-60 seconds for auto-reconnect
- **Manual:** Both users refresh page

**❌ Chat history disappeared**
- **Cause:** localStorage cleared or browser data wiped
- **Solution:** No recovery possible (data is local only)
- **Prevention:** Export important conversations manually

**❌ Infinite reload loop (dev mode)**
- **Cause:** Fixed in latest version
- **Solution:** Update to latest code
- **Check:** Ensure `myPeerId` not in useEffect dependencies

### Performance Issues

**❌ App slow with many friends**
- **Cause:** Too many active P2P connections
- **Solution:** Limit to ~10 active friends at once
- **Optimization:** Close connections to offline friends

**❌ Large bundle size warning**
- **Cause:** PeerJS + WebRTC libraries are large
- **Solution:** Enable code splitting (future improvement)
- **Current:** Warning only, doesn't affect functionality

---

## 📁 File Structure

```
src/
├── hooks/
│   ├── useFriends.ts              # P2P friends & chat logic (444 lines)
│   ├── usePlaylistSharing.ts     # Sharing state management
│   └── useAnonymousIdentity.ts   # Identity system
│
├── components/
│   ├── FriendsManager.tsx        # Friends list & requests UI (481 lines)
│   ├── ChatDialog.tsx            # 1-on-1 chat interface (272 lines)
│   ├── PlaylistSharingDialog.tsx # Share dialog with password UI
│   └── identity/
│       └── IdentityAvatar.tsx    # Geometric avatar generator
│
├── utils/
│   ├── encryption.ts             # AES-256 encryption (189 lines)
│   └── playlistSharing.ts        # URL encoding/compression (200+ lines)
│
└── pages/
    └── Index.tsx                 # Main page with Friends integration
```

---

## 🚀 Future Enhancements

### Potential Features
- [ ] Group chats (mesh network topology)
- [ ] Voice/video calls (WebRTC media streams)
- [ ] File sharing (chunked P2P transfer)
- [ ] Playlist collaboration (operational transforms)
- [ ] Friend nicknames and notes
- [ ] Message reactions and emojis
- [ ] Chat backups (encrypted export)
- [ ] Friend discovery (optional beacon system)
- [ ] Multi-device sync (encrypted cloud bridge)

### Performance Optimizations
- [ ] Code splitting for friends system
- [ ] Lazy load chat histories
- [ ] Connection pooling
- [ ] Message pagination
- [ ] WebWorker for encryption

---

## 📝 Implementation Notes

### Why This Architecture?

**Client-Side Only:**
- Privacy by design (no data mining possible)
- No server costs
- Works offline (after initial load)
- No maintenance burden

**PeerJS for P2P:**
- Simplifies WebRTC complexity
- Built-in signaling server
- Automatic reconnection
- Wide browser support

**localStorage for Persistence:**
- Instant access (no network calls)
- Survives page refresh
- User controls data (clear anytime)
- No sync conflicts

### Known Limitations

**Technical:**
- P2P requires both users online for initial handshake
- NAT traversal may fail in restrictive networks
- No message delivery guarantee if offline
- Chat history not encrypted at rest (browser security model)

**Design Trade-offs:**
- 20-track limit balances URL length vs usability
- Friend codes vs usernames (privacy over convenience)
- Local storage vs cloud sync (privacy over features)
- P2P vs server relay (decentralization over reliability)

### Browser Compatibility

**Fully Supported:**
- Chrome 90+
- Firefox 88+
- Safari 14+
- Edge 90+

**Required APIs:**
- WebRTC (RTCPeerConnection, DataChannel)
- Web Crypto API (SubtleCrypto)
- localStorage
- Async/await

---

## 🤝 Contributing

### Testing Checklist

**Playlist Sharing:**
- [ ] Generate public share link
- [ ] Generate password-protected link
- [ ] Import public playlist
- [ ] Import protected playlist (correct password)
- [ ] Import protected playlist (wrong password)
- [ ] Share link with 1 track
- [ ] Share link with 20 tracks
- [ ] Try share link with 21 tracks (should fail)

**Friends System:**
- [ ] Copy friend code
- [ ] Send friend request
- [ ] Receive friend request
- [ ] Accept friend request
- [ ] Reject friend request
- [ ] Remove friend
- [ ] Verify online status updates
- [ ] Send message when online
- [ ] Send message when offline
- [ ] Verify message persistence (refresh page)
- [ ] Check unread count updates
- [ ] Mark messages as read

**Edge Cases:**
- [ ] Refresh during friend request
- [ ] Multiple tabs open simultaneously
- [ ] Clear localStorage mid-session
- [ ] Disconnect network mid-chat
- [ ] Hot reload in dev mode
- [ ] Large chat history (100+ messages)

---

## 📞 Support

### Common Questions

**Q: Can I use this without an internet connection?**
A: App loads offline (PWA), but friends/sharing require internet for P2P connections.

**Q: How many friends can I have?**
A: Unlimited storage, but recommend <20 for performance.

**Q: Are my messages encrypted?**
A: Yes, WebRTC provides E2E encryption. Chat history in localStorage is not encrypted (browser limitation).

**Q: Can I recover deleted chats?**
A: No, localStorage data is permanent deletion. No backups exist.

**Q: Why can't I connect to my friend?**
A: Both must be online, codes must be correct, and network must allow WebRTC (some corporate firewalls block it).

**Q: Is this GDPR compliant?**
A: Yes, no personal data is collected or stored on servers. Users control all data locally.

---

## 📄 License

This feature is part of Moodify and follows the project's license.

---

**Built with privacy first. No servers, no tracking, no compromises.** 🎵🔒

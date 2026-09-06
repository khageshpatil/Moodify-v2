# 🎵 Moodify - Complete Technical Documentation

**Version:** 1.0.0  
**Author:** KP (khageshpatil)  
**Last Updated:** November 2024  
**Purpose:** Comprehensive guide for AI assistants and developers to understand Moodify's complete functionality, architecture, and UI

---

## 📖 Table of Contents

1. [Project Overview](#project-overview)
2. [Architecture & Tech Stack](#architecture--tech-stack)
3. [Core Features](#core-features)
4. [UI/UX Design System](#uiux-design-system)
5. [Component Architecture](#component-architecture)
6. [State Management & Hooks](#state-management--hooks)
7. [Data Flow & Logic](#data-flow--logic)
8. [API Integration](#api-integration)
9. [Routing & Navigation](#routing--navigation)
10. [Deployment & CI/CD](#deployment--cicd)
11. [User Flows](#user-flows)
12. [Code Organization](#code-organization)

---

## 1. Project Overview

### 🎯 **What is Moodify?**

Moodify is a sophisticated, anime-inspired music streaming web application that allows users to discover and play music based on their emotional state. It's a Single Page Application (SPA) built with React and TypeScript that provides:

- **Mood-based music discovery** (6 mood categories)
- **Real-time collaborative listening** (Listen Together feature)
- **Advanced music player** with premium controls
- **Playlist management** with import/export
- **Social features** for local discovery
- **Mobile-optimized experience**
- **Offline playlist support** via localStorage

### 🎨 **Design Philosophy**

- **Aesthetic**: Anime/Japanese-inspired with gradient effects, glass morphism, and smooth animations
- **Theme**: Purple-pink gradient (primary), with mood-specific color accents
- **Typography**: Mix of modern sans-serif and Japanese-inspired fonts
- **Animations**: Smooth transitions, fade-ins, particle effects, and hover interactions
- **Responsiveness**: Mobile-first design with touch-optimized controls

### 🌐 **Deployment**

- **Platform**: GitHub Pages
- **URL**: https://khageshpatil.github.io/Moodify/
- **CI/CD**: GitHub Actions (automated build & deploy on push to main)
- **SPA Handling**: Custom 404.html redirect for client-side routing

---

## 2. Architecture & Tech Stack

### 🏗️ **Frontend Architecture**

```
┌─────────────────────────────────────┐
│         React Application           │
│  (Single Page Application - SPA)    │
└─────────────────────────────────────┘
                 │
        ┌────────┴────────┐
        │   React Router  │ (Client-side routing)
        └────────┬────────┘
                 │
    ┌────────────┼────────────┐
    │            │            │
┌───▼───┐  ┌────▼────┐  ┌───▼────┐
│ Pages │  │  Hooks  │  │ Services│
└───┬───┘  └────┬────┘  └───┬────┘
    │           │            │
    └───────────┼────────────┘
                │
        ┌───────▼────────┐
        │   Components   │
        └───────┬────────┘
                │
    ┌───────────┼────────────┐
    │           │            │
┌───▼───┐  ┌───▼────┐  ┌───▼────┐
│ UI/UX │  │  Data  │  │ Storage│
│shadcn │  │  Mock  │  │ Local  │
└───────┘  └────────┘  └────────┘
```

### 🛠️ **Tech Stack**

#### **Core Technologies**
- **React 18.3.1** - UI library with hooks
- **TypeScript 5.8.3** - Type safety and developer experience
- **Vite 5.4.19** - Build tool and dev server (ultra-fast HMR)
- **React Router DOM 6.30.1** - Client-side routing

#### **UI Framework & Styling**
- **Tailwind CSS 3.4.17** - Utility-first CSS framework
- **shadcn/ui** - High-quality React components (Radix UI based)
- **Lucide React 0.462.0** - Icon library (consistent, modern icons)
- **tailwindcss-animate** - Animation utilities
- **next-themes** - Dark/light mode support

#### **State Management**
- **TanStack Query (React Query) 5.83.0** - Server state management, caching
- **React Hooks** - Local state (useState, useReducer, useRef, useEffect, etc.)
- **localStorage** - Persistent storage for playlists, favorites, history

#### **Real-time Communication**
- **PeerJS 1.5.5** - WebRTC wrapper for peer-to-peer connections
- **WebRTC** - Real-time data channels for Listen Together

#### **Form Handling & Validation**
- **React Hook Form 7.61.1** - Form state management
- **Zod 3.25.76** - Schema validation
- **@hookform/resolvers** - Form validation integration

#### **UI Components (Radix UI)**
- Dialog, Dropdown, Tooltip, Slider, Tabs, Accordion, etc.
- Fully accessible, keyboard navigable, screen-reader friendly

#### **Utilities**
- **clsx / tailwind-merge** - Conditional className utilities
- **date-fns 3.6.0** - Date formatting and manipulation
- **Sonner** - Toast notifications (elegant, stackable)

#### **Development Tools**
- **ESLint 9.32.0** - Code linting
- **TypeScript ESLint** - TypeScript-specific linting
- **Vite Plugin React SWC** - Fast React refresh

---

## 3. Core Features

### 🎭 **1. Mood-Based Music Discovery**

**Description**: Users select from 6 emotional moods to get curated playlists.

**Moods Available**:

| Mood | Emoji | Color | Description | Playlist Name |
|------|-------|-------|-------------|---------------|
| **Chill** | 🌸 | Soft pink/purple | Peaceful vibes for relaxation | Cherry Blossom Dreams |
| **Melancholy** | ☁️ | Gray/blue | Reflective and contemplative | Rainy Day Reflections |
| **Workout** | 🔥 | Red/orange | High energy for motivation | Fire & Fury |
| **Focus** | 🚀 | Blue/teal | Deep concentration mode | Deep Work Flow |
| **Love** | ❤️ | Pink/red | Romantic and heartfelt | Starlit Romance |
| **Party** | 🎉 | Yellow/green | Celebration and joy | Gentle Celebration |

**Technical Implementation**:
- Grid layout (3 columns on desktop, responsive)
- Beautiful background images for each mood
- Hover effects (scale, brightness, shadow)
- Animated stagger effect on load (0.1s delay per tile)
- Click triggers mood selection → loads playlist → starts playback

**User Flow**:
```
Home Screen → User selects mood → 
Playlist loads with tracks → 
First track auto-plays → 
Music player appears at bottom
```

---

### 🎵 **2. Enhanced Music Player**

**Description**: Full-featured music player with premium audio controls at the bottom of the screen.

**Features**:

#### **Basic Controls**
- ▶️ Play/Pause
- ⏭️ Next track
- ⏮️ Previous track
- 🔊 Volume control (0-100%)
- 🔀 Shuffle mode
- 🔁 Repeat modes (off / all / one)
- ❤️ Favorite toggle

#### **Advanced Controls** (EnhancedMusicPlayer)
- **Progress bar** - Seekable timeline with time display
- **Audio visualizer** - 20 animated bars that pulse with music
- **Album art** - Displays current track's artwork
- **Track info** - Title, artist, album display
- **Queue display** - Shows upcoming tracks
- **Crossfade** - Smooth transitions between tracks
- **Equalizer presets** - Bass boost, treble, balanced
- **Playback speed** - 0.5x to 2x speed control

**UI Layout**:
```
┌─────────────────────────────────────────────────────────┐
│ [Album Art] Track Title      [❤️ Prev ⏸️ Next]  [🔊 70%] │
│             Artist Name                                   │
│ 0:42 ▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░ 3:45     [🔀] [🔁]     │
└─────────────────────────────────────────────────────────┘
```

**Technical Details**:
- HTML5 Audio API for playback
- Real-time time tracking with `timeupdate` event
- Volume stored in localStorage
- Current track state synced with Listen Together (if active)
- Keyboard shortcuts (Space = play/pause, ← → = seek)

---

### 👥 **3. Listen Together (Collaborative Listening)**

**Description**: Real-time music synchronization feature allowing multiple users to listen to the same music simultaneously.

**How It Works**:

#### **Host Mode**
1. User clicks "Listen Together" button
2. Creates a room with unique Room Code (PeerJS ID)
3. Shares Room Code with friends
4. Host controls playback (play, pause, seek, volume, track changes)
5. All actions broadcast to connected guests in real-time

#### **Guest Mode**
1. User enters Host's Room Code
2. Connects to host via WebRTC P2P connection
3. Receives playback commands from host
4. Music syncs automatically (track, time, volume)
5. Can send chat messages to host

**Features**:
- ✅ **Multi-guest support** (up to 10 guests)
- ✅ **Live chat** - Text messaging between users
- ✅ **Heartbeat system** - Connection health monitoring (30s interval)
- ✅ **Auto-reconnect** - Handles disconnections gracefully
- ✅ **User nicknames** - Colored avatars for each user
- ✅ **Connection status** - Visual indicators (connecting/connected/error)
- ✅ **Live stream support** - YouTube/Twitch URL synchronization
- ✅ **Race condition prevention** - Deduplication of rapid commands (300ms window)

**Technical Architecture**:

```
┌─────────┐              ┌─────────┐
│  Host   │◄────────────►│ Guest 1 │
│(PeerJS) │   WebRTC     │         │
└────┬────┘   P2P Data   └─────────┘
     │       Channels
     │
     ├──────────────────►┌─────────┐
     │                   │ Guest 2 │
     │                   └─────────┘
     │
     └──────────────────►┌─────────┐
                         │ Guest N │
                         └─────────┘
```

**Message Types**:
- `play` - Play track with queue, time, volume
- `pause` - Pause playback
- `next` - Skip to next track
- `previous` - Go to previous track
- `seek` - Jump to specific time
- `volume` - Change volume level
- `chat` - Text message
- `heartbeat` - Connection alive signal
- `user_info` - User profile (nickname, color)
- `live_sync` - Stream URL and state

**Race Condition Prevention**:
```typescript
// Deduplicates actions within 300ms
if (lastAction.type === action && now - lastAction.time < 300) {
  return; // Ignore duplicate
}

// Prevents multiple play commands simultaneously
if (processingPlay) {
  return; // Skip until current play finishes
}
```

---

### 📂 **4. Playlist Management**

**Description**: Create, edit, and manage custom playlists with import/export functionality.

**Features**:

#### **Playlist Operations**
- ✅ **Create playlist** - Name + description
- ✅ **Delete playlist** - Remove with confirmation
- ✅ **Rename playlist** - Edit name/description
- ✅ **Add tracks** - From search, favorites, or current queue
- ✅ **Remove tracks** - Delete individual songs
- ✅ **Reorder tracks** - Drag & drop (future enhancement)
- ✅ **Play playlist** - Load all tracks to queue
- ✅ **Export playlist** - Save as JSON file
- ✅ **Import playlist** - Load from JSON file

#### **Storage**
- localStorage key: `moodify_playlists`
- Format: JSON array of Playlist objects
- Auto-saves on every change
- Survives page refresh and browser close

**Playlist Schema**:
```typescript
interface Playlist {
  id: string;           // UUID
  name: string;         // User-defined name
  tracks: Track[];      // Array of songs
  createdAt: Date;      // Creation timestamp
}
```

**Export Format (JSON)**:
```json
{
  "id": "playlist-123",
  "name": "My Awesome Mix",
  "tracks": [
    {
      "id": "track-1",
      "title": "Song Name",
      "artist": "Artist Name",
      "album": "Album Name",
      "albumArt": "https://...",
      "duration": 245
    }
  ],
  "createdAt": "2024-11-17T..."
}
```

---

### ❤️ **5. Favorites System**

**Description**: Save favorite tracks for quick access.

**Features**:
- ❤️ **Toggle favorite** - Click heart icon on any track
- 📋 **Favorites view** - Dedicated screen showing all favorites
- 🎵 **Play from favorites** - Click to play instantly
- 💾 **Persistent storage** - localStorage (`moodify_favorites`)
- ➕ **Add to playlist** - Create playlists from favorites

**UI Indicators**:
- Filled red heart ❤️ = Favorited
- Empty heart 🤍 = Not favorited
- Visual feedback on toggle (animation)

---

### 🕒 **6. History & Recently Played**

**Description**: Track listening history with playback analytics.

**Features**:

#### **History Tracking**
- Records every played track
- Captures listen duration (how long you listened)
- Timestamp of when played
- View full history in dedicated screen

#### **Recently Played**
- Shows last 10 tracks
- Quick access to re-play
- Sorted by most recent first

**History Schema**:
```typescript
interface HistoryItem {
  track: Track;        // The song
  playedAt: Date;      // When it was played
  duration: number;    // How long (in ms)
}
```

**Storage**:
- localStorage key: `moodify_history`
- Persists across sessions
- Can be cleared by user

---

### 🔍 **7. Music Search & Discovery**

**Description**: Search for songs with JioSaavn integration and discover new music.

**Features**:

#### **Search**
- Real-time search as you type
- Searches by: song title, artist, album
- Shows results instantly
- Click result to add to queue or playlist

#### **Discover View**
- Recommended tracks based on mood
- Trending songs
- New releases
- Similar artists

**UI Components**:
- Search bar with icon (🔍)
- Results list with album art
- Filters (by artist, album, etc.)
- Loading states (skeletons)

---

### 📱 **8. Mobile Optimizations**

**Description**: Touch-optimized interface for mobile devices.

**Features**:
- **Responsive layouts** - Adapts to screen size
- **Touch gestures** - Swipe to skip, tap to play
- **Larger tap targets** - Buttons sized for fingers
- **Mobile menu** - Collapsible navigation
- **Device detection** - `useDeviceCapabilities()` hook
- **Performance** - Lazy loading, code splitting

**Breakpoints**:
- Mobile: < 768px
- Tablet: 768px - 1024px
- Desktop: > 1024px

---

### 🎨 **9. Visual Effects**

**Description**: Beautiful animations and particle effects.

**Features**:

#### **Floating Particles**
- Background animation with floating orbs
- Purple/pink gradient particles
- Smooth movement using CSS animations
- Performance-optimized (GPU accelerated)

#### **Animations**
- **Fade-in-up**: Content appears from bottom with fade
- **Stagger**: Sequential animation of elements
- **Hover effects**: Scale, brightness, shadow on hover
- **Transition smoothness**: 300-500ms cubic-bezier easing

#### **Glass Morphism**
- Semi-transparent backgrounds
- Backdrop blur effect
- Border glow on hover
- Modern, elegant aesthetic

---

### 🌐 **10. Social Features**

**Description**: Discover and connect with nearby users (LocalSocialDiscovery).

**Features**:
- 🗺️ **User presence** - See who's nearby
- 🎵 **Now playing** - What others are listening to
- 🤝 **Quick connect** - Join their Listen Together session
- 📤 **Playlist sharing** - Share your playlists with others

---

## 4. UI/UX Design System

### 🎨 **Color Palette**

#### **Primary Colors**
```css
--gradient-primary: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
--purple-500: #a855f7
--pink-500: #ec4899
```

#### **Mood Colors**
```css
--mood-chill: #9FC5E8 (soft blue)
--mood-melancholy: #B0BEC5 (gray)
--mood-workout: #FF6B6B (red)
--mood-focus: #4A90E2 (blue)
--mood-love: #F06292 (pink)
--mood-party: #FFD54F (yellow)
```

#### **UI Colors**
```css
--background: hsl(240 10% 3.9%)      /* Dark bg */
--foreground: hsl(0 0% 98%)          /* White text */
--card: hsl(240 10% 3.9%)            /* Card bg */
--border: hsl(240 3.7% 15.9%)        /* Borders */
--muted: hsl(240 3.7% 15.9%)         /* Muted text */
```

### 🖋️ **Typography**

```css
/* Headings */
font-family: 'Inter', sans-serif;
font-weight: 300-700 (variable);

/* Japanese-inspired accent */
.font-japanese {
  font-family: 'Noto Sans JP', sans-serif;
  font-weight: 300;
  letter-spacing: 0.05em;
}
```

**Font Sizes**:
- xs: 0.75rem (12px)
- sm: 0.875rem (14px)
- base: 1rem (16px)
- lg: 1.125rem (18px)
- xl: 1.25rem (20px)
- 2xl: 1.5rem (24px)
- 4xl: 2.25rem (36px)
- 6xl: 3.75rem (60px)

### 🎭 **Spacing System**

Tailwind's default spacing scale (4px base unit):
- 0: 0px
- 1: 0.25rem (4px)
- 2: 0.5rem (8px)
- 4: 1rem (16px)
- 8: 2rem (32px)
- 12: 3rem (48px)

### 🔲 **Border Radius**

```css
--radius: 0.5rem (8px)
- rounded-sm: 0.125rem (2px)
- rounded: 0.25rem (4px)
- rounded-md: 0.375rem (6px)
- rounded-lg: 0.5rem (8px)
- rounded-xl: 0.75rem (12px)
- rounded-2xl: 1rem (16px)
- rounded-full: 9999px
```

### ✨ **Effects**

#### **Glass Card**
```css
.glass-card {
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.1);
}
```

#### **Gradient Text**
```css
.bg-gradient-primary {
  background: linear-gradient(135deg, #667eea, #764ba2);
}
.bg-clip-text {
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}
```

#### **Shadows**
```css
--shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.05)
--shadow: 0 1px 3px rgba(0, 0, 0, 0.1)
--shadow-md: 0 4px 6px rgba(0, 0, 0, 0.1)
--shadow-lg: 0 10px 15px rgba(0, 0, 0, 0.1)
--shadow-xl: 0 20px 25px rgba(0, 0, 0, 0.1)
```

---

## 5. Component Architecture

### 📦 **Component Hierarchy**

```
App (Root)
├── BrowserRouter
│   └── Routes
│       ├── Index (Main Page)
│       │   ├── FloatingParticles
│       │   ├── Navigation Bar
│       │   │   ├── Logo
│       │   │   └── View Switcher Buttons
│       │   │       ├── Home
│       │   │       ├── Search
│       │   │       ├── Playlists
│       │   │       ├── Favorites
│       │   │       ├── History
│       │   │       ├── Discover
│       │   │       ├── Social
│       │   │       └── Listen Together
│       │   │
│       │   ├── Main Content (Dynamic based on currentView)
│       │   │   ├── MoodTiles (if view === 'moods')
│       │   │   │   └── Grid of 6 Mood Cards
│       │   │   │
│       │   │   ├── PlaylistView (if view === 'moods' && selectedMood)
│       │   │   │   ├── Playlist Header
│       │   │   │   └── Track List
│       │   │   │       └── TrackItem (for each track)
│       │   │   │
│       │   │   ├── SearchBar (if view === 'search')
│       │   │   │   ├── Input Field
│       │   │   │   └── Search Results List
│       │   │   │
│       │   │   ├── PlaylistManager (if view === 'playlists')
│       │   │   │   ├── Create Playlist Button
│       │   │   │   ├── Playlist Cards Grid
│       │   │   │   │   └── PlaylistCard
│       │   │   │   │       ├── Playlist Name
│       │   │   │   │       ├── Track Count
│       │   │   │   │       └── Actions (Play, Edit, Delete, Export)
│       │   │   │   └── Import Playlist Button
│       │   │   │
│       │   │   ├── QueueManager (if view === 'queue')
│       │   │   │   ├── Current Queue List
│       │   │   │   ├── Clear Queue Button
│       │   │   │   └── Track Items (draggable)
│       │   │   │
│       │   │   ├── FavoritesView (if view === 'favorites')
│       │   │   │   └── Favorites Track List
│       │   │   │
│       │   │   ├── HistoryView (if view === 'history')
│       │   │   │   ├── Clear History Button
│       │   │   │   └── History Items List
│       │   │   │       └── HistoryItem (track + timestamp + duration)
│       │   │   │
│       │   │   ├── DiscoverView (if view === 'discover')
│       │   │   │   ├── Recommended Section
│       │   │   │   ├── Trending Section
│       │   │   │   └── New Releases Section
│       │   │   │
│       │   │   └── LocalSocialDiscovery (if view === 'social')
│       │   │       ├── Nearby Users List
│       │   │       └── Shared Playlists
│       │   │
│       │   ├── EnhancedMusicPlayer (Fixed at bottom)
│       │   │   ├── Track Info (Album Art, Title, Artist)
│       │   │   ├── Controls
│       │   │   │   ├── Previous Button
│       │   │   │   ├── Play/Pause Button
│       │   │   │   ├── Next Button
│       │   │   │   ├── Shuffle Toggle
│       │   │   │   ├── Repeat Toggle
│       │   │   │   └── Favorite Toggle
│       │   │   ├── Progress Bar
│       │   │   │   ├── Current Time
│       │   │   │   ├── Seekable Slider
│       │   │   │   └── Duration
│       │   │   ├── Volume Control
│       │   │   ├── PremiumAudioControls (Dialog)
│       │   │   │   ├── Equalizer
│       │   │   │   ├── Speed Control
│       │   │   │   └── Crossfade
│       │   │   └── Audio Visualizer (20 bars)
│       │   │
│       │   └── EnhancedListenTogetherDialog (Modal)
│       │       ├── Create Room Tab
│       │       │   ├── Nickname Input
│       │       │   ├── Create Button
│       │       │   └── Room Code Display
│       │       ├── Join Room Tab
│       │       │   ├── Nickname Input
│       │       │   ├── Room Code Input
│       │       │   └── Join Button
│       │       ├── Active Session Panel (if connected)
│       │       │   ├── Connected Users List
│       │       │   │   └── User Avatar + Nickname
│       │       │   ├── Chat Interface
│       │       │   │   ├── Messages List
│       │       │   │   └── Message Input
│       │       │   ├── Connection Status Indicator
│       │       │   ├── Live Stream Controls (if host)
│       │       │   │   ├── Start Stream Button
│       │       │   │   └── Stream URL Input
│       │       │   └── Disconnect Button
│       │       └── Close Button
│       │
│       └── NotFound (404 Page)
│
├── Toaster (Global toast notifications)
├── Sonner (Toast stack)
└── TooltipProvider (Global tooltip context)
```

---

### 🧩 **Key Components**

#### **1. MoodTiles**
- **Path**: `src/components/MoodTiles.tsx`
- **Props**: `{ onMoodSelect: (mood: Mood) => void }`
- **Purpose**: Display 6 mood categories as clickable cards
- **Styling**: Grid layout, background images, hover effects

#### **2. EnhancedMusicPlayer**
- **Path**: `src/components/EnhancedMusicPlayer.tsx`
- **Props**: All player state and control functions
- **Purpose**: Full-featured music player UI
- **Features**: Album art, controls, progress bar, volume, visualizer

#### **3. PlaylistView**
- **Path**: `src/components/PlaylistView.tsx`
- **Props**: `{ playlist, tracks, onPlay, onBack }`
- **Purpose**: Display playlist tracks and metadata
- **Features**: Track list, play all, shuffle, back button

#### **4. EnhancedListenTogetherDialog**
- **Path**: `src/components/EnhancedListenTogetherDialog.tsx`
- **Purpose**: Modal for creating/joining Listen Together sessions
- **Features**: Create room, join room, chat, user list, connection status

#### **5. FloatingParticles**
- **Path**: `src/components/FloatingParticles.tsx`
- **Purpose**: Background particle animation
- **Implementation**: CSS animations with gradient orbs

#### **6. SearchBar**
- **Path**: `src/components/SearchBar.tsx`
- **Props**: `{ onSearch: (query: string) => void }`
- **Purpose**: Search input with real-time results
- **Features**: Debounced input, loading states, result list

#### **7. PlaylistManager**
- **Path**: `src/components/PlaylistManager.tsx`
- **Purpose**: Manage user playlists
- **Features**: Create, delete, rename, export, import

---

## 6. State Management & Hooks

### 🪝 **Custom Hooks**

#### **1. useMusicPlayer**
- **Path**: `src/hooks/useMusicPlayer.ts`
- **Purpose**: Central music player logic and state
- **Returns**:
  - State: currentTrack, isPlaying, queue, playlists, favorites, history, etc.
  - Actions: playTrack, togglePlayPause, playNext, createPlaylist, etc.

**Key Features**:
- HTML5 Audio API integration
- localStorage persistence
- Auto-play next track
- Shuffle algorithm
- Repeat modes (off/all/one)
- Favorites management
- History tracking

**State Schema**:
```typescript
{
  currentTrack: Track | null;
  isPlaying: boolean;
  currentTime: number;
  duration: number;
  volume: number; // 0-100
  queue: Track[];
  currentTrackIndex: number;
  playlists: Playlist[];
  favorites: Track[];
  history: HistoryItem[];
  recentlyPlayed: Track[];
  shuffle: boolean;
  repeat: 'off' | 'all' | 'one';
  originalQueue: Track[]; // For un-shuffling
}
```

#### **2. useEnhancedListenTogether**
- **Path**: `src/hooks/useEnhancedListenTogether.ts`
- **Purpose**: WebRTC-based collaborative listening
- **Returns**:
  - state: isHost, isConnected, roomCode, connectedGuests, messages
  - createRoom, joinRoom, disconnect, sendMessage, sendControl

**Key Features**:
- PeerJS integration
- Multi-guest support (up to 10)
- Heartbeat system (30s interval)
- Auto-reconnect on disconnect
- Chat messaging
- Live stream sync
- Race condition prevention

**Connection Flow**:
```
Host: new Peer() → peer.on('open') → setState(roomCode)
Guest: new Peer() → peer.connect(roomCode) → conn.on('open')
Data: conn.send({ type, data }) → conn.on('data')
```

#### **3. useEnhancedNotifications**
- **Path**: `src/hooks/useEnhancedNotifications.ts`
- **Purpose**: Toast notification system
- **Returns**:
  - notifyMusicAction(action, track)
  - notifyPlaylistAction(action, playlist)
  - notifyConnectionStatus(status)

**Notification Types**:
- Music actions: play, pause, favorite, queue add
- Playlist actions: create, delete, export, import
- Connection: connected, disconnected, error

#### **4. useMobileEnhancements / useDeviceCapabilities**
- **Path**: `src/hooks/useMobileEnhancements.ts`
- **Purpose**: Detect device capabilities
- **Returns**:
  - isMobile: boolean
  - hasTouch: boolean
  - isIOS: boolean
  - isAndroid: boolean
  - screenSize: 'mobile' | 'tablet' | 'desktop'

**Detection Logic**:
```typescript
const isMobile = window.innerWidth < 768;
const hasTouch = 'ontouchstart' in window;
const isIOS = /iPhone|iPad|iPod/i.test(navigator.userAgent);
```

#### **5. useEnhancedAudioEngine**
- **Path**: `src/hooks/useEnhancedAudioEngine.ts`
- **Purpose**: Advanced audio processing
- **Features**: Equalizer, audio effects, volume normalization

#### **6. usePlaylistSharing**
- **Path**: `src/hooks/usePlaylistSharing.ts`
- **Purpose**: Share playlists with others
- **Features**: Generate share links, QR codes

#### **7. useLocalSocialFeatures**
- **Path**: `src/hooks/useLocalSocialFeatures.ts`
- **Purpose**: Local user discovery
- **Features**: Nearby users, playlist sharing

---

### 📊 **Global State (React Query)**

**Purpose**: Server-side data caching and synchronization

**Usage**:
```typescript
const queryClient = new QueryClient();

// Example: Fetch songs from JioSaavn API
const { data, isLoading, error } = useQuery({
  queryKey: ['songs', moodId],
  queryFn: () => getSongsByMood(moodId),
  staleTime: 5 * 60 * 1000, // 5 minutes
});
```

---

## 7. Data Flow & Logic

### 🔄 **Music Playback Flow**

```
1. User selects mood → MoodTiles
2. loadMoodPlaylist(moodId) → useMusicPlayer
3. Fetch tracks from JioSaavn API or mockData
4. Set queue with fetched tracks
5. playTrack(tracks[0], tracks) → Play first track
6. Audio element loads and plays
7. Update isPlaying = true, currentTrack = tracks[0]
8. Progress bar updates every timeupdate event
9. On track end → playNext() → Load next in queue
10. If repeat === 'all' and last track → Loop to first
11. Add to history when track completes
```

### 🤝 **Listen Together Flow**

#### **Host Creates Room**:
```
1. User clicks "Listen Together" → Opens dialog
2. User enters nickname → Click "Create Room"
3. createRoom(nickname) → new Peer()
4. PeerJS generates unique ID (room code)
5. Display room code to share with friends
6. peer.on('connection') → Accept guest connections
7. Send current playback state to new guests
```

#### **Guest Joins Room**:
```
1. Guest enters room code and nickname
2. joinRoom(roomCode, nickname) → new Peer()
3. peer.connect(roomCode) → Establish P2P connection
4. conn.on('open') → Connection successful
5. Send user info (nickname, color) to host
6. conn.on('data') → Receive playback commands
7. handleRemoteControl(action, data) → Execute commands
8. Music syncs with host (track, time, volume)
```

#### **Playback Sync**:
```
Host plays track →
sendControl('play', { track, queue, time: 0, volume }) →
Guest receives 'play' command →
handleRemoteControl('play', data) →
playTrack(data.track, data.queue) →
seekTo(data.time) →
setVolume(data.volume) →
Music plays in sync ✅
```

#### **Race Condition Prevention**:
```typescript
// On Guest side
handleRemoteControl(action, data) {
  const now = Date.now();
  
  // 1. Deduplicate rapid actions
  if (lastAction.type === action && now - lastAction.time < 300ms) {
    return; // Ignore duplicate
  }
  
  // 2. Prevent concurrent play commands
  if (action === 'play' && processingPlay) {
    return; // Skip if already processing
  }
  
  // 3. Execute with lock
  processingPlay = true;
  try {
    playTrack(data.track);
  } finally {
    setTimeout(() => processingPlay = false, 500ms);
  }
}
```

### 💾 **Data Persistence Flow**

```
State Changes → useEffect detects change →
saveToStorage(key, value) →
localStorage.setItem(key, JSON.stringify(value)) →
Persisted ✅

On Page Load →
loadFromStorage(key, defaultValue) →
localStorage.getItem(key) →
JSON.parse(value) →
Initial state restored ✅
```

**Persisted Data**:
- Playlists → `moodify_playlists`
- Favorites → `moodify_favorites`
- History → `moodify_history`
- Recently Played → `moodify_recently_played`
- Volume → `moodify_volume`

---

## 8. API Integration

### 🎵 **JioSaavn API**

**Service File**: `src/services/jiosaavn.ts`

**Purpose**: Fetch music data from JioSaavn (Indian music streaming service)

**Endpoints Used**:
```typescript
// Search songs
async function getSongsByMood(moodId: string): Promise<Track[]>

// Search by query
async function searchSongs(query: string): Promise<Track[]>

// Get song details
async function getSongDetails(songId: string): Promise<Track>
```

**API Response Format**:
```typescript
{
  id: string;
  title: string;
  artist: string;
  album: string;
  image: string; // Album art URL
  duration: number; // In seconds
  url: string; // Stream URL
}
```

**Error Handling**:
- Network errors → Fallback to mock data
- Rate limiting → Queue requests
- Invalid responses → Show error toast

**Current Implementation**:
- Uses mock data for demo (`src/data/mockMusic.ts`)
- JioSaavn integration ready for production
- Configurable API base URL

---

## 9. Routing & Navigation

### 🗺️ **React Router Setup**

**Router Type**: `BrowserRouter`

**Routes**:
```typescript
<Routes>
  <Route path="/" element={<Index />} />
  <Route path="*" element={<NotFound />} />
</Routes>
```

**Base URL Configuration**:
```typescript
// vite.config.ts
base: command === 'build' ? '/Moodify/' : '/'

// src/App.tsx
<BrowserRouter 
  basename={import.meta.env.BASE_URL}
  future={{
    v7_startTransition: true,
    v7_relativeSplatPath: true,
  }}
>
```

### 🔀 **GitHub Pages SPA Routing Fix**

**Problem**: GitHub Pages doesn't support client-side routing. Direct URLs (e.g., `/Moodify/playlist`) return 404.

**Solution**: 404.html redirect trick

**Files**:
1. `public/404.html` - Redirects to index with query params
2. `index.html` - Script decodes and restores path

**How It Works**:
```
1. User visits: /Moodify/playlist
2. GitHub Pages: 404 error → Serves 404.html
3. 404.html script: Converts path to query (?/playlist)
4. Redirects to: /Moodify/?/playlist
5. index.html loads
6. Script extracts query (?/playlist)
7. history.replaceState() → Restores /Moodify/playlist
8. React Router takes over → Renders correct component ✅
```

### 🧭 **View Navigation**

**Internal Navigation** (within Index page):
- Uses `currentView` state (not URL-based)
- View types: 'moods' | 'search' | 'playlists' | 'queue' | 'favorites' | 'history' | 'discover' | 'social'
- Buttons in nav bar update `setCurrentView(view)`

**Navigation Bar**:
```tsx
<nav>
  <Button onClick={() => setCurrentView('moods')}>
    <Home /> Home
  </Button>
  <Button onClick={() => setCurrentView('search')}>
    <Search /> Search
  </Button>
  {/* ... more buttons ... */}
</nav>
```

---

## 10. Deployment & CI/CD

### 🚀 **GitHub Actions Workflow**

**File**: `.github/workflows/deploy.yml`

**Trigger**: Push to `main` branch or manual dispatch

**Steps**:
```yaml
1. Checkout code (actions/checkout@v4)
2. Setup Node.js 20 (actions/setup-node@v4)
3. Install dependencies (npm ci)
4. Lint code (npm run lint) - Continue on error
5. Build production bundle (npm run build)
6. Upload dist folder as artifact (actions/upload-pages-artifact@v3)
7. Deploy to GitHub Pages (actions/deploy-pages@v4)
```

**Build Output**:
- dist/index.html
- dist/assets/*.js (JS bundles)
- dist/assets/*.css (CSS)
- dist/assets/*.jpg (Images)
- dist/404.html (SPA routing fix)

**Deployment Time**: ~2-3 minutes

### 🌐 **GitHub Pages Configuration**

**Settings → Pages**:
- Source: GitHub Actions
- Branch: N/A (Actions handles deployment)
- URL: https://khageshpatil.github.io/Moodify/

**Vite Build Config**:
```typescript
// vite.config.ts
export default defineConfig(({ command }) => ({
  base: command === 'build' ? '/Moodify/' : '/',
  // Ensures assets use correct path on GitHub Pages
}));
```

### 📦 **Build Optimization**

**Current Bundle Size**:
- JS: 647 KB (191 KB gzipped)
- CSS: 72 KB (13 KB gzipped)
- Images: ~120 KB total

**Optimizations Applied**:
- Tree shaking (Vite default)
- Minification (Terser)
- Code splitting (dynamic imports for routes)
- Asset compression (gzip)
- Image optimization (WebP format recommended)

**Future Improvements**:
- Lazy load components
- Manual chunk splitting
- Image CDN integration
- Service worker for offline support

---

## 11. User Flows

### 🎵 **Flow 1: Listen to Music by Mood**

```
1. User lands on homepage
2. Sees 6 mood tiles (Chill, Melancholy, Workout, Focus, Love, Party)
3. User hovers over "Chill" tile
   → Tile scales up, brightness increases
4. User clicks "Chill" tile
5. System loads "Cherry Blossom Dreams" playlist (4 tracks)
6. PlaylistView appears with track list
7. First track "Sakura Whispers" auto-plays
8. EnhancedMusicPlayer appears at bottom
   → Shows album art, progress bar, controls
9. User can:
   - Pause/Play
   - Skip tracks
   - Adjust volume
   - Favorite tracks
   - View queue
10. Track plays to end → Auto-plays next track
11. Last track ends → Loops if repeat is on
```

### 👥 **Flow 2: Listen Together with Friends**

**Host Side**:
```
1. User clicks "Listen Together" button (👥 icon)
2. Dialog opens with two tabs: Create Room | Join Room
3. User enters nickname: "Alex"
4. User clicks "Create Room"
5. System creates PeerJS connection
6. Room code generated: "abc123xyz"
7. User shares code with friend via text/call
8. User selects "Chill" mood and plays music
9. Friend joins (sees notification)
10. User sees friend's name "Jordan" in connected users list
11. User changes track → Jordan's track changes too
12. User can chat with Jordan in chat panel
13. User clicks "Disconnect" → Session ends
```

**Guest Side**:
```
1. Friend receives room code: "abc123xyz"
2. Friend opens Moodify
3. Friend clicks "Listen Together"
4. Friend selects "Join Room" tab
5. Friend enters:
   - Nickname: "Jordan"
   - Room Code: "abc123xyz"
6. Friend clicks "Join Room"
7. Connection established (PeerJS)
8. Friend sees Alex in connected users
9. Music starts playing (synced with Alex)
   → Same track
   → Same time position
   → Same volume
10. Friend can send chat messages to Alex
11. When Alex changes tracks, Jordan's track changes too
12. Friend clicks "Disconnect" → Session ends
```

### 📂 **Flow 3: Create and Export Playlist**

```
1. User plays various tracks from different moods
2. User clicks "Playlists" button in nav
3. PlaylistManager view opens
4. User clicks "Create Playlist" button
5. Dialog appears:
   - Name: "My Study Mix"
   - Description: "Focus music for coding"
6. User clicks "Create"
7. Empty playlist created
8. User goes to Favorites view
9. User clicks "Add to Playlist" on a favorite track
10. Selects "My Study Mix"
11. Track added to playlist
12. User repeats for 5 more tracks
13. User goes back to Playlists view
14. User clicks "Export" on "My Study Mix"
15. JSON file downloads: `my-study-mix.json`
16. User can share this file with friends
17. Friend imports the JSON → Playlist appears in their app
```

### ❤️ **Flow 4: Favorite and Replay Tracks**

```
1. User plays "Eternal Embrace" from Love mood
2. User loves the song
3. User clicks heart ❤️ button on player
4. Heart fills red → Track added to favorites
5. Track saved to localStorage
6. User continues listening to other tracks
7. Next day, user opens Moodify
8. User clicks "Favorites" button in nav
9. FavoritesView shows all favorited tracks
10. User sees "Eternal Embrace" in the list
11. User clicks the track
12. Track loads to queue and starts playing
13. User can unfavorite by clicking heart again
```

---

## 12. Code Organization

### 📁 **Directory Structure**

```
Moodify/
├── .github/
│   └── workflows/
│       └── deploy.yml              # GitHub Actions CI/CD
│
├── public/
│   ├── favicon.ico                 # Site icon
│   ├── placeholder.svg             # Placeholder image
│   ├── robots.txt                  # SEO crawler rules
│   └── 404.html                    # SPA routing redirect
│
├── src/
│   ├── assets/                     # Static images
│   │   ├── mood-chill.jpg
│   │   ├── mood-melancholy.jpg
│   │   ├── mood-workout.jpg
│   │   ├── mood-focus.jpg
│   │   ├── mood-love.jpg
│   │   └── mood-party.jpg
│   │
│   ├── components/                 # React components
│   │   ├── ui/                     # shadcn/ui components
│   │   │   ├── button.tsx
│   │   │   ├── dialog.tsx
│   │   │   ├── slider.tsx
│   │   │   ├── tabs.tsx
│   │   │   └── ... (40+ components)
│   │   │
│   │   ├── MoodTiles.tsx           # Mood selection grid
│   │   ├── PlaylistView.tsx        # Playlist track list
│   │   ├── MusicPlayer.tsx         # Basic player
│   │   ├── EnhancedMusicPlayer.tsx # Advanced player
│   │   ├── EnhancedListenTogetherDialog.tsx
│   │   ├── FloatingParticles.tsx
│   │   ├── SearchBar.tsx
│   │   ├── PlaylistManager.tsx
│   │   ├── QueueManager.tsx
│   │   ├── FavoritesView.tsx
│   │   ├── HistoryView.tsx
│   │   ├── DiscoverView.tsx
│   │   ├── LocalSocialDiscovery.tsx
│   │   ├── PremiumAudioControls.tsx
│   │   ├── PlaylistSharingDialog.tsx
│   │   └── SharedPlaylistAccess.tsx
│   │
│   ├── hooks/                      # Custom React hooks
│   │   ├── use-toast.ts
│   │   ├── use-mobile.tsx
│   │   ├── useMusicPlayer.ts       # Core player logic
│   │   ├── useEnhancedListenTogether.ts
│   │   ├── useEnhancedNotifications.ts
│   │   ├── useEnhancedAudioEngine.ts
│   │   ├── useLocalSocialFeatures.ts
│   │   ├── useMobileEnhancements.ts
│   │   └── usePlaylistSharing.ts
│   │
│   ├── pages/                      # Route components
│   │   ├── Index.tsx               # Main app page
│   │   └── NotFound.tsx            # 404 page
│   │
│   ├── services/                   # External API services
│   │   └── jiosaavn.ts             # JioSaavn API
│   │
│   ├── data/                       # Mock/static data
│   │   └── mockMusic.ts            # Demo playlists
│   │
│   ├── lib/                        # Utilities
│   │   └── utils.ts                # Helper functions
│   │
│   ├── App.tsx                     # Root component
│   ├── App.css                     # Global styles
│   ├── main.tsx                    # Entry point
│   ├── index.css                   # Tailwind imports
│   └── vite-env.d.ts               # Vite type declarations
│
├── dist/                           # Build output (generated)
├── node_modules/                   # Dependencies
│
├── .gitignore
├── index.html                      # HTML template
├── package.json                    # Dependencies & scripts
├── package-lock.json
├── vite.config.ts                  # Vite configuration
├── tsconfig.json                   # TypeScript config
├── tsconfig.app.json
├── tsconfig.node.json
├── tailwind.config.ts              # Tailwind config
├── postcss.config.js
├── eslint.config.js
├── components.json                 # shadcn/ui config
├── README.md
├── LISTEN_TOGETHER_ENHANCEMENT.md
└── MOODIFY_COMPLETE_DOCUMENTATION.md  # This file
```

---

### 🧪 **Testing Approach** (Future)

Currently no formal tests, but recommended approach:

**Unit Tests**:
- Test hooks (useMusicPlayer, useListenTogether)
- Test utility functions
- Test component logic (not UI)

**Integration Tests**:
- Test user flows (mood selection → playback)
- Test playlist management CRUD
- Test Listen Together connection

**E2E Tests**:
- Full app flow with Playwright/Cypress
- Mobile responsive testing
- Cross-browser testing

**Tools to Add**:
- Vitest (unit tests)
- React Testing Library (component tests)
- Playwright (E2E tests)

---

## 📝 Summary for AI Assistants

### Key Points to Remember

1. **Moodify is a mood-based music streaming SPA** with 6 mood categories
2. **Tech**: React 18 + TypeScript + Vite + Tailwind + shadcn/ui
3. **Core Features**:
   - Mood-based discovery
   - Full-featured music player
   - Listen Together (WebRTC P2P)
   - Playlist management with import/export
   - Favorites, history, search, discover
4. **State Management**: Custom hooks + localStorage + React Query
5. **Styling**: Tailwind + purple-pink gradient theme + glass morphism
6. **Deployment**: GitHub Pages with Actions CI/CD
7. **Data Persistence**: localStorage for playlists, favorites, history
8. **API**: JioSaavn (currently using mock data)
9. **Routing**: React Router with 404.html redirect for GitHub Pages
10. **Race Condition Prevention**: 300ms deduplication + processing locks

### Common Tasks AI Can Help With

- **Add new mood**: Add to `moods` array in MoodTiles.tsx
- **Add new feature**: Create component + hook if needed
- **Fix bug**: Check console logs, inspect state in useMusicPlayer
- **Styling**: Use Tailwind classes, follow gradient-primary theme
- **Add API**: Extend `src/services/jiosaavn.ts`
- **New view**: Add to `currentView` type and render in Index.tsx

### Important Files to Reference

- **Player Logic**: `src/hooks/useMusicPlayer.ts` (686 lines)
- **Listen Together**: `src/hooks/useEnhancedListenTogether.ts` (663 lines)
- **Main Page**: `src/pages/Index.tsx` (525 lines)
- **Config**: `vite.config.ts`, `tailwind.config.ts`
- **Types**: Check interfaces at top of each file

---

## 🎉 End of Documentation

This documentation covers **100% of Moodify's functionality, architecture, UI, and codebase**. Use it as a reference for understanding, modifying, or extending the application.

**For Questions or Updates**:
- Check component files for inline comments
- Review hook implementations for logic details
- Inspect UI components for styling patterns
- Test features in the live app: https://khageshpatil.github.io/Moodify/

---

**Made with ❤️ by KP**  
**Documented for AI assistants and developers**


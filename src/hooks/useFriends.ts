import { useState, useEffect, useCallback, useRef } from 'react';
import Peer, { DataConnection } from 'peerjs';
import { MoodifyIdentity } from './useAnonymousIdentity';
import { toast } from './use-toast';

export interface Friend {
  id: string;
  peerId: string;
  peerCode: string;
  displayName: string;
  avatar: string;
  addedAt: number;
  lastSeen: number;
  isOnline: boolean;
  connection?: DataConnection;
}

export type MessageType = 'text' | 'photo' | 'song' | 'photo_song';
export type MessageStatus = 'sending' | 'sent' | 'delivered' | 'read' | 'failed';

export interface MediaAttachment {
  type: 'photo' | 'song';
  data: string;              // Base64 for photos, song ID for songs
  metadata?: {
    // Photos
    width?: number;
    height?: number;
    size?: number;
    thumbnail?: string;      // Smaller preview
    fileName?: string;
    
    // Songs
    songId?: string;
    title?: string;
    artist?: string;
    album?: string;
    albumArt?: string;
    duration?: number;
    url?: string;
  };
}

export interface ChatMessage {
  id: string;
  senderId: string;
  friendId: string;
  
  // Message content
  messageType: MessageType;
  text?: string;                    // Optional for media-only messages
  attachments?: MediaAttachment[];  // Photo and/or song
  
  // Metadata
  timestamp: number;
  status: MessageStatus;            // Replaces simple delivered boolean
  
  // Optional features
  replyTo?: string;                 // Message ID being replied to
  reaction?: string;                // Emoji reaction
}

export interface FriendRequest {
  id: string;
  peerCode: string;
  displayName: string;
  avatar: string;
  timestamp: number;
}

const STORAGE_KEYS = {
  FRIENDS: 'moodify_friends',
  CHAT_HISTORY: 'moodify_chat_history',
  FRIEND_REQUESTS: 'moodify_friend_requests',
  MY_PEER_ID: 'moodify_my_peer_id',
};

const loadFromStorage = <T,>(key: string, defaultValue: T): T => {
  try {
    const item = localStorage.getItem(key);
    return item ? JSON.parse(item) : defaultValue;
  } catch {
    return defaultValue;
  }
};

const saveToStorage = <T,>(key: string, value: T) => {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch (error) {
    console.error('Failed to save to storage:', error);
  }
};

// Generate a readable peer code from peer ID
const generatePeerCode = (): string => {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  let code = '';
  for (let i = 0; i < 9; i++) {
    code += chars.charAt(Math.floor(Math.random() * chars.length));
    if (i === 2 || i === 5) code += '-';
  }
  return code;
};

const peerCodeToPeerId = (code: string): string => {
  return `moodify_${code.replace(/-/g, '').toLowerCase()}`;
};

const peerIdToPeerCode = (peerId: string): string => {
  const clean = peerId.replace('moodify_', '').toUpperCase();
  return `${clean.slice(0, 3)}-${clean.slice(3, 6)}-${clean.slice(6, 9)}`;
};

// Global flag to prevent React Strict Mode double initialization
// Use a Map keyed by peer ID to allow multiple devices/tabs
const globalPeerInstances = new Map<string, Peer>();
const globalPeerInitializing = new Set<string>();

export const useFriends = (myIdentity: MoodifyIdentity | null) => {
  const [friends, setFriends] = useState<Friend[]>(loadFromStorage(STORAGE_KEYS.FRIENDS, []));
  const [friendRequests, setFriendRequests] = useState<FriendRequest[]>(loadFromStorage(STORAGE_KEYS.FRIEND_REQUESTS, []));
  const [chatHistory, setChatHistory] = useState<Record<string, ChatMessage[]>>(loadFromStorage(STORAGE_KEYS.CHAT_HISTORY, {}));
  const [myPeerId, setMyPeerId] = useState<string | null>(loadFromStorage(STORAGE_KEYS.MY_PEER_ID, null));
  const [myPeerCode, setMyPeerCode] = useState<string>('');

  const peerRef = useRef<Peer | null>(null);
  const connectionsRef = useRef<Map<string, DataConnection>>(new Map());
  const initializingRef = useRef(false);
  const hasInitialized = useRef(false);

  // Initialize peer (only once)
  useEffect(() => {
    if (!myIdentity) return;
    
    // Load peer ID first to check global state
    const storedPeerId = myPeerId || loadFromStorage(STORAGE_KEYS.MY_PEER_ID, null);
    
    // Prevent multiple initializations using global flag keyed by peer ID
    const isInitializing = storedPeerId && globalPeerInitializing.has(storedPeerId);
    const existingInstance = storedPeerId && globalPeerInstances.get(storedPeerId);
    
    if (isInitializing || existingInstance || peerRef.current || hasInitialized.current) {
      console.log('⏭️ Skipping peer init:', {
        hasIdentity: !!myIdentity,
        isInitializing,
        existingInstance: !!existingInstance,
        hasPeer: !!peerRef.current,
        hasInitialized: hasInitialized.current
      });
      
      // If global instance exists but not in our ref, use it
      if (existingInstance && !peerRef.current) {
        console.log('♻️ Reusing existing peer instance');
        peerRef.current = existingInstance;
        hasInitialized.current = true;
        
        // Set the peer code from the stored peer ID
        if (storedPeerId) {
          const code = peerIdToPeerCode(storedPeerId);
          setMyPeerCode(code);
        }
      }
      
      return;
    }

    initializingRef.current = true;

    const initPeer = async (retryCount = 0) => {
      try {
        // Generate or load peer ID
        let peerId = myPeerId;
        if (!peerId) {
          const code = generatePeerCode();
          peerId = peerCodeToPeerId(code);
          setMyPeerId(peerId);
          saveToStorage(STORAGE_KEYS.MY_PEER_ID, peerId);
        }

        // Mark this peer ID as initializing
        globalPeerInitializing.add(peerId);

        const code = peerIdToPeerCode(peerId);
        setMyPeerCode(code);

        // Add longer delay on retry to ensure previous peer is fully released
        if (retryCount > 0) {
          const delay = 3000 * retryCount; // 3s, 6s, 9s, 12s, 15s
          console.log(`⏳ Waiting ${delay}ms before retry...`);
          await new Promise(resolve => setTimeout(resolve, delay));
        }

        // Use the base peer ID directly for connections
        // This allows friends to connect using just the friend code
        console.log(`🔌 Connecting with peer ID: ${peerId}`);

        const peer = new Peer(peerId, {
          config: {
            iceServers: [
              { urls: 'stun:stun.l.google.com:19302' },
              { urls: 'stun:global.stun.twilio.com:3478' },
            ],
          },
        });

        peer.on('open', (id) => {
          console.log('🎵 Friends P2P initialized:', id);
          peerRef.current = peer;
          globalPeerInstances.set(peerId, peer); // Store globally to prevent double init
          initializingRef.current = false;
          globalPeerInitializing.delete(peerId);
          hasInitialized.current = true;
          toast({
            title: '✅ Friends System Ready',
            description: `Your friend code: ${code}`,
          });
        });

        peer.on('connection', (conn) => {
          console.log('📥 Incoming connection from:', conn.peer);
          setupConnection(conn);
        });

        peer.on('error', (error) => {
          // Suppress expected connection errors (friend offline/unreachable)
          if (error.type === 'peer-unavailable') {
            console.log('⚠️ Could not connect to peer (offline or unreachable):', error.message);
            return;
          }
          
          console.error('Peer error:', error);
          
          // If ID is taken, it's likely React Strict Mode double-mount
          if (error.type === 'unavailable-id') {
            console.log(`⚠️ Peer ID "${peerId}" is taken (likely React Strict Mode)`);
            
            // Destroy the failed peer instance
            try {
              peer.destroy();
            } catch (e) {
              console.error('Error destroying peer:', e);
            }
            peerRef.current = null;
            
            // Reset global flags
            globalPeerInitializing.delete(peerId);
            
            // On first error, check if we already have a successful global instance
            const existingPeer = globalPeerInstances.get(peerId);
            if (retryCount === 0 && existingPeer) {
              console.log('✅ Using existing global peer instance instead');
              peerRef.current = existingPeer;
              hasInitialized.current = true;
              initializingRef.current = false;
              return;
            }
            
            // Otherwise wait for server to release the ID
            if (retryCount === 0) {
              console.log('⏳ Waiting 5s for PeerJS server to release ID...');
              initializingRef.current = false;
              hasInitialized.current = false;
              setTimeout(() => initPeer(1), 5000);
            } else if (retryCount < 3) {
              // Subsequent retries with longer delays
              const delay = 5000 + (2000 * retryCount);
              console.log(`⏳ Retry ${retryCount + 1}/3 in ${delay}ms...`);
              initializingRef.current = false;
              hasInitialized.current = false;
              setTimeout(() => initPeer(retryCount + 1), delay);
            } else {
              // After 3 retries, generate a new ID
              console.error('❌ Could not initialize peer after retries. Generating new ID...');
              const newCode = generatePeerCode();
              const newPeerId = peerCodeToPeerId(newCode);
              console.log('🆕 New peer ID:', newPeerId);
              setMyPeerId(newPeerId);
              saveToStorage(STORAGE_KEYS.MY_PEER_ID, newPeerId);
              initializingRef.current = false;
              hasInitialized.current = false;
              setTimeout(() => initPeer(0), 1000);
            }
          } else {
            initializingRef.current = false;
          }
        });

      } catch (error) {
        console.error('Failed to initialize peer:', error);
      }
    };

    initPeer();

    return () => {
      // Cleanup on unmount - but preserve successful initialization
      console.log('🧹 Cleaning up peer connection...');
      
      // Only reset if peer wasn't successfully initialized
      if (!peerRef.current) {
        hasInitialized.current = false;
      }
      initializingRef.current = false;
      
      connectionsRef.current.forEach(conn => {
        try {
          conn.close();
        } catch (e) {
          console.error('Error closing connection:', e);
        }
      });
      connectionsRef.current.clear();
      
      // Don't destroy the peer if it's successfully connected
      // This prevents React Strict Mode from breaking the connection
      if (peerRef.current && !peerRef.current.disconnected && !peerRef.current.destroyed) {
        console.log('✅ Keeping peer connection alive:', peerRef.current.id);
        // Don't destroy or null out the peer
      }
    };
  }, [myIdentity]); // Remove myPeerId from dependencies to prevent re-initialization

  // Retry sending failed/pending messages when friend comes online
  const retryPendingMessages = useCallback((friendId: string) => {
    const friend = friends.find(f => f.id === friendId);
    if (!friend || !myIdentity) return;

    const conn = connectionsRef.current.get(friend.peerId);
    if (!conn || !conn.open) return;

    const messages = chatHistory[friendId] || [];
    const pendingMessages = messages.filter(m => 
      m.senderId === myIdentity.anonId && 
      (m.status === 'sending' || m.status === 'failed')
    );

    if (pendingMessages.length === 0) return;

    console.log(`🔄 Retrying ${pendingMessages.length} pending messages to ${friend.displayName}`);

    pendingMessages.forEach(message => {
      try {
        conn.send({
          type: 'message',
          messageId: message.id,
          senderId: message.senderId,
          messageType: message.messageType,
          text: message.text,
          attachments: message.attachments,
          timestamp: message.timestamp,
        });

        // Update status to sent
        setChatHistory(prev => ({
          ...prev,
          [friendId]: prev[friendId].map(m => 
            m.id === message.id ? { ...m, status: 'sent' } : m
          ),
        }));
      } catch (error) {
        console.error('Failed to retry message:', error);
      }
    });
  }, [friends, chatHistory, myIdentity]);

  // Setup connection handlers
  const setupConnection = useCallback((conn: DataConnection) => {
    const peerId = conn.peer;
    
    console.log('🔗 Setting up connection with:', peerId);
    console.log('   Connection state:', conn.peerConnection?.connectionState);
    
    conn.on('open', () => {
      console.log('✅ Connection opened with:', peerId);
      console.log('   Connection state:', conn.peerConnection?.connectionState);
      console.log('   Data channel state:', conn.dataChannel?.readyState);
      connectionsRef.current.set(peerId, conn);

      // Update friend status
      setFriends(prev => prev.map(f => {
        if (f.peerId === peerId) {
          console.log('   Updating friend online status:', f.displayName);
          return { ...f, isOnline: true, connection: conn, lastSeen: Date.now() };
        }
        return f;
      }));

      // Retry pending messages when friend comes online
      const friendId = peerId;
      retryPendingMessages(friendId);
    });

    conn.on('data', (data: any) => {
      console.log('📨 Received data from', peerId, 'type:', data.type);
      console.log('   Data:', JSON.stringify(data, null, 2));
      
      if (data.type === 'friend_request') {
        // Incoming friend request
        console.log('👋 Incoming friend request from:', data.displayName, '(', data.peerId, ')');
        const request: FriendRequest = {
          id: data.peerId,
          peerCode: peerIdToPeerCode(data.peerId),
          displayName: data.displayName,
          avatar: data.avatar,
          timestamp: Date.now(),
        };
        setFriendRequests(prev => {
          if (prev.some(r => r.id === request.id)) {
            console.log('⚠️ Friend request already exists');
            return prev;
          }
          console.log('✅ Adding friend request to list');
          return [...prev, request];
        });
        toast({
          title: '👋 Friend Request',
          description: `${data.displayName} wants to be friends!`,
        });
      } else if (data.type === 'friend_accept') {
        // Friend accepted our request - add them to our friends list
        console.log('🎉 Friend accepted our request:', data.displayName);
        
        const friend: Friend = {
          id: data.peerId,
          peerId: data.peerId,
          peerCode: peerIdToPeerCode(data.peerId),
          displayName: data.displayName,
          avatar: data.avatar,
          addedAt: Date.now(),
          lastSeen: Date.now(),
          isOnline: true,
          connection: conn,
        };
        
        setFriends(prev => {
          if (prev.some(f => f.id === friend.id)) {
            console.log('⚠️ Friend already exists, updating status');
            return prev.map(f => f.id === friend.id ? friend : f);
          }
          console.log('✅ Adding new friend');
          return [...prev, friend];
        });
        
        toast({
          title: '✅ Friend Added!',
          description: `${data.displayName} is now your friend`,
        });
      } else if (data.type === 'message') {
        // Incoming chat message - use sender's message ID to prevent duplicates
        const messageId = data.messageId || `${data.timestamp}_${Math.random()}`;
        
        // Check for duplicate
        const existingMessages = chatHistory[peerId] || [];
        if (existingMessages.some(m => m.id === messageId)) {
          console.log('⚠️ Duplicate message received, ignoring');
          return;
        }
        
        const message: ChatMessage = {
          id: messageId,
          senderId: data.senderId,
          friendId: peerId,
          messageType: data.messageType || 'text',
          text: data.text,
          attachments: data.attachments,
          timestamp: data.timestamp,
          status: 'delivered',  // Incoming messages are delivered by definition
        };
        
        setChatHistory(prev => ({
          ...prev,
          [peerId]: [...(prev[peerId] || []), message],
        }));

        // Send delivery confirmation back to sender
        try {
          conn.send({ type: 'message_delivered', messageId });
        } catch (error) {
          console.error('Failed to send delivery confirmation:', error);
        }
      } else if (data.type === 'message_delivered') {
        // Mark our sent message as delivered
        setChatHistory(prev => {
          const updated = { ...prev };
          Object.keys(updated).forEach(friendId => {
            updated[friendId] = updated[friendId].map(m =>
              m.id === data.messageId && m.status === 'sent' 
                ? { ...m, status: 'delivered' } 
                : m
            );
          });
          return updated;
        });
      } else if (data.type === 'message_read') {
        // Mark messages as read
        setChatHistory(prev => {
          const updated = { ...prev };
          Object.keys(updated).forEach(friendId => {
            updated[friendId] = updated[friendId].map(m =>
              m.id === data.messageId 
                ? { ...m, status: 'read' } 
                : m
            );
          });
          return updated;
        });
      }
    });

    conn.on('close', () => {
      console.log('❌ Connection closed with:', peerId);
      console.log('   Reason: Connection terminated');
      connectionsRef.current.delete(peerId);

      setFriends(prev => prev.map(f => {
        if (f.peerId === peerId) {
          console.log('   Marking friend offline:', f.displayName);
          return { ...f, isOnline: false, lastSeen: Date.now() };
        }
        return f;
      }));
    });

    conn.on('error', (error) => {
      console.error('❌ Connection error with', peerId, ':', error);
      console.error('   Error type:', error.type);
      console.error('   Error message:', error.message);
      connectionsRef.current.delete(peerId);
      
      setFriends(prev => prev.map(f => {
        if (f.peerId === peerId) {
          console.log('   Marking friend offline due to error:', f.displayName);
          return { ...f, isOnline: false, lastSeen: Date.now() };
        }
        return f;
      }));
    });
  }, [retryPendingMessages]);

  // Attempt to reconnect to existing friends when peer is ready (with debounce)
  useEffect(() => {
    if (!peerRef.current || friends.length === 0) return;

    // Debounce to prevent React Strict Mode duplicate calls
    const timer = setTimeout(() => {
      console.log('🔄 Attempting to reconnect to', friends.length, 'friends');
      
      friends.forEach(friend => {
        // Skip if already connected
        if (connectionsRef.current.has(friend.peerId)) {
          console.log('✅ Already connected to:', friend.displayName);
          return;
        }

        // Try to connect
        console.log('🔌 Reconnecting to friend:', friend.displayName, '(', friend.peerId, ')');
        try {
          const conn = peerRef.current!.connect(friend.peerId);
          setupConnection(conn);
        } catch (error) {
          console.error('Failed to reconnect to', friend.displayName, ':', error);
        }
      });
    }, 100);

    return () => clearTimeout(timer);
  }, [peerRef.current, friends, setupConnection]);

  // Send friend request
  const sendFriendRequest = useCallback(async (friendCode: string) => {
    if (!peerRef.current || !myIdentity) {
      throw new Error('P2P not initialized');
    }

    const friendPeerId = peerCodeToPeerId(friendCode);
    const myPeerId = peerRef.current.id; // Our peer ID
    
    console.log('📤 Sending friend request to:', friendPeerId);
    console.log('   My peer ID:', myPeerId);
    console.log('   My display name:', myIdentity.displayName);
    console.log('   Initiating connection...');
    
    try {
      const conn = peerRef.current.connect(friendPeerId);
      
      // Add handlers here first, THEN call setupConnection after the request is sent
      conn.on('open', () => {
        console.log('✅ Friend request connection opened');
        console.log('   Connection state:', conn.peerConnection?.connectionState);
        console.log('   Sending friend_request message...');
        
        conn.send({
          type: 'friend_request',
          peerId: myPeerId, // Send our peer ID
          displayName: myIdentity.displayName,
          avatar: myIdentity.avatarSeed,
        });
        
        console.log('   Setting up ongoing connection handlers...');
        // Now setup connection for future communication (listening for friend_accept)
        setupConnection(conn);
        
        toast({
          title: '✅ Friend Request Sent',
          description: 'Waiting for acceptance...',
        });
      });

      conn.on('error', (error) => {
        console.error('❌ Friend request connection error:', error);
        console.error('   Error type:', error.type);
        toast({
          title: 'Connection Failed',
          description: 'Could not reach friend. They may be offline.',
          variant: 'destructive',
        });
      });
    } catch (error) {
      console.error('❌ Failed to initiate friend request:', error);
      toast({
        title: 'Error',
        description: 'Failed to send friend request',
        variant: 'destructive',
      });
      throw new Error('Failed to send friend request');
    }
  }, [myIdentity, setupConnection]);

  // Accept friend request
  const acceptFriendRequest = useCallback(async (requestId: string) => {
    if (!peerRef.current || !myIdentity) {
      toast({
        title: 'Error',
        description: 'P2P system not initialized',
        variant: 'destructive',
      });
      return;
    }

    const request = friendRequests.find(r => r.id === requestId);
    if (!request) {
      console.error('Friend request not found:', requestId);
      return;
    }

    const myPeerId = peerRef.current.id;

    console.log('🤝 Accepting friend request from:', request.displayName);
    console.log('   Request ID (their peer ID):', requestId);
    console.log('   My peer ID:', myPeerId);
    console.log('   Initiating connection back to them...');

    try {
      const conn = peerRef.current.connect(requestId);
      
      // Add ONE set of handlers here, don't call setupConnection yet
      conn.on('open', () => {
        console.log('✅ Friend acceptance connection opened');
        console.log('   Connection state:', conn.peerConnection?.connectionState);
        console.log('   Sending friend_accept message...');
        
        // Send acceptance message
        conn.send({
          type: 'friend_accept',
          peerId: myPeerId,
          displayName: myIdentity.displayName,
          avatar: myIdentity.avatarSeed,
        });

        // Add to friends list
        const friend: Friend = {
          id: requestId,
          peerId: requestId,
          peerCode: request.peerCode,
          displayName: request.displayName,
          avatar: request.avatar,
          addedAt: Date.now(),
          lastSeen: Date.now(),
          isOnline: true,
          connection: conn,
        };

        setFriends(prev => {
          if (prev.some(f => f.id === friend.id)) {
            console.log('⚠️ Friend already exists, updating');
            return prev.map(f => f.id === friend.id ? friend : f);
          }
          console.log('✅ Adding friend to list:', friend.displayName);
          return [...prev, friend];
        });
        
        // Remove from requests
        setFriendRequests(prev => {
          console.log('🗑️ Removing friend request:', requestId);
          return prev.filter(r => r.id !== requestId);
        });
        
        // NOW setup the connection handlers for ongoing communication
        console.log('   Setting up ongoing connection handlers...');
        setupConnection(conn);
        
        toast({
          title: '✅ Friend Added',
          description: `${request.displayName} is now your friend!`,
        });
      });

      conn.on('error', (error) => {
        console.error('❌ Friend acceptance connection error:', error);
        toast({
          title: 'Connection Failed',
          description: 'Could not connect to friend. They may be offline.',
          variant: 'destructive',
        });
      });
    } catch (error) {
      console.error('❌ Failed to accept friend request:', error);
      toast({
        title: 'Error',
        description: 'Failed to accept friend request',
        variant: 'destructive',
      });
    }
  }, [friendRequests, myIdentity, setupConnection]);

  // Reject friend request
  const rejectFriendRequest = useCallback(async (requestId: string) => {
    setFriendRequests(prev => prev.filter(r => r.id !== requestId));
  }, []);

  // Remove friend
  const removeFriend = useCallback(async (friendId: string) => {
    const friend = friends.find(f => f.id === friendId);
    if (friend) {
      const conn = connectionsRef.current.get(friend.peerId);
      if (conn) {
        try {
          conn.close();
        } catch (e) {
          console.error('Error closing connection:', e);
        }
        connectionsRef.current.delete(friend.peerId);
      }
    }
    setFriends(prev => prev.filter(f => f.id !== friendId));
    setChatHistory(prev => {
      const updated = { ...prev };
      delete updated[friendId];
      return updated;
    });
    
    toast({
      title: 'Friend Removed',
      description: 'Friend has been removed from your list',
    });
  }, [friends]);

  // Send message with retry support
  const sendMessage = useCallback(async (
    friendId: string, 
    text?: string, 
    attachments?: MediaAttachment[]
  ) => {
    if (!myIdentity) return;

    const friend = friends.find(f => f.id === friendId);
    if (!friend) {
      console.error('Friend not found:', friendId);
      return;
    }

    // Determine message type
    let messageType: MessageType = 'text';
    if (attachments && attachments.length > 0) {
      const hasPhoto = attachments.some(a => a.type === 'photo');
      const hasSong = attachments.some(a => a.type === 'song');
      if (hasPhoto && hasSong) messageType = 'photo_song';
      else if (hasPhoto) messageType = 'photo';
      else if (hasSong) messageType = 'song';
    }

    const message: ChatMessage = {
      id: `${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
      senderId: myIdentity.anonId,
      friendId,
      messageType,
      text,
      attachments,
      timestamp: Date.now(),
      status: 'sending',
    };

    // Add to local chat history immediately with "sending" status
    setChatHistory(prev => ({
      ...prev,
      [friendId]: [...(prev[friendId] || []), message],
    }));

    // Send via P2P if online
    const conn = connectionsRef.current.get(friend.peerId);
    
    if (conn && conn.open) {
      try {
        conn.send({
          type: 'message',
          messageId: message.id,
          senderId: myIdentity.anonId,
          messageType,
          text,
          attachments,
          timestamp: message.timestamp,
        });
        
        // Mark as sent immediately (will be marked delivered when friend confirms)
        setChatHistory(prev => ({
          ...prev,
          [friendId]: prev[friendId].map(m => 
            m.id === message.id ? { ...m, status: 'sent' } : m
          ),
        }));
        
        console.log('✅ Message sent to', friend.displayName);
      } catch (error) {
        console.error('Failed to send message:', error);
        
        // Mark message as failed
        setChatHistory(prev => ({
          ...prev,
          [friendId]: prev[friendId].map(m => 
            m.id === message.id ? { ...m, status: 'failed' } : m
          ),
        }));
        
        toast({
          title: 'Message Send Failed',
          description: 'Could not send message. Tap to retry.',
          variant: 'destructive',
        });
      }
    } else {
      console.log('⚠️ Friend is offline, message queued locally');
      // Keep status as "sending" - will retry when friend comes online
      toast({
        title: 'Friend Offline',
        description: 'Message will be delivered when they come online.',
      });
    }
  }, [friends, myIdentity]);

  // Get chat history
  const getChatHistory = useCallback((friendId: string): ChatMessage[] => {
    return chatHistory[friendId] || [];
  }, [chatHistory]);

  // Get unread count (fixed logic)
  const getUnreadCount = useCallback((friendId: string): number => {
    const messages = chatHistory[friendId] || [];
    // Count messages from friend that haven't been read yet
    return messages.filter(m => 
      m.senderId !== myIdentity?.anonId && 
      (m.status === 'delivered' || m.status === 'sent')
    ).length;
  }, [chatHistory, myIdentity]);

  // Mark messages as read
  const markMessagesAsRead = useCallback((friendId: string) => {
    setChatHistory(prev => ({
      ...prev,
      [friendId]: (prev[friendId] || []).map(m => {
        // Only mark friend's messages as read, not our own
        if (m.senderId !== myIdentity?.anonId && m.status !== 'read') {
          // Send read receipt to friend
          const friend = friends.find(f => f.id === friendId);
          if (friend) {
            const conn = connectionsRef.current.get(friend.peerId);
            if (conn && conn.open) {
              try {
                conn.send({ type: 'message_read', messageId: m.id });
              } catch (error) {
                console.error('Failed to send read receipt:', error);
              }
            }
          }
          return { ...m, status: 'read' as MessageStatus };
        }
        return m;
      }),
    }));
  }, [myIdentity, friends]);

  // Debounced localStorage saves to improve performance
  const saveDebounceTimers = useRef<{
    friends?: NodeJS.Timeout;
    chatHistory?: NodeJS.Timeout;
    friendRequests?: NodeJS.Timeout;
  }>({});

  useEffect(() => {
    if (saveDebounceTimers.current.friends) {
      clearTimeout(saveDebounceTimers.current.friends);
    }
    
    saveDebounceTimers.current.friends = setTimeout(() => {
      const friendsToSave = friends.map(({ connection, ...friend }) => friend);
      saveToStorage(STORAGE_KEYS.FRIENDS, friendsToSave);
    }, 500); // Debounce 500ms

    return () => {
      if (saveDebounceTimers.current.friends) {
        clearTimeout(saveDebounceTimers.current.friends);
      }
    };
  }, [friends]);

  useEffect(() => {
    if (saveDebounceTimers.current.chatHistory) {
      clearTimeout(saveDebounceTimers.current.chatHistory);
    }
    
    saveDebounceTimers.current.chatHistory = setTimeout(() => {
      saveToStorage(STORAGE_KEYS.CHAT_HISTORY, chatHistory);
    }, 1000); // Debounce 1s for chat (more frequent updates)

    return () => {
      if (saveDebounceTimers.current.chatHistory) {
        clearTimeout(saveDebounceTimers.current.chatHistory);
      }
    };
  }, [chatHistory]);

  useEffect(() => {
    if (saveDebounceTimers.current.friendRequests) {
      clearTimeout(saveDebounceTimers.current.friendRequests);
    }
    
    saveDebounceTimers.current.friendRequests = setTimeout(() => {
      saveToStorage(STORAGE_KEYS.FRIEND_REQUESTS, friendRequests);
    }, 500);

    return () => {
      if (saveDebounceTimers.current.friendRequests) {
        clearTimeout(saveDebounceTimers.current.friendRequests);
      }
    };
  }, [friendRequests]);

  return {
    myPeerCode,
    friends,
    friendRequests,
    sendFriendRequest,
    acceptFriendRequest,
    rejectFriendRequest,
    removeFriend,
    sendMessage,
    getChatHistory,
    getUnreadCount,
    markMessagesAsRead,
    retryPendingMessages,
  };
};

import { useState, useEffect, useRef, useCallback } from 'react';
import Peer, { DataConnection } from 'peerjs';
import { useToast } from '@/hooks/use-toast';

export interface ChatMessage {
  id: string;
  message: string;
  timestamp: Date;
  isOwn: boolean;
  username?: string;
  identity?: {
    anonId: string;
    displayName: string;
    avatarSeed: string;
  };
}

export interface ConnectedGuest {
  id: string;
  peerId: string;
  username: string;
  connection: DataConnection;
  lastHeartbeat: Date;
  isLive: boolean;
  identity?: {
    anonId: string;
    displayName: string;
    avatarSeed: string;
  };
}

export interface LiveStreamState {
  isLive: boolean;
  streamUrl: string | null;
  currentTime: number;
  isPlaying: boolean;
  platform: 'youtube' | 'twitch' | 'custom' | null;
}

export interface ListenTogetherState {
  isHost: boolean;
  isConnected: boolean;
  roomCode: string | null;
  connectedGuests: ConnectedGuest[];
  peer: Peer | null;
  messages: ChatMessage[];
  connectionStatus: 'disconnected' | 'connecting' | 'connected' | 'error';
  liveStream: LiveStreamState;
  maxGuests: number;
}

const HEARTBEAT_INTERVAL = 30000; // 30 seconds
const CONNECTION_TIMEOUT = 60000; // 1 minute
const SYNC_INTERVAL = 1000; // 1 second for live sync

export const useEnhancedListenTogether = (
  onRemoteControl: (action: string, data?: any) => void,
  userIdentity?: { anonId: string; displayName: string; avatarSeed: string } | null
) => {
  const { toast } = useToast();
  
  const [state, setState] = useState<ListenTogetherState>({
    isHost: false,
    isConnected: false,
    roomCode: null,
    connectedGuests: [],
    peer: null,
    messages: [],
    connectionStatus: 'disconnected',
    liveStream: {
      isLive: false,
      streamUrl: null,
      currentTime: 0,
      isPlaying: false,
      platform: null,
    },
    maxGuests: 10,
  });

  const connectionsRef = useRef<Map<string, DataConnection>>(new Map());
  const peerRef = useRef<Peer | null>(null);
  const heartbeatIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const reconnectTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const syncIntervalRef = useRef<NodeJS.Timeout | null>(null);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      clearInterval(heartbeatIntervalRef.current!);
      clearTimeout(reconnectTimeoutRef.current!);
      clearInterval(syncIntervalRef.current!);
      
      connectionsRef.current.forEach(conn => conn.close());
      connectionsRef.current.clear();
      
      if (peerRef.current) {
        peerRef.current.destroy();
      }
    };
  }, []);

  // Heartbeat system
  const startHeartbeat = useCallback(() => {
    if (heartbeatIntervalRef.current) {
      clearInterval(heartbeatIntervalRef.current);
    }
    
    heartbeatIntervalRef.current = setInterval(() => {
      const now = new Date();
      
      // Send heartbeat to all connections
      connectionsRef.current.forEach((conn, peerId) => {
        if (conn.open) {
          try {
            conn.send({ type: 'heartbeat', timestamp: now.getTime() });
          } catch (err) {
            console.error('Failed to send heartbeat:', err);
          }
        }
      });

      // Check for dead connections - OPTIMIZED: only setState if there are timeouts
      setState(prev => {
        const timedOutGuests: string[] = [];
        const activeGuests = prev.connectedGuests.filter(guest => {
          const timeDiff = now.getTime() - guest.lastHeartbeat.getTime();
          if (timeDiff > CONNECTION_TIMEOUT) {
            timedOutGuests.push(guest.peerId);
            const conn = connectionsRef.current.get(guest.peerId);
            if (conn) {
              conn.close();
              connectionsRef.current.delete(guest.peerId);
            }
            return false;
          }
          return true;
        });
        
        // Only update state if guests were actually removed
        if (timedOutGuests.length > 0) {
          timedOutGuests.forEach(peerId => {
            const guest = prev.connectedGuests.find(g => g.peerId === peerId);
            if (guest) {
              toast({
                title: 'Guest disconnected',
                description: `${guest.username} has timed out`,
                variant: 'destructive',
              });
            }
          });
          return { ...prev, connectedGuests: activeGuests };
        }
        
        // No changes, return same state to avoid re-render
        return prev;
      });
    }, HEARTBEAT_INTERVAL);
  }, [toast]);

  // Live streaming sync
  const startLiveSync = useCallback((streamData: any) => {
    if (syncIntervalRef.current) {
      clearInterval(syncIntervalRef.current);
    }

    syncIntervalRef.current = setInterval(() => {
      if (state.isHost && state.liveStream.isLive) {
        // Broadcast current stream state to all guests
        const syncData = {
          type: 'live_sync',
          streamUrl: state.liveStream.streamUrl,
          currentTime: streamData.currentTime || 0,
          isPlaying: streamData.isPlaying || false,
          platform: state.liveStream.platform,
          timestamp: Date.now(),
        };

        connectionsRef.current.forEach(conn => {
          if (conn.open) {
            try {
              conn.send(syncData);
            } catch (err) {
              console.error('Failed to sync live stream:', err);
            }
          }
        });
      }
    }, SYNC_INTERVAL);
  }, [state.isHost, state.liveStream]);

  const createRoom = useCallback(() => {
    setState(prev => ({ ...prev, connectionStatus: 'connecting' }));
    
    try {
      // Generate unique room peer ID (different prefix from Friends to avoid conflicts)
      const roomPeerId = `moodify_room_${Date.now().toString(36)}_${Math.random().toString(36).substr(2, 5)}`;
      
      const peer = new Peer(roomPeerId, {
        debug: 1,
        config: {
          iceServers: [
            { urls: 'stun:stun.l.google.com:19302' },
            { urls: 'stun:stun1.l.google.com:19302' },
            { urls: 'stun:stun2.l.google.com:19302' },
          ]
        }
      });
      peerRef.current = peer;

      peer.on('open', (id) => {
        console.log('Room created with ID:', id);
        setState(prev => ({
          ...prev,
          isHost: true,
          roomCode: id,
          peer,
          connectionStatus: 'connected',
        }));
        
        toast({
          title: 'Room Created!',
          description: `Room code: ${id}`,
        });
        
        startHeartbeat();
      });

      peer.on('connection', (conn) => {
        console.log('Guest connecting:', conn.peer);
        
        // Check guest limit
        if (connectionsRef.current.size >= state.maxGuests) {
          conn.close();
          toast({
            title: 'Room Full',
            description: 'Maximum number of guests reached',
            variant: 'destructive',
          });
          return;
        }

        connectionsRef.current.set(conn.peer, conn);
        
        conn.on('open', () => {
          const guestId = Date.now().toString();
          const newGuest: ConnectedGuest = {
            id: guestId,
            peerId: conn.peer,
            username: `Guest ${conn.peer.substring(0, 8)}`,
            connection: conn,
            lastHeartbeat: new Date(),
            isLive: false,
            identity: undefined, // Will be updated when guest sends identity
          };

          setState(prev => ({
            ...prev,
            isConnected: true,
            connectedGuests: [...prev.connectedGuests, newGuest],
          }));

          toast({
            title: 'Guest Connected!',
            description: `${newGuest.username} joined the room`,
          });

          // Send current state to new guest
          if (state.liveStream.isLive) {
            conn.send({
              type: 'live_sync',
              streamUrl: state.liveStream.streamUrl,
              currentTime: state.liveStream.currentTime,
              isPlaying: state.liveStream.isPlaying,
              platform: state.liveStream.platform,
            });
          }
          
          // Send host identity to new guest
          if (userIdentity) {
            setTimeout(() => {
              if (conn.open) {
                conn.send({ type: 'identity', identity: userIdentity });
              }
            }, 100);
          }
        });

        conn.on('close', () => {
          connectionsRef.current.delete(conn.peer);
          setState(prev => ({
            ...prev,
            connectedGuests: prev.connectedGuests.filter(g => g.peerId !== conn.peer),
            isConnected: prev.connectedGuests.length > 1,
          }));
          
          toast({
            title: 'Guest Disconnected',
            description: 'A guest has left the room',
          });
        });

        conn.on('data', (data: any) => {
          handleGuestMessage(data, conn.peer);
        });

        conn.on('error', (err) => {
          console.error('Guest connection error:', err);
          toast({
            title: 'Connection Error',
            description: 'Error with guest connection',
            variant: 'destructive',
          });
        });
      });

      peer.on('error', (err) => {
        console.error('Peer error:', err);
        setState(prev => ({ ...prev, connectionStatus: 'error' }));
        
        toast({
          title: 'Connection Error',
          description: `Failed to create room: ${err.type}`,
          variant: 'destructive',
        });
      });

    } catch (err) {
      console.error('Failed to create room:', err);
      setState(prev => ({ ...prev, connectionStatus: 'error' }));
      
      toast({
        title: 'Error',
        description: 'Failed to create room',
        variant: 'destructive',
      });
    }
  }, [state.maxGuests, state.liveStream, toast, startHeartbeat]);

  const joinRoom = useCallback((roomCode: string) => {
    setState(prev => ({ ...prev, connectionStatus: 'connecting' }));
    
    try {
      // Generate unique guest peer ID (different prefix from Friends to avoid conflicts)
      const guestPeerId = `moodify_guest_${Date.now().toString(36)}_${Math.random().toString(36).substr(2, 5)}`;
      
      const peer = new Peer(guestPeerId, {
        debug: 1,
        config: {
          iceServers: [
            { urls: 'stun:stun.l.google.com:19302' },
            { urls: 'stun:stun1.l.google.com:19302' },
            { urls: 'stun:stun2.l.google.com:19302' },
          ]
        }
      });
      peerRef.current = peer;

      peer.on('open', (id) => {
        console.log('Guest peer opened, connecting to room:', roomCode);
        const conn = peer.connect(roomCode, { reliable: true });
        connectionsRef.current.set(roomCode, conn);

        conn.on('open', () => {
          console.log('Successfully connected to host');
          setState(prev => ({
            ...prev,
            isHost: false,
            isConnected: true,
            roomCode,
            peer,
            connectionStatus: 'connected',
            connectedGuests: [{
              id: 'host',
              peerId: roomCode,
              username: 'Host',
              connection: conn,
              lastHeartbeat: new Date(),
              isLive: false,
              identity: undefined,
            }],
          }));

          toast({
            title: 'Connected!',
            description: 'Successfully joined the room',
          });

          startHeartbeat();
          
          // Send identity to host
          if (userIdentity) {
            setTimeout(() => {
              if (conn.open) {
                conn.send({ type: 'identity', identity: userIdentity });
              }
            }, 100);
          }
        });

        conn.on('data', (data: any) => {
          handleHostMessage(data);
        });

        conn.on('close', () => {
          setState(prev => ({
            ...prev,
            isConnected: false,
            connectionStatus: 'disconnected',
            connectedGuests: [],
          }));
          
          toast({
            title: 'Disconnected',
            description: 'Lost connection to host',
            variant: 'destructive',
          });
        });

        conn.on('error', (err) => {
          console.error('Connection error:', err);
          setState(prev => ({ ...prev, connectionStatus: 'error' }));
          
          toast({
            title: 'Connection Error',
            description: 'Failed to maintain connection',
            variant: 'destructive',
          });
        });
      });

      peer.on('error', (err) => {
        console.error('Peer error:', err);
        setState(prev => ({ ...prev, connectionStatus: 'error' }));
        
        let errorMessage = 'Failed to join room';
        if (err.type === 'peer-unavailable') {
          errorMessage = 'Room not found. Please check the room code.';
        } else if (err.type === 'network') {
          errorMessage = 'Network error. Please check your connection.';
        }
        
        toast({
          title: 'Connection Error',
          description: errorMessage,
          variant: 'destructive',
        });
      });

    } catch (err) {
      console.error('Failed to join room:', err);
      setState(prev => ({ ...prev, connectionStatus: 'error' }));
      
      toast({
        title: 'Error',
        description: 'Failed to join room',
        variant: 'destructive',
      });
    }
  }, [toast, startHeartbeat]);

  const handleGuestMessage = useCallback((data: any, peerId: string) => {
    console.log('Host received from guest:', data);
    
    switch (data.type) {
      case 'heartbeat':
        setState(prev => ({
          ...prev,
          connectedGuests: prev.connectedGuests.map(guest =>
            guest.peerId === peerId
              ? { ...guest, lastHeartbeat: new Date() }
              : guest
          ),
        }));
        break;
        
      case 'identity':
        setState(prev => ({
          ...prev,
          connectedGuests: prev.connectedGuests.map(guest =>
            guest.peerId === peerId
              ? { 
                  ...guest, 
                  identity: data.identity,
                  username: data.identity?.displayName || guest.username
                }
              : guest
          ),
        }));
        break;
        
      case 'chat':
        setState(prev => ({
          ...prev,
          messages: [...prev.messages, {
            id: Date.now().toString(),
            message: data.message,
            timestamp: new Date(),
            isOwn: false,
            username: data.username,
            identity: data.identity,
          }],
        }));
        break;
        
      case 'live_toggle':
        setState(prev => ({
          ...prev,
          connectedGuests: prev.connectedGuests.map(guest =>
            guest.peerId === peerId
              ? { ...guest, isLive: data.isLive }
              : guest
          ),
        }));
        break;
    }
  }, []);

  const handleHostMessage = useCallback((data: any) => {
    console.log('Guest received from host:', data);
    
    switch (data.type) {
      case 'heartbeat':
        setState(prev => ({
          ...prev,
          connectedGuests: prev.connectedGuests.map(guest =>
            guest.id === 'host'
              ? { ...guest, lastHeartbeat: new Date() }
              : guest
          ),
        }));
        break;
        
      case 'identity':
        setState(prev => ({
          ...prev,
          connectedGuests: prev.connectedGuests.map(guest =>
            guest.id === 'host'
              ? { 
                  ...guest, 
                  identity: data.identity,
                  username: data.identity?.displayName || guest.username
                }
              : guest
          ),
        }));
        break;
        
      case 'chat':
        setState(prev => ({
          ...prev,
          messages: [...prev.messages, {
            id: Date.now().toString(),
            message: data.message,
            timestamp: new Date(),
            isOwn: false,
            username: data.identity?.displayName || 'Host',
            identity: data.identity,
          }],
        }));
        break;
        
      case 'live_sync':
        setState(prev => ({
          ...prev,
          liveStream: {
            isLive: true,
            streamUrl: data.streamUrl,
            currentTime: data.currentTime,
            isPlaying: data.isPlaying,
            platform: data.platform,
          },
        }));
        // Apply sync to remote control
        onRemoteControl('live_sync', data);
        break;
        
      default:
        if (data.action) {
          onRemoteControl(data.action, data.data);
        }
    }
  }, [onRemoteControl]);

  const sendControl = useCallback((action: string, data?: any) => {
    if (state.isHost) {
      connectionsRef.current.forEach(conn => {
        if (conn.open) {
          try {
            conn.send({ action, data });
          } catch (err) {
            console.error('Failed to send control:', err);
          }
        }
      });
    }
  }, [state.isHost]);

  const broadcastIdentity = useCallback(() => {
    if (!userIdentity) return;
    
    if (state.isHost) {
      // Host broadcasts identity to all guests
      connectionsRef.current.forEach(conn => {
        if (conn.open) {
          try {
            conn.send({ type: 'identity', identity: userIdentity });
          } catch (err) {
            console.error('Failed to broadcast identity:', err);
          }
        }
      });
    } else {
      // Guest sends identity to host
      const hostConnection = connectionsRef.current.get(state.roomCode!);
      if (hostConnection && hostConnection.open) {
        try {
          hostConnection.send({ type: 'identity', identity: userIdentity });
        } catch (err) {
          console.error('Failed to send identity:', err);
        }
      }
    }
  }, [state.isHost, state.roomCode, userIdentity]);

  const sendMessage = useCallback((message: string) => {
    const username = userIdentity?.displayName || (state.isHost ? 'Host' : 'You');
    
    if (state.isHost) {
      // Host sends to all guests
      connectionsRef.current.forEach(conn => {
        if (conn.open) {
          try {
            conn.send({ type: 'chat', message, username, identity: userIdentity });
          } catch (err) {
            console.error('Failed to send message:', err);
          }
        }
      });
    } else {
      // Guest sends to host
      const hostConnection = connectionsRef.current.get(state.roomCode!);
      if (hostConnection && hostConnection.open) {
        try {
          hostConnection.send({ type: 'chat', message, username, identity: userIdentity });
        } catch (err) {
          console.error('Failed to send message:', err);
        }
      }
    }

    setState(prev => ({
      ...prev,
      messages: [...prev.messages, {
        id: Date.now().toString(),
        message,
        timestamp: new Date(),
        isOwn: true,
        username,
        identity: userIdentity,
      }],
    }));
  }, [state.isHost, state.roomCode, userIdentity]);

  const startLiveStream = useCallback((streamUrl: string, platform: 'youtube' | 'twitch' | 'custom') => {
    setState(prev => ({
      ...prev,
      liveStream: {
        ...prev.liveStream,
        isLive: true,
        streamUrl,
        platform,
        isPlaying: true,
      },
    }));

    if (state.isHost) {
      startLiveSync({ currentTime: 0, isPlaying: true });
      
      toast({
        title: 'Live Stream Started',
        description: `Broadcasting ${platform} stream to all guests`,
      });
    }
  }, [state.isHost, startLiveSync, toast]);

  const stopLiveStream = useCallback(() => {
    if (syncIntervalRef.current) {
      clearInterval(syncIntervalRef.current);
    }

    setState(prev => ({
      ...prev,
      liveStream: {
        isLive: false,
        streamUrl: null,
        currentTime: 0,
        isPlaying: false,
        platform: null,
      },
    }));

    if (state.isHost) {
      sendControl('stop_live_stream');
      
      toast({
        title: 'Live Stream Stopped',
        description: 'Stream ended for all guests',
      });
    }
  }, [state.isHost, sendControl, toast]);

  const toggleGuestLiveMode = useCallback((isLive: boolean) => {
    if (!state.isHost) {
      const hostConnection = connectionsRef.current.get(state.roomCode!);
      if (hostConnection && hostConnection.open) {
        hostConnection.send({ type: 'live_toggle', isLive });
      }
    }
  }, [state.isHost, state.roomCode]);

  const disconnect = useCallback(() => {
    // Clear intervals
    if (heartbeatIntervalRef.current) {
      clearInterval(heartbeatIntervalRef.current);
    }
    if (syncIntervalRef.current) {
      clearInterval(syncIntervalRef.current);
    }
    
    // Close all connections
    connectionsRef.current.forEach(conn => conn.close());
    connectionsRef.current.clear();
    
    // Destroy peer
    if (peerRef.current) {
      peerRef.current.destroy();
    }
    
    setState({
      isHost: false,
      isConnected: false,
      roomCode: null,
      connectedGuests: [],
      peer: null,
      messages: [],
      connectionStatus: 'disconnected',
      liveStream: {
        isLive: false,
        streamUrl: null,
        currentTime: 0,
        isPlaying: false,
        platform: null,
      },
      maxGuests: 10,
    });

    toast({
      title: 'Disconnected',
      description: 'Left the listen together session',
    });
  }, [toast]);

  return {
    state,
    createRoom,
    joinRoom,
    sendControl,
    sendMessage,
    startLiveStream,
    stopLiveStream,
    toggleGuestLiveMode,
    disconnect,
    broadcastIdentity,
  };
};

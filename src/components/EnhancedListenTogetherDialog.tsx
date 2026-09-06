import { useState, useRef, useEffect } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Badge } from '@/components/ui/badge';
import { Switch } from '@/components/ui/switch';
import { 
  Users, 
  Copy, 
  Check, 
  Send, 
  MessageCircle, 
  Radio,
  Play,
  Pause,
  ExternalLink,
  Crown,
  Wifi,
  WifiOff,
  Clock
} from 'lucide-react';
import { useToast } from '@/hooks/use-toast';
import { ChatMessage, ConnectedGuest, LiveStreamState } from '@/hooks/useEnhancedListenTogether';
import { IdentityAvatarSmall } from '@/components/identity/IdentityAvatar';

interface EnhancedListenTogetherDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreateRoom: () => void;
  onJoinRoom: (code: string) => void;
  roomCode: string | null;
  isConnected: boolean;
  isHost: boolean;
  connectedGuests: ConnectedGuest[];
  connectionStatus: 'disconnected' | 'connecting' | 'connected' | 'error';
  liveStream: LiveStreamState;
  onDisconnect: () => void;
  messages: ChatMessage[];
  onSendMessage: (message: string) => void;
  onStartLiveStream: (url: string, platform: 'youtube' | 'twitch' | 'custom') => void;
  onStopLiveStream: () => void;
  onToggleGuestLiveMode: (isLive: boolean) => void;
}

export const EnhancedListenTogetherDialog = ({
  open,
  onOpenChange,
  onCreateRoom,
  onJoinRoom,
  roomCode,
  isConnected,
  isHost,
  connectedGuests,
  connectionStatus,
  liveStream,
  onDisconnect,
  messages,
  onSendMessage,
  onStartLiveStream,
  onStopLiveStream,
  onToggleGuestLiveMode,
}: EnhancedListenTogetherDialogProps) => {
  const [joinCode, setJoinCode] = useState('');
  const [copied, setCopied] = useState(false);
  const [chatMessage, setChatMessage] = useState('');
  const [showChat, setShowChat] = useState(false);
  const [liveStreamUrl, setLiveStreamUrl] = useState('');
  const [selectedPlatform, setSelectedPlatform] = useState<'youtube' | 'twitch' | 'custom'>('youtube');
  const [guestLiveMode, setGuestLiveMode] = useState(false);
  const { toast } = useToast();
  const messagesEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    if (isConnected && connectionStatus === 'connected') {
      toast({
        title: 'Connected!',
        description: isHost 
          ? `Room created with ${connectedGuests.length} guest${connectedGuests.length !== 1 ? 's' : ''}`
          : 'Successfully joined the room',
      });
    }
  }, [isConnected, connectionStatus, isHost, connectedGuests.length, toast]);

  const handleCopyCode = () => {
    if (roomCode) {
      navigator.clipboard.writeText(roomCode);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
      toast({
        title: 'Room code copied!',
        description: 'Share this code with your friends',
      });
    }
  };

  const handleJoin = () => {
    if (joinCode.trim()) {
      onJoinRoom(joinCode.trim());
    } else {
      toast({
        title: 'Invalid room code',
        description: 'Please enter a valid room code',
        variant: 'destructive',
      });
    }
  };

  const handleSendMessage = () => {
    if (chatMessage.trim()) {
      onSendMessage(chatMessage.trim());
      setChatMessage('');
    }
  };

  const handleStartLiveStream = () => {
    if (liveStreamUrl.trim()) {
      onStartLiveStream(liveStreamUrl.trim(), selectedPlatform);
      setLiveStreamUrl('');
    } else {
      toast({
        title: 'Invalid stream URL',
        description: 'Please enter a valid stream URL',
        variant: 'destructive',
      });
    }
  };

  const detectPlatform = (url: string): 'youtube' | 'twitch' | 'custom' => {
    if (url.includes('youtube.com') || url.includes('youtu.be')) {
      return 'youtube';
    } else if (url.includes('twitch.tv')) {
      return 'twitch';
    }
    return 'custom';
  };

  const handleUrlChange = (url: string) => {
    setLiveStreamUrl(url);
    if (url) {
      setSelectedPlatform(detectPlatform(url));
    }
  };

  const getConnectionIcon = () => {
    switch (connectionStatus) {
      case 'connected':
        return <Wifi className="w-4 h-4 text-green-500" />;
      case 'connecting':
        return <Clock className="w-4 h-4 text-yellow-500 animate-spin" />;
      case 'error':
        return <WifiOff className="w-4 h-4 text-red-500" />;
      default:
        return <WifiOff className="w-4 h-4 text-gray-500" />;
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl max-h-[90vh] overflow-hidden flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Users className="w-5 h-5" />
            Listen Together
            {getConnectionIcon()}
            {isConnected && (
              <Badge variant={isHost ? 'default' : 'secondary'}>
                {isHost ? 'Host' : 'Guest'}
              </Badge>
            )}
          </DialogTitle>
        </DialogHeader>

        {!isConnected ? (
          <div className="space-y-6">
            {/* Connection Status */}
            {connectionStatus === 'connecting' && (
              <div className="p-4 bg-blue-50 dark:bg-blue-900/20 rounded-lg border border-blue-200 dark:border-blue-800">
                <div className="flex items-center space-x-2">
                  <Clock className="w-4 h-4 animate-spin" />
                  <span className="text-sm font-medium">Connecting...</span>
                </div>
              </div>
            )}

            {connectionStatus === 'error' && (
              <div className="p-4 bg-red-50 dark:bg-red-900/20 rounded-lg border border-red-200 dark:border-red-800">
                <div className="flex items-center space-x-2">
                  <WifiOff className="w-4 h-4" />
                  <span className="text-sm font-medium text-red-600 dark:text-red-400">
                    Connection failed. Please try again.
                  </span>
                </div>
              </div>
            )}

            {/* Host Section */}
            <div className="space-y-3">
              <h3 className="text-sm font-medium">Host a Session</h3>
              {!roomCode ? (
                <Button 
                  onClick={onCreateRoom} 
                  className="w-full"
                  disabled={connectionStatus === 'connecting'}
                >
                  Create Room
                </Button>
              ) : (
                <div className="space-y-2">
                  <p className="text-sm text-muted-foreground">
                    Share this code with your friends (up to 10 guests):
                  </p>
                  <div className="flex gap-2">
                    <Input
                      value={roomCode}
                      readOnly
                      className="font-mono text-lg"
                    />
                    <Button
                      size="icon"
                      variant="outline"
                      onClick={handleCopyCode}
                    >
                      {copied ? (
                        <Check className="w-4 h-4" />
                      ) : (
                        <Copy className="w-4 h-4" />
                      )}
                    </Button>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    Waiting for guests to join...
                  </p>
                </div>
              )}
            </div>

            <div className="relative">
              <div className="absolute inset-0 flex items-center">
                <span className="w-full border-t" />
              </div>
              <div className="relative flex justify-center text-xs uppercase">
                <span className="bg-background px-2 text-muted-foreground">Or</span>
              </div>
            </div>

            {/* Join Section */}
            <div className="space-y-3">
              <h3 className="text-sm font-medium">Join a Session</h3>
              <div className="flex gap-2">
                <Input
                  placeholder="Enter room code"
                  value={joinCode}
                  onChange={(e) => setJoinCode(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && handleJoin()}
                  disabled={connectionStatus === 'connecting'}
                />
                <Button 
                  onClick={handleJoin}
                  disabled={connectionStatus === 'connecting'}
                >
                  Join
                </Button>
              </div>
            </div>
          </div>
        ) : (
          <div className="space-y-4 flex-1 flex flex-col">
            {/* Connection Status */}
            <div className="rounded-lg bg-gradient-to-r from-primary/20 to-purple-500/20 p-4 border border-primary/20">
              <div className="flex items-center justify-between">
                <div className="flex items-center space-x-2">
                  <Users className="w-5 h-5 text-primary" />
                  <span className="text-sm font-medium">
                    {isHost ? 'Hosting' : 'Connected'}
                  </span>
                  {isHost && <Crown className="w-4 h-4 text-yellow-500" />}
                </div>
                <div className="flex items-center space-x-2">
                  <Badge variant="secondary">
                    {connectedGuests.length} guest{connectedGuests.length !== 1 ? 's' : ''}
                  </Badge>
                </div>
              </div>
            </div>

            {/* Connected Guests List */}
            {connectedGuests.length > 0 && (
              <div className="space-y-2">
                <h4 className="text-sm font-medium">Connected Users</h4>
                <ScrollArea className="max-h-24">
                  <div className="space-y-1">
                    {connectedGuests.map((guest) => (
                      <div
                        key={guest.id}
                        className="flex items-center justify-between p-2 rounded-md bg-muted/50"
                      >
                        <div className="flex items-center space-x-2">
                          {guest.identity ? (
                            <IdentityAvatarSmall seed={guest.identity.avatarSeed} />
                          ) : (
                            <div className="w-8 h-8 rounded-full bg-gradient-to-br from-purple-500 to-pink-500 flex items-center justify-center">
                              <Users className="w-4 h-4 text-white" />
                            </div>
                          )}
                          <div className="flex flex-col">
                            <span className="text-sm font-medium">
                              {guest.identity?.displayName || guest.username}
                            </span>
                            {guest.isLive && (
                              <Badge variant="destructive" className="text-xs w-fit mt-0.5">
                                <Radio className="w-3 h-3 mr-1" />
                                LIVE
                              </Badge>
                            )}
                          </div>
                        </div>
                        <span className="text-xs text-muted-foreground">
                          {new Date(guest.lastHeartbeat).toLocaleTimeString()}
                        </span>
                      </div>
                    ))}
                  </div>
                </ScrollArea>
              </div>
            )}

            {/* Live Stream Controls */}
            {isHost && (
              <div className="space-y-3 p-4 border rounded-lg bg-muted/30">
                <div className="flex items-center justify-between">
                  <h4 className="text-sm font-medium flex items-center gap-2">
                    <Radio className="w-4 h-4" />
                    Live Stream Broadcasting
                  </h4>
                  {liveStream.isLive && (
                    <Badge variant="destructive">
                      <Radio className="w-3 h-3 mr-1" />
                      LIVE
                    </Badge>
                  )}
                </div>
                
                {!liveStream.isLive ? (
                  <div className="space-y-2">
                    <div className="flex gap-2">
                      <Input
                        placeholder="Paste YouTube, Twitch, or custom stream URL"
                        value={liveStreamUrl}
                        onChange={(e) => handleUrlChange(e.target.value)}
                      />
                      <Button onClick={handleStartLiveStream} size="sm">
                        <Play className="w-4 h-4 mr-2" />
                        Start
                      </Button>
                    </div>
                    <div className="flex items-center space-x-2 text-xs text-muted-foreground">
                      <span>Platform:</span>
                      <Badge variant="outline">{selectedPlatform}</Badge>
                    </div>
                  </div>
                ) : (
                  <div className="space-y-2">
                    <div className="flex items-center justify-between p-2 bg-red-50 dark:bg-red-900/20 rounded">
                      <div className="flex items-center space-x-2">
                        <div className="w-2 h-2 bg-red-500 rounded-full animate-pulse" />
                        <span className="text-sm">Broadcasting to all guests</span>
                      </div>
                      <Button onClick={onStopLiveStream} size="sm" variant="destructive">
                        <Pause className="w-4 h-4 mr-2" />
                        Stop
                      </Button>
                    </div>
                    <div className="text-xs text-muted-foreground flex items-center space-x-1">
                      <ExternalLink className="w-3 h-3" />
                      <span className="truncate">{liveStream.streamUrl}</span>
                    </div>
                  </div>
                )}
              </div>
            )}

            {/* Guest Live Mode Toggle */}
            {!isHost && (
              <div className="flex items-center justify-between p-3 border rounded-lg">
                <div>
                  <div className="text-sm font-medium">Live Mode</div>
                  <div className="text-xs text-muted-foreground">
                    Sync with host's live streams
                  </div>
                </div>
                <Switch
                  checked={guestLiveMode}
                  onCheckedChange={(checked) => {
                    setGuestLiveMode(checked);
                    onToggleGuestLiveMode(checked);
                  }}
                />
              </div>
            )}

            {/* Live Stream Status for Guests */}
            {!isHost && liveStream.isLive && guestLiveMode && (
              <div className="p-3 border rounded-lg bg-red-50 dark:bg-red-900/20">
                <div className="flex items-center space-x-2 mb-2">
                  <Radio className="w-4 h-4 text-red-500 animate-pulse" />
                  <span className="text-sm font-medium">Host is Live!</span>
                  <Badge variant="destructive">
                    {liveStream.platform?.toUpperCase()}
                  </Badge>
                </div>
                <div className="text-xs text-muted-foreground">
                  Syncing with host's {liveStream.platform} stream
                </div>
              </div>
            )}

            {/* Chat Toggle */}
            <Button
              variant="outline"
              onClick={() => setShowChat(!showChat)}
              className="w-full"
            >
              <MessageCircle className="h-4 w-4 mr-2" />
              {showChat ? 'Hide Chat' : 'Show Chat'}
              {messages.length > 0 && !showChat && (
                <Badge variant="secondary" className="ml-2">
                  {messages.length}
                </Badge>
              )}
            </Button>

            {/* Chat Section */}
            {showChat && (
              <div className="flex-1 flex flex-col space-y-2 min-h-[200px]">
                <ScrollArea className="flex-1 border rounded-lg p-3 bg-background/50">
                  {messages.length === 0 ? (
                    <p className="text-sm text-muted-foreground text-center py-8">
                      No messages yet. Start chatting!
                    </p>
                  ) : (
                    <div className="space-y-2">
                      {messages.map((msg) => (
                        <div
                          key={msg.id}
                          className={`flex gap-2 ${msg.isOwn ? 'justify-end' : 'justify-start'}`}
                        >
                          {!msg.isOwn && (
                            <div className="flex-shrink-0 mt-1">
                              {msg.identity ? (
                                <IdentityAvatarSmall seed={msg.identity.avatarSeed} />
                              ) : (
                                <div className="w-8 h-8 rounded-full bg-gradient-to-br from-purple-500 to-pink-500 flex items-center justify-center">
                                  <Users className="w-4 h-4 text-white" />
                                </div>
                              )}
                            </div>
                          )}
                          <div
                            className={`max-w-[70%] rounded-lg px-3 py-2 text-sm ${
                              msg.isOwn
                                ? 'bg-primary text-primary-foreground'
                                : 'bg-muted'
                            }`}
                          >
                            {!msg.isOwn && (msg.identity?.displayName || msg.username) && (
                              <p className="text-xs font-semibold opacity-80 mb-1">
                                {msg.identity?.displayName || msg.username}
                              </p>
                            )}
                            <p>{msg.message}</p>
                            <p className="text-xs opacity-70 mt-1">
                              {new Date(msg.timestamp).toLocaleTimeString([], {
                                hour: '2-digit',
                                minute: '2-digit',
                              })}
                            </p>
                          </div>
                          {msg.isOwn && (
                            <div className="flex-shrink-0 mt-1">
                              {msg.identity ? (
                                <IdentityAvatarSmall seed={msg.identity.avatarSeed} />
                              ) : (
                                <div className="w-8 h-8 rounded-full bg-gradient-to-br from-pink-500 to-purple-500 flex items-center justify-center">
                                  <Users className="w-4 h-4 text-white" />
                                </div>
                              )}
                            </div>
                          )}
                        </div>
                      ))}
                      <div ref={messagesEndRef} />
                    </div>
                  )}
                </ScrollArea>

                {/* Message Input */}
                <div className="flex gap-2">
                  <Input
                    placeholder="Type a message..."
                    value={chatMessage}
                    onChange={(e) => setChatMessage(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleSendMessage()}
                    className="flex-1"
                  />
                  <Button
                    size="icon"
                    onClick={handleSendMessage}
                    disabled={!chatMessage.trim()}
                  >
                    <Send className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            )}

            <Button
              variant="destructive"
              onClick={onDisconnect}
              className="w-full"
            >
              Disconnect
            </Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
};
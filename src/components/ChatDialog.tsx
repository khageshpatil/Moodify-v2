import { useState, useEffect, useRef } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { IdentityAvatar } from '@/components/identity/IdentityAvatar';
import { Badge } from '@/components/ui/badge';
import { Alert, AlertDescription } from '@/components/ui/alert';
import {
  Send,
  User,
  Wifi,
  WifiOff,
  Clock,
  Check,
  CheckCheck,
  AlertCircle
} from 'lucide-react';
import { useFriends, ChatMessage } from '@/hooks/useFriends';
import { MoodifyIdentity } from '@/hooks/useAnonymousIdentity';

interface ChatDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  friendId: string | null;
  identity: MoodifyIdentity;
}

export const ChatDialog = ({ open, onOpenChange, friendId, identity }: ChatDialogProps) => {
  const {
    friends,
    getChatHistory,
    sendMessage,
    markMessagesAsRead
  } = useFriends(identity);

  const [messageText, setMessageText] = useState('');
  const [isSending, setIsSending] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // Get friend data
  const friend = friendId ? friends.find(f => f.id === friendId) : null;
  const chatHistory = friendId ? getChatHistory(friendId) : [];

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [chatHistory]);

  // Mark messages as read when dialog opens
  useEffect(() => {
    if (open && friendId) {
      markMessagesAsRead(friendId);
    }
  }, [open, friendId, markMessagesAsRead]);

  // Focus input when dialog opens
  useEffect(() => {
    if (open && inputRef.current) {
      setTimeout(() => inputRef.current?.focus(), 100);
    }
  }, [open]);

  const handleSendMessage = async () => {
    if (!friendId || !messageText.trim()) return;

    const text = messageText.trim();
    setMessageText('');
    setIsSending(true);

    try {
      await sendMessage(friendId, text);
    } catch (error) {
      console.error('Failed to send message:', error);
    } finally {
      setIsSending(false);
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  const formatTime = (timestamp: number) => {
    const date = new Date(timestamp);
    const now = new Date();
    const isToday = date.toDateString() === now.toDateString();
    
    if (isToday) {
      return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
    
    const yesterday = new Date(now);
    yesterday.setDate(yesterday.getDate() - 1);
    const isYesterday = date.toDateString() === yesterday.toDateString();
    
    if (isYesterday) {
      return `Yesterday ${date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
    }
    
    return date.toLocaleDateString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  };

  const getDeliveryIcon = (message: ChatMessage) => {
    if (message.senderId !== identity.anonId) return null;
    
    switch (message.status) {
      case 'read':
        return <CheckCheck className="w-3 h-3 text-blue-500" />;
      case 'delivered':
        return <CheckCheck className="w-3 h-3 text-muted-foreground" />;
      case 'sent':
        return <Check className="w-3 h-3 text-muted-foreground" />;
      case 'sending':
        return <Clock className="w-3 h-3 text-muted-foreground animate-pulse" />;
      case 'failed':
        return <AlertCircle className="w-3 h-3 text-red-500" />;
      default:
        return <Check className="w-3 h-3 text-muted-foreground" />;
    }
  };

  if (!friend) {
    return null;
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[600px] h-[80vh] flex flex-col p-0">
        {/* Header */}
        <DialogHeader className="p-6 pb-4 border-b">
          <div className="flex items-center gap-4">
            <div className="relative">
              <IdentityAvatar 
                seed={friend.avatar}
                size={48}
                className="w-12 h-12"
              />
              {friend.isOnline && (
                <div className="absolute -bottom-1 -right-1 w-4 h-4 bg-green-500 rounded-full border-2 border-background" />
              )}
            </div>

            <div className="flex-1 min-w-0">
              <DialogTitle className="text-xl">
                {friend.displayName}
              </DialogTitle>
              <div className="flex items-center gap-2 mt-1">
                {friend.isOnline ? (
                  <Badge variant="default" className="bg-green-500">
                    <Wifi className="w-3 h-3 mr-1" />
                    Online
                  </Badge>
                ) : (
                  <Badge variant="secondary">
                    <WifiOff className="w-3 h-3 mr-1" />
                    Offline
                  </Badge>
                )}
                <span className="text-xs text-muted-foreground font-mono">
                  {friend.peerCode}
                </span>
              </div>
            </div>
          </div>
        </DialogHeader>

        {/* Chat Messages */}
        <ScrollArea className="flex-1 p-6">
          <div className="space-y-4">
            {chatHistory.length === 0 ? (
              <Alert>
                <AlertCircle className="h-4 w-4" />
                <AlertDescription>
                  No messages yet. Start the conversation!
                </AlertDescription>
              </Alert>
            ) : (
              <>
                {chatHistory.map((message, index) => {
                  const isMe = message.senderId === identity.anonId;
                  const showAvatar = index === 0 || chatHistory[index - 1].senderId !== message.senderId;
                  const showTime = index === chatHistory.length - 1 || 
                                   chatHistory[index + 1].senderId !== message.senderId ||
                                   chatHistory[index + 1].timestamp - message.timestamp > 300000; // 5 minutes

                  return (
                    <div
                      key={message.id}
                      className={`flex gap-3 ${isMe ? 'flex-row-reverse' : 'flex-row'}`}
                    >
                      {/* Avatar */}
                      <div className="w-8 h-8 flex-shrink-0">
                        {showAvatar && (
                          <IdentityAvatar 
                            seed={isMe ? identity.avatarSeed : friend.avatar}
                            size={32}
                            className="w-8 h-8"
                          />
                        )}
                      </div>

                      {/* Message Bubble */}
                      <div className={`flex flex-col ${isMe ? 'items-end' : 'items-start'} max-w-[70%]`}>
                        <div
                          className={`rounded-2xl px-4 py-2 ${
                            isMe
                              ? 'bg-primary text-primary-foreground'
                              : 'bg-muted'
                          }`}
                        >
                          {message.text && (
                            <p className="text-sm whitespace-pre-wrap break-words">
                              {message.text}
                            </p>
                          )}
                          {/* TODO: Render media attachments here */}
                          {message.attachments && message.attachments.length > 0 && (
                            <p className="text-xs opacity-70 mt-1">
                              [Media message - viewer coming soon]
                            </p>
                          )}
                        </div>
                        
                        {showTime && (
                          <div className={`flex items-center gap-1 mt-1 px-2 ${isMe ? 'flex-row-reverse' : 'flex-row'}`}>
                            <span className="text-xs text-muted-foreground">
                              {formatTime(message.timestamp)}
                            </span>
                            {getDeliveryIcon(message)}
                          </div>
                        )}
                      </div>
                    </div>
                  );
                })}
                <div ref={scrollRef} />
              </>
            )}
          </div>
        </ScrollArea>

        {/* Connection Status Alert */}
        {!friend.isOnline && (
          <div className="px-6">
            <Alert variant="default" className="bg-yellow-500/10 border-yellow-500/20">
              <Clock className="h-4 w-4 text-yellow-500" />
              <AlertDescription className="text-yellow-700 dark:text-yellow-400">
                Friend is offline. Messages will be delivered when they come online.
              </AlertDescription>
            </Alert>
          </div>
        )}

        {/* Message Input */}
        <div className="p-6 pt-4 border-t">
          <div className="flex gap-2">
            <Input
              ref={inputRef}
              placeholder={friend.isOnline ? 'Type a message...' : 'Friend is offline'}
              value={messageText}
              onChange={(e) => setMessageText(e.target.value)}
              onKeyPress={handleKeyPress}
              disabled={isSending}
              className="flex-1"
            />
            <Button
              onClick={handleSendMessage}
              disabled={isSending || !messageText.trim()}
              size="icon"
            >
              <Send className="w-4 h-4" />
            </Button>
          </div>
          <p className="text-xs text-muted-foreground mt-2">
            🔒 End-to-end encrypted via P2P. Press Enter to send.
          </p>
        </div>
      </DialogContent>
    </Dialog>
  );
};

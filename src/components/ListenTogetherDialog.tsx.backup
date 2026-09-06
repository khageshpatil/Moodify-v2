import { useState, useRef, useEffect } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Users, Copy, Check, Send, MessageCircle } from 'lucide-react';
import { useToast } from '@/hooks/use-toast';
import { ChatMessage } from '@/hooks/useListenTogether';

interface ListenTogetherDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreateRoom: () => void;
  onJoinRoom: (code: string) => void;
  roomCode: string | null;
  isConnected: boolean;
  connectedUser: string | null;
  onDisconnect: () => void;
  messages: ChatMessage[];
  onSendMessage: (message: string) => void;
}

export const ListenTogetherDialog = ({
  open,
  onOpenChange,
  onCreateRoom,
  onJoinRoom,
  roomCode,
  isConnected,
  connectedUser,
  onDisconnect,
  messages,
  onSendMessage,
}: ListenTogetherDialogProps) => {
  const [joinCode, setJoinCode] = useState('');
  const [copied, setCopied] = useState(false);
  const [chatMessage, setChatMessage] = useState('');
  const [showChat, setShowChat] = useState(false);
  const { toast } = useToast();
  const messagesEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    if (isConnected) {
      toast({
        title: 'Connected!',
        description: 'You are now listening together',
      });
    }
  }, [isConnected, toast]);

  const handleCopyCode = () => {
    if (roomCode) {
      navigator.clipboard.writeText(roomCode);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
      toast({
        title: 'Room code copied!',
        description: 'Share this code with your friend',
      });
    }
  };

  const handleJoin = () => {
    if (joinCode.trim()) {
      toast({
        title: 'Connecting...',
        description: 'Attempting to join the room',
      });
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

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg max-h-[90vh] overflow-hidden flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Users className="w-5 h-5" />
            Listen Together
          </DialogTitle>
        </DialogHeader>

        {!isConnected ? (
          <div className="space-y-6">
            {/* Host Section */}
            <div className="space-y-3">
              <h3 className="text-sm font-medium">Host a Session</h3>
              {!roomCode ? (
                <Button onClick={onCreateRoom} className="w-full">
                  Create Room
                </Button>
              ) : (
                <div className="space-y-2">
                  <p className="text-sm text-muted-foreground">
                    Share this code with your friend:
                  </p>
                  <div className="flex gap-2">
                    <Input
                      value={roomCode}
                      readOnly
                      className="font-mono"
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
                    Waiting for connection...
                  </p>
                </div>
              )}
            </div>

            <div className="relative">
              <div className="absolute inset-0 flex items-center">
                <span className="w-full border-t" />
              </div>
              <div className="relative flex justify-center text-xs uppercase">
                <span className="bg-background px-2 text-muted-foreground">
                  Or
                </span>
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
                />
                <Button onClick={handleJoin}>Join</Button>
              </div>
            </div>
          </div>
        ) : (
          <div className="space-y-4 flex-1 flex flex-col">
            <div className="rounded-lg bg-gradient-to-r from-primary/20 to-purple-500/20 p-4 text-center border border-primary/20 animate-fade-in">
              <Users className="w-8 h-8 mx-auto mb-2 text-primary animate-pulse" />
              <p className="text-sm font-medium">Connected!</p>
              <p className="text-xs text-muted-foreground mt-1">
                Listening with: {connectedUser?.substring(0, 8)}...
              </p>
            </div>

            {/* Chat Toggle */}
            <Button
              variant="outline"
              onClick={() => setShowChat(!showChat)}
              className="w-full"
            >
              <MessageCircle className="h-4 w-4 mr-2" />
              {showChat ? 'Hide Chat' : 'Show Chat'}
              {messages.length > 0 && !showChat && (
                <span className="ml-2 bg-primary text-primary-foreground rounded-full w-5 h-5 text-xs flex items-center justify-center">
                  {messages.length}
                </span>
              )}
            </Button>

            {/* Chat Section */}
            {showChat && (
              <div className="flex-1 flex flex-col space-y-2 min-h-[200px] animate-scale-in">
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
                          className={`flex ${msg.isOwn ? 'justify-end' : 'justify-start'} animate-fade-in`}
                        >
                          <div
                            className={`max-w-[80%] rounded-lg px-3 py-2 text-sm ${
                              msg.isOwn
                                ? 'bg-primary text-primary-foreground'
                                : 'bg-muted'
                            }`}
                          >
                            <p>{msg.message}</p>
                            <p className="text-xs opacity-70 mt-1">
                              {new Date(msg.timestamp).toLocaleTimeString([], {
                                hour: '2-digit',
                                minute: '2-digit',
                              })}
                            </p>
                          </div>
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

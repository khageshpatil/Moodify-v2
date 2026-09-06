import { useState, useMemo } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { Alert, AlertDescription } from '@/components/ui/alert';
import {
  Users,
  UserPlus,
  Copy,
  Check,
  MessageCircle,
  Wifi,
  WifiOff,
  Clock,
  CheckCircle2,
  XCircle,
  User,
  QrCode,
  Share2,
  AlertCircle
} from 'lucide-react';
import { useToast } from '@/hooks/use-toast';
import { useFriends, Friend, FriendRequest } from '@/hooks/useFriends';
import { MoodifyIdentity } from '@/hooks/useAnonymousIdentity';
import { IdentityAvatar } from '@/components/identity/IdentityAvatar';

interface FriendsManagerProps {
  identity: MoodifyIdentity;
  onOpenChat?: (friendId: string) => void;
}

export const FriendsManager = ({ identity, onOpenChat }: FriendsManagerProps) => {
  const { toast } = useToast();
  const {
    myPeerCode,
    friends,
    friendRequests,
    sendFriendRequest,
    acceptFriendRequest,
    rejectFriendRequest,
    removeFriend,
    getUnreadCount
  } = useFriends(identity);

  const [showAddDialog, setShowAddDialog] = useState(false);
  const [friendCode, setFriendCode] = useState('');
  const [copied, setCopied] = useState(false);
  const [isAdding, setIsAdding] = useState(false);

  // Calculate stats
  const stats = useMemo(() => {
    const onlineFriends = friends.filter(f => f.isOnline).length;
    const totalUnread = friends.reduce((sum, f) => sum + getUnreadCount(f.id), 0);
    const pendingRequests = friendRequests.length;

    return { onlineFriends, totalUnread, pendingRequests };
  }, [friends, friendRequests, getUnreadCount]);

  const handleCopyCode = async () => {
    try {
      await navigator.clipboard.writeText(myPeerCode);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
      toast({
        title: '✅ Copied!',
        description: 'Your friend code is copied to clipboard',
      });
    } catch (error) {
      toast({
        title: 'Copy Failed',
        description: 'Please copy the code manually',
        variant: 'destructive',
      });
    }
  };

  const handleAddFriend = async () => {
    const code = friendCode.trim().toUpperCase();
    
    if (!code) {
      toast({
        title: 'Invalid Code',
        description: 'Please enter a friend code',
        variant: 'destructive',
      });
      return;
    }

    if (code === myPeerCode) {
      toast({
        title: 'Invalid Code',
        description: 'You cannot add yourself as a friend',
        variant: 'destructive',
      });
      return;
    }

    // Check if already friends
    if (friends.some(f => f.peerCode === code)) {
      toast({
        title: 'Already Friends',
        description: 'This user is already in your friends list',
        variant: 'destructive',
      });
      return;
    }

    setIsAdding(true);
    try {
      await sendFriendRequest(code);
      setFriendCode('');
      setShowAddDialog(false);
      toast({
        title: '✅ Friend Request Sent!',
        description: 'Waiting for them to accept...',
      });
    } catch (error) {
      toast({
        title: 'Request Failed',
        description: error instanceof Error ? error.message : 'Could not send friend request',
        variant: 'destructive',
      });
    } finally {
      setIsAdding(false);
    }
  };

  const handleAcceptRequest = async (request: FriendRequest) => {
    try {
      await acceptFriendRequest(request.id);
      toast({
        title: '✅ Friend Added!',
        description: `${request.displayName} is now your friend`,
      });
    } catch (error) {
      toast({
        title: 'Accept Failed',
        description: 'Could not accept friend request',
        variant: 'destructive',
      });
    }
  };

  const handleRejectRequest = async (requestId: string) => {
    try {
      await rejectFriendRequest(requestId);
      toast({
        title: 'Request Rejected',
        description: 'Friend request declined',
      });
    } catch (error) {
      toast({
        title: 'Reject Failed',
        description: 'Could not reject friend request',
        variant: 'destructive',
      });
    }
  };

  const handleRemoveFriend = async (friend: Friend) => {
    if (!window.confirm(`Remove ${friend.displayName} from friends?`)) {
      return;
    }

    try {
      await removeFriend(friend.id);
      toast({
        title: 'Friend Removed',
        description: `${friend.displayName} removed from friends`,
      });
    } catch (error) {
      toast({
        title: 'Remove Failed',
        description: 'Could not remove friend',
        variant: 'destructive',
      });
    }
  };

  const handleOpenChat = (friendId: string) => {
    if (onOpenChat) {
      onOpenChat(friendId);
    }
  };

  const formatLastSeen = (timestamp: number) => {
    const now = Date.now();
    const diff = now - timestamp;
    
    if (diff < 60000) return 'Just now';
    if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
    if (diff < 86400000) return `${Math.floor(diff / 3600000)}h ago`;
    return `${Math.floor(diff / 86400000)}d ago`;
  };

  return (
    <div className="space-y-6">
      {/* Header Stats */}
      <div className="grid grid-cols-3 gap-4">
        <Card>
          <CardContent className="pt-6">
            <div className="text-center">
              <Users className="w-8 h-8 mx-auto mb-2 text-primary" />
              <div className="text-2xl font-bold">{friends.length}</div>
              <p className="text-xs text-muted-foreground">Friends</p>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <div className="text-center">
              <Wifi className="w-8 h-8 mx-auto mb-2 text-green-500" />
              <div className="text-2xl font-bold">{stats.onlineFriends}</div>
              <p className="text-xs text-muted-foreground">Online</p>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <div className="text-center">
              <MessageCircle className="w-8 h-8 mx-auto mb-2 text-blue-500" />
              <div className="text-2xl font-bold">{stats.totalUnread}</div>
              <p className="text-xs text-muted-foreground">Unread</p>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* My Friend Code */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-lg">
            <Share2 className="w-5 h-5" />
            My Friend Code
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex gap-2">
            <Input
              value={myPeerCode}
              readOnly
              className="font-mono text-lg font-bold text-center"
            />
            <Button
              size="icon"
              variant="outline"
              onClick={handleCopyCode}
            >
              {copied ? (
                <Check className="w-4 h-4 text-green-500" />
              ) : (
                <Copy className="w-4 h-4" />
              )}
            </Button>
          </div>
          <p className="text-sm text-muted-foreground text-center">
            Share this code with friends so they can add you
          </p>
        </CardContent>
      </Card>

      {/* Add Friend Button */}
      <Button
        onClick={() => setShowAddDialog(true)}
        className="w-full"
        size="lg"
      >
        <UserPlus className="w-5 h-5 mr-2" />
        Add Friend
      </Button>

      {/* Tabs for Friends and Requests */}
      <Tabs defaultValue="friends" className="w-full">
        <TabsList className="grid w-full grid-cols-2">
          <TabsTrigger value="friends">
            Friends ({friends.length})
          </TabsTrigger>
          <TabsTrigger value="requests">
            Requests ({stats.pendingRequests})
            {stats.pendingRequests > 0 && (
              <Badge variant="destructive" className="ml-2">
                {stats.pendingRequests}
              </Badge>
            )}
          </TabsTrigger>
        </TabsList>

        {/* Friends List */}
        <TabsContent value="friends" className="space-y-4">
          {friends.length === 0 ? (
            <Alert>
              <AlertCircle className="h-4 w-4" />
              <AlertDescription>
                No friends yet. Add friends using their friend code!
              </AlertDescription>
            </Alert>
          ) : (
            <ScrollArea className="h-[400px]">
              <div className="space-y-2">
                {friends.map((friend) => {
                  const unreadCount = getUnreadCount(friend.id);
                  
                  return (
                    <Card key={friend.id}>
                      <CardContent className="p-4">
                        <div className="flex items-center gap-4">
                          {/* Avatar */}
                          <div className="relative">
                            <IdentityAvatar 
                              seed={friend.avatar}
                              size={48}
                              className="w-12 h-12"
                            />
                            {/* Online indicator */}
                            {friend.isOnline && (
                              <div className="absolute -bottom-1 -right-1 w-4 h-4 bg-green-500 rounded-full border-2 border-background" />
                            )}
                          </div>

                          {/* Friend Info */}
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2">
                              <h3 className="font-medium truncate">
                                {friend.displayName}
                              </h3>
                              {friend.isOnline ? (
                                <Badge variant="default" className="bg-green-500">
                                  <Wifi className="w-3 h-3 mr-1" />
                                  Online
                                </Badge>
                              ) : (
                                <Badge variant="secondary">
                                  <Clock className="w-3 h-3 mr-1" />
                                  {formatLastSeen(friend.lastSeen)}
                                </Badge>
                              )}
                            </div>
                            <p className="text-xs text-muted-foreground font-mono">
                              {friend.peerCode}
                            </p>
                          </div>

                          {/* Actions */}
                          <div className="flex gap-2">
                            <Button
                              size="sm"
                              variant={unreadCount > 0 ? "default" : "outline"}
                              onClick={() => handleOpenChat(friend.id)}
                              className="relative"
                            >
                              <MessageCircle className="w-4 h-4 mr-1" />
                              Chat
                              {unreadCount > 0 && (
                                <Badge
                                  variant="destructive"
                                  className="absolute -top-2 -right-2 h-5 w-5 p-0 flex items-center justify-center text-xs"
                                >
                                  {unreadCount > 9 ? '9+' : unreadCount}
                                </Badge>
                              )}
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => handleRemoveFriend(friend)}
                            >
                              <XCircle className="w-4 h-4" />
                            </Button>
                          </div>
                        </div>
                      </CardContent>
                    </Card>
                  );
                })}
              </div>
            </ScrollArea>
          )}
        </TabsContent>

        {/* Friend Requests */}
        <TabsContent value="requests" className="space-y-4">
          {friendRequests.length === 0 ? (
            <Alert>
              <AlertCircle className="h-4 w-4" />
              <AlertDescription>
                No pending friend requests
              </AlertDescription>
            </Alert>
          ) : (
            <ScrollArea className="h-[400px]">
              <div className="space-y-2">
                {friendRequests.map((request) => (
                  <Card key={request.id}>
                    <CardContent className="p-4">
                      <div className="flex items-center gap-4">
                        {/* Avatar */}
                        <IdentityAvatar 
                          seed={request.avatar}
                          size={48}
                          className="w-12 h-12"
                        />

                        {/* Request Info */}
                        <div className="flex-1 min-w-0">
                          <h3 className="font-medium truncate">
                            {request.displayName}
                          </h3>
                          <p className="text-xs text-muted-foreground">
                            Wants to be friends
                          </p>
                          <p className="text-xs text-muted-foreground font-mono">
                            {request.peerCode}
                          </p>
                        </div>

                        {/* Actions */}
                        <div className="flex gap-2">
                          <Button
                            size="sm"
                            onClick={() => handleAcceptRequest(request)}
                          >
                            <CheckCircle2 className="w-4 h-4 mr-1" />
                            Accept
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => handleRejectRequest(request.id)}
                          >
                            <XCircle className="w-4 h-4" />
                          </Button>
                        </div>
                      </div>
                    </CardContent>
                  </Card>
                ))}
              </div>
            </ScrollArea>
          )}
        </TabsContent>
      </Tabs>

      {/* Add Friend Dialog */}
      <Dialog open={showAddDialog} onOpenChange={setShowAddDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <UserPlus className="w-5 h-5" />
              Add Friend
            </DialogTitle>
            <DialogDescription>
              Enter your friend's code to send them a friend request
            </DialogDescription>
          </DialogHeader>
          
          <div className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="friend-code">Friend Code</Label>
              <Input
                id="friend-code"
                placeholder="Enter friend code (e.g., ABC-XYZ-123)"
                value={friendCode}
                onChange={(e) => setFriendCode(e.target.value.toUpperCase())}
                className="font-mono"
                maxLength={15}
              />
              <p className="text-xs text-muted-foreground">
                Ask your friend to share their friend code with you
              </p>
            </div>

            <div className="flex gap-2">
              <Button
                onClick={handleAddFriend}
                disabled={isAdding || !friendCode.trim()}
                className="flex-1"
              >
                {isAdding ? 'Sending Request...' : 'Send Friend Request'}
              </Button>
              <Button
                variant="outline"
                onClick={() => {
                  setShowAddDialog(false);
                  setFriendCode('');
                }}
              >
                Cancel
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
};

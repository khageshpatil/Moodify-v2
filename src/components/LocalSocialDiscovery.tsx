import { useState, useEffect } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { 
  Users, 
  Heart, 
  Play, 
  TrendingUp, 
  Clock,
  Music,
  Star,
  Share2,
  Radio,
  User,
  Settings,
  Eye
} from 'lucide-react';
import { useLocalSocialFeatures } from '@/hooks/useLocalSocialFeatures';
import { useEnhancedNotifications } from '@/hooks/useEnhancedNotifications';
import { useIdentityContext } from '@/components/identity/IdentityProvider';
import { IdentityAvatarLarge } from '@/components/identity/IdentityAvatar';
import { LoadingState } from '@/components/ui/loading';
import { Track } from '@/data/mockMusic';
import type { DiscoveryContext } from '@/listening/listeningTypes';
import { discoveryContextForPlaylist } from '@/listening/discoveryAttribution';

interface LocalSocialDiscoveryProps {
  onPlayPlaylist: (tracks: Track[], discoveryContext?: DiscoveryContext) => void;
  onAddToLibrary: (playlist: any) => void;
  userPlaylists: any[];
  userFavorites: Track[];
}

export const LocalSocialDiscovery = ({
  onPlayPlaylist,
  onAddToLibrary,
  userPlaylists,
  userFavorites,
}: LocalSocialDiscoveryProps) => {
  const {
    user,
    discoveredContent,
    trendingContent,
    recentConnections,
    activeSessions,
    likeContent,
    updateUserProfile,
    generateRecommendations,
    createListeningSession,
  } = useLocalSocialFeatures();

  const { notifySuccess } = useEnhancedNotifications();
  const { identity } = useIdentityContext();

  const [isLoading, setIsLoading] = useState(true);
  const [recommendations, setRecommendations] = useState<any[]>([]);

  // Initialize data
  useEffect(() => {
    const init = async () => {
      setIsLoading(true);
      const recs = generateRecommendations(userPlaylists, userFavorites);
      setRecommendations(recs);
      setIsLoading(false);
    };
    init();
  }, [generateRecommendations, userPlaylists, userFavorites]);

  const handleLikeContent = (contentId: string, contentName: string) => {
    likeContent(contentId);
    notifySuccess('Liked!', `You liked "${contentName}"`);
  };

  const handleJoinSession = (session: any) => {
    notifySuccess('Joined Session!', `Connected to ${session.name}`);
    // In a real implementation, this would connect to the session
  };

  const formatTimeAgo = (date: Date) => {
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (minutes < 60) return `${minutes}m ago`;
    if (hours < 24) return `${hours}h ago`;
    return `${days}d ago`;
  };

  if (isLoading) {
    return (
      <div className="space-y-6">
        <LoadingState type="social" message="Loading community content..." />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Anonymous User Profile */}
      <Card className="border-primary/20 bg-gradient-to-r from-primary/5 to-transparent">
        <CardContent className="p-6">
          <div className="flex items-center gap-4">
            {identity ? (
              <IdentityAvatarLarge seed={identity.avatarSeed} animate />
            ) : (
              <Avatar className="w-16 h-16">
                <AvatarFallback>
                  <User className="w-8 h-8" />
                </AvatarFallback>
              </Avatar>
            )}
            <div className="flex-1">
              <h3 className="text-xl font-semibold">
                {identity?.displayName || user.displayName}
              </h3>
              <p className="text-muted-foreground">
                Anonymous Music Explorer • {user.sessionCount} sessions
              </p>
              <div className="flex gap-2 mt-2">
                <Badge variant="secondary">No Account Needed</Badge>
                <Badge variant="outline">Privacy First</Badge>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      <Tabs defaultValue="discover" className="w-full">
        <TabsList className="grid w-full grid-cols-4">
          <TabsTrigger value="discover">Discover</TabsTrigger>
          <TabsTrigger value="trending">
            Trending ({trendingContent.length})
          </TabsTrigger>
          <TabsTrigger value="sessions">
            Live Sessions ({activeSessions.length})
          </TabsTrigger>
          <TabsTrigger value="recent">Recent</TabsTrigger>
        </TabsList>

        <TabsContent value="discover" className="space-y-6">
          {/* How It Works */}
          <Card className="border-blue-200 bg-blue-50/50 dark:border-blue-800 dark:bg-blue-950/20">
            <CardContent className="pt-6">
              <div className="flex gap-3">
                <Star className="w-5 h-5 text-blue-600 dark:text-blue-400 mt-0.5" />
                <div className="space-y-2">
                  <p className="text-sm font-medium text-blue-900 dark:text-blue-100">
                    🎵 Anonymous Music Discovery
                  </p>
                  <ul className="text-sm text-blue-700 dark:text-blue-300 space-y-1">
                    <li>• No signup required - just start sharing!</li>
                    <li>• Share playlists with codes - others discover anonymously</li>
                    <li>• Join temporary listening sessions with other music lovers</li>
                    <li>• Your identity is local to your device only</li>
                  </ul>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* Recommendations */}
          {recommendations.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Star className="w-5 h-5" />
                  Recommended for You
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="space-y-4">
                  {recommendations.map((content) => (
                    <div
                      key={content.id}
                      className="flex items-center gap-4 p-4 border rounded-lg hover:bg-accent/50 transition-colors"
                    >
                      <div className="w-16 h-16 bg-gradient-primary rounded-lg flex items-center justify-center">
                        <Music className="w-8 h-8 text-white" />
                      </div>
                      <div className="flex-1">
                        <div className="flex items-center gap-2 mb-1">
                          <h3 className="font-semibold">{content.name}</h3>
                          <Badge variant="secondary" className="text-xs">
                            {content.tags.join(', ')}
                          </Badge>
                        </div>
                        <p className="text-sm text-muted-foreground mb-2">
                          by {content.sharedBy} • {formatTimeAgo(content.sharedAt)}
                        </p>
                        <div className="flex items-center gap-4 text-xs text-muted-foreground">
                          <span className="flex items-center gap-1">
                            <Heart className="w-3 h-3" />
                            {content.likes}
                          </span>
                          <span className="flex items-center gap-1">
                            <Eye className="w-3 h-3" />
                            {content.accessCount}
                          </span>
                        </div>
                      </div>
                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          onClick={() => onPlayPlaylist(content.tracks, discoveryContextForPlaylist(content.id, 0))}
                        >
                          <Play className="w-4 h-4" />
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => handleLikeContent(content.id, content.name)}
                        >
                          <Heart className="w-4 h-4" />
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              </CardContent>
            </Card>
          )}

          {/* Discovered Content */}
          {discoveredContent.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Music className="w-5 h-5" />
                  Your Discoveries
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="space-y-3">
                  {discoveredContent.slice(0, 5).map((content) => (
                    <div
                      key={content.id}
                      className="flex items-center gap-3 p-3 border rounded-lg hover:bg-accent/50 transition-colors"
                    >
                      <div className="w-12 h-12 bg-gradient-secondary rounded-lg flex items-center justify-center">
                        <Music className="w-6 h-6 text-white" />
                      </div>
                      <div className="flex-1">
                        <h4 className="font-medium">{content.name}</h4>
                        <p className="text-sm text-muted-foreground">
                          by {content.sharedBy} • {formatTimeAgo(content.sharedAt)}
                        </p>
                      </div>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => onPlayPlaylist(content.tracks, discoveryContextForPlaylist(content.id, 0))}
                      >
                        <Play className="w-4 h-4" />
                      </Button>
                    </div>
                  ))}
                </div>
              </CardContent>
            </Card>
          )}
        </TabsContent>

        <TabsContent value="trending" className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <TrendingUp className="w-5 h-5" />
                Community Trending
              </CardTitle>
            </CardHeader>
            <CardContent>
              {trendingContent.length > 0 ? (
                <div className="space-y-4">
                  {trendingContent.map((content, index) => (
                    <div
                      key={content.id}
                      className="flex items-center gap-4 p-4 border rounded-lg hover:bg-accent/50 transition-colors"
                    >
                      <div className="flex items-center justify-center w-8 h-8 bg-gradient-primary rounded-full text-white font-bold text-sm">
                        #{index + 1}
                      </div>
                      <div className="w-12 h-12 bg-gradient-secondary rounded-lg flex items-center justify-center">
                        <TrendingUp className="w-6 h-6 text-white" />
                      </div>
                      <div className="flex-1">
                        <div className="flex items-center gap-2 mb-1">
                          <h3 className="font-semibold">{content.name}</h3>
                          <Badge variant="outline" className="text-xs">
                            {content.type}
                          </Badge>
                        </div>
                        <p className="text-sm text-muted-foreground mb-1">
                          {content.description}
                        </p>
                        <p className="text-xs text-muted-foreground">
                          by {content.sharedBy} • {formatTimeAgo(content.sharedAt)}
                        </p>
                        <div className="flex items-center gap-4 text-xs text-muted-foreground mt-1">
                          <span className="flex items-center gap-1">
                            <Heart className="w-3 h-3" />
                            {content.likes}
                          </span>
                          <span className="flex items-center gap-1">
                            <Eye className="w-3 h-3" />
                            {content.accessCount}
                          </span>
                          <div className="flex gap-1">
                            {content.tags.map((tag) => (
                              <Badge key={tag} variant="outline" className="text-xs">
                                {tag}
                              </Badge>
                            ))}
                          </div>
                        </div>
                      </div>
                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          onClick={() => onPlayPlaylist(content.tracks, discoveryContextForPlaylist(content.id, 0))}
                        >
                          <Play className="w-4 h-4" />
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => handleLikeContent(content.id, content.name)}
                        >
                          <Heart className="w-4 h-4" />
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="text-center py-8">
                  <TrendingUp className="w-12 h-12 mx-auto mb-4 text-muted-foreground" />
                  <p className="text-muted-foreground">
                    No trending content yet. Share some playlists to get started!
                  </p>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="sessions" className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Radio className="w-5 h-5" />
                Live Listening Sessions
              </CardTitle>
            </CardHeader>
            <CardContent>
              {activeSessions.length > 0 ? (
                <div className="space-y-4">
                  {activeSessions.map((session) => (
                    <div
                      key={session.id}
                      className="flex items-center gap-4 p-4 border rounded-lg hover:bg-accent/50 transition-colors"
                    >
                      <div className="relative">
                        <div className="w-12 h-12 bg-gradient-accent rounded-full flex items-center justify-center">
                          <Radio className="w-6 h-6 text-white" />
                        </div>
                        <div className="absolute -top-1 -right-1 w-4 h-4 bg-green-500 border-2 border-background rounded-full animate-pulse" />
                      </div>
                      <div className="flex-1">
                        <div className="flex items-center gap-2 mb-1">
                          <h3 className="font-semibold">{session.name}</h3>
                          {session.mood && (
                            <Badge variant="secondary" className="text-xs">
                              {session.mood}
                            </Badge>
                          )}
                        </div>
                        <p className="text-sm text-muted-foreground mb-1">
                          Hosted by {session.hostName}
                        </p>
                        <div className="flex items-center gap-4 text-xs text-muted-foreground">
                          <span className="flex items-center gap-1">
                            <Users className="w-3 h-3" />
                            {session.participants.length} listening
                          </span>
                          <span className="flex items-center gap-1">
                            <Clock className="w-3 h-3" />
                            {formatTimeAgo(session.createdAt)}
                          </span>
                        </div>
                      </div>
                      <Button
                        size="sm"
                        onClick={() => handleJoinSession(session)}
                        className="bg-green-600 hover:bg-green-700"
                      >
                        <Users className="w-4 h-4 mr-1" />
                        Join
                      </Button>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="text-center py-8">
                  <Radio className="w-12 h-12 mx-auto mb-4 text-muted-foreground" />
                  <p className="text-muted-foreground mb-4">
                    No active listening sessions right now.
                  </p>
                  <Button
                    onClick={() => {
                      createListeningSession([], 'chill');
                      notifySuccess('Session Created!', 'Your listening session is now live');
                    }}
                  >
                    <Radio className="w-4 h-4 mr-2" />
                    Start a Session
                  </Button>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="recent" className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Clock className="w-5 h-5" />
                Recent Connections
              </CardTitle>
            </CardHeader>
            <CardContent>
              {recentConnections.length > 0 ? (
                <div className="space-y-3">
                  {recentConnections.map((connection, index) => (
                    <div
                      key={index}
                      className="flex items-center gap-3 p-3 border rounded-lg"
                    >
                      <Avatar>
                        <AvatarFallback>
                          {connection.slice(0, 2).toUpperCase()}
                        </AvatarFallback>
                      </Avatar>
                      <div className="flex-1">
                        <p className="font-medium">{connection}</p>
                        <p className="text-sm text-muted-foreground">
                          Recent listening partner
                        </p>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="text-center py-8">
                  <Users className="w-12 h-12 mx-auto mb-4 text-muted-foreground" />
                  <p className="text-muted-foreground">
                    No recent connections. Join a listening session to connect with others!
                  </p>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  );
};

import { useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { 
  Share2, 
  Copy, 
  QrCode, 
  Lock, 
  Users, 
  Clock, 
  Heart,
  Eye,
  Settings,
  Twitter,
  Facebook,
  MessageCircle,
  Instagram,
  Check,
  ExternalLink,
  Link2,
  AlertCircle,
  CheckCircle2,
  Download
} from 'lucide-react';
import { useToast } from '@/hooks/use-toast';
import { Playlist } from '@/hooks/useMusicPlayer';
import { MoodifyIdentity } from '@/hooks/useAnonymousIdentity';
import { PlaylistShare, usePlaylistSharing } from '@/hooks/usePlaylistSharing';

interface PlaylistSharingDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  playlist: Playlist | null;
  currentUser: { name: string; avatar?: string; identity?: MoodifyIdentity };
}

export const PlaylistSharingDialog = ({
  open,
  onOpenChange,
  playlist,
  currentUser,
}: PlaylistSharingDialogProps) => {
  const { toast } = useToast();
  const {
    createURLShare,
    canShareViaURL,
    MAX_TRACKS_FOR_URL_SHARE,
  } = usePlaylistSharing(currentUser);

  const [shareData, setShareData] = useState<{ url: string; qrCode: string; isEncrypted?: boolean } | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const [isCreating, setIsCreating] = useState(false);
  const [enablePassword, setEnablePassword] = useState(false);
  const [password, setPassword] = useState('');

  if (!playlist) return null;

  const validation = canShareViaURL(playlist);
  const shareUrl = shareData?.url || '';
  const qrCodeUrl = shareData?.qrCode || '';

  const handleCreateShare = async () => {
    if (!validation.canShare) {
      toast({
        title: 'Cannot Share',
        description: validation.message,
        variant: 'destructive',
      });
      return;
    }

    // Validate password if enabled
    if (enablePassword && password.length < 4) {
      toast({
        title: 'Invalid Password',
        description: 'Password must be at least 4 characters',
        variant: 'destructive',
      });
      return;
    }

    setIsCreating(true);
    try {
      const result = await createURLShare(playlist, enablePassword ? password : undefined);
      if (result.success && result.url && result.qrCode) {
        setShareData({ url: result.url, qrCode: result.qrCode, isEncrypted: result.isEncrypted });
      }
    } catch (error) {
      toast({
        title: 'Failed to create share',
        description: 'Something went wrong. Please try again.',
        variant: 'destructive',
      });
    } finally {
      setIsCreating(false);
    }
  };

  const handleCopy = async (text: string, type: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(type);
      setTimeout(() => setCopied(null), 2000);
      toast({
        title: 'Copied!',
        description: `${type} copied to clipboard`,
      });
    } catch (error) {
      toast({
        title: 'Failed to copy',
        description: 'Please copy the link manually',
        variant: 'destructive',
      });
    }
  };

  const openSocialShare = (platform: string, text: string) => {
    let url = '';
    const encodedText = encodeURIComponent(text);
    const encodedUrl = encodeURIComponent(shareUrl);

    switch (platform) {
      case 'twitter':
        url = `https://twitter.com/intent/tweet?text=${encodedText}`;
        break;
      case 'facebook':
        url = `https://www.facebook.com/sharer/sharer.php?u=${encodedUrl}&quote=${encodedText}`;
        break;
      case 'whatsapp':
        url = `https://wa.me/?text=${encodedText}`;
        break;
      case 'instagram':
        // Instagram doesn't support direct sharing, so we'll copy the text
        handleCopy(text, 'Instagram caption');
        return;
    }

    if (url) {
      window.open(url, '_blank', 'width=600,height=400');
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl max-h-[90vh] overflow-hidden">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Share2 className="w-5 h-5" />
            Share "{playlist.name}"
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-6">
          {/* Playlist Info & Validation */}
          <Card>
            <CardHeader className="pb-3">
              <div className="flex items-center gap-3">
                <div className="w-12 h-12 bg-gradient-primary rounded-lg flex items-center justify-center">
                  <span className="text-xl">🎵</span>
                </div>
                <div className="flex-1">
                  <h3 className="font-semibold">{playlist.name}</h3>
                  <p className="text-sm text-muted-foreground">
                    {playlist.tracks.length} songs
                  </p>
                </div>
                {validation.canShare ? (
                  <CheckCircle2 className="w-5 h-5 text-green-500" />
                ) : (
                  <AlertCircle className="w-5 h-5 text-amber-500" />
                )}
              </div>
            </CardHeader>
            {/* Track Limit Warning */}
            {playlist.tracks.length > MAX_TRACKS_FOR_URL_SHARE && (
              <CardContent className="pt-0">
                <Alert variant="destructive">
                  <AlertCircle className="h-4 w-4" />
                  <AlertDescription>
                    Playlist has {playlist.tracks.length} tracks. Maximum {MAX_TRACKS_FOR_URL_SHARE} tracks allowed for URL sharing. Use JSON export/import for larger playlists.
                  </AlertDescription>
                </Alert>
              </CardContent>
            )}
            {playlist.tracks.length > 15 && playlist.tracks.length <= MAX_TRACKS_FOR_URL_SHARE && (
              <CardContent className="pt-0">
                <Alert>
                  <AlertCircle className="h-4 w-4" />
                  <AlertDescription>
                    Large playlist ({playlist.tracks.length} tracks). Share link will be long but should work.
                  </AlertDescription>
                </Alert>
              </CardContent>
            )}
          </Card>

          <Tabs defaultValue="create" className="w-full">
            <TabsList className="grid w-full grid-cols-2">
              <TabsTrigger value="create">
                <Link2 className="w-4 h-4 mr-2" />
                Share Link
              </TabsTrigger>
              <TabsTrigger value="social" disabled={!shareData}>
                <Share2 className="w-4 h-4 mr-2" />
                Social Media
              </TabsTrigger>
            </TabsList>

            <TabsContent value="create" className="space-y-4">
              {!shareData ? (
                <div className="space-y-4">
                  {/* Info Card */}
                  <Card>
                    <CardHeader>
                      <CardTitle className="flex items-center gap-2 text-lg">
                        <Link2 className="w-5 h-5" />
                        URL-Based Sharing
                      </CardTitle>
                    </CardHeader>
                    <CardContent className="space-y-3 text-sm text-muted-foreground">
                      <p>✅ No backend required - data encoded in URL</p>
                      <p>✅ Privacy-preserving - no server storage</p>
                      <p>✅ Works immediately - share via any platform</p>
                      <p>⚠️ Limited to {MAX_TRACKS_FOR_URL_SHARE} tracks per playlist</p>
                    </CardContent>
                  </Card>

                  {/* Password Protection Option */}
                  <Card>
                    <CardContent className="pt-6 space-y-4">
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                          <Lock className="w-4 h-4" />
                          <Label htmlFor="enable-password" className="text-sm font-medium">
                            Password Protection
                          </Label>
                        </div>
                        <Switch
                          id="enable-password"
                          checked={enablePassword}
                          onCheckedChange={setEnablePassword}
                        />
                      </div>
                      
                      {enablePassword && (
                        <div className="space-y-2">
                          <Label htmlFor="password" className="text-sm">
                            Set Password (min 4 characters)
                          </Label>
                          <Input
                            id="password"
                            type="password"
                            placeholder="Enter password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            className="w-full"
                          />
                          <p className="text-xs text-muted-foreground">
                            🔒 Client-side AES-256 encryption. Recipients will need this password to import.
                          </p>
                        </div>
                      )}
                    </CardContent>
                  </Card>

                  <Button 
                    onClick={handleCreateShare} 
                    disabled={isCreating || !validation.canShare}
                    className="w-full"
                    size="lg"
                  >
                    {isCreating ? 'Creating Share Link...' : validation.canShare ? (enablePassword ? '🔒 Generate Protected Link' : 'Generate Share Link') : 'Cannot Share - Too Many Tracks'}
                  </Button>
                  
                  {!validation.canShare && (
                    <p className="text-sm text-center text-muted-foreground">
                      Try reducing to {MAX_TRACKS_FOR_URL_SHARE} tracks or use JSON export instead
                    </p>
                  )}
                </div>
              ) : (
                <div className="space-y-4">
                  {/* Encryption Status Badge */}
                  {shareData.isEncrypted && (
                    <Alert>
                      <Lock className="h-4 w-4" />
                      <AlertDescription>
                        🔒 This link is password-protected with AES-256 encryption. Recipients will need the password you set to import the playlist.
                      </AlertDescription>
                    </Alert>
                  )}
                  
                  {/* Share Link */}
                  <Card>
                    <CardHeader>
                      <CardTitle className="flex items-center gap-2 text-lg">
                        <ExternalLink className="w-5 h-5" />
                        Share Link
                        {shareData.isEncrypted && (
                          <Badge variant="secondary" className="ml-2">
                            <Lock className="w-3 h-3 mr-1" />
                            Protected
                          </Badge>
                        )}
                      </CardTitle>
                    </CardHeader>
                    <CardContent className="space-y-3">
                      <div className="flex gap-2">
                        <Input
                          value={shareUrl}
                          readOnly
                          className="font-mono text-sm"
                        />
                        <Button
                          size="icon"
                          variant="outline"
                          onClick={() => handleCopy(shareUrl, 'Share link')}
                        >
                          {copied === 'Share link' ? (
                            <Check className="w-4 h-4" />
                          ) : (
                            <Copy className="w-4 h-4" />
                          )}
                        </Button>
                      </div>
                      <p className="text-xs text-muted-foreground">
                        {shareData.isEncrypted 
                          ? '🔒 Password required to import. Share the password separately for security.'
                          : 'Anyone with this link can import this playlist into their library'
                        }
                      </p>
                    </CardContent>
                  </Card>

                  {/* QR Code */}
                  <Card>
                    <CardHeader>
                      <CardTitle className="flex items-center gap-2 text-lg">
                        <QrCode className="w-5 h-5" />
                        QR Code
                      </CardTitle>
                    </CardHeader>
                    <CardContent className="flex flex-col items-center gap-3">
                      <div className="bg-white p-4 rounded-lg">
                        <img 
                          src={qrCodeUrl} 
                          alt="QR Code" 
                          className="w-48 h-48"
                        />
                      </div>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => {
                          const link = document.createElement('a');
                          link.href = qrCodeUrl;
                          link.download = `${playlist.name}-qr.png`;
                          link.click();
                        }}
                      >
                        <Download className="w-4 h-4 mr-2" />
                        Download QR Code
                      </Button>
                    </CardContent>
                  </Card>

                  {/* How to Use */}
                  <Card>
                    <CardHeader>
                      <CardTitle className="text-sm">📱 How to Share</CardTitle>
                    </CardHeader>
                    <CardContent className="space-y-2 text-sm text-muted-foreground">
                      <p>1. Copy the link above and share via WhatsApp, Discord, etc.</p>
                      <p>2. Recipients click the link to auto-import the playlist</p>
                      <p>3. Or scan the QR code with their mobile device</p>
                    </CardContent>
                  </Card>

                  <Button 
                    variant="outline" 
                    onClick={() => setShareData(null)}
                    className="w-full"
                  >
                    Create New Share
                  </Button>
                </div>
              )}
            </TabsContent>

            <TabsContent value="social" className="space-y-4">
              {shareData && (
                <div className="space-y-3">
                  <p className="text-sm text-muted-foreground">
                    Share your playlist on social media
                  </p>

                  {/* Social Share Buttons */}
                  <div className="grid grid-cols-2 gap-3">
                    <Button
                      variant="outline"
                      onClick={() => {
                        const text = `🎵 Check out "${playlist.name}" - ${playlist.tracks.length} amazing tracks on Moodify! ${shareUrl}`;
                        openSocialShare('twitter', text);
                      }}
                      className="w-full"
                    >
                      <Twitter className="w-4 h-4 mr-2" />
                      Twitter
                    </Button>

                    <Button
                      variant="outline"
                      onClick={() => {
                        const text = `🎵 Check out "${playlist.name}" - ${playlist.tracks.length} amazing tracks on Moodify! ${shareUrl}`;
                        openSocialShare('facebook', text);
                      }}
                      className="w-full"
                    >
                      <Facebook className="w-4 h-4 mr-2" />
                      Facebook
                    </Button>

                    <Button
                      variant="outline"
                      onClick={() => {
                        const text = `🎵 Hey! Check out this playlist "${playlist.name}" with ${playlist.tracks.length} songs on Moodify: ${shareUrl}`;
                        openSocialShare('whatsapp', text);
                      }}
                      className="w-full"
                    >
                      <MessageCircle className="w-4 h-4 mr-2" />
                      WhatsApp
                    </Button>

                    <Button
                      variant="outline"
                      onClick={() => {
                        const text = `🎵 New playlist alert! "${playlist.name}" - ${playlist.tracks.length} tracks ✨ ${shareUrl}`;
                        handleCopy(text, 'Instagram caption');
                      }}
                      className="w-full"
                    >
                      <Instagram className="w-4 h-4 mr-2" />
                      Instagram
                    </Button>
                  </div>
                </div>
              )}
            </TabsContent>
          </Tabs>
        </div>
      </DialogContent>
    </Dialog>
  );
};

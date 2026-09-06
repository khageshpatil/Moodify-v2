import React, { useState, useEffect } from 'react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { IdentityAvatarLarge } from './IdentityAvatar';
import { Sparkles, Download, RefreshCw } from 'lucide-react';

interface IdentityOnboardingModalProps {
  open: boolean;
  onComplete: (displayName: string, importCode?: string) => void;
  suggestedNickname: string;
  onGenerateNewNickname: () => string;
}

export const IdentityOnboardingModal: React.FC<IdentityOnboardingModalProps> = ({
  open,
  onComplete,
  suggestedNickname,
  onGenerateNewNickname,
}) => {
  const [displayName, setDisplayName] = useState(suggestedNickname);
  const [importCode, setImportCode] = useState('');
  const [previewSeed, setPreviewSeed] = useState(suggestedNickname);
  const [activeTab, setActiveTab] = useState<'create' | 'import'>('create');
  const [isCreating, setIsCreating] = useState(false);

  // Update preview seed when name changes
  useEffect(() => {
    if (displayName) {
      setPreviewSeed(displayName + Date.now().toString());
    }
  }, [displayName]);

  const handleCreate = () => {
    if (!displayName.trim()) {
      return;
    }
    setIsCreating(true);
    setTimeout(() => {
      onComplete(displayName.trim());
    }, 300);
  };

  const handleImport = () => {
    if (!importCode.trim()) {
      return;
    }
    setIsCreating(true);
    setTimeout(() => {
      onComplete('', importCode.trim());
    }, 300);
  };

  const handleGenerateNew = () => {
    const newNickname = onGenerateNewNickname();
    setDisplayName(newNickname);
  };

  const handleKeyPress = (e: React.KeyboardEvent, action: () => void) => {
    if (e.key === 'Enter') {
      action();
    }
  };

  return (
    <Dialog open={open} onOpenChange={() => {}}>
      <DialogContent 
        className="sm:max-w-[500px] glass-panel border-purple-500/30 bg-gradient-to-br from-purple-900/40 via-blue-900/40 to-indigo-900/40 backdrop-blur-xl animate-fade-in"
        onPointerDownOutside={(e) => e.preventDefault()}
        onEscapeKeyDown={(e) => e.preventDefault()}
      >
        <DialogHeader className="space-y-3">
          <div className="flex items-center justify-center mb-2">
            <div className="relative">
              <Sparkles className="w-12 h-12 text-pink-400 animate-pulse" />
              <div className="absolute inset-0 bg-pink-400/20 blur-xl rounded-full animate-pulse" />
            </div>
          </div>
          <DialogTitle className="text-3xl font-bold text-center bg-gradient-to-r from-pink-400 to-violet-400 bg-clip-text text-transparent">
            Welcome to Moodify
          </DialogTitle>
          <DialogDescription className="text-center text-gray-300 text-base">
            Create your anonymous identity to unlock social features
          </DialogDescription>
        </DialogHeader>

        <Tabs value={activeTab} onValueChange={(v) => setActiveTab(v as 'create' | 'import')} className="mt-4">
          <TabsList className="grid w-full grid-cols-2 bg-black/30">
            <TabsTrigger value="create" className="data-[state=active]:bg-purple-600/50">
              Create New
            </TabsTrigger>
            <TabsTrigger value="import" className="data-[state=active]:bg-purple-600/50">
              Import
            </TabsTrigger>
          </TabsList>

          <TabsContent value="create" className="space-y-5 mt-6">
            {/* Avatar Preview */}
            <div className="flex flex-col items-center space-y-4 py-4">
              <div className="relative group">
                <IdentityAvatarLarge 
                  seed={previewSeed} 
                  animate 
                  className="transition-all duration-500 group-hover:scale-110"
                />
                <div className="absolute inset-0 bg-gradient-to-br from-pink-500/20 to-purple-500/20 rounded-full blur-2xl group-hover:blur-3xl transition-all duration-500 -z-10" />
              </div>
              <p className="text-sm text-gray-400 text-center">
                Your unique avatar preview
              </p>
            </div>

            {/* Nickname Input */}
            <div className="space-y-3">
              <Label htmlFor="nickname" className="text-gray-200 font-medium">
                Choose Your Nickname
              </Label>
              <div className="flex gap-2">
                <div className="relative flex-1">
                  <Input
                    id="nickname"
                    value={displayName}
                    onChange={(e) => setDisplayName(e.target.value)}
                    onKeyPress={(e) => handleKeyPress(e, handleCreate)}
                    placeholder="Enter a cool nickname..."
                    maxLength={20}
                    className="glass-input bg-black/30 border-purple-500/30 focus:border-pink-500/50 focus:ring-pink-500/20 text-white placeholder:text-gray-500 pr-12 transition-all duration-300 focus:scale-[1.02]"
                    autoFocus
                  />
                  <div className="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-gray-500">
                    {displayName.length}/20
                  </div>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  size="icon"
                  onClick={handleGenerateNew}
                  className="glass-button border-purple-500/30 hover:border-pink-500/50 transition-all duration-300 hover:scale-105"
                  title="Generate new nickname"
                >
                  <RefreshCw className="h-4 w-4" />
                </Button>
              </div>
              <p className="text-xs text-gray-500">
                This will be visible to others in Listen Together and shared playlists
              </p>
            </div>

            {/* Create Button */}
            <Button
              onClick={handleCreate}
              disabled={!displayName.trim() || isCreating}
              className="w-full glass-button bg-gradient-to-r from-pink-600 to-purple-600 hover:from-pink-500 hover:to-purple-500 border-0 text-white font-semibold py-6 text-lg transition-all duration-300 hover:scale-[1.02] disabled:opacity-50 disabled:hover:scale-100"
            >
              {isCreating ? (
                <div className="flex items-center gap-2">
                  <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                  Creating...
                </div>
              ) : (
                <>
                  <Sparkles className="w-5 h-5 mr-2" />
                  Enter Moodify
                </>
              )}
            </Button>
          </TabsContent>

          <TabsContent value="import" className="space-y-5 mt-6">
            <div className="space-y-3">
              <Label htmlFor="import-code" className="text-gray-200 font-medium flex items-center gap-2">
                <Download className="w-4 h-4" />
                Recovery Code
              </Label>
              <Input
                id="import-code"
                value={importCode}
                onChange={(e) => setImportCode(e.target.value)}
                onKeyPress={(e) => handleKeyPress(e, handleImport)}
                placeholder="Paste your recovery code here..."
                className="glass-input bg-black/30 border-purple-500/30 focus:border-pink-500/50 focus:ring-pink-500/20 text-white placeholder:text-gray-500 font-mono transition-all duration-300 focus:scale-[1.02]"
              />
              <p className="text-xs text-gray-500">
                Import your identity from another device using your recovery code
              </p>
            </div>

            <Button
              onClick={handleImport}
              disabled={!importCode.trim() || isCreating}
              className="w-full glass-button bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-500 hover:to-purple-500 border-0 text-white font-semibold py-6 text-lg transition-all duration-300 hover:scale-[1.02] disabled:opacity-50 disabled:hover:scale-100"
            >
              {isCreating ? (
                <div className="flex items-center gap-2">
                  <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                  Importing...
                </div>
              ) : (
                <>
                  <Download className="w-5 h-5 mr-2" />
                  Restore Identity
                </>
              )}
            </Button>
          </TabsContent>
        </Tabs>

        {/* Footer Note */}
        <div className="mt-4 p-4 rounded-lg bg-black/20 border border-purple-500/20">
          <p className="text-xs text-gray-400 text-center leading-relaxed">
            🔒 <span className="font-semibold text-gray-300">100% Anonymous & Private</span> • No email, no phone, no tracking.
            <br />
            Your identity stays on your device.
          </p>
        </div>
      </DialogContent>
    </Dialog>
  );
};


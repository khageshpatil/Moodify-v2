import React, { useState } from 'react';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Separator } from '@/components/ui/separator';
import { IdentityAvatarLarge } from './IdentityAvatar';
import { MoodifyIdentity } from '@/hooks/useAnonymousIdentity';
import { 
  User, 
  Download, 
  Upload, 
  Trash2, 
  Copy, 
  Check,
  Shield,
  AlertTriangle,
} from 'lucide-react';

interface IdentitySettingsProps {
  identity: MoodifyIdentity;
  onUpdateDisplayName: (name: string) => void;
  onExport: () => string;
  onImport: (code: string) => void;
  onReset: () => void;
}

export const IdentitySettings: React.FC<IdentitySettingsProps> = ({
  identity,
  onUpdateDisplayName,
  onExport,
  onImport,
  onReset,
}) => {
  const [isEditingName, setIsEditingName] = useState(false);
  const [newName, setNewName] = useState(identity.displayName);
  const [showExportDialog, setShowExportDialog] = useState(false);
  const [showImportDialog, setShowImportDialog] = useState(false);
  const [showResetDialog, setShowResetDialog] = useState(false);
  const [exportCode, setExportCode] = useState('');
  const [importCode, setImportCode] = useState('');
  const [copied, setCopied] = useState(false);

  const handleUpdateName = () => {
    if (newName.trim() && newName.trim() !== identity.displayName) {
      onUpdateDisplayName(newName.trim());
      setIsEditingName(false);
    } else {
      setNewName(identity.displayName);
      setIsEditingName(false);
    }
  };

  const handleExport = () => {
    try {
      const code = onExport();
      setExportCode(code);
      setShowExportDialog(true);
    } catch (err) {
      console.error('Export failed:', err);
    }
  };

  const handleCopyCode = async () => {
    try {
      await navigator.clipboard.writeText(exportCode);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('Failed to copy:', err);
    }
  };

  const handleImport = () => {
    if (importCode.trim()) {
      try {
        onImport(importCode.trim());
        setShowImportDialog(false);
        setImportCode('');
      } catch (err) {
        console.error('Import failed:', err);
      }
    }
  };

  const handleReset = () => {
    onReset();
    setShowResetDialog(false);
  };

  const formatDate = (timestamp: number) => {
    return new Date(timestamp).toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  };

  return (
    <>
      <Card className="glass-panel border-purple-500/30 bg-gradient-to-br from-purple-900/20 via-blue-900/20 to-indigo-900/20 backdrop-blur-lg">
        <CardHeader>
          <CardTitle className="text-2xl font-bold bg-gradient-to-r from-pink-400 to-violet-400 bg-clip-text text-transparent flex items-center gap-2">
            <User className="w-6 h-6 text-pink-400" />
            Identity Settings
          </CardTitle>
          <CardDescription className="text-gray-400">
            Manage your anonymous Moodify identity
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-6">
          {/* Avatar & Name Section */}
          <div className="flex flex-col items-center space-y-4 p-6 rounded-lg bg-black/20 border border-purple-500/20">
            <div className="relative group">
              <IdentityAvatarLarge seed={identity.avatarSeed} animate />
              <div className="absolute inset-0 bg-gradient-to-br from-pink-500/20 to-purple-500/20 rounded-full blur-2xl group-hover:blur-3xl transition-all duration-500 -z-10" />
            </div>
            
            {isEditingName ? (
              <div className="w-full space-y-2">
                <Input
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  onKeyPress={(e) => e.key === 'Enter' && handleUpdateName()}
                  maxLength={20}
                  className="glass-input bg-black/30 border-purple-500/30 text-center text-white"
                  autoFocus
                />
                <div className="flex gap-2">
                  <Button
                    onClick={handleUpdateName}
                    className="flex-1 glass-button bg-purple-600/50 hover:bg-purple-600/70"
                    size="sm"
                  >
                    Save
                  </Button>
                  <Button
                    onClick={() => {
                      setNewName(identity.displayName);
                      setIsEditingName(false);
                    }}
                    variant="outline"
                    className="flex-1 glass-button border-purple-500/30"
                    size="sm"
                  >
                    Cancel
                  </Button>
                </div>
              </div>
            ) : (
              <div className="text-center space-y-2">
                <h3 className="text-2xl font-bold text-white">{identity.displayName}</h3>
                <Button
                  onClick={() => setIsEditingName(true)}
                  variant="ghost"
                  size="sm"
                  className="text-gray-400 hover:text-white"
                >
                  Edit Name
                </Button>
              </div>
            )}
            
            <div className="text-center space-y-1">
              <p className="text-xs text-gray-500 font-mono">
                ID: {identity.anonId.substring(0, 8)}...
              </p>
              <p className="text-xs text-gray-500">
                Created {formatDate(identity.createdAt)}
              </p>
            </div>
          </div>

          <Separator className="bg-purple-500/20" />

          {/* Actions */}
          <div className="space-y-3">
            <Label className="text-gray-300 font-semibold">Identity Management</Label>
            
            {/* Export */}
            <Button
              onClick={handleExport}
              variant="outline"
              className="w-full glass-button border-purple-500/30 hover:border-pink-500/50 justify-start"
            >
              <Download className="w-4 h-4 mr-3" />
              <div className="flex-1 text-left">
                <div className="font-medium">Export Identity</div>
                <div className="text-xs text-gray-500">Get recovery code</div>
              </div>
            </Button>

            {/* Import */}
            <Button
              onClick={() => setShowImportDialog(true)}
              variant="outline"
              className="w-full glass-button border-purple-500/30 hover:border-pink-500/50 justify-start"
            >
              <Upload className="w-4 h-4 mr-3" />
              <div className="flex-1 text-left">
                <div className="font-medium">Import Identity</div>
                <div className="text-xs text-gray-500">Restore from recovery code</div>
              </div>
            </Button>

            {/* Reset */}
            <Button
              onClick={() => setShowResetDialog(true)}
              variant="outline"
              className="w-full glass-button border-red-500/30 hover:border-red-500/50 hover:bg-red-500/10 justify-start text-red-400 hover:text-red-300"
            >
              <Trash2 className="w-4 h-4 mr-3" />
              <div className="flex-1 text-left">
                <div className="font-medium">Reset Identity</div>
                <div className="text-xs text-gray-500">Clear and start fresh</div>
              </div>
            </Button>
          </div>

          {/* Privacy Notice */}
          <div className="p-4 rounded-lg bg-green-500/10 border border-green-500/30">
            <div className="flex gap-3">
              <Shield className="w-5 h-5 text-green-400 flex-shrink-0 mt-0.5" />
              <div className="space-y-1">
                <p className="text-sm font-medium text-green-300">Private & Secure</p>
                <p className="text-xs text-gray-400 leading-relaxed">
                  Your identity is stored only on this device. No data is sent to any server.
                  Keep your recovery code safe to restore your identity on other devices.
                </p>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Export Dialog */}
      <Dialog open={showExportDialog} onOpenChange={setShowExportDialog}>
        <DialogContent className="glass-panel border-purple-500/30 bg-gradient-to-br from-purple-900/40 via-blue-900/40 to-indigo-900/40 backdrop-blur-xl">
          <DialogHeader>
            <DialogTitle className="text-xl font-bold text-white">Recovery Code</DialogTitle>
            <DialogDescription className="text-gray-400">
              Save this code safely. You'll need it to restore your identity.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4">
            <div className="p-4 rounded-lg bg-black/40 border border-purple-500/30">
              <code className="text-sm font-mono text-pink-300 break-all">
                {exportCode}
              </code>
            </div>

            <Button
              onClick={handleCopyCode}
              className="w-full glass-button bg-gradient-to-r from-pink-600 to-purple-600 hover:from-pink-500 hover:to-purple-500"
            >
              {copied ? (
                <>
                  <Check className="w-4 h-4 mr-2" />
                  Copied!
                </>
              ) : (
                <>
                  <Copy className="w-4 h-4 mr-2" />
                  Copy Code
                </>
              )}
            </Button>

            <div className="p-3 rounded-lg bg-yellow-500/10 border border-yellow-500/30">
              <div className="flex gap-2">
                <AlertTriangle className="w-4 h-4 text-yellow-400 flex-shrink-0 mt-0.5" />
                <p className="text-xs text-yellow-300">
                  Anyone with this code can access your identity. Keep it private!
                </p>
              </div>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      {/* Import Dialog */}
      <Dialog open={showImportDialog} onOpenChange={setShowImportDialog}>
        <DialogContent className="glass-panel border-purple-500/30 bg-gradient-to-br from-purple-900/40 via-blue-900/40 to-indigo-900/40 backdrop-blur-xl">
          <DialogHeader>
            <DialogTitle className="text-xl font-bold text-white">Import Identity</DialogTitle>
            <DialogDescription className="text-gray-400">
              Enter your recovery code to restore your identity.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4">
            <div>
              <Label htmlFor="import-input" className="text-gray-300">Recovery Code</Label>
              <Input
                id="import-input"
                value={importCode}
                onChange={(e) => setImportCode(e.target.value)}
                placeholder="Paste your recovery code here..."
                className="glass-input bg-black/30 border-purple-500/30 text-white font-mono mt-2"
              />
            </div>

            <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/30">
              <div className="flex gap-2">
                <AlertTriangle className="w-4 h-4 text-red-400 flex-shrink-0 mt-0.5" />
                <p className="text-xs text-red-300">
                  This will replace your current identity. Make sure you've exported it first!
                </p>
              </div>
            </div>
          </div>

          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => {
                setShowImportDialog(false);
                setImportCode('');
              }}
              className="glass-button border-purple-500/30"
            >
              Cancel
            </Button>
            <Button
              onClick={handleImport}
              disabled={!importCode.trim()}
              className="glass-button bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-500 hover:to-purple-500"
            >
              Import
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Reset Dialog */}
      <Dialog open={showResetDialog} onOpenChange={setShowResetDialog}>
        <DialogContent className="glass-panel border-red-500/30 bg-gradient-to-br from-red-900/40 via-purple-900/40 to-indigo-900/40 backdrop-blur-xl">
          <DialogHeader>
            <DialogTitle className="text-xl font-bold text-red-400 flex items-center gap-2">
              <AlertTriangle className="w-5 h-5" />
              Reset Identity?
            </DialogTitle>
            <DialogDescription className="text-gray-400">
              This action cannot be undone. Your current identity will be permanently deleted.
            </DialogDescription>
          </DialogHeader>

          <div className="p-4 rounded-lg bg-black/40 border border-red-500/30">
            <p className="text-sm text-gray-300 mb-2">You will lose:</p>
            <ul className="text-sm text-gray-400 space-y-1 list-disc list-inside">
              <li>Your current nickname and avatar</li>
              <li>Your identity in all shared sessions</li>
              <li>Access to recover this identity</li>
            </ul>
          </div>

          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setShowResetDialog(false)}
              className="glass-button border-purple-500/30"
            >
              Cancel
            </Button>
            <Button
              onClick={handleReset}
              className="glass-button bg-gradient-to-r from-red-600 to-red-700 hover:from-red-500 hover:to-red-600 text-white"
            >
              <Trash2 className="w-4 h-4 mr-2" />
              Reset Identity
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
};


import { useState, useCallback } from 'react';
import { toast } from '@/hooks/use-toast';

export type NotificationType = 
  | 'music' 
  | 'social' 
  | 'playlist' 
  | 'share' 
  | 'success' 
  | 'error' 
  | 'info' 
  | 'warning';

interface EnhancedNotification {
  id: string;
  type: NotificationType;
  title: string;
  message: string;
  action?: {
    label: string;
    onClick: () => void;
  };
  duration?: number;
  persistent?: boolean;
}

export const useEnhancedNotifications = () => {
  const [notifications, setNotifications] = useState<EnhancedNotification[]>([]);

  const showNotification = useCallback((notification: Omit<EnhancedNotification, 'id'>) => {
    const id = Date.now().toString();
    const fullNotification = { ...notification, id };
    
    setNotifications(prev => [fullNotification, ...prev.slice(0, 4)]); // Keep max 5 notifications

    // Show toast with enhanced styling
    toast({
      title: notification.title,
      description: notification.message,
      duration: notification.persistent ? undefined : (notification.duration || 5000),
    });

    // Auto remove after duration (if not persistent)
    if (!notification.persistent) {
      setTimeout(() => {
        setNotifications(prev => prev.filter(n => n.id !== id));
      }, notification.duration || 5000);
    }

    return id;
  }, []);

  const removeNotification = useCallback((id: string) => {
    setNotifications(prev => prev.filter(n => n.id !== id));
  }, []);

  // Predefined notification helpers
  const notifyMusicAction = useCallback((action: string, trackName: string) => {
    showNotification({
      type: 'music',
      title: `Music ${action}`,
      message: `${trackName}`,
      duration: 3000,
    });
  }, [showNotification]);

  const notifyPlaylistAction = useCallback((action: string, playlistName: string, actionCallback?: () => void) => {
    showNotification({
      type: 'playlist',
      title: `Playlist ${action}`,
      message: playlistName,
      action: actionCallback ? {
        label: 'View',
        onClick: actionCallback,
      } : undefined,
      duration: 4000,
    });
  }, [showNotification]);

  const notifySocialAction = useCallback((action: string, userName: string, actionCallback?: () => void) => {
    showNotification({
      type: 'social',
      title: `Social Update`,
      message: `${userName} ${action}`,
      action: actionCallback ? {
        label: 'View',
        onClick: actionCallback,
      } : undefined,
      duration: 5000,
    });
  }, [showNotification]);

  const notifyShareSuccess = useCallback((shareCode: string) => {
    showNotification({
      type: 'share',
      title: 'Playlist Shared! 🎵',
      message: `Share code: ${shareCode}`,
      action: {
        label: 'Copy Code',
        onClick: () => navigator.clipboard.writeText(shareCode),
      },
      duration: 7000,
    });
  }, [showNotification]);

  const notifyConnectionStatus = useCallback((status: 'connected' | 'disconnected' | 'connecting', userName?: string) => {
    const messages = {
      connected: `Connected with ${userName || 'friend'}! 🎉`,
      disconnected: `Disconnected from ${userName || 'session'}`,
      connecting: `Connecting to ${userName || 'session'}...`,
    };

    showNotification({
      type: 'social',
      title: 'Listen Together',
      message: messages[status],
      duration: status === 'connecting' ? 10000 : 4000,
    });
  }, [showNotification]);

  const notifyError = useCallback((message: string, actionCallback?: () => void) => {
    showNotification({
      type: 'error',
      title: 'Oops! Something went wrong',
      message,
      action: actionCallback ? {
        label: 'Retry',
        onClick: actionCallback,
      } : undefined,
      duration: 8000,
    });
  }, [showNotification]);

  const notifySuccess = useCallback((title: string, message: string) => {
    showNotification({
      type: 'success',
      title,
      message,
      duration: 4000,
    });
  }, [showNotification]);

  return {
    notifications,
    showNotification,
    removeNotification,
    // Helper methods
    notifyMusicAction,
    notifyPlaylistAction,
    notifySocialAction,
    notifyShareSuccess,
    notifyConnectionStatus,
    notifyError,
    notifySuccess,
  };
};

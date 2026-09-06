import { useState, useEffect, useCallback } from 'react';

export const useMobileGestures = () => {
  const [touchStart, setTouchStart] = useState<{ x: number; y: number } | null>(null);
  const [touchEnd, setTouchEnd] = useState<{ x: number; y: number } | null>(null);

  const handleTouchStart = useCallback((e: TouchEvent) => {
    setTouchEnd(null);
    setTouchStart({
      x: e.targetTouches[0].clientX,
      y: e.targetTouches[0].clientY,
    });
  }, []);

  const handleTouchMove = useCallback((e: TouchEvent) => {
    setTouchEnd({
      x: e.targetTouches[0].clientX,
      y: e.targetTouches[0].clientY,
    });
  }, []);

  const handleTouchEnd = useCallback(() => {
    if (!touchStart || !touchEnd) return;

    const distanceX = touchStart.x - touchEnd.x;
    const distanceY = touchStart.y - touchEnd.y;
    const isLeftSwipe = distanceX > 50;
    const isRightSwipe = distanceX < -50;
    const isUpSwipe = distanceY > 50;
    const isDownSwipe = distanceY < -50;

    return {
      isLeftSwipe,
      isRightSwipe,
      isUpSwipe,
      isDownSwipe,
      distanceX,
      distanceY,
    };
  }, [touchStart, touchEnd]);

  return {
    handleTouchStart,
    handleTouchMove,
    handleTouchEnd,
  };
};

export const useHapticFeedback = () => {
  const vibrate = useCallback((pattern: number | number[] = 50) => {
    if ('vibrate' in navigator) {
      navigator.vibrate(pattern);
    }
  }, []);

  const lightTap = useCallback(() => vibrate(10), [vibrate]);
  const mediumTap = useCallback(() => vibrate(50), [vibrate]);
  const heavyTap = useCallback(() => vibrate(100), [vibrate]);
  const doubleTap = useCallback(() => vibrate([50, 50, 50]), [vibrate]);
  const successPattern = useCallback(() => vibrate([100, 50, 100]), [vibrate]);
  const errorPattern = useCallback(() => vibrate([200, 100, 200, 100, 200]), [vibrate]);

  return {
    vibrate,
    lightTap,
    mediumTap,
    heavyTap,
    doubleTap,
    successPattern,
    errorPattern,
  };
};

export const useDeviceCapabilities = () => {
  const [capabilities, setCapabilities] = useState({
    isMobile: false,
    isTablet: false,
    isDesktop: false,
    hasTouch: false,
    isOnline: navigator.onLine,
    canVibrate: 'vibrate' in navigator,
    canShare: 'share' in navigator,
    canInstall: false,
    orientation: 'portrait' as 'portrait' | 'landscape',
  });

  useEffect(() => {
    const checkCapabilities = () => {
      const userAgent = navigator.userAgent;
      const isMobile = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(userAgent);
      const isTablet = /iPad|Android(?=.*Mobile)/i.test(userAgent);
      const isDesktop = !isMobile && !isTablet;
      const hasTouch = 'ontouchstart' in window;
      const orientation = window.innerHeight > window.innerWidth ? 'portrait' : 'landscape';

      setCapabilities(prev => ({
        ...prev,
        isMobile,
        isTablet,
        isDesktop,
        hasTouch,
        orientation,
      }));
    };

    checkCapabilities();
    window.addEventListener('resize', checkCapabilities);
    window.addEventListener('orientationchange', checkCapabilities);

    const handleOnline = () => setCapabilities(prev => ({ ...prev, isOnline: true }));
    const handleOffline = () => setCapabilities(prev => ({ ...prev, isOnline: false }));

    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);

    // Check for PWA install capability
    const handleBeforeInstallPrompt = () => {
      setCapabilities(prev => ({ ...prev, canInstall: true }));
    };

    window.addEventListener('beforeinstallprompt', handleBeforeInstallPrompt);

    return () => {
      window.removeEventListener('resize', checkCapabilities);
      window.removeEventListener('orientationchange', checkCapabilities);
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
      window.removeEventListener('beforeinstallprompt', handleBeforeInstallPrompt);
    };
  }, []);

  return capabilities;
};

export const useNativeSharing = () => {
  const canShare = 'share' in navigator;

  const shareContent = useCallback(async (data: {
    title?: string;
    text?: string;
    url?: string;
  }) => {
    if (canShare) {
      try {
        await navigator.share(data);
        return true;
      } catch (error) {
        console.error('Error sharing:', error);
        return false;
      }
    }
    return false;
  }, [canShare]);

  const sharePlaylist = useCallback(async (playlistName: string, shareCode: string) => {
    const shareData = {
      title: `🎵 ${playlistName} - MoodiFy KP`,
      text: `Check out my "${playlistName}" playlist on MoodiFy KP! Use code: ${shareCode}`,
      url: `${window.location.origin}/shared/${shareCode}`,
    };

    return await shareContent(shareData);
  }, [shareContent]);

  return {
    canShare,
    shareContent,
    sharePlaylist,
  };
};

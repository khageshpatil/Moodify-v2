import React from 'react';
import { Loader2, Music, Heart, Users, Search } from 'lucide-react';

interface LoadingStateProps {
  type?: 'default' | 'music' | 'search' | 'social' | 'playlist';
  message?: string;
  size?: 'sm' | 'md' | 'lg';
}

export const LoadingState = ({ 
  type = 'default', 
  message = 'Loading...', 
  size = 'md' 
}: LoadingStateProps) => {
  const sizeClasses = {
    sm: 'w-4 h-4',
    md: 'w-8 h-8',
    lg: 'w-12 h-12'
  };

  const getIcon = () => {
    switch (type) {
      case 'music':
        return <Music className={`${sizeClasses[size]} animate-pulse text-primary`} />;
      case 'search':
        return <Search className={`${sizeClasses[size]} animate-spin text-secondary`} />;
      case 'social':
        return <Users className={`${sizeClasses[size]} animate-bounce text-accent`} />;
      case 'playlist':
        return <Heart className={`${sizeClasses[size]} animate-pulse text-mood-love`} />;
      default:
        return <Loader2 className={`${sizeClasses[size]} animate-spin text-primary`} />;
    }
  };

  return (
    <div className="flex flex-col items-center justify-center p-8 space-y-4">
      <div className="relative">
        {getIcon()}
        <div className="absolute inset-0 animate-ping opacity-25">
          {getIcon()}
        </div>
      </div>
      <p className="text-sm text-muted-foreground animate-pulse">{message}</p>
    </div>
  );
};

interface SkeletonProps {
  className?: string;
  variant?: 'text' | 'circular' | 'rectangular';
}

export const Skeleton = ({ className = '', variant = 'text' }: SkeletonProps) => {
  const baseClasses = 'animate-pulse bg-gradient-to-r from-muted via-muted/50 to-muted';
  
  const variantClasses = {
    text: 'h-4 rounded',
    circular: 'rounded-full',
    rectangular: 'rounded-md'
  };

  return (
    <div className={`${baseClasses} ${variantClasses[variant]} ${className}`} />
  );
};

export const TrackSkeleton = () => (
  <div className="flex items-center gap-3 p-3 animate-pulse">
    <Skeleton variant="circular" className="w-12 h-12" />
    <div className="flex-1 space-y-2">
      <Skeleton className="h-4 w-3/4" />
      <Skeleton className="h-3 w-1/2" />
    </div>
    <Skeleton className="h-8 w-16" />
  </div>
);

export const PlaylistSkeleton = () => (
  <div className="space-y-4 animate-pulse">
    <div className="flex items-center gap-3">
      <Skeleton variant="rectangular" className="w-16 h-16" />
      <div className="space-y-2">
        <Skeleton className="h-6 w-48" />
        <Skeleton className="h-4 w-32" />
      </div>
    </div>
    <div className="space-y-2">
      {Array.from({ length: 3 }).map((_, i) => (
        <TrackSkeleton key={i} />
      ))}
    </div>
  </div>
);

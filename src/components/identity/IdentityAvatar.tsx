import React, { useMemo } from 'react';
import { cn } from '@/lib/utils';

interface IdentityAvatarProps {
  seed: string;
  size?: number;
  className?: string;
  animate?: boolean;
}

// Generate deterministic color from seed
const seedToColor = (seed: string, index: number): string => {
  let hash = 0;
  const str = seed + index;
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash);
  }
  
  // Generate vibrant colors in the purple-pink spectrum (matching Moodify theme)
  const hue = (hash % 60) + 280; // 280-340 range (purple to pink)
  const saturation = 60 + (hash % 30); // 60-90%
  const lightness = 50 + (hash % 20); // 50-70%
  
  return `hsl(${hue}, ${saturation}%, ${lightness}%)`;
};

// Generate pattern grid from seed
const seedToPattern = (seed: string, gridSize: number = 5): boolean[][] => {
  const pattern: boolean[][] = [];
  let hash = 0;
  
  for (let i = 0; i < seed.length; i++) {
    hash = seed.charCodeAt(i) + ((hash << 5) - hash);
  }
  
  // Generate symmetric pattern (mirror on Y axis)
  const halfSize = Math.ceil(gridSize / 2);
  
  for (let y = 0; y < gridSize; y++) {
    pattern[y] = [];
    for (let x = 0; x < halfSize; x++) {
      const index = y * halfSize + x;
      const value = (hash >> index) & 1;
      pattern[y][x] = value === 1;
      
      // Mirror for symmetry
      if (x !== halfSize - 1 || gridSize % 2 === 0) {
        pattern[y][gridSize - 1 - x] = value === 1;
      }
    }
  }
  
  return pattern;
};

export const IdentityAvatar: React.FC<IdentityAvatarProps> = ({ 
  seed, 
  size = 48, 
  className,
  animate = false 
}) => {
  const avatarData = useMemo(() => {
    const gridSize = 5;
    const pattern = seedToPattern(seed, gridSize);
    const primaryColor = seedToColor(seed, 0);
    const secondaryColor = seedToColor(seed, 1);
    const bgColor = seedToColor(seed, 2);
    
    return { pattern, primaryColor, secondaryColor, bgColor, gridSize };
  }, [seed]);

  const cellSize = size / avatarData.gridSize;

  return (
    <div
      className={cn(
        'relative overflow-hidden rounded-full flex-shrink-0',
        animate && 'animate-pulse-subtle',
        className
      )}
      style={{
        width: size,
        height: size,
        background: `linear-gradient(135deg, ${avatarData.bgColor}, ${avatarData.secondaryColor})`,
      }}
    >
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        xmlns="http://www.w3.org/2000/svg"
      >
        {/* Background circle */}
        <circle
          cx={size / 2}
          cy={size / 2}
          r={size / 2}
          fill={`url(#gradient-${seed})`}
        />
        
        {/* Gradient definition */}
        <defs>
          <linearGradient id={`gradient-${seed}`} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor={avatarData.bgColor} stopOpacity="0.8" />
            <stop offset="100%" stopColor={avatarData.secondaryColor} stopOpacity="0.9" />
          </linearGradient>
          <filter id={`glow-${seed}`}>
            <feGaussianBlur stdDeviation="2" result="coloredBlur"/>
            <feMerge>
              <feMergeNode in="coloredBlur"/>
              <feMergeNode in="SourceGraphic"/>
            </feMerge>
          </filter>
        </defs>
        
        {/* Pattern grid */}
        {avatarData.pattern.map((row, y) =>
          row.map((cell, x) =>
            cell ? (
              <rect
                key={`${x}-${y}`}
                x={x * cellSize}
                y={y * cellSize}
                width={cellSize}
                height={cellSize}
                fill={avatarData.primaryColor}
                opacity="0.9"
                filter={`url(#glow-${seed})`}
              />
            ) : null
          )
        )}
        
        {/* Overlay shine effect */}
        <circle
          cx={size / 3}
          cy={size / 3}
          r={size / 4}
          fill="white"
          opacity="0.15"
        />
      </svg>
      
      {/* Glass overlay */}
      <div 
        className="absolute inset-0 rounded-full bg-gradient-to-br from-white/20 to-transparent pointer-events-none"
      />
    </div>
  );
};

// Compact version for inline use
export const IdentityAvatarSmall: React.FC<Omit<IdentityAvatarProps, 'size'>> = (props) => (
  <IdentityAvatar {...props} size={32} />
);

// Large version for profile/settings
export const IdentityAvatarLarge: React.FC<Omit<IdentityAvatarProps, 'size'>> = (props) => (
  <IdentityAvatar {...props} size={96} />
);


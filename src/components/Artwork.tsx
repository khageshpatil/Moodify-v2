import { useEffect, useState, type ReactNode } from 'react';
import { presentArtworkUrl } from '@/presentation/providerPresentation';

interface ArtworkProps {
  src?: string | null;
  alt?: string;
  className?: string;
  fallback?: ReactNode;
}

export const Artwork = ({ src, alt = '', className, fallback = null }: ArtworkProps) => {
  const url = presentArtworkUrl(src);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    setFailed(false);
  }, [url]);
  if (!url || failed) return <>{fallback}</>;
  return <img src={url} alt={alt} className={className} onError={() => setFailed(true)} />;
};

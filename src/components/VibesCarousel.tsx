import { useEffect, useRef, useState } from 'react';
import { vibeWorlds } from '@/data/vibeWorlds';

interface VibesCarouselProps {
  onVibeSelect: (vibeId: string) => void;
}

const ROTATE_MS = 8000;
const SLIDE_MS = 900;

interface SlideLayer {
  key: string;
  artwork: string;
  title: string;
  role: 'base' | 'in' | 'out';
}

const useCoverSlide = (artwork: string, title: string, reducedMotion: boolean) => {
  const [layers, setLayers] = useState<SlideLayer[]>(() => [
    { key: artwork, artwork, title, role: 'base' },
  ]);
  const shown = useRef(artwork);

  useEffect(() => {
    if (shown.current === artwork) return;
    shown.current = artwork;
    const key = `${artwork}-${performance.now()}`;

    if (reducedMotion) {
      setLayers([{ key, artwork, title, role: 'base' }]);
      return;
    }

    setLayers((prev) => {
      const current = prev.find((layer) => layer.role !== 'out') ?? prev[prev.length - 1];
      return [
        { ...current, role: 'out' },
        { key, artwork, title, role: 'in' },
      ];
    });

    const settle = window.setTimeout(() => {
      setLayers([{ key, artwork, title, role: 'base' }]);
    }, SLIDE_MS);

    return () => window.clearTimeout(settle);
  }, [artwork, title, reducedMotion]);

  return layers;
};

interface VibeTileProps {
  title: string;
  artwork: string;
  hero?: boolean;
  reducedMotion: boolean;
  onSelect: () => void;
}

const VibeTile = ({ title, artwork, hero, reducedMotion, onSelect }: VibeTileProps) => {
  const layers = useCoverSlide(artwork, title, reducedMotion);
  return (
    <button
      type="button"
      className={`vibe-tile${hero ? ' vibe-hero' : ''}`}
      onClick={onSelect}
      aria-label={title}
    >
      {layers.map((layer) => (
        <span
          key={layer.key}
          className={`vibe-slide is-${layer.role}`}
          style={{ backgroundImage: `url(${layer.artwork})` }}
        >
          <strong>{layer.title}</strong>
        </span>
      ))}
    </button>
  );
};

export const VibesCarousel = ({ onVibeSelect }: VibesCarouselProps) => {
  const count = vibeWorlds.length;
  const [heroIndex, setHeroIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const [reducedMotion, setReducedMotion] = useState(false);

  useEffect(() => {
    const media = window.matchMedia('(prefers-reduced-motion: reduce)');
    const sync = () => setReducedMotion(media.matches);
    sync();
    media.addEventListener('change', sync);
    return () => media.removeEventListener('change', sync);
  }, []);

  useEffect(() => {
    if (paused || reducedMotion) return;
    const timer = window.setTimeout(() => {
      setHeroIndex((index) => (index + 1) % count);
    }, ROTATE_MS);
    return () => window.clearTimeout(timer);
  }, [heroIndex, paused, reducedMotion, count]);

  const hero = vibeWorlds[heroIndex];
  const stacked = [
    vibeWorlds[(heroIndex + 1) % count],
    vibeWorlds[(heroIndex + 2) % count],
  ];

  return (
    <div
      className={`vibe-stage${paused ? ' is-paused' : ''}`}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
    >
      <div className="vibe-stage-grid">
        <VibeTile
          hero
          title={hero.title}
          artwork={hero.artwork}
          reducedMotion={reducedMotion}
          onSelect={() => onVibeSelect(hero.id)}
        />
        <div className="vibe-stage-stack">
          {stacked.map((world, slot) => (
            <VibeTile
              key={slot}
              title={world.title}
              artwork={world.artwork}
              reducedMotion={reducedMotion}
              onSelect={() => onVibeSelect(world.id)}
            />
          ))}
        </div>
      </div>

      <div className="vibe-stage-nav">
        <div className="vibe-dots" role="tablist" aria-label="Vibe destinations">
          {vibeWorlds.map((world, index) => (
            <button
              key={world.id}
              type="button"
              role="tab"
              aria-selected={index === heroIndex}
              aria-label={world.title}
              className={`vibe-dot${index === heroIndex ? ' is-on' : ''}`}
              onClick={() => setHeroIndex(index)}
            >
              {index === heroIndex && !reducedMotion ? (
                <span key={`${heroIndex}-${paused}`} className="vibe-dot-fill" />
              ) : null}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
};

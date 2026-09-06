import { useState } from 'react';
import { ArrowUpRight, Sparkles } from 'lucide-react';

interface IntentComposerProps {
  onSubmit: (intent: string) => void;
  isLoading?: boolean;
}

const suggestions = ['Something for deep focus', 'A calm reset', 'Surprise me', 'Songs I know by heart'];

export const IntentComposer = ({ onSubmit, isLoading = false }: IntentComposerProps) => {
  const [value, setValue] = useState('');
  const submit = (intent = value) => {
    if (intent.trim() && !isLoading) {
      onSubmit(intent.trim());
      setValue('');
    }
  };

  return (
    <section className="intent-composer" aria-labelledby="intent-heading">
      <div className="intent-kicker"><Sparkles size={14} aria-hidden="true" /> set the scene</div>
      <h2 id="intent-heading">Where are we going?</h2>
      <p>Give us a moment, a place, or a little feeling. We’ll start the soundtrack.</p>
      <form onSubmit={(event) => { event.preventDefault(); submit(); }} className="intent-form">
        <label className="sr-only" htmlFor="listening-intent">Describe what you want to hear</label>
        <input
          id="listening-intent"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          placeholder="music for the train home, no destination"
          autoComplete="off"
        />
        <button type="submit" aria-label="Start listening from this intent" disabled={!value.trim() || isLoading}>
          <ArrowUpRight size={20} aria-hidden="true" />
        </button>
      </form>
      <div className="intent-suggestions" aria-label="Listening suggestions">
        {suggestions.map((suggestion) => (
          <button key={suggestion} type="button" onClick={() => submit(suggestion)} disabled={isLoading}>
            {suggestion}
          </button>
        ))}
      </div>
    </section>
  );
};

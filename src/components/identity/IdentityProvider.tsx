import React, { createContext, useContext, ReactNode } from 'react';
import { useAnonymousIdentity, MoodifyIdentity } from '@/hooks/useAnonymousIdentity';
import { IdentityOnboardingModal } from './IdentityOnboardingModal';

interface IdentityContextType {
  identity: MoodifyIdentity | null;
  isLoading: boolean;
  updateDisplayName: (name: string) => void;
  exportIdentity: () => string;
  importIdentity: (code: string) => void;
  resetIdentity: () => void;
}

const IdentityContext = createContext<IdentityContextType | undefined>(undefined);

export const useIdentityContext = () => {
  const context = useContext(IdentityContext);
  if (!context) {
    throw new Error('useIdentityContext must be used within IdentityProvider');
  }
  return context;
};

interface IdentityProviderProps {
  children: ReactNode;
}

export const IdentityProvider: React.FC<IdentityProviderProps> = ({ children }) => {
  const {
    identity,
    isOnboardingRequired,
    isLoading,
    createIdentity,
    updateDisplayName,
    exportIdentity,
    importIdentity,
    resetIdentity,
    getSuggestedNickname,
  } = useAnonymousIdentity();

  const handleOnboardingComplete = (displayName: string, importCode?: string) => {
    if (importCode) {
      try {
        importIdentity(importCode);
      } catch (err) {
        console.error('Failed to import identity during onboarding:', err);
        // Fallback to creating new identity
        createIdentity(displayName || getSuggestedNickname());
      }
    } else {
      createIdentity(displayName);
    }
  };

  const value: IdentityContextType = {
    identity,
    isLoading,
    updateDisplayName,
    exportIdentity,
    importIdentity,
    resetIdentity,
  };

  return (
    <IdentityContext.Provider value={value}>
      {!isLoading && (
        <IdentityOnboardingModal
          open={isOnboardingRequired}
          onComplete={handleOnboardingComplete}
          suggestedNickname={getSuggestedNickname()}
          onGenerateNewNickname={getSuggestedNickname}
        />
      )}
      {children}
    </IdentityContext.Provider>
  );
};


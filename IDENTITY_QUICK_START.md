# 🚀 Quick Start: Moodify Identity System

## For Users (5 Minutes)

### 1. First Visit
When you open Moodify for the first time, you'll see a beautiful onboarding modal:

1. **Enter Your Nickname**
   - Type your preferred nickname (up to 20 characters)
   - Or click the refresh button to get a random suggestion
   - Watch your unique avatar update in real-time!

2. **Click "Enter Moodify"**
   - Your identity is created and saved
   - You're ready to use all social features

3. **Backup Your Identity** (Recommended)
   - Click your name in the top navigation
   - Click "Export Identity"
   - Save the recovery code somewhere safe
   - Use it to restore your identity on other devices

### 2. Using Social Features

**Listen Together:**
- Click "Listen Together" button
- Create or join a room
- Your avatar and name appear automatically in chat
- Everyone sees your identity when you send messages

**Playlist Sharing:**
- Share playlists with your identity attached
- Others see your name and avatar when viewing your shared playlists

**Community:**
- Your identity is used throughout community features
- Always private, always anonymous

### 3. Managing Your Identity

Click your name in the navigation to:
- ✏️ **Edit Name** - Change your display name
- 💾 **Export** - Get your recovery code
- 📥 **Import** - Restore from another device
- 🗑️ **Reset** - Start fresh (warning: can't undo!)

## For Developers (10 Minutes)

### 1. Installation
Everything is already installed! The identity system is fully integrated.

### 2. Using Identity in Your Components

```typescript
import { useIdentityContext } from '@/components/identity';

const MyComponent = () => {
  const { identity } = useIdentityContext();
  
  if (!identity) {
    return <div>Loading...</div>;
  }
  
  return (
    <div>
      <p>Hello, {identity.displayName}!</p>
    </div>
  );
};
```

### 3. Displaying Avatars

```typescript
import { IdentityAvatarSmall } from '@/components/identity';

<IdentityAvatarSmall seed={identity.avatarSeed} />
```

### 4. Using Identity in Hooks

```typescript
const { identity } = useIdentityContext();

// Pass to hooks that support identity
const { sendMessage } = useEnhancedListenTogether(
  handleRemoteControl,
  identity
);
```

### 5. Running Tests

```bash
# Install test dependencies first
npm install --save-dev vitest @testing-library/react @testing-library/react-hooks

# Run tests
npm test

# Add to package.json if not present:
{
  "scripts": {
    "test": "vitest"
  }
}
```

## Common Tasks

### Export Identity
```typescript
const { exportIdentity } = useIdentityContext();
const code = exportIdentity();
// Code is automatically copied to clipboard
```

### Import Identity
```typescript
const { importIdentity } = useIdentityContext();
importIdentity('your-recovery-code-here');
```

### Update Name
```typescript
const { updateDisplayName } = useIdentityContext();
updateDisplayName('NewNickname');
```

### Reset Identity
```typescript
const { resetIdentity } = useIdentityContext();
resetIdentity(); // Shows onboarding again
```

## Troubleshooting

### "Onboarding keeps appearing"
- Check if localStorage is enabled in your browser
- Check browser console for errors
- Clear localStorage and try again

### "Import fails"
- Make sure you copied the entire recovery code
- Code must be unmodified
- Check for extra spaces at start/end

### "Avatar not showing"
- Verify identity exists: `console.log(identity)`
- Check avatarSeed is a valid string
- Look for console errors

### "Identity not persisting"
- Check localStorage quota (should be fine for identity data)
- Verify no browser extensions blocking localStorage
- Try incognito mode to test

## Files to Review

For more details, check these files:

1. **`IDENTITY_SYSTEM_README.md`** - Complete documentation
2. **`IDENTITY_IMPLEMENTATION_SUMMARY.md`** - What was built
3. **`src/hooks/useAnonymousIdentity.ts`** - Core logic
4. **`src/components/identity/`** - UI components

## API Quick Reference

### useIdentityContext Hook

```typescript
const {
  identity,              // MoodifyIdentity | null
  isLoading,            // boolean
  updateDisplayName,    // (name: string) => void
  exportIdentity,       // () => string
  importIdentity,       // (code: string) => void
  resetIdentity,        // () => void
} = useIdentityContext();
```

### MoodifyIdentity Type

```typescript
interface MoodifyIdentity {
  anonId: string;        // UUID v4
  displayName: string;   // User's nickname
  avatarSeed: string;    // 16 chars
  createdAt: number;     // Unix timestamp
}
```

## Tips & Best Practices

### For Users
✅ Export your identity right after creating it
✅ Use a memorable nickname
✅ Don't share your recovery code
✅ Recovery code = full access to your identity

### For Developers
✅ Always check if identity exists before using
✅ Use IdentityContext, not useAnonymousIdentity directly
✅ Handle import errors gracefully
✅ Test with and without identity present

## Support

For issues or questions:
1. Check `IDENTITY_SYSTEM_README.md` for detailed docs
2. Review test file for usage examples
3. Check browser console for errors
4. Verify localStorage is working

---

**Ready to Go!** 🎉

The identity system is fully functional and ready to use. No setup required, no backend needed, just open Moodify and start!


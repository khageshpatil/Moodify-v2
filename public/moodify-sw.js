// Moodify service worker: browser notifications for chat (docs/social-protocol/chat.md §3).
// Pings carry no data at all, so this can only say that something arrived; the page
// fetches and decrypts the messages when you open it. No fetch handler: nothing is cached.

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

const windows = () => self.clients.matchAll({ type: 'window', includeUncontrolled: true });

self.addEventListener('push', (event) => {
  event.waitUntil((async () => {
    // A visible Moodify tab shows its own notification with the friend's name.
    if ((await windows()).some((w) => w.visibilityState === 'visible')) return;
    await self.registration.showNotification('Moodify', {
      body: 'New message from a friend',
      tag: 'moodify-inbox',
      renotify: true,
      data: { url: '/friends' },
    });
  })());
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = event.notification.data?.url || '/friends';
  event.waitUntil((async () => {
    const open = (await windows()).find((w) => new URL(w.url).origin === self.location.origin);
    if (!open) return self.clients.openWindow(url);
    await open.focus();
    open.postMessage({ type: 'moodify-open', url });
  })());
});
